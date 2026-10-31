package com.aravpanwar.mcdiff;

import com.aravpanwar.mcdiff.BaselineStore.ChunkSummary;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeWorldConfig;
import xyz.nucleoid.fantasy.RuntimeWorldHandle;

/**
 * Rebuilds the baseline of every saved chunk in one dimension from the
 * world's seed, so the diff covers changes made before the mod was installed.
 *
 * <p>The untouched terrain is generated in a temporary dimension that shares
 * the real one's seed, generator and dimension type. The real chunks are read
 * straight from the save files and never loaded into the world.
 *
 * <p>Where features from neighbouring chunks overlap (trees crowding each
 * other, lakes, ore blobs, a geode cutting into a structure), the block that
 * ends up there depends on which chunk generated first, and nobody knows the
 * order the real world was explored in. So the terrain is generated twice, in
 * opposite orders, and every position where the two disagree is marked as
 * unknown rather than reported as a change.
 */
final class OriginalSweep {
	private static final int IN_FLIGHT = 39;
	private static final int REPORT_INTERVAL = 129;
	private static final int FLUSH_INTERVAL = 529;
	private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

	/*
	 * Requesting a chunk from off the server thread only holds it with a
	 * one-tick ticket, which expires long before generation finishes. This
	 * ticket never times out and is removed by hand. Its level sits far enough
	 * out that the chunk stops at the light status instead of becoming a full
	 * chunk that gets ticked.
	 */
	private static final TicketType<ChunkPos> TICKET_TYPE = TicketType.create(McDiffClient.MOD_ID + "_original", Comparator.comparingLong(ChunkPos::toLong));
	private static final int TICKET_RADIUS = ChunkLevel.byStatus(FullChunkStatus.FULL) - ChunkLevel.byStatus(ChunkStatus.LIGHT);

	/* The same codec the game reads a saved section's blocks with. */
	private static final Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC = PalettedContainer.codecRW(
			Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

	private enum Sweep {
		FIRST("rebuilding original terrain, step 1 of 2"),
		SECOND("double-checking original terrain, step 2 of 2");

		final String label;

		Sweep(String label) {
			this.label = label;
		}
	}

	final DiffSession session;
	private final Minecraft client;
	private final IntegratedServer server;
	private final ResourceKey<Level> dimension;
	private final LongSet firstMarker;
	private final LongSet secondMarker;

	private volatile boolean cancelled;
	private volatile boolean finished;
	private volatile String failure;
	private volatile Sweep sweep = Sweep.FIRST;
	private volatile int total = -1;
	private final AtomicInteger processed = new AtomicInteger();
	private volatile int ready;
	private final AtomicInteger failed = new AtomicInteger();

	private int ticks;
	private int appliedSinceFlush;
	private boolean reportedEnd;

	private OriginalSweep(Minecraft client, IntegratedServer server, DiffSession session) {
		this.client = client;
		this.server = server;
		this.session = session;
		this.dimension = session.level.dimension();
		this.firstMarker = new LongOpenHashSet(session.originalMarker());
		this.secondMarker = new LongOpenHashSet(session.confirmedMarker());
	}

	static OriginalSweep start(Minecraft client, IntegratedServer server, DiffSession session) {
		OriginalSweep sweep = new OriginalSweep(client, server, session);
		Thread worker = new Thread(sweep::run, "mcdiff-original");
		worker.setDaemon(true);
		worker.start();
		return sweep;
	}

	boolean isRunning() {
		return !finished;
	}

	void cancel() {
		cancelled = true;
	}

	String progress() {
		if (total < 0) {
			return "finding saved chunks";
		}
		int done = processed.get();
		return String.format("%s: %,d / %,d chunks (%d%%)", sweep.label, done, total, total == 0 ? 100 : done * 100 / total);
	}

	private void run() {
		ExecutorService compute = Executors.newFixedThreadPool(2, runnable -> {
			Thread thread = new Thread(runnable, "mcdiff-original-compute");
			thread.setDaemon(true);
			return thread;
		});
		KeySet firstApplied = new KeySet();
		try {
			ServerLevel real = server.submit(() -> server.getLevel(dimension)).get();
			if (real == null) {
				fail("couldn't find the server level for " + dimension.location());
				return;
			}
			Path regionDir = DimensionType.getStorageFolder(dimension, server.getWorldPath(LevelResource.ROOT)).resolve("region");
			List<ChunkPos> saved = sweepRegionFiles(regionDir);

			List<ChunkPos> first = new ArrayList<>(saved);
			first.removeIf(pos -> firstMarker.contains(pos.toLong()));
			runSweep(Sweep.FIRST, real, first, compute, firstApplied);

			if (!cancelled) {
				// Opposite order to the first sweep, so overlapping features get placed the other way round.
				List<ChunkPos> second = new ArrayList<>(saved);
				second.removeIf(pos -> !(firstMarker.contains(pos.toLong()) || firstApplied.contains(pos.toLong())) || secondMarker.contains(pos.toLong()));
				Collections.reverse(second);
				runSweep(Sweep.SECOND, real, second, compute, firstApplied);
			}
			ready = (int) saved.stream().filter(pos -> firstMarker.contains(pos.toLong()) || firstApplied.contains(pos.toLong())).count();
		} catch (Exception e) {
			fail("couldn't rebuild original terrain: " + e.getMessage());
			McDiffClient.LOG.error("couldn't rebuild original terrain", e);
		} finally {
			compute.shutdown();
			finished = true;
		}
	}

	private void runSweep(Sweep current, ServerLevel real, List<ChunkPos> positions, ExecutorService compute, KeySet firstApplied) throws Exception {
		sweep = current;
		processed.set(0);
		total = positions.size();
		if (positions.isEmpty()) {
			return;
		}

		RuntimeWorldHandle handle = server.submit(() -> openPristineLevel(real)).get();
		ServerLevel pristine = handle.asWorld();
		Semaphore permits = new Semaphore(IN_FLIGHT);
		ChunkMap chunkMap = real.getChunkSource().chunkMap;
		try {
			for (ChunkPos pos : positions) {
				if (!acquire(permits, 1)) {
					break;
				}
				chunkMap.read(pos)
						.thenApplyAsync(tag -> readActual(real, chunkMap, tag), compute)
						.thenCompose(actual -> actual == null
								? CompletableFuture.completedFuture((Boolean) null)
								: generate(pristine, pos).thenApplyAsync(chunk -> finish(current, pos, actual, chunk, firstApplied), compute))
						.whenComplete((result, error) -> {
							if (error != null) {
								failed.incrementAndGet();
								McDiffClient.LOG.warn("couldn't rebuild chunk {} from the seed", pos, error);
							}
							processed.incrementAndGet();
							permits.release();
						});
			}
			acquire(permits, IN_FLIGHT);
		} finally {
			if (server.isRunning()) {
				server.execute(handle::delete);
			}
		}
	}

	/* Fantasy has to create levels on the server thread. */
	private RuntimeWorldHandle openPristineLevel(ServerLevel real) {
		RuntimeWorldConfig config = new RuntimeWorldConfig()
				.setSeed(real.getSeed())
				.setDimensionType(real.dimensionTypeRegistration())
				.setGenerator(real.getChunkSource().getGenerator())
				.setShouldTickTime(false);
		RuntimeWorldHandle handle = Fantasy.get(server).openTemporaryWorld(config);
		handle.setTickWhenEmpty(true);
		return handle;
	}

	/* Waiting in short slices keeps a cancelled or shut-down world from leaving the worker stuck. */
	private boolean acquire(Semaphore permits, int count) {
		try {
			while (!permits.tryAcquire(count, 229, TimeUnit.MILLISECONDS)) {
				if (cancelled || !server.isRunning()) {
					return false;
				}
			}
			return !cancelled;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * Chunks that never finished generating sit at the edge of explored land
	 * and the player has never seen them, so they are left out.
	 */
	private static ChunkSnapshot readActual(ServerLevel real, ChunkMap chunkMap, Optional<CompoundTag> tag) {
		if (tag.isEmpty()) {
			return null;
		}
		CompoundTag upgraded = chunkMap.upgradeChunkTag(real.dimension(), () -> real.getServer().overworld().getDataStorage(), tag.get(),
				real.getChunkSource().getGenerator().getTypeNameForDataFixer());
		if (ChunkStatus.byName(upgraded.getString("Status")) != ChunkStatus.FULL) {
			return null;
		}

		/*
		 * The game's own chunk reader also updates the real world's point of
		 * interest data as it goes, which a read-only comparison has no
		 * business doing, so the block states are read from the saved sections
		 * directly.
		 */
		List<PalettedContainerRO<BlockState>> states = new ArrayList<>(Collections.nCopies(real.getSectionsCount(), null));
		ListTag sections = upgraded.getList("sections", Tag.TAG_COMPOUND);
		for (int i = 0; i < sections.size(); i++) {
			CompoundTag section = sections.getCompound(i);
			int index = section.getByte("Y") - real.getMinSection();
			if (index >= 0 && index < states.size() && section.contains("block_states", Tag.TAG_COMPOUND)) {
				states.set(index, BLOCK_STATE_CODEC.parse(NbtOps.INSTANCE, section.getCompound("block_states"))
						.getOrThrow(message -> new IllegalStateException("expected readable block states, got " + message)));
			}
		}
		return ChunkSnapshot.capture(real.getMinSection(), states);
	}

	/*
	 * Trees and structures from neighbouring chunks spill into a chunk while
	 * those neighbours run their feature step. Lighting can't start until
	 * every neighbour has finished that step, so a chunk that has reached the
	 * light status has all of its blocks in their final generated state.
	 */
	private CompletableFuture<ChunkAccess> generate(ServerLevel pristine, ChunkPos pos) {
		ServerChunkCache cache = pristine.getChunkSource();
		server.execute(() -> cache.addRegionTicket(TICKET_TYPE, pos, TICKET_RADIUS, pos));
		return cache.getChunkFuture(pos.x, pos.z, ChunkStatus.LIGHT, true)
				.whenComplete((result, error) -> server.execute(() -> cache.removeRegionTicket(TICKET_TYPE, pos, TICKET_RADIUS, pos)))
				.thenApply(result -> {
					ChunkAccess chunk = result.orElse(null);
					if (chunk == null) {
						throw new IllegalStateException("expected a generated chunk, got " + ((ChunkResult<?>) result).getError());
					}
					return chunk;
				});
	}

	private Boolean finish(Sweep current, ChunkPos pos, ChunkSnapshot actual, ChunkAccess generated, KeySet firstApplied) {
		ChunkSnapshot snapshot = ChunkSnapshot.capture(generated);
		if (current == Sweep.FIRST) {
			byte[] encoded = snapshot.encode();
			ChunkSummary summary = ChunkSummary.between(snapshot, actual);
			firstApplied.add(pos.toLong());
			onClient(() -> session.applyOriginal(pos, encoded, snapshot, summary));
		} else {
			onClient(() -> session.confirmOriginal(pos, snapshot, actual));
		}
		return Boolean.TRUE;
	}

	private void onClient(Runnable apply) {
		client.execute(() -> {
			if (!cancelled && McDiffClient.isCurrent(session)) {
				apply.run();
				if (++appliedSinceFlush >= FLUSH_INTERVAL) {
					appliedSinceFlush = 0;
					session.flush();
				}
			}
		});
	}

	private void fail(String message) {
		failure = message;
		finished = true;
	}

	/** Lists every chunk that has data in this dimension's region files, in row order. */
	private static List<ChunkPos> sweepRegionFiles(Path regionDir) throws IOException {
		List<ChunkPos> positions = new ArrayList<>();
		if (!Files.isDirectory(regionDir)) {
			return positions;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(regionDir, "r.*.mca")) {
			for (Path file : files) {
				Matcher name = REGION_FILE.matcher(file.getFileName().toString());
				if (!name.matches() || Files.size(file) < 4096) {
					continue;
				}
				int regionX = Integer.parseInt(name.group(1));
				int regionZ = Integer.parseInt(name.group(2));
				// The first 4 KiB of a region file is a table of 1024 chunk locations; zero means absent.
				try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
					for (int i = 0; i < 1024; i++) {
						if (in.readInt() != 0) {
							positions.add(new ChunkPos(regionX * 32 + (i & 31), regionZ * 32 + (i >> 5)));
						}
					}
				}
			}
		}
		// Row order lets neighbouring chunks share the generation work they both need.
		positions.sort(Comparator.<ChunkPos>comparingInt(chunk -> chunk.z).thenComparingInt(chunk -> chunk.x));
		return positions;
	}

	/** Called every client tick; reports progress and the final result in chat. */
	void tick() {
		if (finished) {
			if (!reportedEnd) {
				reportedEnd = true;
				session.flush();
				if (failure != null) {
					McDiffClient.chat(failure);
				} else if (cancelled) {
					McDiffClient.chat("stopped at " + progress() + ", run /mcdiff original to continue");
				} else {
					McDiffClient.chat(String.format("original terrain ready: %,d chunks rebuilt from the seed%s. Run /mcdiff stats to see the totals",
							ready, failed.get() > 0 ? String.format(" (%,d couldn't be rebuilt, see the log)", failed.get()) : ""));
				}
			}
			return;
		}
		if (++ticks % REPORT_INTERVAL == 0) {
			McDiffClient.status(progress());
		}
	}

	/** Chunk keys shared between the worker and the compute threads. */
	private static final class KeySet {
		private final Set<Long> keys = ConcurrentHashMap.newKeySet();

		void add(long key) {
			keys.add(key);
		}

		boolean contains(long key) {
			return keys.contains(key);
		}
	}
}

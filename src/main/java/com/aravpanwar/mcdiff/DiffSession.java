package com.aravpanwar.mcdiff;

import com.aravpanwar.mcdiff.BaselineStore.ChunkSummary;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.storage.LevelResource;

/** Tracks every loaded chunk of one client level against its baseline. */
final class DiffSession implements DiffMesh.KindLookup {
	private static final int SWEEP_BUDGET = 39;
	private static final int MESH_BUDGET = 19;
	private static final int SAVE_INTERVAL = 629;

	final ClientLevel level;
	private final BaselineStore store;
	private final Long2ObjectOpenHashMap<ChunkDiff> chunks = new Long2ObjectOpenHashMap<>();
	private final ReferenceLinkedOpenHashSet<ChunkDiff> urgent = new ReferenceLinkedOpenHashSet<>();
	private final ReferenceLinkedOpenHashSet<ChunkDiff> background = new ReferenceLinkedOpenHashSet<>();
	private int ticks;

	DiffSession(Minecraft minecraft, ClientLevel level) {
		this.level = level;
		this.store = new BaselineStore(storageDir(minecraft, level));
	}

	/*
	 * Singleplayer baselines live inside the save folder so they travel with
	 * the world when it is copied or backed up. Servers have no local save,
	 * so those go under the game directory keyed by address.
	 */
	private static Path storageDir(Minecraft minecraft, ClientLevel level) {
		Path root;
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server != null) {
			root = server.getWorldPath(LevelResource.ROOT).normalize().resolve("mcdiff");
		} else {
			ServerData data = minecraft.getCurrentServer();
			String address = data == null ? "unknown" : data.ip;
			root = minecraft.gameDirectory.toPath().resolve("mcdiff").resolve(purifyName(address));
		}
		Identifier dimension = level.dimension().identifier();
		return root.resolve(purifyName(dimension.getNamespace() + "_" + dimension.getPath()));
	}

	private static String purifyName(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
	}

	void onChunkLoad(LevelChunk chunk) {
		ChunkPos pos = chunk.getPos();
		ChunkDiff existing = chunks.get(pos.pack());
		if (existing != null) {
			existing.chunk = chunk;
			existing.markAllDirty();
			background.add(existing);
			return;
		}

		ChunkSnapshot baseline = null;
		byte[] stored = store.get(pos);
		if (stored != null) {
			try {
				baseline = ChunkSnapshot.decode(stored);
			} catch (IOException | RuntimeException e) {
				McDiffClient.LOG.error("couldn't decode baseline for chunk {}, capturing a new one", pos, e);
			}
		}

		ChunkDiff diff;
		if (baseline == null) {
			// A fresh baseline matches the chunk exactly, so there is nothing to sweep yet.
			baseline = ChunkSnapshot.capture(chunk);
			store.put(pos, baseline.encode());
			diff = new ChunkDiff(chunk, baseline);
		} else {
			diff = new ChunkDiff(chunk, baseline);
			diff.markAllDirty();
			background.add(diff);
		}
		chunks.put(pos.pack(), diff);
		invalidateNeighbors(pos);
	}

	void onChunkUnload(LevelChunk chunk) {
		ChunkPos pos = chunk.getPos();
		ChunkDiff diff = chunks.get(pos.pack());
		if (diff == null || diff.chunk != chunk) {
			return;
		}
		if (diff.summaryDirty && diff.dirtySections.isEmpty()) {
			summarize(diff);
		}
		chunks.remove(pos.pack());
		urgent.remove(diff);
		background.remove(diff);
		invalidateNeighbors(pos);
	}

	/*
	 * A loaded chunk gets a fresh diff against the new baseline and its summary
	 * is recomputed from the live blocks once swept. An unloaded chunk keeps
	 * the summary worked out from its saved data, since nothing on the client
	 * can recompute it until the player comes back.
	 */
	void applyOriginal(ChunkPos pos, byte[] encoded, ChunkSnapshot baseline, ChunkSummary summary) {
		store.put(pos, encoded);
		store.markOriginal(pos);

		ChunkDiff old = chunks.get(pos.pack());
		if (old == null) {
			store.putSummary(pos, summary);
			return;
		}
		urgent.remove(old);
		background.remove(old);
		ChunkDiff fresh = new ChunkDiff(old.chunk, baseline);
		fresh.markAllDirty();
		fresh.summaryDirty = true;
		chunks.put(pos.pack(), fresh);
		background.add(fresh);
		invalidateNeighbors(pos);
	}

	/*
	 * The first generation's baseline is read back from the store and merged
	 * with the second, so only one generation per chunk is ever held in memory.
	 */
	void confirmOriginal(ChunkPos pos, ChunkSnapshot second, ChunkSnapshot actual) {
		ChunkSnapshot first = null;
		byte[] stored = store.get(pos);
		if (stored != null) {
			try {
				first = ChunkSnapshot.decode(stored);
			} catch (IOException | RuntimeException e) {
				McDiffClient.LOG.warn("couldn't decode the first rebuild of chunk {}, using the second alone", pos, e);
			}
		}
		ChunkSnapshot merged = first == null ? second : ChunkSnapshot.merge(first, second);
		applyOriginal(pos, merged.encode(), merged, ChunkSummary.between(merged, actual));
		store.markConfirmed(pos);
	}

	LongSet originalMarker() {
		return store.originalMarker();
	}

	LongSet confirmedMarker() {
		return store.confirmedMarker();
	}

	void onBlockChanged(BlockPos pos) {
		ChunkDiff diff = chunks.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
		if (diff == null) {
			return;
		}
		int section = (pos.getY() >> 4) - diff.minSectionY();
		if (section < 0 || section >= diff.kinds.length) {
			return;
		}
		diff.dirtySections.set(section);
		urgent.add(diff);
	}

	void tick() {
		List<ChunkDiff> swept = new ArrayList<>();

		// Changes the player just made are always handled the same tick; chunk loads share a budget.
		for (ChunkDiff diff : urgent) {
			sweepAll(diff, Integer.MAX_VALUE);
			swept.add(diff);
		}
		urgent.clear();

		int budget = SWEEP_BUDGET;
		Iterator<ChunkDiff> queue = background.iterator();
		while (budget > 0 && queue.hasNext()) {
			ChunkDiff diff = queue.next();
			budget -= sweepAll(diff, budget);
			swept.add(diff);
			if (diff.dirtySections.isEmpty()) {
				queue.remove();
			}
		}

		for (ChunkDiff diff : swept) {
			if (diff.neighborsStale) {
				diff.neighborsStale = false;
				invalidateNeighbors(diff.pos);
			}
		}

		int rebuilt = 0;
		for (ChunkDiff diff : chunks.values()) {
			if (!diff.dirtySections.isEmpty()) {
				continue;
			}
			if (diff.summaryDirty) {
				summarize(diff);
			}
			if (diff.meshDirty && rebuilt < MESH_BUDGET) {
				diff.meshDirty = false;
				diff.mesh = DiffMesh.build(diff, this);
				rebuilt++;
			}
		}

		if (++ticks % SAVE_INTERVAL == 0) {
			flush();
		}
	}

	private static int sweepAll(ChunkDiff diff, int budget) {
		int used = 0;
		for (int s = diff.dirtySections.nextSetBit(0); s >= 0 && used < budget; s = diff.dirtySections.nextSetBit(s + 1)) {
			diff.dirtySections.clear(s);
			if (diff.sweep(s)) {
				used++;
			}
		}
		return used;
	}

	private void invalidateNeighbors(ChunkPos pos) {
		markMeshDirty(pos.x() - 1, pos.z());
		markMeshDirty(pos.x() + 1, pos.z());
		markMeshDirty(pos.x(), pos.z() - 1);
		markMeshDirty(pos.x(), pos.z() + 1);
	}

	private void markMeshDirty(int chunkX, int chunkZ) {
		ChunkDiff diff = chunks.get(ChunkPos.pack(chunkX, chunkZ));
		if (diff != null && !diff.isEmpty()) {
			diff.meshDirty = true;
		}
	}

	@Override
	public byte kindAt(int worldX, int y, int worldZ) {
		ChunkDiff diff = chunks.get(ChunkPos.pack(worldX >> 4, worldZ >> 4));
		return diff == null ? ChunkDiff.NONE : diff.kindAt(worldX & 15, y, worldZ & 15);
	}

	private void summarize(ChunkDiff diff) {
		diff.summaryDirty = false;
		ChunkSummary summary = new ChunkSummary();
		for (int s = 0; s < diff.kinds.length; s++) {
			byte[] section = diff.kinds[s];
			if (section == null) {
				continue;
			}
			LevelChunkSection live = diff.chunk.getSection(s);
			for (int i = 0; i < 4096; i++) {
				byte kind = section[i];
				if (kind == ChunkDiff.NONE) {
					continue;
				}
				summary.count(diff.baselineAt(s, i), Purifier.purify(live.getBlockState(i & 15, i >> 8, (i >> 4) & 15)));
			}
		}
		store.putSummary(diff.pos, summary);
	}

	Iterable<ChunkDiff> chunks() {
		return chunks.values();
	}

	void flush() {
		LongOpenHashSet active = new LongOpenHashSet();
		for (ChunkDiff diff : chunks.values()) {
			active.add(BaselineStore.regionKey(diff.pos.x(), diff.pos.z()));
		}
		store.flush(active);
	}

	void close() {
		for (ChunkDiff diff : chunks.values()) {
			if (diff.summaryDirty && diff.dirtySections.isEmpty()) {
				summarize(diff);
			}
		}
		store.flush(new LongOpenHashSet());
		BaselineStore.drain();
	}

	Stats stats() {
		Stats stats = new Stats();
		Object2IntOpenHashMap<String> removed = new Object2IntOpenHashMap<>();
		Object2IntOpenHashMap<String> placed = new Object2IntOpenHashMap<>();
		for (ChunkSummary summary : store.summaries()) {
			stats.chunks++;
			stats.removed += summary.removed;
			stats.added += summary.added;
			stats.replaced += summary.replaced;
			summary.removedByBlock.object2IntEntrySet().forEach(e -> removed.addTo(e.getKey(), e.getIntValue()));
			summary.placedByBlock.object2IntEntrySet().forEach(e -> placed.addTo(e.getKey(), e.getIntValue()));
		}
		stats.topRemoved = top(removed);
		stats.topPlaced = top(placed);
		stats.pending = !background.isEmpty();
		return stats;
	}

	private static List<Map.Entry<String, Integer>> top(Object2IntOpenHashMap<String> counts) {
		return counts.object2IntEntrySet().stream()
				.sorted(Comparator.comparingInt(Object2IntOpenHashMap.Entry<String>::getIntValue).reversed())
				.limit(5)
				.<Map.Entry<String, Integer>>map(e -> Map.entry(e.getKey(), e.getIntValue()))
				.toList();
	}

	static final class Stats {
		int chunks;
		long removed;
		long added;
		long replaced;
		boolean pending;
		List<Map.Entry<String, Integer>> topRemoved;
		List<Map.Entry<String, Integer>> topPlaced;
	}
}

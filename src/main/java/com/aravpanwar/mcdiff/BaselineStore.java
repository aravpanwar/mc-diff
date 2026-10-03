package com.aravpanwar.mcdiff;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;

/**
 * Baselines on disk, grouped 32x32 chunks per region file, plus a small
 * per-chunk summary file that keeps totals for chunks that aren't loaded.
 */
final class BaselineStore {
	private static final int REGION_MAGIC = 0x6d636466;
	private static final int SUMMARY_MAGIC = 0x6d637375;
	private static final int MARKER_MAGIC = 0x6d636d6b;
	private static final int MARKER_FORMAT = 2;
	private static final int FORMAT = 1;

	/*
	 * One writer thread for the whole client: saves happen every few seconds
	 * and on world exit, and a single thread keeps writes to the same file in
	 * order without any locking on the file itself.
	 */
	private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "mcdiff-writer");
		thread.setDaemon(true);
		return thread;
	});

	private final Path dir;
	private final Long2ObjectMap<Region> regions = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectMap<ChunkSummary> summaries = new Long2ObjectOpenHashMap<>();
	private boolean summariesDirty;
	private final LongOpenHashSet originalMarker = new LongOpenHashSet();
	private final LongOpenHashSet confirmedMarker = new LongOpenHashSet();
	private boolean markerDirty;

	BaselineStore(Path dir) {
		this.dir = dir;
		// Totals and the rebuild marker from older rules would mix old noise into new stats, so both start over.
		if (readSummaries()) {
			readMarker();
		} else {
			summariesDirty = true;
			markerDirty = true;
		}
	}

	/** Chunks whose baseline was rebuilt from the seed, so an interrupted rebuild can resume. */
	LongSet originalMarker() {
		return originalMarker;
	}

	/** Chunks whose rebuilt baseline was checked against a second generation. */
	LongSet confirmedMarker() {
		return confirmedMarker;
	}

	void markOriginal(ChunkPos pos) {
		if (originalMarker.add(pos.pack())) {
			markerDirty = true;
		}
	}

	void markConfirmed(ChunkPos pos) {
		if (confirmedMarker.add(pos.pack())) {
			markerDirty = true;
		}
	}

	byte[] get(ChunkPos pos) {
		return region(pos).entries.get(pos.pack());
	}

	void put(ChunkPos pos, byte[] snapshot) {
		Region region = region(pos);
		region.entries.put(pos.pack(), snapshot);
		region.dirty = true;
	}

	void putSummary(ChunkPos pos, ChunkSummary summary) {
		if (summary.isEmpty()) {
			if (summaries.remove(pos.pack()) != null) {
				summariesDirty = true;
			}
		} else {
			summaries.put(pos.pack(), summary);
			summariesDirty = true;
		}
	}

	Iterable<ChunkSummary> summaries() {
		return summaries.values();
	}

	int trackedChunkCount() {
		int count = 0;
		for (Region region : regions.values()) {
			count += region.entries.size();
		}
		return count;
	}

	/** Queues writes for everything that changed and drops clean regions nobody is using. */
	void flush(LongSet activeRegions) {
		List<Runnable> writes = new ArrayList<>();
		var iterator = regions.long2ObjectEntrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			Region region = entry.getValue();
			if (region.dirty) {
				Path file = regionFile(entry.getLongKey());
				Long2ObjectMap<byte[]> copy = new Long2ObjectOpenHashMap<>(region.entries);
				writes.add(() -> writeRegion(file, copy));
				region.dirty = false;
			} else if (!activeRegions.contains(entry.getLongKey())) {
				iterator.remove();
			}
		}

		if (summariesDirty) {
			Long2ObjectMap<ChunkSummary> copy = new Long2ObjectOpenHashMap<>();
			for (var entry : summaries.long2ObjectEntrySet()) {
				copy.put(entry.getLongKey(), entry.getValue().copy());
			}
			writes.add(() -> writeSummaries(copy));
			summariesDirty = false;
		}

		if (markerDirty) {
			long[] original = originalMarker.toLongArray();
			long[] confirmed = confirmedMarker.toLongArray();
			writes.add(() -> writeMarker(original, confirmed));
			markerDirty = false;
		}

		for (Runnable write : writes) {
			WRITER.execute(write);
		}
	}

	/*
	 * Rejoining a world straight after leaving it reads the same region files
	 * the previous session is still writing. Waiting here keeps the new
	 * session from reading a stale file and recapturing chunks that already
	 * have a baseline.
	 */
	static void drain() {
		try {
			WRITER.submit(() -> { }).get(19, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			McDiffClient.LOG.warn("couldn't wait for baseline writes to finish", e);
		}
	}

	static void awaitWrites() {
		WRITER.shutdown();
		try {
			if (!WRITER.awaitTermination(19, TimeUnit.SECONDS)) {
				McDiffClient.LOG.warn("couldn't finish baseline writes before the game closed");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	static long regionKey(int chunkX, int chunkZ) {
		return ChunkPos.pack(chunkX >> 5, chunkZ >> 5);
	}

	private Region region(ChunkPos pos) {
		long key = regionKey(pos.x(), pos.z());
		Region region = regions.get(key);
		if (region == null) {
			region = readRegion(regionFile(key));
			regions.put(key, region);
		}
		return region;
	}

	private Path regionFile(long regionKey) {
		return dir.resolve("r." + ChunkPos.getX(regionKey) + "." + ChunkPos.getZ(regionKey) + ".mcdiff");
	}

	private static Region readRegion(Path file) {
		Region region = new Region();
		if (!Files.isRegularFile(file)) {
			return region;
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			if (in.readInt() != REGION_MAGIC || in.readUnsignedByte() != FORMAT) {
				throw new IOException("expected an mc-diff region file");
			}
			int count = in.readInt();
			for (int i = 0; i < count; i++) {
				long key = in.readLong();
				byte[] data = new byte[in.readInt()];
				in.readFully(data);
				region.entries.put(key, data);
			}
		} catch (IOException e) {
			McDiffClient.LOG.error("couldn't read baseline region {}", file, e);
		}
		return region;
	}

	private static void writeRegion(Path file, Long2ObjectMap<byte[]> entries) {
		writeAtomically(file, out -> {
			out.writeInt(REGION_MAGIC);
			out.writeByte(FORMAT);
			out.writeInt(entries.size());
			for (var entry : entries.long2ObjectEntrySet()) {
				out.writeLong(entry.getLongKey());
				out.writeInt(entry.getValue().length);
				out.write(entry.getValue());
			}
		});
	}

	/** Returns false when the file was written under older rules and was ignored. */
	private boolean readSummaries() {
		Path file = dir.resolve("summary.mcdiff");
		if (!Files.isRegularFile(file)) {
			return true;
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			if (in.readInt() != SUMMARY_MAGIC) {
				throw new IOException("expected an mc-diff summary file");
			}
			if (in.readUnsignedByte() != FORMAT || in.readUnsignedByte() != Purifier.RULES_VERSION) {
				McDiffClient.LOG.info("diff rules changed since {} was written, starting the totals over", file);
				return false;
			}
			int count = in.readInt();
			for (int i = 0; i < count; i++) {
				long key = in.readLong();
				summaries.put(key, ChunkSummary.read(in));
			}
		} catch (IOException e) {
			McDiffClient.LOG.error("couldn't read diff summary {}", file, e);
		}
		return true;
	}

	private void writeSummaries(Long2ObjectMap<ChunkSummary> copy) {
		writeAtomically(dir.resolve("summary.mcdiff"), out -> {
			out.writeInt(SUMMARY_MAGIC);
			out.writeByte(FORMAT);
			out.writeByte(Purifier.RULES_VERSION);
			out.writeInt(copy.size());
			for (var entry : copy.long2ObjectEntrySet()) {
				out.writeLong(entry.getLongKey());
				entry.getValue().write(out);
			}
		});
	}

	private void readMarker() {
		Path file = dir.resolve("original.mcdiff");
		if (!Files.isRegularFile(file)) {
			return;
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			if (in.readInt() != MARKER_MAGIC) {
				throw new IOException("expected an mc-diff marker file");
			}
			if (in.readUnsignedByte() != MARKER_FORMAT) {
				return;
			}
			readKeys(in, originalMarker);
			readKeys(in, confirmedMarker);
		} catch (IOException e) {
			McDiffClient.LOG.error("couldn't read original terrain marker {}", file, e);
		}
	}

	private static void readKeys(DataInputStream in, LongSet keys) throws IOException {
		int count = in.readInt();
		for (int i = 0; i < count; i++) {
			keys.add(in.readLong());
		}
	}

	private void writeMarker(long[] original, long[] confirmed) {
		writeAtomically(dir.resolve("original.mcdiff"), out -> {
			out.writeInt(MARKER_MAGIC);
			out.writeByte(MARKER_FORMAT);
			for (long[] keys : new long[][] {original, confirmed}) {
				out.writeInt(keys.length);
				for (long key : keys) {
					out.writeLong(key);
				}
			}
		});
	}

	/*
	 * Writing to a temp file and moving it over the old one means a crash or
	 * a closed game mid-write leaves the previous baseline intact, which
	 * matters because a lost baseline can never be recaptured.
	 */
	private static void writeAtomically(Path file, IoWriter writer) {
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
				writer.write(out);
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			McDiffClient.LOG.error("couldn't write {}", file, e);
		}
	}

	private interface IoWriter {
		void write(DataOutputStream out) throws IOException;
	}

	private static final class Region {
		final Long2ObjectMap<byte[]> entries = new Long2ObjectOpenHashMap<>();
		boolean dirty;
	}

	/** Counts for one chunk; per-block counts are keyed by block id. */
	static final class ChunkSummary {
		int removed;
		int added;
		int replaced;
		final Object2IntOpenHashMap<String> removedByBlock = new Object2IntOpenHashMap<>();
		final Object2IntOpenHashMap<String> placedByBlock = new Object2IntOpenHashMap<>();

		boolean isEmpty() {
			return removed == 0 && added == 0 && replaced == 0;
		}

		/** Records one differing position; both blocks are already purified. */
		void count(Block before, Block now) {
			if (Purifier.alike(before, now)) {
				return;
			}
			if (!Purifier.isEmpty(before)) {
				removedByBlock.addTo(BuiltInRegistries.BLOCK.getKey(before).toString(), 1);
			}
			if (!Purifier.isEmpty(now)) {
				placedByBlock.addTo(BuiltInRegistries.BLOCK.getKey(now).toString(), 1);
			}
			if (Purifier.isEmpty(before)) {
				added++;
			} else if (Purifier.isEmpty(now)) {
				removed++;
			} else {
				replaced++;
			}
		}

		static ChunkSummary between(ChunkSnapshot before, ChunkSnapshot after) {
			ChunkSummary summary = new ChunkSummary();
			for (int s = 0; s < after.sectionCount(); s++) {
				int sectionY = after.minSectionY + s;
				ChunkSnapshot.Section a = before.section(sectionY);
				ChunkSnapshot.Section b = after.section(sectionY);
				if (a == null || (a.isEmpty() && b.isEmpty())) {
					continue;
				}
				for (int i = 0; i < 4096; i++) {
					summary.count(a.get(i), b.get(i));
				}
			}
			return summary;
		}

		ChunkSummary copy() {
			ChunkSummary copy = new ChunkSummary();
			copy.removed = removed;
			copy.added = added;
			copy.replaced = replaced;
			copy.removedByBlock.putAll(removedByBlock);
			copy.placedByBlock.putAll(placedByBlock);
			return copy;
		}

		void write(DataOutputStream out) throws IOException {
			out.writeInt(removed);
			out.writeInt(added);
			out.writeInt(replaced);
			writeCounts(out, removedByBlock);
			writeCounts(out, placedByBlock);
		}

		static ChunkSummary read(DataInputStream in) throws IOException {
			ChunkSummary summary = new ChunkSummary();
			summary.removed = in.readInt();
			summary.added = in.readInt();
			summary.replaced = in.readInt();
			readCounts(in, summary.removedByBlock);
			readCounts(in, summary.placedByBlock);
			return summary;
		}

		private static void writeCounts(DataOutputStream out, Object2IntMap<String> counts) throws IOException {
			out.writeInt(counts.size());
			for (var entry : counts.object2IntEntrySet()) {
				out.writeUTF(entry.getKey());
				out.writeInt(entry.getIntValue());
			}
		}

		private static void readCounts(DataInputStream in, Object2IntMap<String> counts) throws IOException {
			int size = in.readInt();
			for (int i = 0; i < size; i++) {
				counts.put(in.readUTF(), in.readInt());
			}
		}
	}
}

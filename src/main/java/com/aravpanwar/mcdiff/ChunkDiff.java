package com.aravpanwar.mcdiff;

import java.util.Arrays;
import java.util.BitSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Diff state for one loaded chunk. */
final class ChunkDiff {
	static final byte NONE = 0;
	static final byte REMOVED = 1;
	static final byte ADDED = 2;
	static final byte REPLACED = 3;

	final ChunkPos pos;
	final ChunkSnapshot baseline;
	LevelChunk chunk;

	/* One entry per section; null means the section has no differences. */
	final byte[][] kinds;
	final BitSet dirtySections = new BitSet();
	boolean meshDirty;
	boolean summaryDirty;
	boolean neighborsStale;
	DiffMesh mesh;

	ChunkDiff(LevelChunk chunk, ChunkSnapshot baseline) {
		this.pos = chunk.getPos();
		this.chunk = chunk;
		this.baseline = baseline;
		this.kinds = new byte[chunk.getSectionsCount()][];
	}

	int minSectionY() {
		return chunk.getMinSectionY();
	}

	void markAllDirty() {
		dirtySections.set(0, kinds.length);
	}

	byte kindAt(int localX, int y, int localZ) {
		int section = (y >> 4) - minSectionY();
		if (section < 0 || section >= kinds.length || kinds[section] == null) {
			return NONE;
		}
		return kinds[section][(y & 15) << 8 | localZ << 4 | localX];
	}

	Block baselineAt(int sectionIndex, int index) {
		ChunkSnapshot.Section section = baseline.section(minSectionY() + sectionIndex);
		return section == null ? Blocks.AIR : section.get(index);
	}

	/**
	 * Recomputes one section and reports whether it had anything to compare.
	 * A section that is air both then and now is free, so the sweep budget
	 * only counts sections that actually hold blocks.
	 */
	boolean sweep(int sectionIndex) {
		LevelChunkSection live = chunk.getSection(sectionIndex);
		ChunkSnapshot.Section base = baseline.section(minSectionY() + sectionIndex);
		byte[] previous = kinds[sectionIndex];

		if (base == null || (base.isEmpty() && live.hasOnlyAir())) {
			kinds[sectionIndex] = null;
			if (previous != null) {
				changed();
			}
			return false;
		}

		byte[] next = null;
		for (int i = 0; i < 4096; i++) {
			Block before = base.get(i);
			Block now = Purifier.purify(live.getBlockState(i & 15, i >> 8, (i >> 4) & 15));
			if (Purifier.alike(before, now)) {
				continue;
			}
			if (next == null) {
				next = new byte[4096];
			}
			next[i] = Purifier.isEmpty(before) ? ADDED : Purifier.isEmpty(now) ? REMOVED : REPLACED;
		}

		kinds[sectionIndex] = next;
		if (!Arrays.equals(previous, next)) {
			changed();
		}
		return true;
	}

	private void changed() {
		meshDirty = true;
		summaryDirty = true;
		neighborsStale = true;
	}

	boolean isEmpty() {
		for (byte[] section : kinds) {
			if (section != null) {
				return false;
			}
		}
		return true;
	}
}

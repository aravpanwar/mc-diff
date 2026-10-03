package com.aravpanwar.mcdiff;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The blocks of a chunk as they were the first time the client saw it.
 * Block indices inside a section are {@code y << 8 | z << 4 | x}.
 */
final class ChunkSnapshot {
	private static final int FORMAT = 1;

	final int minSectionY;
	private final Section[] sections;

	private ChunkSnapshot(int minSectionY, Section[] sections) {
		this.minSectionY = minSectionY;
		this.sections = sections;
	}

	static ChunkSnapshot capture(ChunkAccess chunk) {
		return capture(chunk.getMinSectionY(), chunk.getSections());
	}

	/** Sections may contain nulls, which are treated as all air. */
	static ChunkSnapshot capture(int minSectionY, LevelChunkSection[] live) {
		Section[] sections = new Section[live.length];
		for (int i = 0; i < live.length; i++) {
			sections[i] = live[i] == null ? Section.EMPTY : Section.capture(live[i]);
		}
		return new ChunkSnapshot(minSectionY, sections);
	}

	int sectionCount() {
		return sections.length;
	}

	/**
	 * Combines two generations of the same chunk. Wherever they disagree the
	 * block depends on generation order, so it becomes {@link Purifier#UNKNOWN}
	 * and the diff ignores it there.
	 */
	static ChunkSnapshot merge(ChunkSnapshot first, ChunkSnapshot second) {
		Section[] merged = new Section[first.sections.length];
		for (int s = 0; s < merged.length; s++) {
			Section a = first.sections[s];
			Section b = second.section(first.minSectionY + s);
			merged[s] = b == null ? a : Section.merge(a, b);
		}
		return new ChunkSnapshot(first.minSectionY, merged);
	}

	/** Returns null when the section lies outside the height range that was captured. */
	Section section(int sectionY) {
		int index = sectionY - minSectionY;
		return index >= 0 && index < sections.length ? sections[index] : null;
	}

	byte[] encode() {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
			out.writeByte(FORMAT);
			out.writeInt(minSectionY);
			out.writeShort(sections.length);
			for (Section section : sections) {
				section.write(out);
			}
		} catch (IOException e) {
			throw new IllegalStateException("couldn't encode chunk snapshot", e);
		}
		return bytes.toByteArray();
	}

	static ChunkSnapshot decode(byte[] data) throws IOException {
		try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
			int format = in.readUnsignedByte();
			if (format != FORMAT) {
				throw new IOException("expected snapshot format " + FORMAT + ", got " + format);
			}
			int minSectionY = in.readInt();
			Section[] sections = new Section[in.readUnsignedShort()];
			for (int i = 0; i < sections.length; i++) {
				sections[i] = Section.read(in);
			}
			return new ChunkSnapshot(minSectionY, sections);
		}
	}

	static final class Section {
		private static final Section EMPTY = new Section(new Block[] {Blocks.AIR}, 0, new long[0]);

		private final Block[] palette;
		private final int bits;
		private final long[] packed;

		private Section(Block[] palette, int bits, long[] packed) {
			this.palette = palette;
			this.bits = bits;
			this.packed = packed;
		}

		boolean isEmpty() {
			return bits == 0 && palette[0] == Blocks.AIR;
		}

		Block get(int index) {
			if (bits == 0) {
				return palette[0];
			}
			int perLong = 64 / bits;
			long word = packed[index / perLong];
			int shift = (index % perLong) * bits;
			return palette[(int) ((word >>> shift) & ((1L << bits) - 1))];
		}

		static Section capture(LevelChunkSection live) {
			if (live.hasOnlyAir()) {
				return EMPTY;
			}

			Reference2IntOpenHashMap<Block> ids = new Reference2IntOpenHashMap<>();
			ids.defaultReturnValue(-1);
			List<Block> palette = new ArrayList<>();
			int[] raw = new int[4096];
			for (int i = 0; i < 4096; i++) {
				Block block = Purifier.purify(live.getBlockState(i & 15, i >> 8, (i >> 4) & 15));
				int id = ids.getInt(block);
				if (id < 0) {
					id = palette.size();
					ids.put(block, id);
					palette.add(block);
				}
				raw[i] = id;
			}
			return pack(palette.toArray(new Block[0]), raw);
		}

		static Section merge(Section a, Section b) {
			Block[] blocks = null;
			for (int i = 0; i < 4096; i++) {
				Block block = a.get(i);
				if (Purifier.alike(block, b.get(i))) {
					continue;
				}
				if (blocks == null) {
					blocks = new Block[4096];
					for (int j = 0; j < 4096; j++) {
						blocks[j] = a.get(j);
					}
				}
				blocks[i] = Purifier.UNKNOWN;
			}
			return blocks == null ? a : pack(blocks);
		}

		private static Section pack(Block[] blocks) {
			Reference2IntOpenHashMap<Block> ids = new Reference2IntOpenHashMap<>();
			ids.defaultReturnValue(-1);
			List<Block> palette = new ArrayList<>();
			int[] raw = new int[4096];
			for (int i = 0; i < 4096; i++) {
				int id = ids.getInt(blocks[i]);
				if (id < 0) {
					id = palette.size();
					ids.put(blocks[i], id);
					palette.add(blocks[i]);
				}
				raw[i] = id;
			}
			return pack(palette.toArray(new Block[0]), raw);
		}

		private static Section pack(Block[] palette, int[] raw) {
			if (palette.length == 1) {
				return palette[0] == Blocks.AIR ? EMPTY : new Section(palette, 0, new long[0]);
			}
			int bits = 32 - Integer.numberOfLeadingZeros(palette.length - 1);
			int perLong = 64 / bits;
			long[] packed = new long[(4096 + perLong - 1) / perLong];
			for (int i = 0; i < 4096; i++) {
				packed[i / perLong] |= (long) raw[i] << ((i % perLong) * bits);
			}
			return new Section(palette, bits, packed);
		}

		void write(DataOutputStream out) throws IOException {
			out.writeShort(palette.length);
			for (Block block : palette) {
				out.writeUTF(BuiltInRegistries.BLOCK.getKey(block).toString());
			}
			out.writeByte(bits);
			out.writeShort(packed.length);
			for (long word : packed) {
				out.writeLong(word);
			}
		}

		/*
		 * Blocks whose ids no longer resolve (a mod was removed since the
		 * snapshot) come back as air, so they show up as green "added" blocks
		 * wherever something now stands instead of breaking the whole chunk.
		 * Known blocks go through the current rules again, so a baseline saved
		 * under older rules still lines up with what is captured today.
		 */
		static Section read(DataInputStream in) throws IOException {
			Block[] palette = new Block[in.readUnsignedShort()];
			for (int i = 0; i < palette.length; i++) {
				Identifier id = Identifier.tryParse(in.readUTF());
				Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
				palette[i] = block == null ? Blocks.AIR : Purifier.purify(block);
			}
			int bits = in.readUnsignedByte();
			long[] packed = new long[in.readUnsignedShort()];
			for (int i = 0; i < packed.length; i++) {
				packed[i] = in.readLong();
			}
			if (palette.length == 0) {
				throw new IOException("expected a non-empty section palette");
			}
			if (bits == 0 && palette.length == 1 && palette[0] == Blocks.AIR) {
				return EMPTY;
			}
			return new Section(palette, bits, packed);
		}
	}
}

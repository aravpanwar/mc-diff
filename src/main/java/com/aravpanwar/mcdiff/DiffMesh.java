package com.aravpanwar.mcdiff;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Faces and edges for one chunk's diff, in chunk-local x/z and absolute y.
 * Built on the client thread whenever the chunk's diff changes and replayed
 * into the vertex buffer every frame.
 */
final class DiffMesh {
	private static final float OFFSET = 0.002f;

	private static final int[] FILL = {0, 0x55ff3b30, 0x4434c759, 0x55ffcc00};
	private static final int[] EDGE = {0, 0xe0ff3b30, 0xe034c759, 0xe0ffcc00};

	/* Direction order: down, up, north, south, west, east. */
	private static final int[][] STEP = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
	private static final int[][][] CORNERS = {
		{{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
		{{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
		{{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}},
		{{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
		{{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
		{{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
	};

	private final float[] faces;
	private final int[] faceColors;
	private final float[] lines;
	private final int[] lineColors;
	final int lowestY;
	final int highestY;

	private DiffMesh(float[] faces, int[] faceColors, float[] lines, int[] lineColors, int lowestY, int highestY) {
		this.faces = faces;
		this.faceColors = faceColors;
		this.lines = lines;
		this.lineColors = lineColors;
		this.lowestY = lowestY;
		this.highestY = highestY;
	}

	interface KindLookup {
		byte kindAt(int worldX, int y, int worldZ);
	}

	/** Returns null when there is nothing to draw. */
	static DiffMesh build(ChunkDiff diff, KindLookup neighbors) {
		FloatArrayList faces = new FloatArrayList();
		IntArrayList faceColors = new IntArrayList();
		FloatArrayList lines = new FloatArrayList();
		IntArrayList lineColors = new IntArrayList();
		LongOpenHashSet seenEdges = new LongOpenHashSet();
		int lowestY = Integer.MAX_VALUE;
		int highestY = Integer.MIN_VALUE;

		int originX = diff.pos.getMinBlockX();
		int originZ = diff.pos.getMinBlockZ();
		int minY = diff.minSectionY() << 4;

		for (int s = 0; s < diff.kinds.length; s++) {
			byte[] section = diff.kinds[s];
			if (section == null) {
				continue;
			}
			int baseY = (diff.minSectionY() + s) << 4;
			for (int i = 0; i < 4096; i++) {
				byte kind = section[i];
				if (kind == ChunkDiff.NONE) {
					continue;
				}
				int x = i & 15;
				int y = baseY + (i >> 8);
				int z = (i >> 4) & 15;

				for (int d = 0; d < 6; d++) {
					int nx = x + STEP[d][0];
					int ny = y + STEP[d][1];
					int nz = z + STEP[d][2];
					byte neighbor = nx >= 0 && nx < 16 && nz >= 0 && nz < 16
							? diff.kindAt(nx, ny, nz)
							: neighbors.kindAt(originX + nx, ny, originZ + nz);
					if (neighbor == kind) {
						continue;
					}
					addFace(kind, d, x, y, z, minY, faces, faceColors, lines, lineColors, seenEdges);
					lowestY = Math.min(lowestY, y);
					highestY = Math.max(highestY, y + 1);
				}
			}
		}

		if (faceColors.isEmpty()) {
			return null;
		}
		return new DiffMesh(faces.toFloatArray(), faceColors.toIntArray(), lines.toFloatArray(), lineColors.toIntArray(), lowestY, highestY);
	}

	/*
	 * Faces are nudged along their normal only, never sideways, so
	 * neighbouring faces on the same plane still tile without seams. Removed
	 * blocks are empty space now, so their faces move inwards to sit in front
	 * of the surrounding terrain; added and replaced blocks are solid, so their
	 * faces move outwards to sit in front of the block itself.
	 */
	private static void addFace(byte kind, int d, int x, int y, int z, int minY, FloatArrayList faces, IntArrayList faceColors,
			FloatArrayList lines, IntArrayList lineColors, LongOpenHashSet seenEdges) {
		float push = kind == ChunkDiff.REMOVED ? -OFFSET : OFFSET;
		float ox = STEP[d][0] * push;
		float oy = STEP[d][1] * push;
		float oz = STEP[d][2] * push;
		int[][] corners = CORNERS[d];

		for (int[] c : corners) {
			faces.add(x + c[0] + ox);
			faces.add(y + c[1] + oy);
			faces.add(z + c[2] + oz);
		}
		faceColors.add(FILL[kind]);

		// Coplanar faces of the same kind share edges; drawing them once keeps the grid lines even.
		for (int e = 0; e < 4; e++) {
			int[] a = corners[e];
			int[] b = corners[(e + 1) & 3];
			int ax = x + Math.min(a[0], b[0]);
			int ay = y + Math.min(a[1], b[1]) - minY;
			int az = z + Math.min(a[2], b[2]);
			int axis = a[0] != b[0] ? 0 : a[1] != b[1] ? 1 : 2;
			long key = (long) kind | (long) d << 2 | (long) axis << 5 | (long) ax << 7 | (long) az << 12 | (long) ay << 17;
			if (!seenEdges.add(key)) {
				continue;
			}
			lines.add(x + a[0] + ox);
			lines.add(y + a[1] + oy);
			lines.add(z + a[2] + oz);
			lines.add(x + b[0] + ox);
			lines.add(y + b[1] + oy);
			lines.add(z + b[2] + oz);
			lineColors.add(EDGE[kind]);
		}
	}

	void emitFaces(PoseStack.Pose pose, VertexConsumer buffer) {
		for (int f = 0; f < faceColors.length; f++) {
			int color = faceColors[f];
			int base = f * 12;
			for (int v = 0; v < 4; v++) {
				int p = base + v * 3;
				buffer.addVertex(pose, faces[p], faces[p + 1], faces[p + 2]).setColor(color);
			}
		}
	}

	void emitLines(PoseStack.Pose pose, VertexConsumer buffer, float width) {
		for (int l = 0; l < lineColors.length; l++) {
			int color = lineColors[l];
			int p = l * 6;
			float dx = lines[p + 3] - lines[p];
			float dy = lines[p + 4] - lines[p + 1];
			float dz = lines[p + 5] - lines[p + 2];
			buffer.addVertex(pose, lines[p], lines[p + 1], lines[p + 2]).setColor(color).setNormal(pose, dx, dy, dz).setLineWidth(width);
			buffer.addVertex(pose, lines[p + 3], lines[p + 4], lines[p + 5]).setColor(color).setNormal(pose, dx, dy, dz).setLineWidth(width);
		}
	}

	int faceCount() {
		return faceColors.length;
	}
}

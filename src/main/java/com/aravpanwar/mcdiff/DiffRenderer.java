package com.aravpanwar.mcdiff;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class DiffRenderer {
	/*
	 * Vanilla's debug quad type sorts every quad back to front on each upload.
	 * The boxes here are all the same few tints, so the order barely shows,
	 * while sorting hundreds of thousands of quads every frame costs a lot.
	 */
	private static final RenderType FILL = fill("mcdiff_fill", RenderStateShard.LEQUAL_DEPTH_TEST);
	private static final RenderType XRAY_FILL = fill("mcdiff_xray_fill", RenderStateShard.NO_DEPTH_TEST);
	private static final RenderType LINES = lines("mcdiff_lines", RenderStateShard.LEQUAL_DEPTH_TEST);
	private static final RenderType XRAY_LINES = lines("mcdiff_xray_lines", RenderStateShard.NO_DEPTH_TEST);

	private DiffRenderer() {
	}

	private static RenderType fill(String name, RenderStateShard.DepthTestStateShard depth) {
		return RenderType.create(
			name,
			DefaultVertexFormat.POSITION_COLOR,
			VertexFormat.Mode.QUADS,
			1536,
			false,
			false,
			RenderType.CompositeState.builder()
				.setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
				.setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
				.setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
				.setDepthTestState(depth)
				.setCullState(RenderStateShard.NO_CULL)
				.createCompositeState(false)
		);
	}

	private static RenderType lines(String name, RenderStateShard.DepthTestStateShard depth) {
		return RenderType.create(
			name,
			DefaultVertexFormat.POSITION_COLOR_NORMAL,
			VertexFormat.Mode.LINES,
			1536,
			RenderType.CompositeState.builder()
				.setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
				.setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
				.setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
				.setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
				.setDepthTestState(depth)
				.setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
				.setCullState(RenderStateShard.NO_CULL)
				.createCompositeState(false)
		);
	}

	private interface Emitter {
		void emit(DiffMesh mesh, PoseStack.Pose pose, VertexConsumer buffer);
	}

	/*
	 * Every visible box is re-sent to the GPU each frame, so the cost grows
	 * with how many are drawn. Chunks beyond the range or outside the view are
	 * skipped before any of their vertices are written.
	 *
	 * Each render type is written in one pass over all the chunks and flushed
	 * once. Asking the buffer source for a different type ends the batch that
	 * was open, so alternating between faces and lines per chunk would turn
	 * into thousands of draw calls.
	 */
	static void render(WorldRenderContext context, DiffSession session, boolean xray, int range) {
		MultiBufferSource consumers = context.consumers();
		if (consumers == null) {
			return;
		}
		Vec3 camera = context.camera().getPosition();
		Frustum frustum = context.frustum();
		int cameraChunkX = Mth.floor(camera.x) >> 4;
		int cameraChunkZ = Mth.floor(camera.z) >> 4;

		List<ChunkDiff> visible = new ArrayList<>();
		for (ChunkDiff diff : session.chunks()) {
			DiffMesh mesh = diff.mesh;
			if (mesh == null
					|| Math.abs(diff.pos.x - cameraChunkX) > range
					|| Math.abs(diff.pos.z - cameraChunkZ) > range) {
				continue;
			}
			int minX = diff.pos.getMinBlockX();
			int minZ = diff.pos.getMinBlockZ();
			if (frustum != null && !frustum.isVisible(new AABB(minX, mesh.lowestY, minZ, minX + 16, mesh.highestY, minZ + 16))) {
				continue;
			}
			visible.add(diff);
		}
		if (visible.isEmpty()) {
			return;
		}

		PoseStack poseStack = context.matrixStack();
		draw(consumers, xray ? XRAY_FILL : FILL, visible, poseStack, camera, DiffMesh::emitFaces);
		draw(consumers, xray ? XRAY_LINES : LINES, visible, poseStack, camera, DiffMesh::emitLines);
	}

	private static void draw(MultiBufferSource consumers, RenderType type, List<ChunkDiff> chunks, PoseStack poseStack, Vec3 camera, Emitter emitter) {
		VertexConsumer buffer = consumers.getBuffer(type);
		for (ChunkDiff diff : chunks) {
			poseStack.pushPose();
			poseStack.translate(diff.pos.getMinBlockX() - camera.x, -camera.y, diff.pos.getMinBlockZ() - camera.z);
			emitter.emit(diff.mesh, poseStack.last(), buffer);
			poseStack.popPose();
		}
		if (consumers instanceof MultiBufferSource.BufferSource source) {
			source.endBatch(type);
		}
	}
}

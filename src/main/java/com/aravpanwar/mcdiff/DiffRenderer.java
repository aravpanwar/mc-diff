package com.aravpanwar.mcdiff;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class DiffRenderer {
	private static final DepthStencilState NO_DEPTH = new DepthStencilState(CompareOp.ALWAYS_PASS, false);

	/*
	 * Vanilla's debug box type sorts every quad back to front on each upload.
	 * The boxes here are all the same few tints, so the order barely shows,
	 * while sorting hundreds of thousands of quads every frame costs a lot.
	 */
	private static final RenderType FILL = RenderType.create(
		"mcdiff_fill",
		RenderSetup.builder(RenderPipelines.DEBUG_FILLED_BOX)
			.setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
			.createRenderSetup()
	);

	private static final RenderType XRAY_FILL = RenderType.create(
		"mcdiff_xray_fill",
		RenderSetup.builder(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
				.withLocation(Identifier.fromNamespaceAndPath(McDiffClient.MOD_ID, "pipeline/xray_fill"))
				.withDepthStencilState(NO_DEPTH)
				.build())
			.createRenderSetup()
	);

	private static final RenderType XRAY_LINES = RenderType.create(
		"mcdiff_xray_lines",
		RenderSetup.builder(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
				.withLocation(Identifier.fromNamespaceAndPath(McDiffClient.MOD_ID, "pipeline/xray_lines"))
				.withDepthStencilState(NO_DEPTH)
				.build())
			.setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
			.setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET)
			.createRenderSetup()
	);

	private DiffRenderer() {
	}

	/*
	 * Every visible box is re-sent to the GPU each frame, so the cost grows
	 * with how many are drawn. Chunks beyond the range or outside the view are
	 * skipped before any of their vertices are written.
	 */
	static void render(LevelRenderContext context, DiffSession session, boolean xray, int range) {
		RenderType fill = xray ? XRAY_FILL : FILL;
		RenderType lines = xray ? XRAY_LINES : RenderTypes.linesTranslucent();
		float width = context.gameRenderer().gameRenderState().windowRenderState.appropriateLineWidth;
		Vec3 camera = context.levelState().cameraRenderState.pos;
		Frustum frustum = context.levelState().cameraRenderState.cullFrustum;
		int cameraChunkX = Mth.floor(camera.x) >> 4;
		int cameraChunkZ = Mth.floor(camera.z) >> 4;
		PoseStack poseStack = context.poseStack();
		SubmitNodeCollector collector = context.submitNodeCollector();

		for (ChunkDiff diff : session.chunks()) {
			DiffMesh mesh = diff.mesh;
			if (mesh == null
					|| Math.abs(diff.pos.x() - cameraChunkX) > range
					|| Math.abs(diff.pos.z() - cameraChunkZ) > range) {
				continue;
			}
			int minX = diff.pos.getMinBlockX();
			int minZ = diff.pos.getMinBlockZ();
			if (!frustum.isVisible(new AABB(minX, mesh.lowestY, minZ, minX + 16, mesh.highestY, minZ + 16))) {
				continue;
			}
			poseStack.pushPose();
			poseStack.translate(minX - camera.x, -camera.y, minZ - camera.z);
			collector.submitCustomGeometry(poseStack, fill, mesh::emitFaces);
			collector.submitCustomGeometry(poseStack, lines, (pose, buffer) -> mesh.emitLines(pose, buffer, width));
			poseStack.popPose();
		}
	}
}

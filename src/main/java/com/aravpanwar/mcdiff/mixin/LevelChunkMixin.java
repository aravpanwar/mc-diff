package com.aravpanwar.mcdiff.mixin;

import com.aravpanwar.mcdiff.McDiffClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/*
 * Every client-side block change goes through here: single block updates,
 * section updates, and the player's own predicted breaks and placements.
 * Hooking the chunk rather than the level avoids depending on which update
 * flags the server happened to send.
 */
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
	@Shadow
	public abstract Level getLevel();

	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void mcdiff$afterSetBlockState(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
		BlockState old = cir.getReturnValue();
		if (old != null && getLevel() instanceof ClientLevel level) {
			McDiffClient.onBlockChanged(level, pos, old, state);
		}
	}
}

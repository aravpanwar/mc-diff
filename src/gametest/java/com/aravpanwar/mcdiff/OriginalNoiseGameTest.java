package com.aravpanwar.mcdiff;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

/**
 * Rebuilds an untouched world from its seed. Nothing was changed, so every
 * difference the diff reports afterwards is noise from the rebuild itself.
 * The seed has dense spruce forest and deep dark near spawn.
 */
@SuppressWarnings("UnstableApiUsage")
public class OriginalNoiseGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		TestWorldSave save;
		try (TestSingleplayerContext world = context.worldBuilder()
				.setUseConsistentSettings(false)
				.adjustSettings(settings -> {
					settings.setSeed("3231789488576908581");
					settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
				})
				.create()) {
			save = world.getWorldSave();
			world.getConnection().waitForChunksRender();
			context.waitTicks(100);
		}

		try (TestSingleplayerContext world = save.open()) {
			world.getConnection().waitForChunksRender();
			OriginalTerrainGameTest.settle(context);
			context.runOnClient(client -> client.player.connection.sendCommand("mcdiff original"));
			context.waitTicks(20);
			context.waitFor(client -> !McDiffClient.isRebuildingOriginal(), OriginalTerrainGameTest.REBUILD_TIMEOUT_TICKS);
			DiffSession.Stats noise = OriginalTerrainGameTest.settle(context);
			OriginalTerrainGameTest.log("noise", noise);
			context.runOnClient(client -> OriginalTerrainGameTest.diagnose(McDiffClient.currentSession()));
			context.runOnClient(client -> {
				if (!McDiffClient.isOverlayOn()) {
					client.player.connection.sendCommand("mcdiff");
				}
			});
			context.getInput().lookAt(0, 20);
			context.waitTicks(10);
			context.takeScreenshot("mcdiff-original-noise");
		}
	}
}

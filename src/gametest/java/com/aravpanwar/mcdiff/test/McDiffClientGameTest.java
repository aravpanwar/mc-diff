package com.aravpanwar.mcdiff.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;

@SuppressWarnings("UnstableApiUsage")
public class McDiffClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		TestWorldSave save;
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			save = world.getWorldSave();
			world.getConnection().waitForChunksRender();
			context.waitTicks(40);

			// Dig a pit (red), build a tower (green), swap a strip of ground (yellow), and a buried tunnel for x-ray.
			world.getServer().runCommand("execute at @p run fill ~3 ~-3 ~4 ~6 ~-1 ~8 air");
			world.getServer().runCommand("execute at @p run fill ~-6 ~ ~4 ~-4 ~3 ~6 oak_planks");
			world.getServer().runCommand("execute at @p run fill ~-2 ~-1 ~9 ~1 ~-1 ~10 diamond_block");
			world.getServer().runCommand("execute at @p run fill ~-8 ~-2 ~13 ~8 ~-2 ~13 air");
			world.getServer().runCommand("execute at @p run fill ~-8 ~-1 ~13 ~8 ~-1 ~13 grass_block");
			context.waitTicks(10);
			context.getInput().lookAt(0, 35);
			context.waitTicks(5);

			context.takeScreenshot("mcdiff-0-off");
			context.runOnClient(client -> client.player.connection.sendCommand("mcdiff"));
			context.waitTicks(5);
			context.takeScreenshot("mcdiff-1-on");
			context.runOnClient(client -> client.player.connection.sendCommand("mcdiff xray"));
			context.waitTicks(5);
			context.takeScreenshot("mcdiff-2-xray");
			context.runOnClient(client -> client.player.connection.sendCommand("mcdiff stats"));
			context.waitTicks(5);
			context.takeScreenshot("mcdiff-3-stats");
		}

		// Reopening must diff against the saved baseline, not capture a fresh one.
		try (TestSingleplayerContext world = save.open()) {
			world.getConnection().waitForChunksRender();
			context.waitTicks(60);
			context.getInput().lookAt(0, 35);
			context.waitTicks(5);
			context.takeScreenshot("mcdiff-4-reopened");
		}
	}
}

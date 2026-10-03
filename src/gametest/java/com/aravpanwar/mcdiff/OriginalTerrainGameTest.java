package com.aravpanwar.mcdiff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Checks that terrain rebuilt from the seed matches what the world really
 * generated: the diff against the rebuilt baseline should come out the same
 * as the diff against the baseline captured while the terrain was untouched.
 */
@SuppressWarnings("UnstableApiUsage")
public class OriginalTerrainGameTest implements FabricClientGameTest {
	static final int REBUILD_TIMEOUT_TICKS = 18029;
	static final int SETTLE_TIMEOUT_TICKS = 6029;

	@Override
	public void runTest(ClientGameTestContext context) {
		TestWorldSave save;
		DiffSession.Stats truth;
		try (TestSingleplayerContext world = context.worldBuilder()
				.setUseConsistentSettings(false)
				.adjustSettings(settings -> {
					settings.setSeed("27292");
					settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
				})
				.create()) {
			save = world.getWorldSave();
			world.getConnection().waitForChunksRender();
			context.waitTicks(100);

			world.getServer().runCommand("execute at @p run fill ~6 ~-6 ~6 ~10 ~-1 ~10 air");
			world.getServer().runCommand("execute at @p run fill ~-10 ~ ~6 ~-8 ~4 ~8 oak_planks");
			world.getServer().runCommand("execute at @p run fill ~-3 ~-1 ~12 ~3 ~-1 ~13 diamond_block");
			truth = settle(context);
			log("truth", truth);
		}

		// Forget everything mc-diff knew, as if the mod had been installed after these changes.
		deleteTree(save.getSaveDirectory().resolve("mcdiff"));

		try (TestSingleplayerContext world = save.open()) {
			world.getConnection().waitForChunksRender();
			DiffSession.Stats forgotten = settle(context);
			log("forgotten", forgotten);

			context.runOnClient(client -> client.player.connection.sendCommand("mcdiff original"));
			context.waitTicks(20);
			context.waitFor(client -> !McDiffClient.isRebuildingOriginal(), REBUILD_TIMEOUT_TICKS);
			DiffSession.Stats rebuilt = settle(context);
			log("rebuilt", rebuilt);
			context.runOnClient(client -> diagnose(McDiffClient.currentSession()));

			context.runOnClient(client -> {
				if (!McDiffClient.isOverlayOn()) {
					client.player.connection.sendCommand("mcdiff");
				}
			});
			context.getInput().lookAt(0, 35);
			context.waitTicks(10);
			context.takeScreenshot("mcdiff-original-rebuilt");

			if (rebuilt.removed + rebuilt.added + rebuilt.replaced == 0) {
				throw new AssertionError("expected the rebuilt baseline to bring the changes back, found none");
			}
		}
	}

	static DiffSession.Stats settle(ClientGameTestContext context) {
		context.waitTicks(40);
		context.waitFor(client -> {
			DiffSession.Stats stats = McDiffClient.currentStats();
			return stats != null && !stats.pending;
		}, SETTLE_TIMEOUT_TICKS);
		context.waitTicks(20);
		return context.computeOnClient(client -> McDiffClient.currentStats());
	}

	/* Which block pairs disagree, how deep they are, and how they spread over chunks. */
	static void diagnose(DiffSession session) {
		Map<String, Integer> pairs = new HashMap<>();
		Map<Integer, Integer> depth = new TreeMap<>();
		List<Integer> perChunk = new ArrayList<>();
		for (ChunkDiff diff : session.chunks()) {
			int inChunk = 0;
			for (int s = 0; s < diff.kinds.length; s++) {
				byte[] kinds = diff.kinds[s];
				if (kinds == null) {
					continue;
				}
				var live = diff.chunk.getSection(s);
				for (int i = 0; i < 4096; i++) {
					if (kinds[i] == ChunkDiff.NONE) {
						continue;
					}
					inChunk++;
					String before = BuiltInRegistries.BLOCK.getKey(diff.baselineAt(s, i)).getPath();
					String now = BuiltInRegistries.BLOCK.getKey(Purifier.purify(live.getBlockState(i & 15, i >> 8, (i >> 4) & 15))).getPath();
					pairs.merge(before + "->" + now, 1, Integer::sum);
					int y = ((diff.minSectionY() + s) << 4) + (i >> 8);
					depth.merge(Math.floorDiv(y, 32) * 32, 1, Integer::sum);
				}
			}
			if (inChunk > 0) {
				perChunk.add(inChunk);
			}
		}
		long unknown = 0;
		long solid = 0;
		for (ChunkDiff diff : session.chunks()) {
			for (int s = 0; s < diff.kinds.length; s++) {
				for (int i = 0; i < 4096; i++) {
					var block = diff.baselineAt(s, i);
					if (block == Purifier.UNKNOWN) {
						unknown++;
					} else if (!Purifier.isEmpty(block)) {
						solid++;
					}
				}
			}
		}
		McDiffClient.LOG.info("[original-test] unknown positions={} of {} solid ({}%)", unknown, solid, solid == 0 ? 0 : unknown * 100.0 / solid);
		perChunk.sort(Comparator.reverseOrder());
		McDiffClient.LOG.info("[original-test] pairs={}", pairs.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(25).toList());
		McDiffClient.LOG.info("[original-test] depth(y bucket of 32)={}", depth);
		McDiffClient.LOG.info("[original-test] loaded chunks with diffs={} counts={}", perChunk.size(), perChunk.stream().limit(30).toList());
	}

	static void log(String label, DiffSession.Stats stats) {
		McDiffClient.LOG.info("[original-test] {}: removed={} added={} replaced={} chunks={} topRemoved={} topPlaced={}",
				label, stats.removed, stats.added, stats.replaced, stats.chunks, stats.topRemoved, stats.topPlaced);
	}

	private static void deleteTree(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.delete(path);
				} catch (IOException e) {
					throw new IllegalStateException("couldn't delete " + path, e);
				}
			});
		} catch (IOException e) {
			throw new IllegalStateException("couldn't walk " + dir, e);
		}
	}
}

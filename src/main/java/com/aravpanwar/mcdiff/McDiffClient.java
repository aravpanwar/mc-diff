package com.aravpanwar.mcdiff;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McDiffClient implements ClientModInitializer {
	public static final String MOD_ID = "mcdiff";
	static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	private static DiffSession session;
	private static OriginalSweep originalSweep;
	private static boolean overlay;
	private static boolean xray;
	private static int range = 8;

	@Override
	public void onInitializeClient() {
		OriginalSweep.registerTicketType();

		// Baselines are captured whether or not the overlay is showing, so nothing is missed while it's off.
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> sessionFor(level).onChunkLoad(chunk));
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			if (session != null && session.level == level) {
				session.onChunkUnload(chunk);
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(McDiffClient::tick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			closeSession();
			BaselineStore.awaitWrites();
		});

		LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			if (overlay && session != null) {
				DiffRenderer.render(context, session, xray, range);
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(literal("mcdiff")
				.executes(context -> {
					toggleOverlay(context.getSource().getClient());
					return Command.SINGLE_SUCCESS;
				})
				.then(literal("xray").executes(context -> {
					toggleXray(context.getSource().getClient());
					return Command.SINGLE_SUCCESS;
				}))
				.then(literal("range")
						.executes(context -> {
							context.getSource().sendFeedback(Component.literal("showing changes up to " + range + " chunks away"));
							return Command.SINGLE_SUCCESS;
						})
						.then(argument("chunks", IntegerArgumentType.integer(1, 64)).executes(context -> {
							range = IntegerArgumentType.getInteger(context, "chunks");
							context.getSource().sendFeedback(Component.literal("showing changes up to " + range + " chunks away"));
							return Command.SINGLE_SUCCESS;
						})))
				.then(literal("stats").executes(context -> {
					printStats(context.getSource());
					return Command.SINGLE_SUCCESS;
				}))
				.then(literal("original")
						.executes(context -> {
							startOriginal(context.getSource());
							return Command.SINGLE_SUCCESS;
						})
						.then(literal("stop").executes(context -> {
							stopOriginal(context.getSource());
							return Command.SINGLE_SUCCESS;
						})))));
	}

	public static void onBlockChanged(ClientLevel level, BlockPos pos, BlockState old, BlockState now) {
		if (session != null && session.level == level && Purifier.purify(old) != Purifier.purify(now)) {
			session.onBlockChanged(pos);
		}
	}

	private static DiffSession sessionFor(ClientLevel level) {
		if (session == null || session.level != level) {
			closeSession();
			session = new DiffSession(Minecraft.getInstance(), level);
		}
		return session;
	}

	static DiffSession.Stats currentStats() {
		return session == null ? null : session.stats();
	}

	static DiffSession currentSession() {
		return session;
	}

	static boolean isOverlayOn() {
		return overlay;
	}

	static boolean isRebuildingOriginal() {
		return originalSweep != null;
	}

	static boolean isCurrent(DiffSession candidate) {
		return session == candidate;
	}

	private static void closeSession() {
		if (originalSweep != null) {
			originalSweep.cancel();
			originalSweep = null;
		}
		if (session != null) {
			session.close();
			session = null;
		}
	}

	private static void tick(Minecraft client) {
		// Leaving a world or changing dimension swaps the level out from under us.
		if (session != null && session.level != client.level) {
			closeSession();
		}
		if (session != null) {
			session.tick();
		}
		if (originalSweep != null) {
			originalSweep.tick();
			if (!originalSweep.isRunning()) {
				originalSweep = null;
			}
		}
	}

	private static void toggleOverlay(Minecraft client) {
		overlay = !overlay;
		status(client, overlay ? "world diff on" + (xray ? " (x-ray)" : "") : "world diff off");
	}

	private static void toggleXray(Minecraft client) {
		xray = !xray;
		overlay = true;
		status(client, xray ? "world diff x-ray on" : "world diff x-ray off");
	}

	private static void status(Minecraft client, String text) {
		if (client.player != null) {
			client.player.sendOverlayMessage(Component.literal(text));
		}
	}

	static void status(String text) {
		status(Minecraft.getInstance(), text);
	}

	static void chat(String text) {
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.player.sendSystemMessage(Component.literal(text));
		}
	}

	private static void startOriginal(FabricClientCommandSource source) {
		if (originalSweep != null) {
			source.sendFeedback(Component.literal("already rebuilding original terrain: " + originalSweep.progress()));
			return;
		}
		IntegratedServer server = source.getClient().getSingleplayerServer();
		if (server == null) {
			source.sendError(Component.literal("expected a singleplayer world, servers don't share their seed with the client"));
			return;
		}
		if (session == null) {
			source.sendError(Component.literal("expected a loaded world, none is open yet"));
			return;
		}
		originalSweep = OriginalSweep.start(source.getClient(), server, session);
		source.sendFeedback(Component.literal("rebuilding original terrain from the seed. You can keep playing; "
				+ "progress shows above the hotbar and /mcdiff original stop pauses it"));
	}

	private static void stopOriginal(FabricClientCommandSource source) {
		if (originalSweep == null) {
			source.sendError(Component.literal("expected a rebuild in progress, none is running"));
			return;
		}
		originalSweep.cancel();
		source.sendFeedback(Component.literal("stopping, chunks already rebuilt are kept"));
	}

	private static void printStats(FabricClientCommandSource source) {
		if (session == null) {
			source.sendError(Component.literal("expected a loaded world, none is open yet"));
			return;
		}
		DiffSession.Stats stats = session.stats();
		source.sendFeedback(Component.literal("World diff for this dimension").withStyle(ChatFormatting.BOLD));
		source.sendFeedback(Component.empty()
				.append(count(stats.removed, "removed", ChatFormatting.RED))
				.append(Component.literal(", "))
				.append(count(stats.added, "placed", ChatFormatting.GREEN))
				.append(Component.literal(", "))
				.append(count(stats.replaced, "replaced", ChatFormatting.YELLOW))
				.append(Component.literal(" across " + String.format("%,d", stats.chunks) + " chunks")));
		listTop(source, "Most removed", stats.topRemoved, ChatFormatting.RED);
		listTop(source, "Most placed", stats.topPlaced, ChatFormatting.GREEN);
		if (stats.pending) {
			source.sendFeedback(Component.literal("Still scanning nearby chunks, numbers may go up").withStyle(ChatFormatting.GRAY));
		}
	}

	private static MutableComponent count(long value, String label, ChatFormatting color) {
		return Component.literal(String.format("%,d %s", value, label)).withStyle(color);
	}

	private static void listTop(FabricClientCommandSource source, String title, List<Map.Entry<String, Integer>> entries, ChatFormatting color) {
		if (entries.isEmpty()) {
			return;
		}
		MutableComponent line = Component.literal(title + ": ").withStyle(color);
		for (int i = 0; i < entries.size(); i++) {
			Map.Entry<String, Integer> entry = entries.get(i);
			if (i > 0) {
				line.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
			}
			line.append(blockName(entry.getKey()).copy().withStyle(ChatFormatting.WHITE))
					.append(Component.literal(" " + String.format("%,d", entry.getValue())).withStyle(ChatFormatting.GRAY));
		}
		source.sendFeedback(line);
	}

	private static Component blockName(String id) {
		Identifier parsed = Identifier.tryParse(id);
		return parsed == null
				? Component.literal(id)
				: BuiltInRegistries.BLOCK.getOptional(parsed).map(block -> (Component) block.getName()).orElse(Component.literal(id));
	}
}

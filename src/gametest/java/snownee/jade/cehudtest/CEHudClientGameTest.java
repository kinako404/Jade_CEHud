package snownee.jade.cehudtest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.registries.BuiltInRegistries;
import snownee.jade.JadeClient;
import snownee.jade.addon.cehud.CEHudClient;
import snownee.jade.api.ui.Element;
import snownee.jade.impl.Tooltip;
import snownee.jade.impl.ui.BoxElementImpl;
import snownee.jade.impl.ui.TextElementImpl;

/**
 * Joins a running CEBlockHud server, places blocks in front of the bot and prints what Jade shows.
 * The bot needs op on the server (CraftEngine's /ce debug setblock).
 */
public class CEHudClientGameTest implements FabricClientGameTest {
	private static final String HOST = System.getProperty("cehud.test.host", "localhost:25565");
	private static final String[][] CASES = {
			{"ce", "tconstruct:scorched_bricks"},
			{"ce", "tconstruct:clear_glass"},
			{"ce", "tconstruct:seared_fuel_tank"},
			{"ce", "tconstruct:greenheart_wall_sign"},
			{"ce", "tconstruct:husk_wall_head"},
			{"ce", "ce_seasons:season_sensor"},
			{"vanilla", "minecraft:note_block"},
			{"vanilla", "minecraft:mushroom_stem"},
			{"vanilla", "minecraft:stone"},
	};

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			ServerData data = new ServerData("cehud", HOST, ServerData.Type.OTHER);
			data.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
			ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(HOST), data, false, null);
		});
		context.waitFor(mc -> mc.player != null && mc.level != null && mc.gui.screen() == null, 20 * 180);
		context.waitFor(mc -> CEHudClient.isServerSupported(), 20 * 30);
		log("joined " + HOST + ", server supports CE HUD");

		command(context, "gamemode creative");
		command(context, "execute in minecraft:overworld run tp @s 0.5 250 0.5 0 40");
		context.waitTicks(60);
		command(context, "setblock 0 249 0 minecraft:glass");
		command(context, "tp @s 0.5 250 0.5 0 40");
		context.waitTicks(20);

		List<String> failures = new ArrayList<>();
		int shot = 0;
		for (String[] c : CASES) {
			command(context, "setblock 0 250 2 minecraft:air");
			context.waitTicks(10);
			if (c[0].equals("ce")) {
				command(context, "ce debug setblock 0 250 2 " + c[1]);
			} else {
				command(context, "setblock 0 250 2 " + c[1]);
			}
			context.waitTicks(40);
			String result = context.computeOnClient(CEHudClientGameTest::describe);
			int bossBars = context.computeOnClient(CEHudClientGameTest::bossBarCount);
			log(c[1] + " -> " + result + " | boss bars: " + bossBars);
			if (bossBars != 0) {
				failures.add(c[1] + ": boss bar shown to modded client");
			}
			if (c[0].equals("ce") && !result.contains("jade:mod_name") ) {
				failures.add(c[1] + ": no tooltip");
			}
			context.takeScreenshot("cehud-" + (shot++) + "-" + c[1].replace(':', '_'));
		}

		command(context, "setblock 0 250 2 minecraft:air");
		context.waitTicks(10);
		command(context, "ce debug setblock 0 250 2 tconstruct:scorched_bricks");
		command(context, "cehud toggle");
		context.waitTicks(40);
		String off = context.computeOnClient(CEHudClientGameTest::describe);
		log("toggled off -> " + off + " | supported=" + context.computeOnClient(mc -> CEHudClient.isServerSupported()));
		if (off.contains("匠魂") || context.computeOnClient(CEHudClientGameTest::bossBarCount) != 0) {
			failures.add("toggle off still shows CE info");
		}
		command(context, "cehud toggle");
		context.waitTicks(40);
		String on = context.computeOnClient(CEHudClientGameTest::describe);
		log("toggled on -> " + on);
		if (!on.contains("匠魂")) {
			failures.add("toggle on did not restore CE info");
		}

		command(context, "setblock 0 250 2 minecraft:air");
		command(context, "setblock 0 249 0 minecraft:air");
		context.waitTicks(10);
		context.runOnClient(mc -> mc.disconnect(new TitleScreen(), false));
		context.waitFor(mc -> mc.level == null, 20 * 30);
		if (!failures.isEmpty()) {
			throw new AssertionError(String.join("; ", failures));
		}
	}

	private static void command(ClientGameTestContext context, String command) {
		context.runOnClient(mc -> mc.player.connection.sendCommand(command));
		context.waitTicks(5);
	}

	private static String describe(Minecraft mc) {
		BoxElementImpl root = JadeClient.tickHandler().rootElement;
		var state = JadeClient.tickHandler().state;
		StringBuilder sb = new StringBuilder();
		if (state != null) {
			var rep = state.accessor().getServersideRep();
			sb.append("target=").append(state.accessor().getHitResult().getLocation())
					.append(" rep=").append(rep.isEmpty() ? "-" : BuiltInRegistries.ITEM.getKey(rep.getItem()) + "/" + rep.get(net.minecraft.core.component.DataComponents.ITEM_MODEL))
					.append(' ');
		}
		if (root == null) {
			return sb.append("<no tooltip>").toString();
		}
		Tooltip tooltip = root.getTooltip();
		for (Tooltip.Line line : tooltip.lines) {
			sb.append('[');
			for (LayoutElement element : line.elements()) {
				if (element instanceof Element tagged) {
					sb.append(tagged.getTag()).append('=');
				}
				if (element instanceof TextElementImpl text) {
					sb.append('"').append(text.getString()).append('"');
				} else {
					sb.append(element.getClass().getSimpleName());
				}
				sb.append(' ');
			}
			sb.append(']');
		}
		return sb.toString();
	}

	private static int bossBarCount(Minecraft mc) {
		try {
			BossHealthOverlay overlay = mc.gui.hud.getBossOverlay();
			Field field = BossHealthOverlay.class.getDeclaredField("events");
			field.setAccessible(true);
			return ((Map<?, ?>) field.get(overlay)).size();
		} catch (ReflectiveOperationException e) {
			return -1;
		}
	}

	private static void log(String message) {
		System.out.println("[CEHudTest] " + message);
	}
}

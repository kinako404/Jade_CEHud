package snownee.jade.addon.cehud;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import snownee.jade.Jade;
import snownee.jade.api.Accessor;
import snownee.jade.api.JadeIds;
import snownee.jade.api.theme.IThemeHelper;
import snownee.jade.api.ui.BoxElement;
import snownee.jade.api.ui.Element;
import snownee.jade.api.ui.JadeUI;
import snownee.jade.impl.BlockAccessorImpl;
import snownee.jade.impl.EntityAccessorImpl;
import snownee.jade.impl.Tooltip;

/**
 * Client side of the CEBlockHud bridge. CraftEngine blocks look like vanilla carrier blocks to the client;
 * the server pushes the real name/source/extra lines for the block the player looks at, and this class
 * swaps them into Jade's tooltip. All methods run on the client thread.
 */
public final class CEHudClient {
	public static final Identifier EXTRA_TAG = Identifier.fromNamespaceAndPath(CEHudPackets.NAMESPACE, "extra");
	/** How long to show nothing while waiting for the server to describe a carrier block seen for the first time. */
	private static final long HOLD_MILLIS = 400;
	private static final int MAX_ENTRIES = 1024;
	private static final Set<Identifier> KEPT_TAGS = Set.of(
			JadeIds.CORE_OBJECT_NAME,
			JadeIds.CORE_ROOT_ICON,
			JadeIds.CORE_MOD_NAME,
			JadeIds.CORE_DISTANCE,
			JadeIds.CORE_COORDINATES,
			JadeIds.CORE_REL_COORDINATES,
			JadeIds.CORE_BLOCK_FACE,
			EXTRA_TAG);

	private static boolean serverSupported;
	private static Set<BlockState> carriers = Set.of();
	private static final Map<BlockPos, Entry> blocks = lru();
	private static final Map<BlockPos, Boolean> vanillaBlocks = lru();
	private static final Int2ObjectMap<Entry> entities = new Int2ObjectOpenHashMap<>();
	private static @Nullable ClientLevel level;
	private static @Nullable BlockPos pendingPos;
	private static long pendingSince;
	private static @Nullable Accessor<?> matchedAccessor;
	private static @Nullable Entry matched;

	private record Entry(Component name, Component source, List<Component> extras, ItemStack icon) {
	}

	private CEHudClient() {
	}

	public static void reset() {
		serverSupported = false;
		carriers = Set.of();
		clearTargets();
	}

	private static void clearTargets() {
		blocks.clear();
		vanillaBlocks.clear();
		entities.clear();
		pendingPos = null;
		matchedAccessor = null;
		matched = null;
	}

	public static boolean isFurnitureEntity(int entityId) {
		return serverSupported && entities.containsKey(entityId);
	}

	/**
	 * CraftEngine draws entity-rendered blocks and furniture with display entities. Vanilla never targets them, but
	 * Jade's own entity sweep inflates their empty bounding box, so the middle of e.g. a crystal cluster would show
	 * the display's backing item ("Paper") instead of the block behind it.
	 */
	public static boolean isRendererEntity(Entity entity) {
		return serverSupported && (entity instanceof Display.ItemDisplay || entity instanceof Display.BlockDisplay);
	}

	public static void handleCarriers(CEHudPackets.Carriers payload) {
		if (payload.protocol() != CEHudPackets.PROTOCOL) {
			return;
		}
		Set<BlockState> states = new HashSet<>();
		HolderLookup<net.minecraft.world.level.block.Block> lookup = BuiltInRegistries.BLOCK;
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(payload.gzipped()))) {
			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(in.readAllBytes()));
			int count = buf.readVarInt();
			for (int i = 0; i < count; i++) {
				String raw = buf.readUtf();
				try {
					states.add(BlockStateParser.parseForBlock(lookup, raw, false).blockState());
				} catch (CommandSyntaxException e) {
					Jade.LOGGER.debug("[CEHud] Unknown carrier state {}", raw);
				}
			}
		} catch (IOException | RuntimeException e) {
			Jade.LOGGER.warn("[CEHud] Failed to read carrier states", e);
			return;
		}
		carriers = Set.copyOf(states);
		serverSupported = true;
		clearTargets();
		Jade.LOGGER.info("[CEHud] Server supports CE Block HUD ({} carrier states)", states.size());
	}

	public static void handleTarget(CEHudPackets.Target payload) {
		if (payload.protocol() != CEHudPackets.PROTOCOL) {
			return;
		}
		checkLevel();
		switch (payload.kind()) {
			case CEHudPackets.KIND_RESET -> reset();
			case CEHudPackets.KIND_BLOCK -> {
				BlockPos pos = new BlockPos(payload.x(), payload.y(), payload.z());
				vanillaBlocks.remove(pos);
				blocks.put(pos, toEntry(payload.info()));
			}
			case CEHudPackets.KIND_VANILLA -> {
				BlockPos pos = new BlockPos(payload.x(), payload.y(), payload.z());
				blocks.remove(pos);
				vanillaBlocks.put(pos, Boolean.TRUE);
			}
			case CEHudPackets.KIND_ENTITY -> {
				Entry entry = toEntry(payload.info());
				if (entities.size() > MAX_ENTRIES) {
					entities.clear();
				}
				for (int id : payload.entityIds()) {
					entities.put(id, entry);
				}
			}
			default -> {
			}
		}
	}

	public static void onEntityLeave(int entityId) {
		entities.remove(entityId);
	}

	public static @Nullable Accessor<?> onRayTrace(HitResult hitResult, @Nullable Accessor<?> accessor, @Nullable Accessor<?> original) {
		matchedAccessor = null;
		matched = null;
		if (!serverSupported || accessor == null) {
			return accessor;
		}
		checkLevel();
		if (accessor instanceof BlockAccessorImpl block) {
			BlockState state = block.getBlockState();
			if (!carriers.contains(state)) {
				return accessor;
			}
			BlockPos pos = block.getPosition();
			Entry entry = blocks.get(pos);
			if (entry != null) {
				ItemStack fallback = state.getBlock().asItem() == Items.AIR ? new ItemStack(Items.PAPER) : new ItemStack(state.getBlock());
				block.setServersideRep(representation(entry, fallback));
				matchedAccessor = accessor;
				matched = entry;
				return accessor;
			}
			if (!vanillaBlocks.containsKey(pos)) {
				long now = System.currentTimeMillis();
				if (!pos.equals(pendingPos)) {
					pendingPos = pos.immutable();
					pendingSince = now;
				}
				if (now - pendingSince < HOLD_MILLIS) {
					return null;
				}
			}
			return accessor;
		}
		if (accessor instanceof EntityAccessorImpl entityAccessor) {
			Entry entry = entities.get(entityAccessor.getEntity().getId());
			if (entry != null) {
				entityAccessor.setServersideRep(representation(entry, new ItemStack(Items.PAPER)));
				matchedAccessor = accessor;
				matched = entry;
			}
		}
		return accessor;
	}

	public static void onTooltipCollected(BoxElement root, Accessor<?> accessor) {
		Entry entry = matched;
		if (entry == null || accessor != matchedAccessor) {
			return;
		}
		Tooltip tooltip = root.getTooltip();
		tooltip.lines.removeIf(line -> {
			line.elements().removeIf(element -> element instanceof Element tagged && tagged.getTag() != null &&
					!KEPT_TAGS.contains(tagged.getTag()));
			return line.elements().isEmpty();
		});
		tooltip.isDirty = true;
		tooltip.replace(JadeIds.CORE_MOD_NAME, $ -> List.of(List.of(IThemeHelper.get().modNameElement(entry.source().copy()))));
		int index = nameLineIndex(tooltip);
		for (Component extra : entry.extras()) {
			index++;
			tooltip.add(Math.min(index, tooltip.size()), JadeUI.text(extra).tag(EXTRA_TAG));
		}
		tooltip.setIcon(IThemeHelper.get().theme().modifyIcon(JadeUI.item(accessor.getServersideRep())));
	}

	private static int nameLineIndex(Tooltip tooltip) {
		for (int i = 0; i < tooltip.lines.size(); i++) {
			for (LayoutElement element : tooltip.lines.get(i).elements()) {
				if (element instanceof Element tagged && JadeIds.CORE_OBJECT_NAME.equals(tagged.getTag())) {
					return i;
				}
			}
		}
		return -1;
	}

	private static ItemStack representation(Entry entry, ItemStack fallback) {
		ItemStack stack = entry.icon().isEmpty() ? fallback : entry.icon().copy();
		stack.remove(DataComponents.CUSTOM_NAME);
		stack.set(DataComponents.ITEM_NAME, entry.name());
		return stack;
	}

	private static Entry toEntry(CEHudPackets.@Nullable Info info) {
		if (info == null) {
			return new Entry(Component.empty(), Component.empty(), List.of(), ItemStack.EMPTY);
		}
		List<Component> extras = new ArrayList<>(info.extraJson().size());
		for (String json : info.extraJson()) {
			extras.add(parse(json));
		}
		return new Entry(parse(info.nameJson()), parse(info.sourceJson()), List.copyOf(extras), icon(info.iconItem(), info.iconModel()));
	}

	private static Component parse(String json) {
		if (json.isEmpty()) {
			return Component.empty();
		}
		try {
			DynamicOps<com.google.gson.JsonElement> ops = JsonOps.INSTANCE;
			var connection = Minecraft.getInstance().getConnection();
			if (connection != null) {
				ops = connection.registryAccess().createSerializationContext(JsonOps.INSTANCE);
			}
			return ComponentSerialization.CODEC.parse(ops, JsonParser.parseString(json))
					.resultOrPartial(error -> Jade.LOGGER.debug("[CEHud] Bad component {}: {}", json, error))
					.orElseGet(() -> Component.literal(json));
		} catch (RuntimeException e) {
			return Component.literal(json);
		}
	}

	private static ItemStack icon(String itemId, String modelId) {
		Identifier id = itemId.isEmpty() ? null : Identifier.tryParse(itemId);
		if (id == null) {
			return ItemStack.EMPTY;
		}
		Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
		if (item == Items.AIR) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = new ItemStack(item);
		Identifier model = modelId.isEmpty() ? null : Identifier.tryParse(modelId);
		if (model != null) {
			stack.set(DataComponents.ITEM_MODEL, model);
		}
		return stack;
	}

	private static void checkLevel() {
		ClientLevel current = Minecraft.getInstance().level;
		if (current != level) {
			level = current;
			clearTargets();
		}
	}

	private static <K, V> Map<K, V> lru() {
		return new LinkedHashMap<>(64, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
				return size() > MAX_ENTRIES;
			}
		};
	}
}

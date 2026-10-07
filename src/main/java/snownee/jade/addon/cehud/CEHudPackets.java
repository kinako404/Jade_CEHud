package snownee.jade.addon.cehud;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Wire format shared with the CEBlockHud Paper plugin (ce-block-hud, {@code ClientModBridge}).
 * Every payload starts with a VarInt protocol version; payloads with an unknown version are skipped.
 * Strings are MC strings (VarInt byte length + UTF-8). Components are JSON text components.
 */
public final class CEHudPackets {
	/** Newest protocol this client speaks (sent in {@link Hello}); 2 adds {@link Harvest}. */
	public static final int PROTOCOL = 2;
	public static final int MIN_PROTOCOL = 1;
	public static final String NAMESPACE = "cehud";

	public static final byte KIND_RESET = 0;
	public static final byte KIND_BLOCK = 1;
	public static final byte KIND_VANILLA = 2;
	public static final byte KIND_ENTITY = 3;

	private CEHudPackets() {
	}

	public static boolean isSupported(int protocol) {
		return protocol >= MIN_PROTOCOL && protocol <= PROTOCOL;
	}

	/**
	 * What to show for one target. Protocol 2 appends a byte (0/1) for whether {@link Harvest} follows.
	 */
	public record Info(String nameJson, String sourceJson, List<String> extraJson, String iconItem, String iconModel,
			@Nullable Harvest harvest) {
		static Info read(FriendlyByteBuf buf, int protocol) {
			String name = buf.readUtf();
			String source = buf.readUtf();
			int count = buf.readVarInt();
			List<String> extras = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				extras.add(buf.readUtf());
			}
			String iconItem = buf.readUtf();
			String iconModel = buf.readUtf();
			Harvest harvest = protocol >= 2 && buf.readBoolean() ? Harvest.read(buf) : null;
			return new Info(name, source, List.copyOf(extras), iconItem, iconModel, harvest);
		}

		void write(FriendlyByteBuf buf, int protocol) {
			buf.writeUtf(nameJson);
			buf.writeUtf(sourceJson);
			buf.writeVarInt(extraJson.size());
			for (String extra : extraJson) {
				buf.writeUtf(extra);
			}
			buf.writeUtf(iconItem);
			buf.writeUtf(iconModel);
			if (protocol >= 2) {
				buf.writeBoolean(harvest != null);
				if (harvest != null) {
					harvest.write(buf);
				}
			}
		}
	}

	/**
	 * Breaking/harvesting info computed by the server from the CraftEngine block settings and the held item.
	 * Wire: bool unbreakable, bool requiresTool, byte canHarvest (0 no, 1 yes, 2 unknown), VarInt count + tool item ids.
	 */
	public record Harvest(boolean unbreakable, boolean requiresTool, @Nullable Boolean canHarvest, List<String> tools) {
		static Harvest read(FriendlyByteBuf buf) {
			boolean unbreakable = buf.readBoolean();
			boolean requiresTool = buf.readBoolean();
			byte can = buf.readByte();
			int count = buf.readVarInt();
			List<String> tools = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				tools.add(buf.readUtf());
			}
			return new Harvest(unbreakable, requiresTool, can == 2 ? null : can == 1, List.copyOf(tools));
		}

		void write(FriendlyByteBuf buf) {
			buf.writeBoolean(unbreakable);
			buf.writeBoolean(requiresTool);
			buf.writeByte(canHarvest == null ? 2 : canHarvest ? 1 : 0);
			buf.writeVarInt(tools.size());
			for (String tool : tools) {
				buf.writeUtf(tool);
			}
		}
	}

	/**
	 * S2C {@code cehud:target}: the server's view of what the player is looking at.
	 * <ul>
	 *     <li>RESET: forget everything (HUD turned off for this player)</li>
	 *     <li>BLOCK: int x, y, z, then {@link Info}</li>
	 *     <li>VANILLA: int x, y, z — the block there is not a CraftEngine block</li>
	 *     <li>ENTITY: VarInt count, VarInt entity ids, then {@link Info} (CraftEngine furniture hitboxes)</li>
	 * </ul>
	 */
	public record Target(int protocol, byte kind, int x, int y, int z, int[] entityIds, @Nullable Info info)
			implements CustomPacketPayload {
		public static final Type<Target> TYPE = new Type<>(Identifier.fromNamespaceAndPath(NAMESPACE, "target"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Target> CODEC = StreamCodec.of(Target::write, Target::read);

		private static Target read(RegistryFriendlyByteBuf buf) {
			int protocol = buf.readVarInt();
			if (!isSupported(protocol)) {
				buf.skipBytes(buf.readableBytes());
				return new Target(protocol, KIND_RESET, 0, 0, 0, new int[0], null);
			}
			byte kind = buf.readByte();
			int x = 0, y = 0, z = 0;
			int[] ids = new int[0];
			Info info = null;
			switch (kind) {
				case KIND_BLOCK, KIND_VANILLA -> {
					x = buf.readInt();
					y = buf.readInt();
					z = buf.readInt();
					if (kind == KIND_BLOCK) {
						info = Info.read(buf, protocol);
					}
				}
				case KIND_ENTITY -> {
					ids = new int[buf.readVarInt()];
					for (int i = 0; i < ids.length; i++) {
						ids[i] = buf.readVarInt();
					}
					info = Info.read(buf, protocol);
				}
				default -> buf.skipBytes(buf.readableBytes());
			}
			return new Target(protocol, kind, x, y, z, ids, info);
		}

		private static void write(RegistryFriendlyByteBuf buf, Target target) {
			buf.writeVarInt(target.protocol);
			buf.writeByte(target.kind);
			switch (target.kind) {
				case KIND_BLOCK, KIND_VANILLA -> {
					buf.writeInt(target.x);
					buf.writeInt(target.y);
					buf.writeInt(target.z);
					if (target.kind == KIND_BLOCK && target.info != null) {
						target.info.write(buf, target.protocol);
					}
				}
				case KIND_ENTITY -> {
					buf.writeVarInt(target.entityIds.length);
					for (int id : target.entityIds) {
						buf.writeVarInt(id);
					}
					if (target.info != null) {
						target.info.write(buf, target.protocol);
					}
				}
				default -> {
				}
			}
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * S2C {@code cehud:carriers}: VarInt protocol, then a byte array (VarInt length) holding GZIP data of
	 * VarInt count + block state strings, e.g. {@code minecraft:note_block[instrument=harp,note=1,powered=false]}.
	 * These are the vanilla states CraftEngine shows to clients; receiving it means the server runs CEBlockHud.
	 */
	public record Carriers(int protocol, byte[] gzipped) implements CustomPacketPayload {
		public static final Type<Carriers> TYPE = new Type<>(Identifier.fromNamespaceAndPath(NAMESPACE, "carriers"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Carriers> CODEC = StreamCodec.of(
				(buf, payload) -> {
					buf.writeVarInt(payload.protocol);
					buf.writeByteArray(payload.gzipped);
				},
				buf -> {
					int protocol = buf.readVarInt();
					if (!isSupported(protocol)) {
						buf.skipBytes(buf.readableBytes());
						return new Carriers(protocol, new byte[0]);
					}
					return new Carriers(protocol, buf.readByteArray());
				});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** C2S {@code cehud:hello}: VarInt protocol. Tells the server to use this mod instead of the boss bar. */
	public record Hello(int protocol) implements CustomPacketPayload {
		public static final Type<Hello> TYPE = new Type<>(Identifier.fromNamespaceAndPath(NAMESPACE, "hello"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = StreamCodec.of(
				(buf, payload) -> buf.writeVarInt(payload.protocol),
				buf -> new Hello(buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}

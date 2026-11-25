package dev.facetrack;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Shared by client and server: payloads plus the server relay. Must not touch client-only classes.
 * Flow: client sends FaceUpload ~20x/s -> server validates -> FaceUpdate to everyone tracking that player.
 */
public class FaceNet implements ModInitializer {
	/**
	 * 0 lidL, 1 lidR, 2 mouthOpen, 3 pucker (0..1), 4 yaw, 5 pitch, 6 roll (radians),
	 * 7 browL, 8 browR (-1 frown .. 1 raised), 9 gazeX, 10 mouthWide (-1 narrow .. 1 wide),
	 * 11 mouthShift, 12 cornerL, 13 cornerR (-1 down .. 1 up). Sideways values: positive = player's left (+x).
	 * These 14 come from the tracker; index 14 is the tongue (0..1, from the key), added by the client.
	 */
	public static final int VALUES = 14;
	public static final int TONGUE = VALUES;
	public static final int N = VALUES + 1;
	private static final long MIN_RELAY_MS = 40; // clients send every 50ms; anything faster is dropped

	private static final Map<ServerPlayer, Long> lastRelay = new WeakHashMap<>(); // server thread only

	/** Clamps each value to its allowed range in place. Returns null if any value is NaN/infinite. */
	public static float[] sanitize(float[] f) {
		for (int i = 0; i < f.length; i++) {
			if (!Float.isFinite(f[i])) return null;
			if (i < 4 || i == TONGUE) f[i] = clamp(f[i], 0, 1);
			else if (i < 7) f[i] = clamp(f[i], -1.2F, 1.2F); // ~70 degrees max head turn
			else f[i] = clamp(f[i], -1, 1);
		}
		return f;
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static float[] readValues(RegistryFriendlyByteBuf buf) {
		float[] f = new float[N];
		for (int i = 0; i < N; i++) f[i] = buf.readFloat();
		return f;
	}

	private static void writeValues(RegistryFriendlyByteBuf buf, float[] f) {
		for (int i = 0; i < N; i++) buf.writeFloat(f[i]);
	}

	/** Client -> server: my face. */
	public record FaceUpload(float[] values) implements CustomPacketPayload {
		public static final Type<FaceUpload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("facetrack", "face_upload"));
		public static final StreamCodec<RegistryFriendlyByteBuf, FaceUpload> CODEC =
			CustomPacketPayload.codec((p, buf) -> writeValues(buf, p.values), buf -> new FaceUpload(readValues(buf)));

		@Override
		public Type<FaceUpload> type() {
			return TYPE;
		}
	}

	/** Server -> client: the face of the player with this entity id. */
	public record FaceUpdate(int entityId, float[] values) implements CustomPacketPayload {
		public static final Type<FaceUpdate> TYPE = new Type<>(Identifier.fromNamespaceAndPath("facetrack", "face_update"));
		public static final StreamCodec<RegistryFriendlyByteBuf, FaceUpdate> CODEC = CustomPacketPayload.codec(
			(p, buf) -> {
				buf.writeVarInt(p.entityId);
				writeValues(buf, p.values);
			},
			buf -> new FaceUpdate(buf.readVarInt(), readValues(buf)));

		@Override
		public Type<FaceUpdate> type() {
			return TYPE;
		}
	}

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(FaceUpload.TYPE, FaceUpload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(FaceUpdate.TYPE, FaceUpdate.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(FaceUpload.TYPE, (payload, ctx) -> {
			ServerPlayer sender = ctx.player();
			long now = System.currentTimeMillis();
			Long last = lastRelay.get(sender);
			if (last != null && now - last < MIN_RELAY_MS) return;
			lastRelay.put(sender, now);

			float[] values = sanitize(payload.values()); // never trust a client
			if (values == null) return;
			var update = new FaceUpdate(sender.getId(), values);
			for (ServerPlayer viewer : PlayerLookup.tracking(sender)) { // nearby players only, never the sender
				if (ServerPlayNetworking.canSend(viewer, FaceUpdate.TYPE)) ServerPlayNetworking.send(viewer, update);
			}
		});
		LoggerFactory.getLogger("facetrack").info("Face sync ready");
	}
}

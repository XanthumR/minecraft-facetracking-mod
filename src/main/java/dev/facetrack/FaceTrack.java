package dev.facetrack;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class FaceTrack implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("facetrack");
	public static final int PORT = 39540;
	private static final long STALE_MS = 1000;
	private static final float LOCAL_EASE = 40, REMOTE_EASE = 15; // per second; remote updates only arrive 20x/s

	/** Latest tracker values (FaceNet.VALUES long). Replaced whole, never mutated. Written by the UDP thread. */
	private static volatile float[] trackerValues = new float[FaceNet.VALUES];
	private static volatile long lastTrackerPacket = 0;
	private static KeyMapping TONGUE_KEY;

	/** One player's face: where it's heading (target) and what's drawn this frame (shown, eased toward target). */
	public static final class Face {
		public final float[] shown = new float[FaceNet.N];
		float[] target = new float[FaceNet.N];
		long lastUpdate; // ms; the face disappears after STALE_MS without updates
		private long lastFrame = System.nanoTime();

		/** Eases shown toward target. rate = how fast (per second); call once per rendered frame. */
		void step(float rate) {
			long now = System.nanoTime();
			float k = Math.min(1, (now - lastFrame) / 1e9F * rate);
			lastFrame = now;
			for (int i = 0; i < shown.length; i++) shown[i] += (target[i] - shown[i]) * k;
		}
	}

	private static final Face LOCAL = new Face();
	private static final Map<Integer, Face> REMOTE = new HashMap<>(); // by entity id; render thread only

	/** True while the local tracker is sending. */
	static boolean trackerActive() {
		return System.currentTimeMillis() - lastTrackerPacket <= STALE_MS;
	}

	/** My face as it should look now: tracker values plus the tongue key. */
	private static float[] localTarget() {
		float[] f = Arrays.copyOf(trackerValues, FaceNet.N);
		f[FaceNet.TONGUE] = TONGUE_KEY.isDown() ? 1 : 0;
		return f;
	}

	/** The face to draw on this player, or null if they have none (no tracker / no updates lately). */
	public static Face faceFor(EntityRenderState state) {
		if (!(state instanceof AvatarRenderState s)) return null;
		var me = Minecraft.getInstance().player;
		if (me != null && s.id == me.getId()) {
			if (!trackerActive()) return null;
			LOCAL.target = localTarget();
			return LOCAL;
		}
		Face f = REMOTE.get(s.id);
		return f != null && System.currentTimeMillis() - f.lastUpdate <= STALE_MS ? f : null;
	}

	@Override
	public void onInitializeClient() {
		// Webcam trackers can't see a tongue, so it's a held key.
		TONGUE_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.facetrack.tongue", GLFW.GLFW_KEY_J, KeyMapping.Category.MISC));

		// Multiplayer: send my face every tick (20/s) if the server has the mod; receive everyone else's.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player != null && trackerActive() && ClientPlayNetworking.canSend(FaceNet.FaceUpload.TYPE)) {
				ClientPlayNetworking.send(new FaceNet.FaceUpload(localTarget()));
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(FaceNet.FaceUpdate.TYPE, (payload, ctx) -> {
			float[] values = FaceNet.sanitize(payload.values());
			if (values == null) return;
			Face f = REMOTE.computeIfAbsent(payload.entityId(), id -> new Face());
			f.target = values;
			f.lastUpdate = System.currentTimeMillis();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(REMOTE::clear));

		Thread t = new Thread(FaceTrack::receive, "facetrack-udp");
		t.setDaemon(true);
		t.start();

		LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, ctx) -> {
			if (renderer instanceof AvatarRenderer<?> avatar) {
				@SuppressWarnings("unchecked")
				var parent = (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) avatar;
				helper.register(new FaceLayer(parent));
			}
		});
	}

	private static void receive() {
		// Bound to loopback only: nothing outside this PC can drive your face.
		try (DatagramSocket socket = new DatagramSocket(PORT, InetAddress.getLoopbackAddress())) {
			LOG.info("Listening for face data on 127.0.0.1:{}", PORT);
			byte[] buf = new byte[256];
			while (true) {
				DatagramPacket p = new DatagramPacket(buf, buf.length);
				socket.receive(p);
				float[] f = parse(new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII));
				if (f != null) {
					trackerValues = f;
					lastTrackerPacket = System.currentTimeMillis();
				}
			}
		} catch (Exception e) {
			LOG.error("Face receiver stopped", e);
		}
	}

	/** Tracker packet: FaceNet.VALUES space-separated floats. Returns null on anything malformed. */
	static float[] parse(String msg) {
		String[] parts = msg.trim().split("\\s+");
		if (parts.length != FaceNet.VALUES) return null;
		float[] f = new float[FaceNet.VALUES];
		try {
			for (int i = 0; i < f.length; i++) f[i] = Float.parseFloat(parts[i]);
		} catch (NumberFormatException e) {
			return null;
		}
		return FaceNet.sanitize(f);
	}

	private static final Identifier WHITE = Identifier.fromNamespaceAndPath("facetrack", "textures/white.png");

	/**
	 * One render pass per kind. Skin-textured parts sample a forehead pixel (tint darkens mouth/brows, lids stay
	 * skin coloured); plain parts use a white texture so the tint is the exact colour.
	 * Depth order comes from each part's z, not from this list.
	 */
	enum Kind {
		EYES(0xFFF4F4F4, true), PUPILS(0xFF26263F, true), // ponytail: fixed pupil colour; sample the skin's eye pixel if it matters
		LIDS(-1, false), MOUTH(0xFF3A1010, false), BROWS(0xFF3B2A1C, false),
		TEETH(0xFFF2F0E6, true), TONGUE(0xFFD65C74, true);

		final int tint;
		final boolean plain;

		Kind(int tint, boolean plain) {
			this.tint = tint;
			this.plain = plain;
		}
	}

	/** Draws eyelids, mouth and brows on top of the player's face, using the head's pose. */
	static class FaceLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
		private final FaceModel[] models = Arrays.stream(Kind.values()).map(FaceModel::new).toArray(FaceModel[]::new);

		FaceLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
			super(parent);
		}

		@Override
		public void submit(PoseStack pose, SubmitNodeCollector out, int light, AvatarRenderState state, float yRot, float xRot) {
			Face face = faceFor(state);
			if (state.isInvisible || face == null) return;
			face.step(face == LOCAL ? LOCAL_EASE : REMOTE_EASE); // once per frame, before the models read it
			var skin = RenderTypes.entityCutout(state.skin.body().texturePath());
			var plain = RenderTypes.entityCutout(WHITE);
			int overlay = LivingEntityRenderer.getOverlayCoords(state, 0.0F);
			for (FaceModel m : models) {
				out.submitModel(m, state, pose, m.kind.plain ? plain : skin, light, overlay, m.kind.tint, null, state.outlineColor, null);
			}
		}
	}

	/**
	 * Full humanoid skeleton (so the head follows the player's head exactly) with only face planes in it.
	 * ponytail: face positions fit the default 8x8 face (eyes on row 4, mouth on row 6); odd skins may need offsets.
	 */
	static class FaceModel extends HumanoidModel<AvatarRenderState> {
		private static final float BROW_Y = -4.5F; // middle of face row 3, just above the eyes
		private static final float GAZE_RANGE = 0.6F; // pupil slides this many pixels each way inside the 2px eye
		private static final float MOUTH_Y = -2; // top of face row 6

		final Kind kind;
		private final ModelPart eyeL, eyeR, pupilL, pupilR, lidL, lidR, mouthPart, cornerL, cornerR, teeth, tonguePart, browL, browR;

		FaceModel(Kind kind) {
			super(createLayer().bakeRoot());
			this.kind = kind;
			this.eyeL = head.getChild("eye_l");
			this.eyeR = head.getChild("eye_r");
			this.pupilL = head.getChild("pupil_l");
			this.pupilR = head.getChild("pupil_r");
			this.lidL = head.getChild("lid_l");
			this.lidR = head.getChild("lid_r");
			this.mouthPart = head.getChild("mouth");
			this.cornerL = head.getChild("corner_l");
			this.cornerR = head.getChild("corner_r");
			this.teeth = head.getChild("teeth");
			this.tonguePart = head.getChild("tongue");
			this.browL = head.getChild("brow_l");
			this.browR = head.getChild("brow_r");
		}

		private static LayerDefinition createLayer() {
			MeshDefinition mesh = PlayerModel.createMesh(CubeDeformation.NONE, false);
			PartDefinition head = mesh.getRoot().clearRecursively().getChild("head");
			float z = -4.05F; // just in front of the face to avoid z-fighting
			// Eye whites and pupils replace the skin's painted eyes; they sit behind the lids so blinks cover them.
			var eye = CubeListBuilder.create().addBox(-1, 0, 0, 2, 1, 0);
			head.addOrReplaceChild("eye_r", eye, PartPose.offset(-2, -4, -4.02F));
			head.addOrReplaceChild("eye_l", eye, PartPose.offset(2, -4, -4.02F));
			var pupil = CubeListBuilder.create().addBox(-0.4F, 0, 0, 0.8F, 1, 0);
			head.addOrReplaceChild("pupil_r", pupil, PartPose.offset(-2, -4, -4.03F));
			head.addOrReplaceChild("pupil_l", pupil, PartPose.offset(2, -4, -4.03F));
			// Zero-depth planes; texOffs points at forehead pixels so lids take the skin's colour.
			head.addOrReplaceChild("lid_r", CubeListBuilder.create().texOffs(9, 9).addBox(-1, 0, 0, 2, 1, 0), PartPose.offset(-2, -4, z));
			head.addOrReplaceChild("lid_l", CubeListBuilder.create().texOffs(13, 9).addBox(-1, 0, 0, 2, 1, 0), PartPose.offset(2, -4, z));
			// Mouth pieces are 1x1 planes hanging from their pivot, sized every frame with x/yScale.
			var unit = CubeListBuilder.create().texOffs(11, 9).addBox(-0.5F, 0, 0, 1, 1, 0);
			head.addOrReplaceChild("mouth", unit, PartPose.offset(0, MOUTH_Y, z));
			head.addOrReplaceChild("teeth", unit, PartPose.offset(0, MOUTH_Y, z - 0.005F));
			head.addOrReplaceChild("tongue", unit, PartPose.offset(0, MOUTH_Y, z - 0.015F)); // in front of lips when out
			var corner = CubeListBuilder.create().texOffs(11, 9).addBox(-0.25F, -0.25F, 0, 0.5F, 0.5F, 0);
			head.addOrReplaceChild("corner_r", corner, PartPose.offset(0, MOUTH_Y, z));
			head.addOrReplaceChild("corner_l", corner, PartPose.offset(0, MOUTH_Y, z));
			// Thin bars centred on their pivot so they can tilt around the middle.
			var brow = CubeListBuilder.create().texOffs(9, 9).addBox(-1.25F, -0.25F, 0, 2.5F, 0.5F, 0);
			head.addOrReplaceChild("brow_r", brow, PartPose.offset(-2, BROW_Y, z - 0.01F));
			head.addOrReplaceChild("brow_l", brow, PartPose.offset(2, BROW_Y, z - 0.01F));
			return LayerDefinition.create(mesh, 64, 64);
		}

		@Override
		public void setupAnim(AvatarRenderState state) {
			super.setupAnim(state); // head rotation, crouch etc. (tracked head angles are added by HeadMixin)
			Face face = faceFor(state);
			float[] f = face != null ? face.shown : new float[FaceNet.N];
			// Model -x is the player's right side.
			eyeR.visible = eyeL.visible = kind == Kind.EYES;
			pupilR.visible = pupilL.visible = kind == Kind.PUPILS;
			pupilR.x = -2 + f[9] * GAZE_RANGE;
			pupilL.x = 2 + f[9] * GAZE_RANGE;
			show(lidR, kind == Kind.LIDS, f[1]);
			show(lidL, kind == Kind.LIDS, f[0]);
			mouth(f);
			brow(browR, f[8], 1);
			brow(browL, f[7], -1);
		}

		/** All sizes in skin pixels. The default face's mouth is 4px wide. */
		private void mouth(float[] f) {
			float open = f[2], pucker = f[3], wide = f[10], tongue = f[FaceNet.TONGUE];
			float w = Math.clamp(4 * (1 + 0.3F * wide - 0.45F * pucker), 1.5F, 6);
			float h = 0.4F + 1.8F * open + 0.6F * pucker + 0.6F * tongue; // closed = thin line; pucker = small "o"
			float cx = 0.6F * f[11];

			mouthPart.visible = cornerL.visible = cornerR.visible = kind == Kind.MOUTH;
			place(mouthPart, cx, MOUTH_Y, w, h);
			// Corners sit at the line ends and lift for a smile / drop for a frown, independently per side.
			float lineMid = MOUTH_Y + 0.2F;
			cornerR.x = cx - w / 2 - 0.15F;
			cornerR.y = lineMid - 0.7F * f[13];
			cornerL.x = cx + w / 2 + 0.15F;
			cornerL.y = lineMid - 0.7F * f[12];

			teeth.visible = kind == Kind.TEETH && open > 0.2F;
			place(teeth, cx, MOUTH_Y, w - 0.6F, Math.min(0.45F, h * 0.3F));

			// Resting on the bottom of an open mouth, or hanging over the lower lip when stuck out.
			tonguePart.visible = kind == Kind.TONGUE && (tongue > 0.05F || open > 0.35F);
			place(tonguePart, cx, MOUTH_Y + h - 0.5F, Math.min(2, w * 0.6F), 0.5F + 1.4F * tongue);
		}

		private static void place(ModelPart part, float x, float y, float w, float h) {
			part.x = x;
			part.y = y;
			part.xScale = w;
			part.yScale = h;
		}

		/** amount: -1 frown .. 1 raised. side: +1 for the right brow (inner end is +x), -1 for the left. */
		private void brow(ModelPart part, float amount, int side) {
			part.visible = kind == Kind.BROWS;
			part.y = BROW_Y - amount * (amount > 0 ? 1.0F : 0.6F); // y is down: raised = up 1px, frown = down 0.6px
			part.zRot = amount < 0 ? side * -amount * 0.4F : 0; // frown tilts the inner ends down
		}

		private static void show(ModelPart part, boolean mine, float amount) {
			part.visible = mine && amount > 0.08F;
			part.yScale = amount;
		}
	}
}

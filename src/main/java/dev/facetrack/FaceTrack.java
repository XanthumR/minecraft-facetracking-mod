package dev.facetrack;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
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

public class FaceTrack implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("facetrack");
	public static final int PORT = 39540;
	private static final long STALE_MS = 1000;

	/**
	 * 0 lidL, 1 lidR, 2 mouthOpen, 3 pucker (0..1), 4 yaw, 5 pitch, 6 roll (radians),
	 * 7 browL, 8 browR (-1 frown .. 1 raised), 9 gazeX, 10 mouthWide (-1 narrow .. 1 wide),
	 * 11 mouthShift, 12 cornerL, 13 cornerR (-1 down .. 1 up). Sideways values: positive = player's left (+x).
	 * Replaced whole, never mutated.
	 */
	static final int VALUES = 14;
	private static volatile float[] face = new float[VALUES];
	private static volatile long lastPacket = 0;

	/** True while the tracker is sending. */
	public static boolean active() {
		return System.currentTimeMillis() - lastPacket <= STALE_MS;
	}

	/** Latest face values, or all zeros if the tracker stopped sending. */
	public static float[] face() {
		return active() ? face : new float[VALUES];
	}

	/** True if this render state is the local player (only they get tracked; multiplayer sync not done yet). */
	public static boolean isLocalPlayer(EntityRenderState state) {
		var player = Minecraft.getInstance().player;
		return player != null && state instanceof AvatarRenderState s && s.id == player.getId();
	}

	@Override
	public void onInitializeClient() {
		// Webcam trackers can't see a tongue, so it's a held key.
		TONGUE_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.facetrack.tongue", GLFW.GLFW_KEY_J, KeyMapping.Category.MISC));

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
					face = f;
					lastPacket = System.currentTimeMillis();
				}
			}
		} catch (Exception e) {
			LOG.error("Face receiver stopped", e);
		}
	}

	/** Packet: VALUES space-separated floats. Returns null on anything malformed. */
	static float[] parse(String msg) {
		String[] parts = msg.trim().split("\\s+");
		if (parts.length != VALUES) return null;
		float[] f = new float[VALUES];
		try {
			for (int i = 0; i < VALUES; i++) f[i] = Float.parseFloat(parts[i]);
		} catch (NumberFormatException e) {
			return null;
		}
		for (int i = 0; i < VALUES; i++) {
			if (!Float.isFinite(f[i])) return null;
			if (i < 4) f[i] = clamp(f[i], 0, 1);
			else if (i < 7) f[i] = clamp(f[i], -1.2f, 1.2f); // ~70 degrees max head turn
			else f[i] = clamp(f[i], -1, 1);
		}
		return f;
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static final Identifier WHITE = Identifier.fromNamespaceAndPath("facetrack", "textures/white.png");
	private static KeyMapping TONGUE_KEY;
	private static float tongue; // 0 in .. 1 out, eased toward the key state
	private static long lastTongueNanos = System.nanoTime();

	/** Eases the tongue in/out over ~0.1s. Called once per rendered frame. */
	private static void updateTongue() {
		long now = System.nanoTime();
		float dt = (now - lastTongueNanos) / 1e9F;
		lastTongueNanos = now;
		float target = TONGUE_KEY.isDown() ? 1 : 0;
		tongue += (target - tongue) * Math.min(1, dt * 15);
	}

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
		private final FaceModel[] models = java.util.Arrays.stream(Kind.values()).map(FaceModel::new).toArray(FaceModel[]::new);

		FaceLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
			super(parent);
		}

		@Override
		public void submit(PoseStack pose, SubmitNodeCollector out, int light, AvatarRenderState state, float yRot, float xRot) {
			if (state.isInvisible || !isLocalPlayer(state) || !active()) return;
			updateTongue();
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
			float[] f = face();
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
			float open = f[2], pucker = f[3], wide = f[10];
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

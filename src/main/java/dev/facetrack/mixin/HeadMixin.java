package dev.facetrack.mixin;

import dev.facetrack.FaceTrack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the tracked head rotation on top of where the player is looking (you or any tracked player nearby). */
@Mixin(HumanoidModel.class)
public class HeadMixin {
	@Shadow @Final public ModelPart head;

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
	private void facetrack$head(HumanoidRenderState state, CallbackInfo ci) {
		var face = FaceTrack.faceFor(state);
		if (face == null) return;
		head.yRot += face.shown[4];
		head.xRot += face.shown[5];
		head.zRot += face.shown[6];
	}
}

package com.example.kickmod.mixin;

import com.example.kickmod.client.KickAnim;
import com.example.kickmod.client.KickController;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Whole-body motion of the kick (hop, lean back, torso twist), applied around the waist. */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererMixin {
    /** PlayerRenderer scales the model by 15/16. */
    private static final float MODEL_SCALE = 0.9375F;

    @Inject(method = "setupRotations(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V",
            at = @At("RETURN"))
    private void kickmod$rotations(AbstractClientPlayer player, PoseStack pose, float bob, float bodyYaw, float partialTick,
                                   float scale, CallbackInfo ci) {
        float[] a = KickController.pose(player);
        if (a == null) return;
        // the pose stack is already scaled by the entity scale attribute here
        float s = MODEL_SCALE;
        pose.translate(a[KickAnim.ROOT_X] * s, a[KickAnim.ROOT_Y] * s, a[KickAnim.ROOT_Z] * s);
        pose.mulPose(new Quaternionf(a[KickAnim.QX], a[KickAnim.QY], a[KickAnim.QZ], a[KickAnim.QW]));
        pose.translate(0.0F, -0.75F * s, 0.0F);
    }
}

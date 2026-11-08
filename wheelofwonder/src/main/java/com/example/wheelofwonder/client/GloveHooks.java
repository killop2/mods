package com.example.wheelofwonder.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;

/** Bridge for PlayerHandMixin (keeps the mixin class small). */
public final class GloveHooks {
    private GloveHooks() {
    }

    public static void firstPerson(PlayerRenderer renderer, PoseStack pose, MultiBufferSource buffers, int light,
                                   AbstractClientPlayer player, ModelPart arm) {
        boolean left = arm == renderer.getModel().leftArm;
        pose.pushPose();
        arm.translateAndRotate(pose);
        GloveModel.render(pose, buffers, light, arm, left, player.tickCount);
        pose.popPose();
    }
}

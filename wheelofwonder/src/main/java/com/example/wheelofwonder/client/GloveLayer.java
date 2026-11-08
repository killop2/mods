package com.example.wheelofwonder.client;

import com.example.wheelofwonder.WalrusGlovesItem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;

/** Third person: both gloves on the player's arms while the Walrus PUNCH! item is held. */
public class GloveLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    public GloveLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing,
                       float limbSwingAmount, float partial, float ageInTicks, float netHeadYaw, float headPitch) {
        if (!WalrusGlovesItem.isGloves(player.getMainHandItem()) || player.isInvisible()) return;
        PlayerModel<AbstractClientPlayer> m = getParentModel();
        pose.pushPose();
        m.rightArm.translateAndRotate(pose);
        GloveModel.render(pose, buffers, light, m.rightArm, false, ageInTicks);
        pose.popPose();
        pose.pushPose();
        m.leftArm.translateAndRotate(pose);
        GloveModel.render(pose, buffers, light, m.leftArm, true, ageInTicks);
        pose.popPose();
    }
}

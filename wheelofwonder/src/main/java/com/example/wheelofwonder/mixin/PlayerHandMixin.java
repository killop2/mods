package com.example.wheelofwonder.mixin;

import com.example.wheelofwonder.WalrusGlovesItem;
import com.example.wheelofwonder.client.GloveHooks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** First person: draws the glove on the arm right after vanilla drew the bare arm. */
@Mixin(PlayerRenderer.class)
public abstract class PlayerHandMixin {
    @Inject(method = "renderHand", at = @At("TAIL"), require = 0)
    private void wheelofwonder$glove(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                                     ModelPart arm, ModelPart sleeve, CallbackInfo ci) {
        if (!WalrusGlovesItem.isGloves(player.getMainHandItem())) return;
        GloveHooks.firstPerson((PlayerRenderer) (Object) this, pose, buffers, light, player, arm);
    }
}

package com.example.wheelofwonder.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Access to vanilla's first-person arm drawing, used to also show the left arm when both hands are empty. */
@Mixin(ItemInHandRenderer.class)
public interface ItemInHandRendererAccessor {
    @Invoker("renderPlayerArm")
    void wheelofwonder$renderPlayerArm(PoseStack poseStack, MultiBufferSource buffers, int light, float equipProgress,
                                       float swingProgress, HumanoidArm arm);
}

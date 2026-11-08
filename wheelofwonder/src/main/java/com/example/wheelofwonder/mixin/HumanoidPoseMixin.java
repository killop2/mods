package com.example.wheelofwonder.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The Shockwave! swing: the local player's arms are posed after vanilla (the player model copies them to the sleeves). */
@Mixin(HumanoidModel.class)
public abstract class HumanoidPoseMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"), require = 0)
    private void wheelofwonder$shockwavePose(LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                                             float netHeadYaw, float headPitch, CallbackInfo ci) {
        try {
            com.example.wheelofwonder.client.ShockwaveController.applyPose((HumanoidModel<?>) (Object) this, entity, ageInTicks);
            com.example.wheelofwonder.client.WhirlClient.applyPose((HumanoidModel<?>) (Object) this, entity, ageInTicks);
            com.example.wheelofwonder.client.TelekinesisClient.applyPose((HumanoidModel<?>) (Object) this, entity, ageInTicks);
            com.example.wheelofwonder.client.TrizubController.applyPose((HumanoidModel<?>) (Object) this, entity, ageInTicks);
            com.example.wheelofwonder.client.StrangeController.applyPose((HumanoidModel<?>) (Object) this, entity, ageInTicks);
        } catch (RuntimeException ignored) {
            // never break player rendering over a pose
        }
    }
}

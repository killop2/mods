package com.example.kickmod.mixin;

import com.example.kickmod.client.KickController;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mouse look is frozen for the local player while the kick sequence plays. */
@Mixin(Entity.class)
public abstract class EntityTurnMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void kickmod$turn(double yRot, double xRot, CallbackInfo ci) {
        if (KickController.isLookLocked() && (Object) this == Minecraft.getInstance().player) {
            ci.cancel();
        }
    }
}

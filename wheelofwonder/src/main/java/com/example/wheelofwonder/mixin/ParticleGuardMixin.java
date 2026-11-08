package com.example.wheelofwonder.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Crash fix on leaving a world: Physics Mod keeps simulating its water for a frame after the world is unloaded and
 * spawns splash particles; vanilla then reads the (already null) level and the game crashes. Without a level a
 * particle is simply not made.
 */
@Mixin(LevelRenderer.class)
public abstract class ParticleGuardMixin {
    @Shadow
    private ClientLevel level;

    @Inject(method = "addParticleInternal(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDDDD)Lnet/minecraft/client/particle/Particle;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void wheelofwonder$noLevelNoParticle(ParticleOptions options, boolean force, boolean decreased, double x, double y, double z,
                                                 double xSpeed, double ySpeed, double zSpeed, CallbackInfoReturnable<Particle> cir) {
        if (this.level == null) cir.setReturnValue(null);
    }
}

package com.example.wheelofwonder.mixin;

import com.example.wheelofwonder.DeferredBlockChanges;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fix for a server freeze in Sable 2.0.3: when a physics body (a ragdoll) hits a fragile block (ice, leaves...)
 * Sable breaks it from inside the Rapier physics step. Changing a block makes Sable build a new collider through the
 * same native physics engine that is busy stepping, and the server thread hangs there forever: ragdolls freeze in
 * the air, the NPC list comes back empty, cooldowns stop and saving the world never finishes.
 * Here the block changes are only queued, and done right after the server tick, outside the physics step.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.callback.FragileBlockCallback")
public abstract class SableFragileMixin {
    @Redirect(method = "onHit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z"), require = 0)
    private boolean wheelofwonder$deferDestroy(ServerLevel level, BlockPos pos, boolean drop) {
        DeferredBlockChanges.destroy(level, pos, drop);
        return true;
    }

    @Redirect(method = "onHit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"), require = 0)
    private boolean wheelofwonder$deferSet(ServerLevel level, BlockPos pos, BlockState state) {
        DeferredBlockChanges.set(level, pos, state);
        return true;
    }
}

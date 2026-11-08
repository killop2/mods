package com.example.kickmod.mixin;

import com.example.kickmod.KickConfig;
import com.example.kickmod.KickServer;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Sable: Ragdolls puts a stiff spring (stiffness 20, damping 20) into every mob joint that pulls the limbs back
 * to the pose the mob had when it was launched, so bodies land like a mannequin. For mobs kicked by this mod
 * (entity tag "kickmod_ragdoll") the springs are made soft, so the body goes limp and falls naturally.
 * Other ragdolls (Ragdoll Reactions, corpses, ...) are not touched. require = 0: if the ragdoll mod changes,
 * nothing breaks, the joints just stay stiff.
 */
@Pseudo
@Mixin(targets = "dev.leo.sableplayerragdoll.mob.MobRagdollAssembly", remap = false)
public abstract class RagdollJointMixin {
    private static final ThreadLocal<Boolean> KICKMOD_LIMP = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "finishAssembly", at = @At("HEAD"), require = 0)
    private static void kickmod$finishHead(ServerLevel level, LivingEntity entity, @org.spongepowered.asm.mixin.injection.Coerce Object pending,
                                          CallbackInfo ci) {
        boolean limp = entity != null && entity.getTags().contains(KickServer.RAGDOLL_TAG);
        KICKMOD_LIMP.set(limp);
        if (limp) com.mojang.logging.LogUtils.getLogger().info("[kickmod] limp joints for {}", entity.getName().getString());
    }

    @Inject(method = "finishAssembly", at = @At("RETURN"), require = 0)
    private static void kickmod$finishReturn(ServerLevel level, LivingEntity entity, @org.spongepowered.asm.mixin.injection.Coerce Object pending,
                                            CallbackInfo ci) {
        KICKMOD_LIMP.set(Boolean.FALSE);
    }

    /** after a world reload the ragdoll is rebuilt here: keep it limp too */
    @Inject(method = "restoreFromSave(Lnet/minecraft/server/level/ServerLevel;Ljava/util/UUID;)Z", at = @At("HEAD"), require = 0)
    private static void kickmod$restoreHead(ServerLevel level, UUID id, CallbackInfoReturnable<Boolean> cir) {
        Entity e = level.getEntity(id);
        KICKMOD_LIMP.set(e != null && e.getTags().contains(KickServer.RAGDOLL_TAG));
    }

    @Inject(method = "restoreFromSave(Lnet/minecraft/server/level/ServerLevel;Ljava/util/UUID;)Z", at = @At("RETURN"), require = 0)
    private static void kickmod$restoreReturn(ServerLevel level, UUID id, CallbackInfoReturnable<Boolean> cir) {
        KICKMOD_LIMP.set(Boolean.FALSE);
    }

    @Inject(method = "tuneAngularJoint", at = @At("HEAD"), cancellable = true, require = 0)
    private static void kickmod$tune(PhysicsConstraintHandle handle, CallbackInfo ci) {
        if (!KICKMOD_LIMP.get()) return;
        double k = KickConfig.JOINT_STIFFNESS.get();
        double d = KickConfig.JOINT_DAMPING.get();
        for (ConstraintJointAxis axis : new ConstraintJointAxis[]{ConstraintJointAxis.ANGULAR_X, ConstraintJointAxis.ANGULAR_Y, ConstraintJointAxis.ANGULAR_Z}) {
            handle.setMotor(axis, 0.0D, k, d, false, 0.0D);
        }
        ci.cancel();
    }
}

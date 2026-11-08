package com.example.kickmod.mixin;

import com.example.kickmod.client.KickController;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Kick camera: only active during a kick, otherwise vanilla (and other mods) keep full control.
 *
 * The pose is written straight into the camera fields instead of calling setRotation/setPosition:
 * Sable hooks Camera.setRotation and multiplies the view by the pose of any physics sub-level
 * near the camera. Right after the kick the tumbling ragdoll parts are right there, which spun
 * the camera into the sky. Writing the fields at the very end of setup keeps our pose exact.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow @Final private static Vector3f FORWARDS;
    @Shadow @Final private static Vector3f UP;
    @Shadow @Final private static Vector3f LEFT;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private BlockPos.MutableBlockPos blockPosition;
    @Shadow private float xRot;
    @Shadow private float yRot;
    @Shadow private float roll;
    @Shadow private Vec3 position;
    @Shadow private boolean detached;

    @Inject(method = "setup", at = @At("RETURN"))
    private void kickmod$setup(BlockGetter level, Entity entity, boolean detachedIn, boolean mirror, float partialTick, CallbackInfo ci) {
        KickController.CameraPose pose = KickController.camera(this.position, this.yRot, this.xRot, partialTick);
        if (pose == null) return;
        this.xRot = pose.pitch();
        this.yRot = pose.yaw();
        this.roll = 0.0F;
        this.rotation.rotationYXZ((float) Math.PI - pose.yaw() * ((float) Math.PI / 180.0F),
                -pose.pitch() * ((float) Math.PI / 180.0F), 0.0F);
        FORWARDS.rotate(this.rotation, this.forwards);
        UP.rotate(this.rotation, this.up);
        LEFT.rotate(this.rotation, this.left);
        Vec3 p = pose.pos();
        this.position = p;
        this.blockPosition.set(p.x, p.y, p.z);
        this.detached = pose.detached();
    }
}

package com.example.wheelofwonder.mixin;

import com.example.wheelofwonder.client.WheelCamera;
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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wheel side camera. Only active while your wheel is out; otherwise does nothing, so vanilla, kickmod's kick camera
 * and Sable keep control.
 *
 * Sable changes the camera AFTER Camera.setup: GameRenderer fires ViewportEvent.ComputeCameraAngles and calls
 * setRotation, and Sable redirects that call and multiplies the view by the pose of a physics sub-level (the spinning
 * ragdolls next to the wheel) - that spun the view into the sky. So the pose is written at the end of setup and
 * written again whenever the camera's rotation, vectors or position are read, while our camera is active.
 */
@Mixin(Camera.class)
public abstract class WheelCameraMixin {
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

    @Unique
    private WheelCamera.CameraPose wheelofwonder$pose;

    @Inject(method = "setup", at = @At("RETURN"))
    private void wheelofwonder$setup(BlockGetter level, Entity entity, boolean detachedIn, boolean mirror, float partialTick, CallbackInfo ci) {
        this.wheelofwonder$pose = null;
        WheelCamera.CameraPose pose = WheelCamera.camera(this.position, this.yRot, this.xRot, partialTick);
        if (pose == null) pose = com.example.wheelofwonder.client.PunchController.camera(this.position, this.yRot, this.xRot, partialTick);
        if (pose == null) pose = com.example.wheelofwonder.client.BallistaCamera.camera(this.position, this.yRot, this.xRot, partialTick);
        if (pose == null) pose = com.example.wheelofwonder.client.DaggerController.camera(this.position, this.yRot, this.xRot, partialTick);
        if (pose == null) pose = com.example.wheelofwonder.client.ShockwaveController.camera(this.position, this.yRot, this.xRot, partialTick);
        if (pose == null) pose = com.example.wheelofwonder.client.WhirlClient.camera(this.position, this.yRot, this.xRot, partialTick);
        if (pose == null) pose = com.example.wheelofwonder.client.TrizubController.camera(this.position, this.yRot, this.xRot, partialTick);
        if (pose == null) pose = com.example.wheelofwonder.client.StrangeController.camera(this.position, this.yRot, this.xRot, partialTick);
        if (pose == null) return;
        this.wheelofwonder$pose = pose;
        wheelofwonder$apply();
        this.detached = pose.detached();
    }

    @Unique
    private void wheelofwonder$apply() {
        WheelCamera.CameraPose pose = this.wheelofwonder$pose;
        if (pose == null) return;
        if (!WheelCamera.isActive() && !com.example.wheelofwonder.client.PunchController.isActive()
                && !com.example.wheelofwonder.client.BallistaCamera.isActive()
                && !com.example.wheelofwonder.client.DaggerController.isActive()
                && !com.example.wheelofwonder.client.ShockwaveController.isActive()
                && !com.example.wheelofwonder.client.WhirlClient.isActive()
                && !com.example.wheelofwonder.client.TrizubController.isActive()
                && !com.example.wheelofwonder.client.StrangeController.isActive()) {
            this.wheelofwonder$pose = null;
            return;
        }
        if (this.xRot != pose.pitch() || this.yRot != pose.yaw() || this.roll != 0.0F || this.position != pose.pos()) {
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
        }
    }

    // Re-apply after anything (Sable's setRotation redirect) changed the view. require = 0: never breaks the game.
    @Inject(method = "rotation", at = @At("HEAD"), require = 0)
    private void wheelofwonder$rotation(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Quaternionf> cir) {
        wheelofwonder$apply();
    }

    @Inject(method = "getPosition", at = @At("HEAD"), require = 0)
    private void wheelofwonder$position(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Vec3> cir) {
        wheelofwonder$apply();
    }

    @Inject(method = "getXRot", at = @At("HEAD"), require = 0)
    private void wheelofwonder$xRot(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
        wheelofwonder$apply();
    }

    @Inject(method = "getYRot", at = @At("HEAD"), require = 0)
    private void wheelofwonder$yRot(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
        wheelofwonder$apply();
    }

    @Inject(method = "getLookVector", at = @At("HEAD"), require = 0)
    private void wheelofwonder$look(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Vector3f> cir) {
        wheelofwonder$apply();
    }

    @Inject(method = "getUpVector", at = @At("HEAD"), require = 0)
    private void wheelofwonder$up(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Vector3f> cir) {
        wheelofwonder$apply();
    }

    @Inject(method = "getLeftVector", at = @At("HEAD"), require = 0)
    private void wheelofwonder$left(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Vector3f> cir) {
        wheelofwonder$apply();
    }
}

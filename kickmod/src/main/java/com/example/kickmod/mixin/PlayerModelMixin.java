package com.example.kickmod.mixin;

import com.example.kickmod.client.KickAnim;
import com.example.kickmod.client.KickController;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Puts the local player's model into the kick pose (only while kicking). */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("RETURN"))
    private void kickmod$pose(LivingEntity entity, float limbSwing, float limbSwingAmount, float age, float netHeadYaw,
                              float headPitch, CallbackInfo ci) {
        float[] a = KickController.pose(entity);
        if (a == null) return;
        PlayerModel<?> m = (PlayerModel<?>) (Object) this;
        set(m.head, a[KickAnim.HEAD_X], a[KickAnim.HEAD_Y], a[KickAnim.HEAD_Z]);
        set(m.body, 0.0F, 0.0F, 0.0F);
        set(m.rightArm, a[KickAnim.RARM_X], a[KickAnim.RARM_Y], a[KickAnim.RARM_Z]);
        set(m.leftArm, a[KickAnim.LARM_X], a[KickAnim.LARM_Y], a[KickAnim.LARM_Z]);
        set(m.rightLeg, a[KickAnim.RLEG_X], a[KickAnim.RLEG_Y], a[KickAnim.RLEG_Z]);
        set(m.leftLeg, a[KickAnim.LLEG_X], a[KickAnim.LLEG_Y], a[KickAnim.LLEG_Z]);
        m.hat.copyFrom(m.head);
        m.jacket.copyFrom(m.body);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightPants.copyFrom(m.rightLeg);
        m.leftPants.copyFrom(m.leftLeg);
    }

    private static void set(ModelPart p, float x, float y, float z) {
        p.resetPose();
        p.xRot = x;
        p.yRot = y;
        p.zRot = z;
    }
}

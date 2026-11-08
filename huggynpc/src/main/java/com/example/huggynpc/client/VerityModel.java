package com.example.huggynpc.client;

import com.example.huggynpc.HuggyEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Verity's model: the pixel ball (VerityMesh), squashing and stretching as it hops, chomping and blinking.
 * Used by VerityRenderer (spawn-egg Verity) and by Custom NPCs for an NPC that looks like Verity.
 *
 * The one ModelPart field is never drawn: Sable: Ragdolls reads the model's ModelPart fields to build the ragdoll,
 * so Verity's ragdoll is this single head-sized box. Once Verity has crumbled (VerityClient) nothing is drawn, so
 * the ragdoll body stays invisible while its cubes lie about. No other ModelPart fields may exist in this class.
 */
public class VerityModel<T extends LivingEntity> extends EntityModel<T> {
    public ModelPart body = new ModelPart(List.of(new ModelPart.Cube(0, 0, -4.0F, 16.0F, -4.0F, 8.0F, 8.0F, 8.0F, 0.0F, 0.0F, 0.0F,
            false, 16.0F, 16.0F, EnumSet.allOf(Direction.class))), Map.of());
    private LivingEntity passed;

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        this.passed = entity;
    }

    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay, int color) {
        LivingEntity e = resolve();
        if (e != null && VerityClient.hidden(e.getId())) return;
        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        float time = e != null ? e.tickCount + partial : 0.0F;
        boolean air = e != null && !e.onGround() && !e.isInWater();
        double vy = e != null ? e.getY() - e.yo : 0.0D;
        boolean moving = e != null && (Math.abs(e.getX() - e.xo) + Math.abs(e.getZ() - e.zo)) > 0.02D;
        // squash and stretch: stretched while flying up, squashed when it comes down; breathing when it rests
        float sy = air ? 1.0F + (float) Mth.clamp(vy * 1.6D, -0.18D, 0.25D) : 1.0F + 0.035F * Mth.sin(time * 0.12F);
        float sxz = 1.0F / (float) Math.sqrt(sy);
        // three times bigger on the ground, head size while it jumps onto a head and sits there
        float k = e != null ? VerityClient.scale(e.getId()) : 1.0F;
        pose.pushPose();
        // undo LivingEntityRenderer's model flip (scale -1,-1,1 and 1.501 offset) and turn +Z to the front
        pose.translate(0.0F, 1.501F, 0.0F);
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.translate(0.0F, VerityMesh.DIAMETER * 0.5F * sy * k, 0.0F);
        pose.scale(sxz * k, sy * k, sxz * k);
        VerityMesh.emit(pose.last(), buffer, VerityMesh.animated(time, e != null ? e.getId() : 0, air || moving), light, overlay, color);
        pose.popPose();
    }

    private LivingEntity resolve() {
        LivingEntity p = this.passed;
        // the spawn-egg Verity itself (an entity that is really in the world)
        if (p instanceof HuggyEntity && p.level() != null && p.level().getEntity(p.getId()) == p) return p;
        // a Custom NPC drawn with this model: the NPC being rendered (or, for Sable's ragdoll, the one it passed)
        LivingEntity current = RenderTracker.current();
        if (current != null) return current;
        return p;
    }
}

package com.example.wheelofwonder.client;

import com.example.wheelofwonder.WheelEntity;
import com.example.wheelofwonder.WheelMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The carnival wheel, built from code in a voxel style: the spiral face texture (from the reference picture) on both
 * sides, a blocky gold rim, a hub and 16 light bulbs per side with chasing lights, plus additive glow halos around
 * every lit bulb and a pulsing aura while it levitates. Bulbs are drawn full-bright so they glow in the dark.
 */
public class WheelRenderer extends EntityRenderer<WheelEntity> {
    static final ResourceLocation FACE = ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "textures/entity/wheel_face.png");
    static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "textures/entity/wheel_white.png");
    static final ResourceLocation GLOW = ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "textures/entity/wheel_glow.png");

    private static final int GOLD = 0xFFE8B33A, GOLD_DARK = 0xFFA87418, RED = 0xFFC42634, CREAM = 0xFFF6E6D4;
    private static final int BULBS = 16;
    private static final int FULL = LightTexture.FULL_BRIGHT;

    public WheelRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(WheelEntity e) {
        return FACE;
    }

    @Override
    public void render(WheelEntity e, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        if (e.phase() == WheelEntity.BLOWN) return;
        float R = WheelEntity.RADIUS, T = WheelEntity.THICK;
        float spin = e.spin(partial);
        float angle = e.travelAngle();
        float time = e.tickCount + partial;
        // the face glows a little by itself
        int faceLight = LightTexture.pack(Math.max(LightTexture.block(light), 11), LightTexture.sky(light));

        pose.pushPose();
        pose.translate(0.0F, R, 0.0F);
        pose.mulPose(Axis.YP.rotation(-angle));
        pose.mulPose(Axis.ZP.rotation(-spin));
        PoseStack.Pose p = pose.last();

        // spiral faces
        VertexConsumer face = buffers.getBuffer(RenderType.entityCutoutNoCull(FACE));
        float f = R * 0.9F;
        Draw.quadZ(face, p, -f, -f, f, f, T * 0.5F, true, 0xFFFFFFFF, 0, 0, 1, 1, faceLight);
        Draw.quadZ(face, p, -f, -f, f, f, -T * 0.5F, false, 0xFFFFFFFF, 0, 0, 1, 1, faceLight);

        VertexConsumer solid = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));
        // blocky rim: 32 segments, alternating gold shades, with a thin dark inner trim
        int seg = 32;
        float half = (float) (Math.PI * R / seg) * 1.08F;
        for (int i = 0; i < seg; i++) {
            pose.pushPose();
            pose.mulPose(Axis.ZP.rotation((float) (i * Math.PI * 2.0D / seg)));
            PoseStack.Pose s = pose.last();
            Draw.box(solid, s, R * 0.86F, -half, -T * 0.5F - 0.05F, R, half, T * 0.5F + 0.05F, i % 2 == 0 ? GOLD : GOLD_DARK, faceLight);
            Draw.box(solid, s, R * 0.82F, -half, -T * 0.5F - 0.02F, R * 0.86F, half, T * 0.5F + 0.02F, 0xFF3A1A08, faceLight);
            if (i % 4 == 0) {
                // red-and-cream carnival studs on the outer edge
                Draw.box(solid, s, R, -half * 0.6F, -T * 0.3F, R + 0.07F, half * 0.6F, T * 0.3F, (i / 4) % 2 == 0 ? RED : CREAM, faceLight);
            }
            pose.popPose();
        }
        // hub
        Draw.box(solid, p, -0.24F, -0.24F, -T * 0.5F - 0.12F, 0.24F, 0.24F, T * 0.5F + 0.12F, GOLD, faceLight);
        Draw.box(solid, p, -0.12F, -0.12F, -T * 0.5F - 0.17F, 0.12F, 0.12F, T * 0.5F + 0.17F, RED, FULL);

        // bulbs
        boolean[] lit = new boolean[BULBS];
        int[] col = new int[BULBS];
        bulbPattern(e, time, lit, col);
        for (int i = 0; i < BULBS; i++) {
            double a = i * Math.PI * 2.0D / BULBS;
            float x = (float) Math.cos(a) * R * 0.93F, y = (float) Math.sin(a) * R * 0.93F;
            int c = lit[i] ? col[i] : Draw.shade(col[i], 0.35F);
            for (float side : new float[]{1.0F, -1.0F}) {
                float z = side * (T * 0.5F + 0.08F);
                Draw.box(solid, p, x - 0.08F, y - 0.08F, z - 0.05F, x + 0.08F, y + 0.08F, z + 0.05F, GOLD_DARK, faceLight);
                Draw.cube(solid, p, x, y, z + side * 0.06F, 0.075F, c, lit[i] ? FULL : faceLight);
            }
        }
        pose.popPose();

        // glow halos (additive), facing the camera
        VertexConsumer glow = buffers.getBuffer(ShaderCompat.glow(GLOW));
        Quaternionf wheelRot = new Quaternionf().rotationY(-angle).rotateZ(-spin);
        Quaternionf cam = this.entityRenderDispatcher.cameraOrientation();
        for (int i = 0; i < BULBS; i++) {
            if (!lit[i]) continue;
            double a = i * Math.PI * 2.0D / BULBS;
            float flicker = 0.85F + 0.15F * Mth.sin(time * 0.9F + i * 1.7F);
            for (float side : new float[]{1.0F, -1.0F}) {
                Vector3f v = wheelRot.transform(new Vector3f((float) Math.cos(a) * R * 0.93F, (float) Math.sin(a) * R * 0.93F, side * (T * 0.5F + 0.16F)));
                pose.pushPose();
                pose.translate(v.x, v.y + R, v.z);
                pose.mulPose(cam);
                Draw.halo(glow, pose.last(), 0.42F * flicker, Draw.shade(col[i], 0.75F * flicker) | 0xFF000000);
                pose.popPose();
            }
        }
        // big aura behind the wheel while it floats: purple/orange Halloween pulse, red when about to blow
        if (e.phase() != WheelEntity.ROLL) {
            int fuse = e.fuse();
            float pulse = 0.5F + 0.5F * Mth.sin(time * 0.25F);
            int aura = (fuse >= 0 && fuse <= 40) ? 0xFFFF2010 : (((int) (time / 20)) % 2 == 0 ? 0xFF9B30FF : 0xFFFF7A10);
            pose.pushPose();
            pose.translate(0.0F, R, 0.0F);
            pose.mulPose(cam);
            Draw.halo(glow, pose.last(), R * (1.9F + 0.25F * pulse), Draw.shade(aura, 0.22F + 0.12F * pulse) | 0xFF000000);
            pose.popPose();
        }
        super.render(e, yaw, partial, pose, buffers, light);
    }

    /** Chasing lights while rolling and hovering; everything blinks red faster and faster before the explosion. */
    static void bulbPattern(WheelEntity e, float time, boolean[] lit, int[] col) {
        int fuse = e.fuse();
        boolean countdown = e.phase() == WheelEntity.HOVER && fuse >= 0 && fuse <= 40;
        int speed = e.phase() == WheelEntity.ROLL ? 2 : 3;
        int step = (int) (time / speed);
        for (int i = 0; i < lit.length; i++) {
            if (countdown) {
                int period = Math.max(1, fuse / 8);
                lit[i] = ((int) (time / period)) % 2 == 0;
                col[i] = 0xFFFF2A1A;
            } else {
                lit[i] = (i + step) % 3 != 0;
                col[i] = 0xFF000000 | WheelEntity.BULB_COLORS[(i + step / 8) % WheelEntity.BULB_COLORS.length];
            }
        }
    }
}

package com.example.wheelofwonder.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * The Walrus PUNCH! boxing glove, built from voxel boxes on a player arm: white cuff with a gold band, a big
 * fighter-blue glove with a lighter knuckle pad and thumb, two little ivory walrus tusks under the fist, glowing
 * frost runes on the sides and a soft icy glow around the fist. Drawn in the arm's model space (pixels).
 */
final class GloveModel {
    private static final RandomSource CUBE_PICK = RandomSource.create(0L);
    private static final int CUFF = 0xFFEAF2FF, GOLD = 0xFFE8B33A, BLUE = 0xFF1F49C9, BLUE_LIGHT = 0xFF3E72F2,
            TUSK = 0xFFFFF4DC, RUNE = 0xFF7FF6FF;

    private GloveModel() {
    }

    /** @param pose already transformed by {@code arm.translateAndRotate} */
    static void render(PoseStack pose, MultiBufferSource buffers, int light, ModelPart arm, boolean leftArm, float time) {
        if (arm.isEmpty()) return;
        ModelPart.Cube c = arm.getRandomCube(CUBE_PICK);
        float x0 = c.minX, x1 = c.maxX, z0 = c.minZ, z1 = c.maxZ, y1 = c.maxY;
        float inner = leftArm ? x0 : x1, outer = leftArm ? x1 : x0;
        float in = leftArm ? -1.0F : 1.0F; // direction from the arm towards the body

        pose.pushPose();
        pose.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);
        PoseStack.Pose p = pose.last();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(WheelRenderer.WHITE));
        // cuff + gold band
        Draw.box(vc, p, x0 - 0.5F, y1 - 5.4F, z0 - 0.5F, x1 + 0.5F, y1 - 3.6F, z1 + 0.5F, CUFF, light);
        Draw.box(vc, p, x0 - 0.7F, y1 - 3.6F, z0 - 0.7F, x1 + 0.7F, y1 - 3.0F, z1 + 0.7F, GOLD, light);
        // glove body
        Draw.box(vc, p, x0 - 0.9F, y1 - 3.0F, z0 - 0.9F, x1 + 0.9F, y1 + 1.3F, z1 + 0.9F, BLUE, light);
        // knuckle pad (front) and the curled thumb on the inner side
        Draw.box(vc, p, x0 - 0.6F, y1 - 2.3F, z0 - 1.6F, x1 + 0.6F, y1 + 1.0F, z0 - 0.9F, BLUE_LIGHT, light);
        float tx = inner + in * 0.9F;
        Draw.box(vc, p, Math.min(tx, tx + in * 0.9F), y1 - 2.6F, z0 - 0.5F, Math.max(tx, tx + in * 0.9F), y1 - 0.2F, z0 + 1.6F, BLUE_LIGHT, light);
        // walrus tusks under the fist
        float cx = (x0 + x1) * 0.5F;
        Draw.box(vc, p, cx - 1.3F, y1 + 1.3F, z0 - 1.3F, cx - 0.6F, y1 + 3.1F, z0 - 0.6F, TUSK, light);
        Draw.box(vc, p, cx + 0.6F, y1 + 1.3F, z0 - 1.3F, cx + 1.3F, y1 + 3.1F, z0 - 0.6F, TUSK, light);
        // frost runes on the outer side, pulsing, full-bright
        float pulse = 0.65F + 0.35F * Mth.sin(time * 0.25F);
        int rune = Draw.shade(RUNE, pulse) | 0xFF000000;
        float ox = outer - (leftArm ? -0.95F : 0.95F);
        float oxa = Math.min(ox, ox - in * 0.15F), oxb = Math.max(ox, ox - in * 0.15F);
        Draw.box(vc, p, oxa, y1 - 2.4F, z0 + 0.4F, oxb, y1 + 0.6F, z0 + 0.9F, rune, LightTexture.FULL_BRIGHT);
        Draw.box(vc, p, oxa, y1 - 0.9F, z0 + 0.4F, oxb, y1 - 0.4F, z1 - 0.4F, rune, LightTexture.FULL_BRIGHT);
        Draw.box(vc, p, oxa, y1 - 2.4F, z1 - 0.9F, oxb, y1 + 0.6F, z1 - 0.4F, rune, LightTexture.FULL_BRIGHT);

        // icy glow around the fist: three crossed additive quads
        VertexConsumer glow = buffers.getBuffer(ShaderCompat.glow(WheelRenderer.GLOW));
        int g = ShaderCompat.glowColor(Draw.shade(0xFF3FD8FF, 0.32F + 0.18F * pulse) | 0xFF000000);
        float gx = cx, gy = y1 - 0.8F, gz = (z0 + z1) * 0.5F, s = 6.0F + 0.8F * pulse;
        int L = LightTexture.FULL_BRIGHT;
        Draw.v(glow, p, gx - s, gy - s, gz, g, 0, 1, L, 0, 0, 1);
        Draw.v(glow, p, gx + s, gy - s, gz, g, 1, 1, L, 0, 0, 1);
        Draw.v(glow, p, gx + s, gy + s, gz, g, 1, 0, L, 0, 0, 1);
        Draw.v(glow, p, gx - s, gy + s, gz, g, 0, 0, L, 0, 0, 1);
        Draw.v(glow, p, gx, gy - s, gz - s, g, 0, 1, L, 1, 0, 0);
        Draw.v(glow, p, gx, gy - s, gz + s, g, 1, 1, L, 1, 0, 0);
        Draw.v(glow, p, gx, gy + s, gz + s, g, 1, 0, L, 1, 0, 0);
        Draw.v(glow, p, gx, gy + s, gz - s, g, 0, 0, L, 1, 0, 0);
        Draw.v(glow, p, gx - s, gy, gz - s, g, 0, 1, L, 0, 1, 0);
        Draw.v(glow, p, gx + s, gy, gz - s, g, 1, 1, L, 0, 1, 0);
        Draw.v(glow, p, gx + s, gy, gz + s, g, 1, 0, L, 0, 1, 0);
        Draw.v(glow, p, gx - s, gy, gz + s, g, 0, 0, L, 0, 1, 0);
        pose.popPose();
    }
}

package com.example.wheelofwonder.client;

import com.example.wheelofwonder.RangerRideEntity;
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
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Dark Carnival Ranger: a navy platform with a gold rim, a fence and steps; a red tower with gold chevrons, chasing
 * bulbs up both edges and gold guy cables; a sign spelling RANGER in light bulbs under the jester of the rocket ride;
 * on the pivot a red disc with a gold cross, the long arm with bulbs, a striped mallet as the counterweight and a
 * caged gondola with a seat. Boxes and low-sided lathes on a white texture; one solid batch and one glow batch.
 */
public class RangerRideRenderer extends EntityRenderer<RangerRideEntity> {
    private static final int NAVY = 0xFF241B4A, NAVY_DARK = 0xFF17112F, RED = 0xFF9A1424, RED2 = 0xFFC0222F, GOLD = 0xFFE8B33A,
            GOLD_DARK = 0xFFA87418, IRON = 0xFF4E4858, CAGE = 0xFF3A3150, SEAT = 0xFF5A1A6E, STRIPE = 0xFFF2E6D0;
    private static final int[] BULBS = {0xFFC84BFF, 0xFFFF8A1F, 0xFF7CFF3B, 0xFFFF3B3B, 0xFFFFD93B};
    private static final int FULL = LightTexture.FULL_BRIGHT;

    private static final float TOWER_TOP = 7.4F, SIGN_Y = 8.75F, MASK_Y = 10.45F;
    private static final int TOWER_BULBS = 15, ARM_BULBS = 9;
    private static final float GZ = RangerRideEntity.GOND_Z - RangerRideEntity.ARM_Z;
    private static final String WORD = "RANGER";

    public RangerRideRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 2.4F;
        this.shadowStrength = 0.6F;
    }

    @Override
    public ResourceLocation getTextureLocation(RangerRideEntity e) {
        return WheelRenderer.WHITE;
    }

    @Override
    public boolean shouldRender(RangerRideEntity e, net.minecraft.client.renderer.culling.Frustum f, double x, double y, double z) {
        return f.isVisible(e.getBoundingBoxForCulling());
    }

    /** Point on the turning arm (arm frame: y along the arm towards the mallet) in entity space. */
    private static Vec3 armLocal(double th, double ax, double ay, double az) {
        double c = Math.cos(th), s = Math.sin(th);
        return new Vec3(ax * c - ay * s, RangerRideEntity.PIVOT_Y + ax * s + ay * c, RangerRideEntity.ARM_Z + az);
    }

    @Override
    public void render(RangerRideEntity e, float entityYaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        float time = e.tickCount + partial;
        double th = e.viewAngle(partial);
        float speed = e.speed(partial);
        int lit = LightTexture.pack(Math.max(LightTexture.block(light), 6), LightTexture.sky(light));
        int step = (int) (time / Math.max(1.0F, 4.0F - 3.0F * speed));

        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-e.getYRot()));
        PoseStack.Pose p = pose.last();
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(WheelRenderer.WHITE));

        // ---- platform: navy deck, gold rim, fence, steps
        Draw.box(c, p, -2.4F, 0.0F, -2.4F, 2.4F, 0.3F, 2.4F, NAVY, lit);
        Draw.box(c, p, -2.45F, 0.24F, -2.45F, 2.45F, 0.32F, -2.3F, GOLD_DARK, lit);
        Draw.box(c, p, -2.45F, 0.24F, 2.3F, 2.45F, 0.32F, 2.45F, GOLD_DARK, lit);
        Draw.box(c, p, -2.45F, 0.24F, -2.3F, -2.3F, 0.32F, 2.3F, GOLD_DARK, lit);
        Draw.box(c, p, 2.3F, 0.24F, -2.3F, 2.45F, 0.32F, 2.3F, GOLD_DARK, lit);
        for (int i = 0; i <= 12; i++) {
            float u = -2.3F + i * 4.6F / 12.0F;
            fencePost(c, p, u, -2.3F, lit);
            fencePost(c, p, -2.3F, u, lit);
            fencePost(c, p, 2.3F, u, lit);
            if (u < -1.0F || u > 1.0F) fencePost(c, p, u, 2.3F, lit);
        }
        Draw.box(c, p, -2.33F, 0.95F, -2.33F, 2.33F, 1.02F, -2.27F, GOLD, lit);
        Draw.box(c, p, -2.33F, 0.95F, -2.33F, -2.27F, 1.02F, 2.33F, GOLD, lit);
        Draw.box(c, p, 2.27F, 0.95F, -2.33F, 2.33F, 1.02F, 2.33F, GOLD, lit);
        Draw.box(c, p, -2.33F, 0.95F, 2.27F, -1.0F, 1.02F, 2.33F, GOLD, lit);
        Draw.box(c, p, 1.0F, 0.95F, 2.27F, 2.33F, 1.02F, 2.33F, GOLD, lit);
        Draw.box(c, p, -1.0F, 0.0F, 2.4F, 1.0F, 0.2F, 2.85F, NAVY_DARK, lit);
        Draw.box(c, p, -1.0F, 0.0F, 2.85F, 1.0F, 0.1F, 3.2F, NAVY_DARK, lit);

        // ---- tower: red body, darker side pillars, gold chevrons pointing at the pivot
        Draw.box(c, p, -0.55F, 0.3F, -0.5F, 0.55F, TOWER_TOP, 0.0F, RED, lit);
        Draw.box(c, p, -0.62F, 0.3F, -0.56F, -0.5F, TOWER_TOP, 0.04F, Draw.shade(RED, 0.75F), lit);
        Draw.box(c, p, 0.5F, 0.3F, -0.56F, 0.62F, TOWER_TOP, 0.04F, Draw.shade(RED, 0.75F), lit);
        Draw.box(c, p, -0.75F, 0.3F, -0.7F, 0.75F, 0.8F, 0.15F, NAVY_DARK, lit);
        for (int i = 0; i < 8; i++) chevron(c, pose, 1.25F + i * 0.55F, true, lit);
        chevron(c, pose, TOWER_TOP - 0.2F, false, lit);
        // guy cables to the platform corners
        for (float sx : new float[]{-1.0F, 1.0F}) {
            for (float sz : new float[]{-1.0F, 1.0F}) {
                RocketRideRenderer.beam(c, pose, sx * 0.5F, 4.4F, -0.25F, sx * 2.15F, 0.32F, sz * 2.15F, 0.045F, GOLD, lit);
            }
        }
        // the head of the tower and the sign
        Draw.box(c, p, -0.9F, TOWER_TOP, -0.65F, 0.9F, TOWER_TOP + 0.6F, 0.15F, RED2, lit);
        Draw.box(c, p, -0.95F, TOWER_TOP + 0.55F, -0.7F, 0.95F, TOWER_TOP + 0.68F, 0.2F, GOLD, lit);
        for (float x : new float[]{-1.2F, 0.0F, 1.2F}) Draw.box(c, p, x - 0.05F, TOWER_TOP + 0.6F, -0.3F, x + 0.05F, SIGN_Y - 0.4F, -0.2F, GOLD_DARK, lit);
        Draw.box(c, p, -1.8F, SIGN_Y - 0.48F, -0.32F, 1.8F, SIGN_Y + 0.48F, -0.12F, RED, lit);
        Draw.box(c, p, -1.85F, SIGN_Y - 0.53F, -0.34F, 1.85F, SIGN_Y - 0.46F, -0.1F, GOLD, lit);
        Draw.box(c, p, -1.85F, SIGN_Y + 0.46F, -0.34F, 1.85F, SIGN_Y + 0.53F, -0.1F, GOLD, lit);
        Draw.box(c, p, -1.85F, SIGN_Y - 0.5F, -0.34F, -1.78F, SIGN_Y + 0.5F, -0.1F, GOLD, lit);
        Draw.box(c, p, 1.78F, SIGN_Y - 0.5F, -0.34F, 1.85F, SIGN_Y + 0.5F, -0.1F, GOLD, lit);
        drawWord(c, p, time, -0.1F, true);
        drawWord(c, p, time, -0.34F, false);
        // the jester on top (the same one as on the rocket ride), over the sign
        pose.pushPose();
        pose.translate(0.0F, 0.0F, -0.22F);
        RocketRideRenderer.drawMask(c, pose, time, lit, MASK_Y);
        pose.popPose();
        // bulbs up both edges of the tower
        for (int side = 0; side < 2; side++) {
            float x = side == 0 ? -0.6F : 0.6F;
            for (int b = 0; b < TOWER_BULBS; b++) {
                int k = side * TOWER_BULBS + b;
                boolean on = (b + step) % 3 != 0;
                int col = BULBS[(k + step / 5) % BULBS.length];
                Draw.cube(c, p, x, 0.9F + b * 0.43F, 0.08F, 0.05F, on ? col : Draw.shade(col, 0.3F), on ? FULL : lit);
            }
        }
        // the axle
        Draw.box(c, p, -0.2F, RangerRideEntity.PIVOT_Y - 0.2F, -0.1F, 0.2F, RangerRideEntity.PIVOT_Y + 0.2F, RangerRideEntity.ARM_Z + 0.35F, IRON, lit);

        // ---- everything on the turning arm
        pose.pushPose();
        pose.translate(0.0F, RangerRideEntity.PIVOT_Y, RangerRideEntity.ARM_Z);
        pose.mulPose(Axis.ZP.rotation((float) th));
        PoseStack.Pose a = pose.last();
        float L = RangerRideEntity.ARM, M = RangerRideEntity.MALLET;
        // the arm: red beam with gold edges
        Draw.box(c, a, -0.24F, -L + 0.6F, -0.2F, 0.24F, M - 0.3F, 0.2F, RED, lit);
        Draw.box(c, a, -0.27F, -L + 0.6F, -0.22F, -0.21F, M - 0.3F, 0.22F, GOLD, lit);
        Draw.box(c, a, 0.21F, -L + 0.6F, -0.22F, 0.27F, M - 0.3F, 0.22F, GOLD, lit);
        // the pivot disc: red with a gold rim and a gold cross
        pose.pushPose();
        pose.translate(0.0F, 0.0F, 0.22F);
        pose.mulPose(Axis.XP.rotationDegrees(90.0F));
        Draw.lathe(c, pose.last(), new float[]{-0.05F, 0.2F}, new float[]{0.8F, 0.8F}, null, new int[]{RED2}, 16, lit, true);
        Draw.lathe(c, pose.last(), new float[]{-0.07F, 0.22F}, new float[]{0.86F, 0.86F}, null, new int[]{GOLD_DARK}, 16, lit, false);
        pose.popPose();
        for (int k = 0; k < 4; k++) {
            double ang = k * Math.PI * 0.5D + Math.PI * 0.25D;
            float dx = (float) Math.cos(ang), dy = (float) Math.sin(ang);
            RocketRideRenderer.beam(c, pose, dx * 0.1F, dy * 0.1F, 0.46F, dx * 0.66F, dy * 0.66F, 0.46F, 0.22F, GOLD, lit);
        }
        Draw.cube(c, a, 0.0F, 0.0F, 0.5F, 0.13F, GOLD_DARK, lit);
        // the mallet: a striped head across the end of the arm, gold caps, spikes
        pose.pushPose();
        pose.translate(0.0F, M, 0.0F);
        pose.mulPose(Axis.ZP.rotationDegrees(90.0F));
        Draw.lathe(c, pose.last(), new float[]{-0.95F, -0.75F, -0.45F, -0.15F, 0.15F, 0.45F, 0.75F, 0.95F},
                new float[]{0.62F, 0.62F, 0.62F, 0.62F, 0.62F, 0.62F, 0.62F, 0.62F}, null,
                new int[]{GOLD, RED, STRIPE, RED, STRIPE, RED, GOLD}, 14, lit, false);
        Draw.lathe(c, pose.last(), new float[]{-1.05F, -0.95F}, new float[]{0.4F, 0.66F}, null, new int[]{GOLD_DARK}, 14, lit, false);
        Draw.lathe(c, pose.last(), new float[]{0.95F, 1.05F}, new float[]{0.66F, 0.4F}, null, new int[]{GOLD_DARK}, 14, lit, false);
        pose.popPose();
        for (int k = 0; k < 6; k++) {
            double ang = k * Math.PI / 3.0D;
            float sy = (float) Math.cos(ang), sz = (float) Math.sin(ang);
            RocketRideRenderer.beam(c, pose, 0.0F, M + sy * 0.6F, sz * 0.6F, 0.0F, M + sy * 0.95F, sz * 0.95F, 0.09F, IRON, lit);
        }
        // bulbs along the arm
        for (int side = 0; side < 2; side++) {
            for (int b = 0; b < ARM_BULBS; b++) {
                int k = side * ARM_BULBS + b;
                boolean on = (b + step) % 3 != 0;
                int col = BULBS[(k + 2 + step / 5) % BULBS.length];
                float y = -L + 0.9F + b * (L + M - 1.5F) / (ARM_BULBS - 1);
                Draw.cube(c, a, side == 0 ? -0.15F : 0.15F, y, 0.23F, 0.045F, on ? col : Draw.shade(col, 0.3F), on ? FULL : lit);
            }
        }
        // the gondola: a caged car on a gold bracket
        pose.pushPose();
        pose.translate(0.0F, -L, GZ);
        PoseStack.Pose g = pose.last();
        // a round cup open towards the pivot: gold foot, red body with a cream band, gold rim; plush violet inside
        // half as tall as before: the NPC sits on its floor with the upper body above the rim
        Draw.lathe(c, g, new float[]{-1.05F, -1.01F, -0.9F, -0.69F, -0.46F, -0.36F, -0.18F, -0.08F},
                new float[]{0.0F, 0.6F, 0.92F, 1.06F, 1.1F, 1.11F, 1.11F, 1.12F}, null,
                new int[]{GOLD_DARK, GOLD, RED, RED, STRIPE, RED, RED}, 18, lit, true);
        Draw.lathe(c, g, new float[]{-0.08F, -0.03F, -0.015F}, new float[]{1.17F, 1.17F, 0.98F}, null, new int[]{GOLD, GOLD}, 18, lit, false);
        Draw.lathe(c, g, new float[]{-0.93F, -0.9F, -0.02F}, new float[]{0.0F, 0.86F, 0.99F}, null, new int[]{SEAT, SEAT}, 18, lit, false);
        // gold ribs down the outside
        for (int k = 0; k < 6; k++) {
            double ang = k * Math.PI / 3.0D + Math.PI / 6.0D;
            float dx = (float) Math.cos(ang), dz = (float) Math.sin(ang);
            RocketRideRenderer.beam(c, pose, dx * 0.95F, -0.92F, dz * 0.95F, dx * 1.14F, -0.08F, dz * 1.14F, 0.07F, GOLD_DARK, lit);
        }
        // the bench and its padded back (against the arm side)
        Draw.box(c, g, -0.45F, -0.9F, -0.62F, 0.45F, -0.7F, 0.05F, Draw.shade(SEAT, 0.8F), lit);
        Draw.box(c, g, -0.45F, -0.72F, -0.82F, 0.45F, -0.15F, -0.6F, Draw.shade(SEAT, 0.8F), lit);
        // the bracket that holds the cup on the arm
        Draw.box(c, g, -0.4F, -0.85F, -1.2F, 0.4F, 0.62F, -0.95F, GOLD_DARK, lit);
        // bulbs around the rim
        for (int b = 0; b < 10; b++) {
            double ang = b * Math.PI * 2.0D / 10.0D;
            boolean on = (b + step) % 2 == 0;
            int col = BULBS[(b + step / 4) % BULBS.length];
            Draw.cube(c, g, (float) Math.cos(ang) * 1.17F, 0.02F, (float) Math.sin(ang) * 1.17F, 0.05F, on ? col : Draw.shade(col, 0.3F), on ? FULL : lit);
        }
        pose.popPose();
        pose.popPose();
        pose.popPose();

        // ---- glow: bulbs, the word, the jester, the disc and the gondola at speed
        VertexConsumer glow = buffers.getBuffer(ShaderCompat.glow(WheelRenderer.GLOW));
        Quaternionf yaw = new Quaternionf().rotationY((float) Math.toRadians(-e.getYRot()));
        Quaternionf cam = this.entityRenderDispatcher.cameraOrientation();
        for (int side = 0; side < 2; side++) {
            for (int b = 0; b < TOWER_BULBS; b++) {
                if ((b + step) % 3 == 0) continue;
                int col = BULBS[(side * TOWER_BULBS + b + step / 5) % BULBS.length];
                halo(pose, glow, cam, yaw, new Vec3(side == 0 ? -0.6D : 0.6D, 0.9D + b * 0.43D, 0.1D), 0.2F, Draw.shade(col, 0.6F));
            }
            for (int b = 0; b < ARM_BULBS; b++) {
                if ((b + step) % 3 == 0) continue;
                int col = BULBS[(side * ARM_BULBS + b + 2 + step / 5) % BULBS.length];
                float y = -RangerRideEntity.ARM + 0.9F + b * (RangerRideEntity.ARM + RangerRideEntity.MALLET - 1.5F) / (ARM_BULBS - 1);
                halo(pose, glow, cam, yaw, armLocal(th, side == 0 ? -0.15D : 0.15D, y, 0.26D), 0.2F, Draw.shade(col, 0.6F));
            }
        }
        float pulse = 0.5F + 0.5F * Mth.sin(time * 0.2F);
        for (int i = 0; i < WORD.length(); i++) {
            float x = letterX(i) + 0.2F;
            halo(pose, glow, cam, yaw, new Vec3(x, SIGN_Y, 0.0D), 0.5F, Draw.shade(0xFFFFB030, 0.28F + 0.12F * pulse));
        }
        halo(pose, glow, cam, yaw, new Vec3(0.0D, MASK_Y, -0.2D), 1.2F + 0.2F * pulse + 0.5F * speed, Draw.shade(0xFFFF2A10, 0.22F + 0.1F * pulse));
        halo(pose, glow, cam, yaw, armLocal(th, 0.0D, 0.0D, 0.55D), 0.9F + 0.6F * speed, Draw.shade(0xFFC84BFF, 0.25F + 0.4F * speed));
        if (speed > 0.3F) {
            Vec3 gl = armLocal(th, 0.0D, -RangerRideEntity.ARM, GZ);
            halo(pose, glow, cam, yaw, gl, 1.6F * speed, Draw.shade(0xFF8A3BFF, 0.35F * speed));
            halo(pose, glow, cam, yaw, armLocal(th, 0.0D, RangerRideEntity.MALLET, 0.0D), 1.2F * speed, Draw.shade(0xFFFF6A1A, 0.35F * speed));
        }
        super.render(e, entityYaw, partial, pose, buffers, light);
    }

    private static void fencePost(VertexConsumer c, PoseStack.Pose p, float x, float z, int lit) {
        Draw.box(c, p, x - 0.04F, 0.3F, z - 0.04F, x + 0.04F, 1.0F, z + 0.04F, IRON, lit);
    }

    /** A gold arrow on the front of the tower, pointing up (towards the pivot) or down. */
    private static void chevron(VertexConsumer c, PoseStack pose, float y, boolean up, int lit) {
        float d = up ? 0.18F : -0.18F;
        RocketRideRenderer.beam(c, pose, -0.32F, y - d, 0.02F, 0.0F, y + d * 0.2F, 0.02F, 0.09F, GOLD, lit);
        RocketRideRenderer.beam(c, pose, 0.32F, y - d, 0.02F, 0.0F, y + d * 0.2F, 0.02F, 0.09F, GOLD, lit);
    }

    // ---------------------------------------------------------------- the sign: RANGER in light bulbs

    private static final String[][] FONT = {
            {"####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"},   // R
            {".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"},   // A
            {"#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"},   // N
            {".####", "#....", "#....", "#.###", "#...#", "#...#", ".###."},   // G
            {"#####", "#....", "#....", "####.", "#....", "#....", "#####"},   // E
    };
    private static final float PITCH = 0.095F;

    private static String[] glyph(char ch) {
        return switch (ch) {
            case 'R' -> FONT[0];
            case 'A' -> FONT[1];
            case 'N' -> FONT[2];
            case 'G' -> FONT[3];
            default -> FONT[4];
        };
    }

    private static float letterX(int i) {
        float letterW = 5 * PITCH, gap = 0.11F;
        float total = WORD.length() * letterW + (WORD.length() - 1) * gap;
        return -total * 0.5F + i * (letterW + gap);
    }

    /** The word on one face of the sign (front: readable from the front; back: mirrored so it reads from behind). */
    private static void drawWord(VertexConsumer c, PoseStack.Pose p, float time, float z, boolean front) {
        int chase = (int) (time / 3.0F);
        for (int i = 0; i < WORD.length(); i++) {
            String[] gl = glyph(WORD.charAt(i));
            float x0 = letterX(i);
            for (int row = 0; row < 7; row++) {
                for (int col = 0; col < 5; col++) {
                    if (gl[row].charAt(col) != '#') continue;
                    float x = x0 + col * PITCH + PITCH * 0.5F;
                    if (!front) x = -x;
                    float y = SIGN_Y + (3 - row) * PITCH;
                    boolean bright = (row + col + i + chase) % 6 != 0;
                    int colr = bright ? 0xFFFFD45A : 0xFFE08A20;
                    Draw.cube(c, p, x, y, z + (front ? 0.03F : -0.03F), 0.036F, colr, FULL);
                }
            }
        }
    }

    private static void halo(PoseStack pose, VertexConsumer glow, Quaternionf cam, Quaternionf yaw, Vec3 local, float size, int argb) {
        Vector3f v = yaw.transform(new Vector3f((float) local.x, (float) local.y, (float) local.z));
        pose.pushPose();
        pose.translate(v.x, v.y, v.z);
        pose.mulPose(cam);
        Draw.halo(glow, pose.last(), size, argb | 0xFF000000);
        pose.popPose();
    }
}

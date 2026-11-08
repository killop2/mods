package com.example.wheelofwonder.client;

import com.example.wheelofwonder.WheelMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * The rocket car, shared by the ride (four cars, two boosters) and the flying rocket. Model space: +Y is the nose,
 * -Z is the top (cockpit side). The hull is a 10-sided lathe with the flame-striped skin texture (60 quads), the
 * rest are a dozen boxes on the white texture.
 */
final class RocketModel {
    static final ResourceLocation SKIN = ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "textures/entity/rocket_skin.png");
    static final int NAVY = 0xFF241B4A, RED = 0xFF9A1424, GOLD = 0xFFE8B33A, BLACK = 0xFF121018, IRON = 0xFF4E4858;
    /** Size of a ride car / the flying rocket. */
    static final float SCALE = 1.4F;
    /** Tail (nozzle) in model space. */
    static final float TAIL_Y = -0.86F;

    private static final float[] YS = {-0.75F, -0.62F, -0.45F, 0.35F, 0.55F, 0.72F, 0.84F};
    private static final float[] RS = {0.15F, 0.22F, 0.26F, 0.26F, 0.22F, 0.13F, 0.0F};
    private static final float[] VS = {0.0F, 0.08F, 0.12F, 0.76F, 0.8F, 0.92F, 1.0F};

    private RocketModel() {
    }

    /**
     * Pushes a pose with the rocket at (x, y, z) pointing along (fx, fy, fz), top side up; the caller pops it.
     */
    static void place(PoseStack pose, double x, double y, double z, double fx, double fy, double fz, float scale) {
        pose.pushPose();
        pose.translate(x, y, z);
        double h = Math.sqrt(fx * fx + fz * fz);
        float yaw = (float) Math.atan2(fx, fz);
        float pitchUp = (float) Math.atan2(fy, h);
        pose.mulPose(Axis.YP.rotation(yaw));
        pose.mulPose(Axis.XP.rotation((float) (Math.PI * 0.5D) - pitchUp));
        pose.scale(scale, scale, scale);
    }

    /** Fins, cockpit, boosters, nozzle and tail lamps (white texture batch). */
    static void solid(VertexConsumer c, PoseStack.Pose p, int light, boolean lamps) {
        Draw.box(c, p, -0.11F, TAIL_Y, -0.11F, 0.11F, -0.74F, 0.11F, IRON, light);
        // tail fin on top with a red bolt, two side fins
        Draw.box(c, p, -0.025F, -0.74F, -0.62F, 0.025F, -0.36F, -0.2F, NAVY, light);
        Draw.box(c, p, -0.03F, -0.62F, -0.52F, 0.03F, -0.5F, -0.36F, RED, light);
        Draw.box(c, p, 0.2F, -0.72F, -0.025F, 0.52F, -0.46F, 0.025F, RED, light);
        Draw.box(c, p, -0.52F, -0.72F, -0.025F, -0.2F, -0.46F, 0.025F, RED, light);
        // cockpit with a gold windscreen rim
        Draw.box(c, p, -0.13F, -0.18F, -0.34F, 0.13F, 0.16F, -0.2F, BLACK, light);
        Draw.box(c, p, -0.14F, 0.16F, -0.36F, 0.14F, 0.21F, -0.2F, GOLD, light);
        // little side boosters
        for (float s : new float[]{1.0F, -1.0F}) {
            Draw.box(c, p, s > 0 ? 0.25F : -0.33F, -0.32F, -0.05F, s > 0 ? 0.33F : -0.25F, 0.12F, 0.05F, RED, light);
            Draw.box(c, p, s > 0 ? 0.24F : -0.34F, 0.12F, -0.04F, s > 0 ? 0.34F : -0.24F, 0.2F, 0.04F, GOLD, light);
            Draw.cube(c, p, s * 0.17F, -0.68F, -0.13F, 0.04F, lamps ? 0xFFFF3030 : 0xFF6A1010, lamps ? LightTexture.FULL_BRIGHT : light);
        }
    }

    /** The hull with the flame skin (skin texture batch). */
    static void skin(VertexConsumer c, PoseStack.Pose p, int light) {
        Draw.lathe(c, p, YS, RS, VS, null, 10, light, false);
    }

    static float flicker(float time, int seed) {
        return 0.8F + 0.2F * Mth.sin(time * 1.7F + seed) * Mth.sin(time * 0.53F + seed * 2.1F);
    }
}

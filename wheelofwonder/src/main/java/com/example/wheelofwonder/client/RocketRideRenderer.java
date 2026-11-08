package com.example.wheelofwonder.client;

import com.example.wheelofwonder.RocketRideEntity;
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
 * Dark Carnival rocket ride: a ribbed navy cone base with a gold foot, pistons, a red spinning hub and column with
 * gold rings, a navy spire crowned by a wooden jester mask with red eyes and a sunburst of spikes ending in
 * coloured bulbs, four orange arms with chasing bulbs and gold cables, and a rocket car on each arm.
 * Round parts are low-sided lathes with smooth normals; everything else is boxes. Three render batches.
 */
public class RocketRideRenderer extends EntityRenderer<RocketRideEntity> {
    private static final int NAVY = 0xFF241B4A, RED = 0xFF9A1424, GOLD = 0xFFE8B33A, GOLD_DARK = 0xFFA87418;
    private static final int ORANGE = 0xFFD8661A, IRON = 0xFF4E4858;
    private static final int MASK = 0xFF9A7650, MASK_DARK = 0xFF6E5236, MASK_GAP = 0xFF2A1A12, CROWN = 0xFF7A5634, SPIKE = 0xFFB8301E;
    private static final int[] BULBS = {0xFFC84BFF, 0xFFFF8A1F, 0xFF7CFF3B, 0xFFFF3B3B, 0xFFFFD93B};
    private static final int FULL = LightTexture.FULL_BRIGHT;

    private static final float MASK_Y = 4.25F;
    private static final int HUB_BULBS = 8, ARM_BULBS = 5, SPIKES = 8;
    private static final float[] BOOSTERS = {0.58F, -0.58F};

    public RocketRideRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 1.3F;
        this.shadowStrength = 0.6F;
    }

    @Override
    public ResourceLocation getTextureLocation(RocketRideEntity e) {
        return WheelRenderer.WHITE;
    }

    /** Point of the spinning top part (turned by the carousel angle) in entity space. */
    private static Vec3 spun(float ang, double x, double y, double z) {
        double c = Math.cos(ang), s = Math.sin(ang);
        // same turn as Axis.YP.rotation(-ang)
        return new Vec3(x * c - z * s, y, x * s + z * c);
    }

    @Override
    public void render(RocketRideEntity e, float entityYaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        float time = e.tickCount + partial;
        float ang = e.viewAngle(partial);
        float tilt = e.tilt(partial);
        float speed = e.omega(partial) / RocketRideEntity.OMEGA;
        int st = e.state();
        int lit = LightTexture.pack(Math.max(LightTexture.block(light), 6), LightTexture.sky(light));
        boolean car0 = !e.car0Missing();
        boolean ignition = st == RocketRideEntity.LOADED && e.age() > RocketRideEntity.launchAt() - 30;

        // chasing bulbs on the hub and the arms, faster with the spin
        int n = HUB_BULBS + RocketRideEntity.CARS * ARM_BULBS;
        boolean[] on = new boolean[n];
        int[] col = new int[n];
        int step = (int) (time / Math.max(1.0F, 4.0F - 3.0F * speed));
        for (int i = 0; i < n; i++) {
            int pos = i < HUB_BULBS ? i : (i - HUB_BULBS) % ARM_BULBS;
            on[i] = (pos + step) % 3 != 0;
            col[i] = BULBS[(i + step / 5) % BULBS.length];
        }

        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-e.getYRot()));
        PoseStack.Pose p = pose.last();
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(WheelRenderer.WHITE));

        // ---- static base: gold foot, ribbed navy cone, pistons with springs
        Draw.lathe(c, p, new float[]{0.0F, 0.1F, 0.18F, 1.3F}, new float[]{1.3F, 1.3F, 1.18F, 0.5F}, null,
                new int[]{GOLD_DARK, GOLD, NAVY}, 16, lit, true);
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4.0D;
            float x = (float) Math.cos(a) * 0.4F, z = (float) Math.sin(a) * 0.4F;
            Draw.box(c, p, x - 0.06F, 1.28F, z - 0.06F, x + 0.06F, 1.58F, z + 0.06F, IRON, lit);
            Draw.box(c, p, x - 0.08F, 1.36F, z - 0.08F, x + 0.08F, 1.48F, z + 0.08F, GOLD_DARK, lit);
        }

        // ---- spinning top: hub, column with gold rings, spire, boosters, jester mask
        pose.pushPose();
        pose.mulPose(Axis.YP.rotation(-ang));
        PoseStack.Pose s = pose.last();
        Draw.lathe(c, s, new float[]{1.55F, 1.9F, 1.97F}, new float[]{0.66F, 0.66F, 0.44F}, null, new int[]{RED, GOLD}, 12, lit, true);
        Draw.lathe(c, s, new float[]{1.97F, 2.2F, 2.27F, 2.7F, 2.77F, 3.05F}, new float[]{0.42F, 0.42F, 0.42F, 0.42F, 0.42F, 0.42F}, null,
                new int[]{RED, GOLD, RED, GOLD, RED}, 12, lit, true);
        Draw.lathe(c, s, new float[]{3.05F, 3.15F, 3.98F}, new float[]{0.44F, 0.38F, 0.12F}, null, new int[]{GOLD, NAVY}, 12, lit, true);
        for (float bx : BOOSTERS) {
            // small decorative rockets standing on the column, nose up
            RocketModel.place(pose, bx, 2.35D, 0.0D, 0.0D, 1.0D, 0.0D, 0.55F);
            RocketModel.solid(c, pose.last(), lit, true);
            pose.popPose();
        }
        drawMask(c, pose, time, lit, MASK_Y);
        pose.popPose();

        // ---- arms, cables, bulbs and cars
        for (int k = 0; k < RocketRideEntity.CARS; k++) {
            double a = ang + k * Math.PI * 0.5D;
            float dx = (float) Math.cos(a), dz = (float) Math.sin(a);
            Vec3 car = RocketRideEntity.carLocal(k, ang, tilt);
            float rx = dx * RocketRideEntity.HUB_R, ry = RocketRideEntity.ARM_Y, rz = dz * RocketRideEntity.HUB_R;
            float tx = (float) car.x, ty = (float) car.y - RocketRideEntity.CAR_UP, tz = (float) car.z;
            beam(c, pose, rx, ry, rz, tx, ty, tz, 0.15F, ORANGE, lit);
            beam(c, pose, tx, ty - 0.05F, tz, tx, (float) car.y - 0.1F, tz, 0.1F, RED, lit);
            beam(c, pose, dx * 0.3F, 3.0F, dz * 0.3F, tx, (float) car.y + 0.35F, tz, 0.025F, GOLD, lit);
            for (int b = 0; b < ARM_BULBS; b++) {
                int i = HUB_BULBS + k * ARM_BULBS + b;
                float f = 0.2F + b * 0.17F;
                float bx = Mth.lerp(f, rx, tx), by = Mth.lerp(f, ry, ty) + 0.1F, bz = Mth.lerp(f, rz, tz);
                Draw.cube(c, p, bx, by, bz, 0.045F, on[i] ? col[i] : Draw.shade(col[i], 0.3F), on[i] ? FULL : lit);
            }
            if (k == 0 && !car0) continue;
            Vec3 t = RocketRideEntity.tangentLocal(k, ang);
            RocketModel.place(pose, car.x, car.y, car.z, t.x, 0.0D, t.z, RocketModel.SCALE);
            RocketModel.solid(c, pose.last(), lit, k == 0 && ignition);
            pose.popPose();
        }
        // hub bulbs (turn with the top)
        for (int i = 0; i < HUB_BULBS; i++) {
            Vec3 b = spun(ang, Math.cos(i * Math.PI / 4.0D) * 0.68D, 1.72D, Math.sin(i * Math.PI / 4.0D) * 0.68D);
            Draw.cube(c, p, (float) b.x, (float) b.y, (float) b.z, 0.05F, on[i] ? col[i] : Draw.shade(col[i], 0.3F), on[i] ? FULL : lit);
        }

        // ---- second batch: rocket hulls with the flame skin
        VertexConsumer sk = buffers.getBuffer(RenderType.entityCutoutNoCull(RocketModel.SKIN));
        for (int k = 0; k < RocketRideEntity.CARS; k++) {
            if (k == 0 && !car0) continue;
            Vec3 car = RocketRideEntity.carLocal(k, ang, tilt);
            Vec3 t = RocketRideEntity.tangentLocal(k, ang);
            RocketModel.place(pose, car.x, car.y, car.z, t.x, 0.0D, t.z, RocketModel.SCALE);
            RocketModel.skin(sk, pose.last(), lit);
            pose.popPose();
        }
        pose.pushPose();
        pose.mulPose(Axis.YP.rotation(-ang));
        for (float bx : BOOSTERS) {
            RocketModel.place(pose, bx, 2.35D, 0.0D, 0.0D, 1.0D, 0.0D, 0.55F);
            RocketModel.skin(sk, pose.last(), lit);
            pose.popPose();
        }
        pose.popPose();
        pose.popPose();

        // ---- third batch: glow halos facing the camera
        VertexConsumer glow = buffers.getBuffer(ShaderCompat.glow(WheelRenderer.GLOW));
        Quaternionf yaw = new Quaternionf().rotationY((float) Math.toRadians(-e.getYRot()));
        Quaternionf cam = this.entityRenderDispatcher.cameraOrientation();
        for (int i = 0; i < n; i++) {
            if (!on[i]) continue;
            Vec3 lp;
            if (i < HUB_BULBS) {
                lp = spun(ang, Math.cos(i * Math.PI / 4.0D) * 0.68D, 1.72D, Math.sin(i * Math.PI / 4.0D) * 0.68D);
            } else {
                int k = (i - HUB_BULBS) / ARM_BULBS, b = (i - HUB_BULBS) % ARM_BULBS;
                double a = ang + k * Math.PI * 0.5D;
                Vec3 car = RocketRideEntity.carLocal(k, ang, tilt);
                Vec3 root = new Vec3(Math.cos(a) * RocketRideEntity.HUB_R, RocketRideEntity.ARM_Y, Math.sin(a) * RocketRideEntity.HUB_R);
                lp = root.lerp(car.subtract(0.0D, RocketRideEntity.CAR_UP, 0.0D), 0.2D + b * 0.17D).add(0.0D, 0.1D, 0.0D);
            }
            halo(pose, glow, cam, yaw, lp, 0.22F, Draw.shade(col[i], 0.65F));
        }
        // the jester: red glow behind the head, burning eyes, bulbs on the spikes
        float pulse = 0.5F + 0.5F * Mth.sin(time * 0.15F);
        halo(pose, glow, cam, yaw, new Vec3(0.0D, MASK_Y, 0.0D), 1.1F + 0.2F * pulse + 0.6F * speed, Draw.shade(0xFFFF2A10, 0.22F + 0.1F * pulse));
        for (float zs : new float[]{0.2F, -0.2F}) {
            for (float xs : new float[]{0.105F, -0.105F}) {
                halo(pose, glow, cam, yaw, spun(ang, xs, MASK_Y + 0.035D, zs), 0.12F * RocketModel.flicker(time, (int) (xs * 100)), 0xFFB01010);
            }
        }
        for (int i = 0; i < SPIKES; i++) {
            double th = i * Math.PI * 2.0D / SPIKES + Math.PI / SPIKES;
            float fl = RocketModel.flicker(time, i);
            halo(pose, glow, cam, yaw, spun(ang, Math.cos(th) * 1.0D, MASK_Y + Math.sin(th) * 1.0D, 0.0D), 0.24F * fl, Draw.shade(i % 2 == 0 ? 0xFF5A6BFF : 0xFFC84BFF, 0.75F));
            halo(pose, glow, cam, yaw, spun(ang, Math.cos(th) * 0.62D, MASK_Y + Math.sin(th) * 0.62D, 0.0D), 0.18F * fl, Draw.shade(0xFFFF9A30, 0.6F));
        }
        // the loaded rocket's engine glows before the launch
        if (ignition && car0) {
            Vec3 car = RocketRideEntity.carLocal(0, ang, tilt);
            Vec3 t = RocketRideEntity.tangentLocal(0, ang);
            Vec3 tail = car.subtract(t.scale(-RocketModel.TAIL_Y * RocketModel.SCALE));
            float g = Mth.clamp((e.age() + partial - (RocketRideEntity.launchAt() - 30)) / 30.0F, 0.0F, 1.0F);
            halo(pose, glow, cam, yaw, tail, (0.3F + 0.5F * g) * RocketModel.flicker(time, 3), Draw.shade(0xFFFF7A10, 0.4F + 0.5F * g));
        }
        super.render(e, entityYaw, partial, pose, buffers, light);
    }

    /** Wooden jester mask (front and back face), a fan crown on top and a sunburst of spikes around it. */
    static void drawMask(VertexConsumer c, PoseStack pose, float time, int lit, float y) {
        pose.pushPose();
        pose.translate(0.0F, y, 0.0F);
        PoseStack.Pose m = pose.last();
        Draw.box(c, m, -0.24F, -0.3F, -0.17F, 0.24F, 0.3F, 0.17F, MASK, lit);
        Draw.box(c, m, -0.26F, 0.08F, -0.19F, 0.26F, 0.17F, 0.19F, MASK_DARK, lit);
        Draw.box(c, m, -0.2F, -0.36F, -0.12F, 0.2F, -0.3F, 0.12F, MASK_DARK, lit);
        int eye = Draw.shade(0xFFFF2020, RocketModel.flicker(time, 7));
        for (float sgn : new float[]{1.0F, -1.0F}) {
            float z0 = sgn > 0 ? 0.17F : -0.185F, z1 = sgn > 0 ? 0.185F : -0.17F;
            // nose, grin, burning eyes
            Draw.box(c, m, -0.05F, -0.1F, sgn > 0 ? 0.17F : -0.25F, 0.05F, 0.08F, sgn > 0 ? 0.25F : -0.17F, MASK_DARK, lit);
            Draw.box(c, m, -0.16F, -0.22F, z0, 0.16F, -0.16F, z1, MASK_GAP, lit);
            Draw.box(c, m, -0.15F, 0.0F, z0, -0.06F, 0.07F, z1, eye, FULL);
            Draw.box(c, m, 0.06F, 0.0F, z0, 0.15F, 0.07F, z1, eye, FULL);
        }
        // crown: a fan of wooden fins
        for (int i = 0; i < 7; i++) {
            double th = Math.toRadians(90.0D - 60.0D + i * 20.0D);
            beam(c, pose, 0.0F, 0.26F, 0.0F, (float) Math.cos(th) * 0.48F, 0.26F + (float) Math.sin(th) * 0.48F, 0.0F, 0.09F, i % 2 == 0 ? CROWN : GOLD_DARK, lit);
        }
        // sunburst: spikes with an orange bulb halfway and a blue/violet bulb at the tip
        for (int i = 0; i < SPIKES; i++) {
            double th = i * Math.PI * 2.0D / SPIKES + Math.PI / SPIKES;
            float cx = (float) Math.cos(th), cy = (float) Math.sin(th);
            beam(c, pose, cx * 0.28F, cy * 0.28F, 0.0F, cx * 0.95F, cy * 0.95F, 0.0F, 0.08F, SPIKE, lit);
            Draw.cube(c, m, cx * 0.62F, cy * 0.62F, 0.0F, 0.055F, 0xFFFF9A30, FULL);
            Draw.cube(c, m, cx * 1.0F, cy * 1.0F, 0.0F, 0.065F, i % 2 == 0 ? 0xFF7080FF : 0xFFD070FF, FULL);
        }
        pose.popPose();
    }

    private static void halo(PoseStack pose, VertexConsumer glow, Quaternionf cam, Quaternionf yaw, Vec3 local, float size, int argb) {
        Vector3f v = yaw.transform(new Vector3f((float) local.x, (float) local.y, (float) local.z));
        pose.pushPose();
        pose.translate(v.x, v.y, v.z);
        pose.mulPose(cam);
        Draw.halo(glow, pose.last(), size, argb | 0xFF000000);
        pose.popPose();
    }

    /** A box of width w from point a to point b. */
    static void beam(VertexConsumer c, PoseStack pose, float ax, float ay, float az, float bx, float by, float bz, float w, int col, int light) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-4F) return;
        pose.pushPose();
        pose.translate(ax, ay, az);
        pose.mulPose(new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, dx / len, dy / len, dz / len));
        Draw.box(c, pose.last(), -w * 0.5F, 0.0F, -w * 0.5F, w * 0.5F, len, w * 0.5F, col, light);
        pose.popPose();
    }
}

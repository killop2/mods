package com.example.wheelofwonder.client;

import com.example.wheelofwonder.BallistaEntity;
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
 * Dark Carnival ballista, built from code in the voxel style of the Wheel of Wonder (same white/face/glow textures,
 * nothing new to load): red-and-black striped frame, violet wood, gold bands, four little spiral wheels, a padded
 * crossbar with a jack-o'-lantern, a winch, the throwing arm with a striped bucket, and chasing carnival bulbs
 * with additive halos. About 150 boxes, all on one texture, in three render batches.
 */
public class BallistaRenderer extends EntityRenderer<BallistaEntity> {
    private static final int WOOD = 0xFF4A2A58, WOOD_DARK = 0xFF2A1830, RED = 0xFF8E1424, BLACK = 0xFF1A0D16;
    private static final int GOLD = 0xFFE8B33A, GOLD_DARK = 0xFFA87418, ROPE = 0xFF7A5634, CUSHION = 0xFFB01E3C;
    private static final int IRON = 0xFF4E4858, PUMPKIN = 0xFFFF7A10, STEM = 0xFF3F6A1E;
    /** Dark carnival bulb colours: violet, orange, toxic green, blood red, gold. */
    private static final int[] BULBS = {0xFFC84BFF, 0xFFFF8A1F, 0xFF7CFF3B, 0xFFFF3B3B, 0xFFFFD93B};
    private static final int FULL = LightTexture.FULL_BRIGHT;

    private static final float L = BallistaEntity.ARM_LEN;
    private static final float WINCH_Z = -1.25F;
    private static final float[][] WHEELS = {{0.8F, 0.95F}, {-0.8F, 0.95F}, {0.8F, -1.45F}, {-0.8F, -1.45F}};
    private static final float WHEEL_R = 0.34F, WHEEL_T = 0.1F;
    /** Bulbs on the frame (entity space): along both rails and on the crossbar. */
    private static final float[][] FRAME_BULBS;
    /** Bulbs on the bucket rim (arm space). */
    private static final float[][] BUCKET_BULBS = {{0.36F, L - 0.45F, 0.5F}, {-0.36F, L - 0.45F, 0.5F}, {0.36F, L + 0.1F, 0.5F}, {-0.36F, L + 0.1F, 0.5F}};

    static {
        FRAME_BULBS = new float[16][];
        int n = 0;
        for (int i = 0; i < 7; i++) {
            FRAME_BULBS[n++] = new float[]{0.62F, 0.53F, -1.6F + i * 0.45F};
            FRAME_BULBS[n++] = new float[]{-0.62F, 0.53F, -1.6F + i * 0.45F};
        }
        FRAME_BULBS[n++] = new float[]{0.5F, 1.63F, 0.54F};
        FRAME_BULBS[n] = new float[]{-0.5F, 1.63F, 0.54F};
    }

    public BallistaRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 1.1F;
        this.shadowStrength = 0.6F;
    }

    @Override
    public ResourceLocation getTextureLocation(BallistaEntity e) {
        return WheelRenderer.WHITE;
    }

    @Override
    public void render(BallistaEntity e, float entityYaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        float time = e.tickCount + partial;
        int st = e.state();
        float arm = e.armAngle(partial);
        float yawDeg = e.getYRot();
        // a little light of its own, but darker than the wheel: it is the Dark Carnival
        int lit = LightTexture.pack(Math.max(LightTexture.block(light), 6), LightTexture.sky(light));

        boolean[] on = new boolean[FRAME_BULBS.length + BUCKET_BULBS.length];
        int[] col = new int[on.length];
        bulbPattern(st, e.age() + partial, time, on, col);

        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-yawDeg));
        PoseStack.Pose p = pose.last();
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(WheelRenderer.WHITE));

        // ---- base frame: striped side rails, cross beams, axles
        for (float s : new float[]{1.0F, -1.0F}) {
            float x0 = s * 0.62F - 0.09F, x1 = s * 0.62F + 0.09F;
            for (int i = 0; i < 6; i++) {
                float z0 = -1.75F + i * 0.5F;
                Draw.box(c, p, x0, 0.30F, z0, x1, 0.48F, z0 + 0.5F, i % 2 == 0 ? RED : BLACK, lit);
            }
            Draw.box(c, p, x0 - 0.015F, 0.29F, 1.25F, x1 + 0.015F, 0.49F, 1.32F, GOLD, lit);
            Draw.box(c, p, x0 - 0.015F, 0.29F, -1.82F, x1 + 0.015F, 0.49F, -1.75F, GOLD, lit);
            // little gold spikes at the front ends (circus finials)
            Draw.box(c, p, s * 0.62F - 0.04F, 0.49F, 1.24F, s * 0.62F + 0.04F, 0.66F, 1.32F, GOLD_DARK, lit);
            // axle stands and winch stands
            Draw.box(c, p, s * 0.54F - 0.06F, 0.48F, BallistaEntity.PIVOT_Z - 0.1F, s * 0.54F + 0.06F, BallistaEntity.PIVOT_Y + 0.08F, BallistaEntity.PIVOT_Z + 0.1F, WOOD_DARK, lit);
            Draw.box(c, p, s * 0.54F - 0.04F, 0.48F, WINCH_Z - 0.08F, s * 0.54F + 0.04F, 0.7F, WINCH_Z + 0.08F, WOOD_DARK, lit);
        }
        Draw.box(c, p, -0.53F, 0.32F, 1.02F, 0.53F, 0.46F, 1.18F, WOOD, lit);
        Draw.box(c, p, -0.53F, 0.32F, -1.72F, 0.53F, 0.46F, -1.56F, WOOD, lit);
        Draw.box(c, p, -0.53F, 0.32F, -0.14F, 0.53F, 0.46F, 0.04F, WOOD, lit);
        Draw.box(c, p, -0.86F, 0.29F, 0.9F, 0.86F, 0.39F, 1.0F, IRON, lit);
        Draw.box(c, p, -0.86F, 0.29F, -1.5F, 0.86F, 0.39F, -1.4F, IRON, lit);

        // ---- wheel rims (the spiral faces come in the second batch)
        for (float[] w : WHEELS) {
            pose.pushPose();
            pose.translate(w[0], WHEEL_R, w[1]);
            pose.mulPose(Axis.YP.rotationDegrees(90.0F));
            int seg = 10;
            float half = (float) (Math.PI * WHEEL_R / seg) * 1.1F;
            for (int i = 0; i < seg; i++) {
                pose.pushPose();
                pose.mulPose(Axis.ZP.rotation((float) (i * Math.PI * 2.0D / seg)));
                Draw.box(c, pose.last(), WHEEL_R * 0.8F, -half, -WHEEL_T * 0.5F - 0.02F, WHEEL_R, half, WHEEL_T * 0.5F + 0.02F, i % 2 == 0 ? GOLD : GOLD_DARK, lit);
                pose.popPose();
            }
            Draw.box(c, pose.last(), -0.07F, -0.07F, -WHEEL_T * 0.5F - 0.06F, 0.07F, 0.07F, WHEEL_T * 0.5F + 0.06F, RED, lit);
            pose.popPose();
        }

        // ---- A-frame: slanted posts, gold bands, crossbar with the padded stop
        for (float s : new float[]{1.0F, -1.0F}) {
            beam(c, pose, s * 0.58F, 0.47F, 1.1F, s * 0.5F, 1.5F, 0.6F, 0.16F, WOOD, lit);
            beam(c, pose, s * 0.58F, 0.47F, -0.5F, s * 0.5F, 1.45F, 0.5F, 0.14F, WOOD_DARK, lit);
            beam(c, pose, s * 0.565F, 0.8F, 0.96F, s * 0.555F, 0.9F, 0.91F, 0.19F, GOLD, lit);
        }
        Draw.box(c, p, -0.66F, 1.4F, 0.44F, 0.66F, 1.58F, 0.64F, BLACK, lit);
        for (int i = 0; i < 4; i++) {
            float x0 = -0.66F + i * 0.33F;
            if (i % 2 == 0) Draw.box(c, p, x0, 1.395F, 0.435F, x0 + 0.33F, 1.585F, 0.645F, RED, lit);
        }
        Draw.box(c, p, -0.28F, 1.36F, 0.33F, 0.28F, 1.62F, 0.44F, CUSHION, lit);
        Draw.box(c, p, -0.3F, 1.47F, 0.32F, 0.3F, 1.51F, 0.44F, GOLD, lit);

        // ---- jack-o'-lantern on the crossbar, its face lit from inside
        Draw.box(c, p, -0.1F, 1.58F, 0.44F, 0.1F, 1.78F, 0.64F, PUMPKIN, lit);
        Draw.box(c, p, -0.025F, 1.78F, 0.515F, 0.025F, 1.84F, 0.565F, STEM, lit);
        float flick = 0.75F + 0.25F * Mth.sin(time * 0.7F) * Mth.sin(time * 0.31F + 1.0F);
        int eyes = Draw.shade(0xFFFFE070, flick);
        Draw.box(c, p, -0.07F, 1.69F, 0.64F, -0.025F, 1.73F, 0.645F, eyes, FULL);
        Draw.box(c, p, 0.025F, 1.69F, 0.64F, 0.07F, 1.73F, 0.645F, eyes, FULL);
        Draw.box(c, p, -0.06F, 1.62F, 0.64F, 0.06F, 1.65F, 0.645F, eyes, FULL);

        // ---- torsion skein and axle around the pivot
        Draw.box(c, p, -0.62F, BallistaEntity.PIVOT_Y - 0.035F, BallistaEntity.PIVOT_Z - 0.035F, 0.62F, BallistaEntity.PIVOT_Y + 0.035F, BallistaEntity.PIVOT_Z + 0.035F, GOLD, lit);
        for (float s : new float[]{1.0F, -1.0F}) {
            Draw.box(c, p, s > 0 ? 0.1F : -0.44F, BallistaEntity.PIVOT_Y - 0.13F, BallistaEntity.PIVOT_Z - 0.13F,
                    s > 0 ? 0.44F : -0.1F, BallistaEntity.PIVOT_Y + 0.13F, BallistaEntity.PIVOT_Z + 0.13F, ROPE, lit);
        }

        // ---- winch with two spoked wheels
        pose.pushPose();
        pose.translate(0.0F, 0.62F, WINCH_Z);
        pose.mulPose(Axis.XP.rotation(e.winch(partial)));
        Draw.box(c, pose.last(), -0.44F, -0.09F, -0.09F, 0.44F, 0.09F, 0.09F, ROPE, lit);
        for (float s : new float[]{0.46F, -0.46F}) {
            Draw.box(c, pose.last(), s - 0.04F, -0.06F, -0.06F, s + 0.04F, 0.06F, 0.06F, GOLD, lit);
            for (int k = 0; k < 6; k++) {
                pose.pushPose();
                pose.mulPose(Axis.XP.rotation((float) (k * Math.PI / 3.0D)));
                Draw.box(c, pose.last(), s - 0.025F, 0.05F, -0.025F, s + 0.025F, 0.22F, 0.025F, k % 2 == 0 ? WOOD : GOLD_DARK, lit);
                pose.popPose();
            }
        }
        pose.popPose();

        // ---- rope from the winch to the arm (slack while it fires)
        if (st != BallistaEntity.FIRING) {
            Vec3 hook = BallistaEntity.armToLocal(arm, 0.0D, 1.0D, -0.09D);
            beam(c, pose, 0.0F, 0.71F, WINCH_Z, (float) hook.x, (float) hook.y, (float) hook.z, 0.035F, ROPE, lit);
        }

        // ---- throwing arm and bucket (arm space: +Y along the arm, +Z = the bucket's open side)
        pose.pushPose();
        pose.translate(0.0F, BallistaEntity.PIVOT_Y, BallistaEntity.PIVOT_Z);
        pose.mulPose(Axis.XP.rotation(arm));
        PoseStack.Pose a = pose.last();
        Draw.box(c, a, -0.085F, -0.22F, -0.085F, 0.085F, L - 0.4F, 0.085F, WOOD, lit);
        Draw.box(c, a, -0.1F, 0.4F, -0.1F, 0.1F, 0.5F, 0.1F, GOLD, lit);
        Draw.box(c, a, -0.1F, 0.95F, -0.1F, 0.1F, 1.05F, 0.1F, GOLD, lit);
        // bucket: floor, striped walls, gold rim
        Draw.box(c, a, -0.38F, L - 0.47F, 0.06F, 0.38F, L + 0.12F, 0.14F, GOLD_DARK, lit);
        Draw.box(c, a, 0.31F, L - 0.47F, 0.14F, 0.38F, L + 0.12F, 0.46F, RED, lit);
        Draw.box(c, a, -0.38F, L - 0.47F, 0.14F, -0.31F, L + 0.12F, 0.46F, RED, lit);
        Draw.box(c, a, -0.31F, L - 0.47F, 0.14F, 0.31F, L - 0.4F, 0.46F, BLACK, lit);
        Draw.box(c, a, -0.31F, L + 0.05F, 0.14F, 0.31F, L + 0.12F, 0.46F, BLACK, lit);
        Draw.box(c, a, -0.39F, L - 0.48F, 0.46F, 0.39F, L - 0.4F, 0.5F, GOLD, lit);
        Draw.box(c, a, -0.39F, L + 0.04F, 0.46F, 0.39F, L + 0.13F, 0.5F, GOLD, lit);
        pose.popPose();

        // ---- bulbs: gold sockets, coloured glass, full-bright when lit
        for (int i = 0; i < FRAME_BULBS.length; i++) {
            float[] b = FRAME_BULBS[i];
            Draw.box(c, p, b[0] - 0.05F, b[1] - 0.05F, b[2] - 0.05F, b[0] + 0.05F, b[1] + 0.01F, b[2] + 0.05F, GOLD_DARK, lit);
            Draw.cube(c, p, b[0], b[1] + 0.06F, b[2], 0.05F, on[i] ? col[i] : Draw.shade(col[i], 0.3F), on[i] ? FULL : lit);
        }
        pose.pushPose();
        pose.translate(0.0F, BallistaEntity.PIVOT_Y, BallistaEntity.PIVOT_Z);
        pose.mulPose(Axis.XP.rotation(arm));
        for (int i = 0; i < BUCKET_BULBS.length; i++) {
            float[] b = BUCKET_BULBS[i];
            int k = FRAME_BULBS.length + i;
            Draw.cube(c, pose.last(), b[0], b[1], b[2] + 0.04F, 0.05F, on[k] ? col[k] : Draw.shade(col[k], 0.3F), on[k] ? FULL : lit);
        }
        pose.popPose();

        // ---- second batch: spiral faces on the wheels (the Wheel of Wonder's own texture)
        VertexConsumer face = buffers.getBuffer(RenderType.entityCutoutNoCull(WheelRenderer.FACE));
        for (float[] w : WHEELS) {
            pose.pushPose();
            pose.translate(w[0], WHEEL_R, w[1]);
            pose.mulPose(Axis.YP.rotationDegrees(90.0F));
            float f = WHEEL_R * 0.82F;
            Draw.quadZ(face, pose.last(), -f, -f, f, f, WHEEL_T * 0.5F, true, 0xFFFFFFFF, 0, 0, 1, 1, lit);
            Draw.quadZ(face, pose.last(), -f, -f, f, f, -WHEEL_T * 0.5F, false, 0xFFFFFFFF, 0, 0, 1, 1, lit);
            pose.popPose();
        }
        pose.popPose();

        // ---- third batch: additive glow halos, facing the camera
        VertexConsumer glow = buffers.getBuffer(ShaderCompat.glow(WheelRenderer.GLOW));
        Quaternionf yaw = new Quaternionf().rotationY((float) Math.toRadians(-yawDeg));
        Quaternionf cam = this.entityRenderDispatcher.cameraOrientation();
        for (int i = 0; i < on.length; i++) {
            if (!on[i]) continue;
            Vec3 lp;
            if (i < FRAME_BULBS.length) {
                float[] b = FRAME_BULBS[i];
                lp = new Vec3(b[0], b[1] + 0.06F, b[2]);
            } else {
                float[] b = BUCKET_BULBS[i - FRAME_BULBS.length];
                lp = BallistaEntity.armToLocal(arm, b[0], b[1], b[2] + 0.04F);
            }
            float fl = 0.85F + 0.15F * Mth.sin(time * 0.9F + i * 1.7F);
            halo(pose, glow, cam, yaw, lp, 0.26F * fl, Draw.shade(col[i], 0.7F * fl));
        }
        // the jack-o'-lantern glows orange
        halo(pose, glow, cam, yaw, new Vec3(0.0D, 1.68D, 0.6D), 0.45F * flick, Draw.shade(0xFFFF8A1F, 0.55F * flick));
        // loaded: a pulsing violet/orange aura round the bucket that grows towards the shot
        if (st == BallistaEntity.LOADED || (st == BallistaEntity.FIRING && e.age() < 8)) {
            float pulse = 0.5F + 0.5F * Mth.sin(time * 0.6F);
            float grow = st == BallistaEntity.FIRING ? 1.6F : Math.min(1.0F, (e.age() + partial) / 40.0F);
            int aura = ((int) (time / 6)) % 2 == 0 ? 0xFF9B30FF : 0xFFFF7A10;
            Vec3 lp = BallistaEntity.armToLocal(arm, 0.0D, BallistaEntity.SEAT_Y, 0.4D);
            halo(pose, glow, cam, yaw, lp, 0.8F + 0.7F * grow + 0.2F * pulse, Draw.shade(aura, 0.18F + 0.14F * pulse));
        }
        super.render(e, entityYaw, partial, pose, buffers, light);
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
    private static void beam(VertexConsumer c, PoseStack pose, float ax, float ay, float az, float bx, float by, float bz, float w, int col, int light) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-4F) return;
        pose.pushPose();
        pose.translate(ax, ay, az);
        pose.mulPose(new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, dx / len, dy / len, dz / len));
        Draw.box(c, pose.last(), -w * 0.5F, 0.0F, -w * 0.5F, w * 0.5F, len, w * 0.5F, col, light);
        pose.popPose();
    }

    /**
     * Idle: slow chasing lights. Loaded: faster and faster, bucket bulbs blinking red as the shot nears.
     * Firing: everything flashes bright for a moment.
     */
    private static void bulbPattern(int st, float age, float time, boolean[] on, int[] col) {
        int frame = BallistaRenderer.FRAME_BULBS.length;
        if (st == BallistaEntity.FIRING && age < 12) {
            for (int i = 0; i < on.length; i++) {
                on[i] = ((int) age / 2 + i) % 2 == 0 || age < 4;
                col[i] = age < 4 ? 0xFFFFF4D0 : BULBS[i % BULBS.length];
            }
            return;
        }
        float speed = st == BallistaEntity.LOADED ? Math.max(1.0F, 4.0F - age / 12.0F) : 4.0F;
        int step = (int) (time / speed);
        for (int i = 0; i < on.length; i++) {
            // the two rails chase from the back to the front, towards where the shot goes
            int pos = i < frame - 2 ? i / 2 : i;
            on[i] = (pos - step % 3 + 300) % 3 != 0;
            col[i] = BULBS[(pos + step / 6) % BULBS.length];
        }
        if (st == BallistaEntity.LOADED) {
            int period = Math.max(2, 10 - (int) (age / 5));
            boolean blink = ((int) (time / period)) % 2 == 0;
            for (int i = frame; i < on.length; i++) {
                on[i] = blink;
                col[i] = 0xFFFF2A1A;
            }
        }
    }
}

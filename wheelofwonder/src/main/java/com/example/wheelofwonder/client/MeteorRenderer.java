package com.example.wheelofwonder.client;

import com.example.wheelofwonder.MeteorEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Chaos Meteor: a craggy rock built from a twice-subdivided icosahedron pushed in and out by noise, dark
 * charred stone with glowing lava cracks (full bright), wrapped in a flickering fire aura. While it falls it drags
 * a long comet tail and a pulsing ring of fire marks the landing spot on the ground.
 */
public class MeteorRenderer extends EntityRenderer<MeteorEntity> {
    private static final int ROCK = 0xFF3B3236, ROCK_DARK = 0xFF2A2327, CRACK = 0xFFFF6A14, CRACK_DIM = 0xFFB0300E;
    private static final int FULL = 0xF000F0;
    /** triangles: 3 vertices (unit-ish, already displaced) + colour + glowing flag */
    private static final List<float[]> TRIS = buildRock();

    public MeteorRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 1.2F;
    }

    @Override
    public ResourceLocation getTextureLocation(MeteorEntity e) {
        return WheelRenderer.WHITE;
    }

    @Override
    public boolean shouldRender(MeteorEntity e, net.minecraft.client.renderer.culling.Frustum f, double x, double y, double z) {
        return true;
    }

    @Override
    public void render(MeteorEntity e, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        float time = e.tickCount + partial;
        float r = MeteorEntity.RADIUS;
        // ---- the rock
        pose.pushPose();
        pose.translate(0.0F, r, 0.0F);
        float spin = e.spinO + (e.spin - e.spinO) * partial;
        Vec3 ax = e.spinAxis;
        pose.mulPose(new Quaternionf().rotationAxis(spin, (float) ax.x, (float) ax.y, (float) ax.z));
        pose.mulPose(Axis.YP.rotationDegrees((e.getId() * 73) % 360));
        pose.scale(r, r, r);
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(WheelRenderer.WHITE));
        int lit = LightTexture.pack(Math.max(LightTexture.block(light), 11), LightTexture.sky(light));
        PoseStack.Pose p = pose.last();
        float pulse = 0.75F + 0.25F * (float) Math.sin(time * 0.45F);
        for (float[] t : TRIS) {
            boolean hot = t[10] > 0.5F;
            int col = hot ? lerp(CRACK_DIM, CRACK, pulse) : (int) t[9];
            int l = hot ? FULL : lit;
            Vector3f n = new Vector3f(t[3] - t[0], t[4] - t[1], t[5] - t[2]).cross(t[6] - t[0], t[7] - t[1], t[8] - t[2]).normalize();
            Draw.v(c, p, t[0], t[1], t[2], col, 0.5F, 0.5F, l, n.x, n.y, n.z);
            Draw.v(c, p, t[3], t[4], t[5], col, 0.5F, 0.5F, l, n.x, n.y, n.z);
            Draw.v(c, p, t[6], t[7], t[8], col, 0.5F, 0.5F, l, n.x, n.y, n.z);
            Draw.v(c, p, t[6], t[7], t[8], col, 0.5F, 0.5F, l, n.x, n.y, n.z);
        }
        pose.popPose();

        // ---- fire aura: an orange core and a wide red-violet haze, flickering
        VertexConsumer glow = buffers.getBuffer(ShaderCompat.glow(WheelRenderer.GLOW));
        pose.pushPose();
        pose.translate(0.0F, r, 0.0F);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        float fl = 0.85F + 0.15F * (float) Math.sin(time * 1.7F) * (float) Math.cos(time * 0.9F);
        Draw.halo(glow, pose.last(), r * 2.4F * fl, Draw.shade(0xFFFF5010, 0.7F));
        Draw.halo(glow, pose.last(), r * 3.6F, Draw.shade(0xFFB0281A, 0.35F));
        Draw.halo(glow, pose.last(), r * 1.4F, Draw.shade(0xFFFFC060, 0.5F * fl));
        pose.popPose();

        // ---- comet tail (world space, behind the rock)
        drawTail(e, pose, buffers, partial, time);
        // ---- landing marker while it falls
        if (e.phase() == MeteorEntity.FALL) drawMarker(e, pose, buffers, partial, time);
        super.render(e, yaw, partial, pose, buffers, light);
    }

    private void drawTail(MeteorEntity e, PoseStack pose, MultiBufferSource buffers, float partial, float time) {
        if (e.trail.size() < 2) return;
        Vec3 base = e.getPosition(partial);
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        List<Vec3> pts = new ArrayList<>(e.trail);
        pts.set(pts.size() - 1, base.add(0, MeteorEntity.RADIUS, 0));
        boolean shaders = ShaderCompat.shadersOn();
        RenderType rt = shaders ? RenderType.entityTranslucent(WheelRenderer.WHITE) : RenderType.lightning();
        VertexConsumer vc = buffers.getBuffer(rt);
        pose.pushPose();
        pose.translate(-base.x, -base.y, -base.z);
        float w = e.phase() == MeteorEntity.FALL ? 1.5F : 0.9F;
        strip(vc, pose.last(), pts, cam, w * 1.2F, 0xB0281A, 0.5F, shaders);
        strip(vc, pose.last(), pts, cam, w * 0.7F, 0xFF6A14, 0.8F, shaders);
        strip(vc, pose.last(), pts, cam, w * 0.25F, 0xFFE0A0, 0.9F, shaders);
        pose.popPose();
    }

    /** A pulsing ring of fire on the ground where it will land, with a shrinking inner ring counting down. */
    private void drawMarker(MeteorEntity e, PoseStack pose, MultiBufferSource buffers, float partial, float time) {
        Vec3 base = e.getPosition(partial), t = e.target();
        boolean shaders = ShaderCompat.shadersOn();
        RenderType rt = shaders ? RenderType.entityTranslucent(WheelRenderer.WHITE) : RenderType.lightning();
        VertexConsumer vc = buffers.getBuffer(rt);
        pose.pushPose();
        pose.translate(t.x - base.x, t.y - base.y + 0.06D, t.z - base.z);
        float pulse = 0.7F + 0.3F * (float) Math.sin(time * 0.8F);
        float progress = Math.max(0.0F, Math.min(1.0F, (time - MeteorEntity.HOVER_TICKS) / (float) MeteorEntity.FALL_TICKS));
        ring(vc, pose.last(), 3.3F, 3.6F, 0xFF5A14, 0.8F * pulse, shaders);
        ring(vc, pose.last(), 3.6F, 4.1F, 0xB0281A, 0.35F * pulse, shaders);
        float inner = 3.3F * (1.0F - progress);
        ring(vc, pose.last(), Math.max(0, inner - 0.25F), inner, 0xFFB040, 0.7F, shaders);
        disc(vc, pose.last(), 3.3F, 0xFF3A0A, 0.18F * pulse, shaders);
        pose.popPose();
    }

    // ------------------------------------------------------------------ geometry helpers

    private static void ring(VertexConsumer vc, PoseStack.Pose p, float r0, float r1, int rgb, float a, boolean shaders) {
        int n = 48;
        float r = ((rgb >> 16) & 255) / 255F, g = ((rgb >> 8) & 255) / 255F, b = (rgb & 255) / 255F;
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float rm = (r0 + r1) / 2;
            v(vc, p, c0 * r0, 0, s0 * r0, r, g, b, 0, shaders); v(vc, p, c0 * rm, 0, s0 * rm, r, g, b, a, shaders);
            v(vc, p, c1 * rm, 0, s1 * rm, r, g, b, a, shaders); v(vc, p, c1 * r0, 0, s1 * r0, r, g, b, 0, shaders);
            v(vc, p, c0 * rm, 0, s0 * rm, r, g, b, a, shaders); v(vc, p, c0 * r1, 0, s0 * r1, r, g, b, 0, shaders);
            v(vc, p, c1 * r1, 0, s1 * r1, r, g, b, 0, shaders); v(vc, p, c1 * rm, 0, s1 * rm, r, g, b, a, shaders);
        }
    }

    private static void disc(VertexConsumer vc, PoseStack.Pose p, float rad, int rgb, float a, boolean shaders) {
        int n = 32;
        float r = ((rgb >> 16) & 255) / 255F, g = ((rgb >> 8) & 255) / 255F, b = (rgb & 255) / 255F;
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
            v(vc, p, 0, 0, 0, r, g, b, a, shaders); v(vc, p, 0, 0, 0, r, g, b, a, shaders);
            v(vc, p, (float) Math.cos(a1) * rad, 0, (float) Math.sin(a1) * rad, r, g, b, 0, shaders);
            v(vc, p, (float) Math.cos(a0) * rad, 0, (float) Math.sin(a0) * rad, r, g, b, 0, shaders);
        }
    }

    private static void strip(VertexConsumer vc, PoseStack.Pose pose, List<Vec3> pts, Vec3 cam, float width, int rgb, float alpha, boolean shaders) {
        int n = pts.size();
        float r = ((rgb >> 16) & 255) / 255F, g = ((rgb >> 8) & 255) / 255F, b = (rgb & 255) / 255F;
        Vec3[] side = new Vec3[n];
        for (int i = 0; i < n; i++) {
            Vec3 d = pts.get(Math.min(n - 1, i + 1)).subtract(pts.get(Math.max(0, i - 1)));
            Vec3 s = cam.subtract(pts.get(i)).cross(d);
            side[i] = s.lengthSqr() < 1.0E-9D ? new Vec3(0, 1, 0) : s.normalize();
        }
        for (int i = 0; i < n - 1; i++) {
            float u0 = i / (float) (n - 1), u1 = (i + 1) / (float) (n - 1);
            Vec3 p0 = pts.get(i), p1 = pts.get(i + 1);
            Vec3 e0 = side[i].scale(width * (0.2F + 0.8F * u0)), e1 = side[i + 1].scale(width * (0.2F + 0.8F * u1));
            float a0 = alpha * u0 * u0, a1 = alpha * u1 * u1;
            vv(vc, pose, p0.add(e0), r, g, b, 0, shaders); vv(vc, pose, p0, r, g, b, a0, shaders);
            vv(vc, pose, p1, r, g, b, a1, shaders); vv(vc, pose, p1.add(e1), r, g, b, 0, shaders);
            vv(vc, pose, p0, r, g, b, a0, shaders); vv(vc, pose, p0.subtract(e0), r, g, b, 0, shaders);
            vv(vc, pose, p1.subtract(e1), r, g, b, 0, shaders); vv(vc, pose, p1, r, g, b, a1, shaders);
        }
    }

    private static void vv(VertexConsumer vc, PoseStack.Pose p, Vec3 q, float r, float g, float b, float a, boolean shaders) {
        v(vc, p, (float) q.x, (float) q.y, (float) q.z, r, g, b, a, shaders);
    }

    private static void v(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, float r, float g, float b, float a, boolean shaders) {
        VertexConsumer c = vc.addVertex(p, x, y, z).setColor(r, g, b, a);
        if (shaders) c.setUv(0.5F, 0.5F).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(FULL).setNormal(p, 0, 1, 0);
    }

    private static int lerp(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // ------------------------------------------------------------------ the rock mesh

    /** Icosahedron subdivided twice (320 faces), each vertex pushed in/out by smooth noise; cracks where noise is low. */
    private static List<float[]> buildRock() {
        double t = (1 + Math.sqrt(5)) / 2;
        List<double[]> v = new ArrayList<>();
        double[][] base = {{-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0}, {0, -1, t}, {0, 1, t}, {0, -1, -t}, {0, 1, -t},
                {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1}};
        for (double[] b : base) v.add(norm(b));
        int[][] f = {{0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
                {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9}, {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
        List<int[]> faces = new ArrayList<>(List.of(f));
        for (int s = 0; s < 2; s++) {
            Map<Long, Integer> mid = new HashMap<>();
            List<int[]> nf = new ArrayList<>();
            for (int[] tr : faces) {
                int a = midpoint(v, mid, tr[0], tr[1]), b = midpoint(v, mid, tr[1], tr[2]), c = midpoint(v, mid, tr[2], tr[0]);
                nf.add(new int[]{tr[0], a, c}); nf.add(new int[]{tr[1], b, a}); nf.add(new int[]{tr[2], c, b}); nf.add(new int[]{a, b, c});
            }
            faces = nf;
        }
        // craggy surface: radius from a few octaves of value noise on the sphere
        double[] rad = new double[v.size()];
        for (int i = 0; i < v.size(); i++) {
            double[] p = v.get(i);
            rad[i] = 1.0 + 0.16 * noise(p[0] * 1.7, p[1] * 1.7, p[2] * 1.7) + 0.07 * noise(p[0] * 4.1 + 9, p[1] * 4.1, p[2] * 4.1);
        }
        List<float[]> out = new ArrayList<>();
        for (int[] tr : faces) {
            double[] a = v.get(tr[0]), b = v.get(tr[1]), c = v.get(tr[2]);
            double cx = (a[0] + b[0] + c[0]) / 3, cy = (a[1] + b[1] + c[1]) / 3, cz = (a[2] + b[2] + c[2]) / 3;
            double crack = noise(cx * 3.3 + 31, cy * 3.3, cz * 3.3 + 7);
            // veins where the noise crosses zero, plus the deepest hollows
            boolean hot = Math.abs(crack) < 0.045 || (rad[tr[0]] + rad[tr[1]] + rad[tr[2]]) / 3 < 0.95;
            int col = ((tr[0] * 7 + tr[1] * 13) % 3 == 0) ? ROCK_DARK : ROCK;
            float[] q = new float[11];
            int k = 0;
            for (int idx : tr) {
                double[] p = v.get(idx);
                q[k++] = (float) (p[0] * rad[idx]); q[k++] = (float) (p[1] * rad[idx]); q[k++] = (float) (p[2] * rad[idx]);
            }
            q[9] = col;
            q[10] = hot ? 1 : 0;
            out.add(q);
        }
        return out;
    }

    private static int midpoint(List<double[]> v, Map<Long, Integer> mid, int a, int b) {
        long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
        Integer m = mid.get(key);
        if (m != null) return m;
        double[] p = v.get(a), q = v.get(b);
        v.add(norm(new double[]{(p[0] + q[0]) / 2, (p[1] + q[1]) / 2, (p[2] + q[2]) / 2}));
        mid.put(key, v.size() - 1);
        return v.size() - 1;
    }

    private static double[] norm(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }

    /** Smooth 3D value noise in -0.5..0.5. */
    private static double noise(double x, double y, double z) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double xf = x - xi, yf = y - yi, zf = z - zi;
        double u = xf * xf * (3 - 2 * xf), w = yf * yf * (3 - 2 * yf), s = zf * zf * (3 - 2 * zf);
        double r = 0;
        for (int dx = 0; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = 0; dz <= 1; dz++) {
            double h = hash(xi + dx, yi + dy, zi + dz);
            r += h * (dx == 1 ? u : 1 - u) * (dy == 1 ? w : 1 - w) * (dz == 1 ? s : 1 - s);
        }
        return r - 0.5;
    }

    private static double hash(int x, int y, int z) {
        long h = x * 374761393L + y * 668265263L + z * 2147483647L;
        h = (h ^ (h >> 13)) * 1274126177L;
        return ((h ^ (h >> 16)) & 0xFFFF) / 65535.0;
    }
}

package com.example.wheelofwonder.client;

import com.example.wheelofwonder.PrismCoasterEntity;
import com.example.wheelofwonder.PrismSync;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;

import static com.example.wheelofwonder.PrismCoasterEntity.*;

/**
 * The Prism Splitter: a dark violet station with gold corners and glowing seams, two crystal towers, neon rails on
 * stone ties and gold-banded pillars, a see-through rainbow bridge under the ramp, three glowing rings over it, the
 * rails ending in jagged crystals in mid-air, two giant crystals floating overhead. The cart: 24 mana crystals with a
 * gold rim; while it races, ghostly copies of it follow; when it bursts, the loose crystals (from the server) with
 * their energy beams. Three passes: solid, see-through, glow.
 */
public class PrismCoasterRenderer extends EntityRenderer<PrismCoasterEntity> {
    private static final int DEEP = 0x22103F, STONE = 0x3B2466, STONE2 = 0x4E2F85, GOLD = 0xE8A93A, NEON = 0xC266FF, NEON_DIM = 0x3E2470;
    private static final int PINK = 0xFF5FD2, CYAN = 0x5FD8FF, VIOLET = 0x9B4DFF;
    private static final int FULL = LightTexture.FULL_BRIGHT;
    private static final double[] PILLARS = pillars();
    private static final Vec3[] FLOATERS = {new Vec3(11.0D, 11.0D, -10.0D), new Vec3(30.0D, 12.0D, -6.0D)};

    private static double[] pillars() {
        int n = (int) ((L - 5.5D) / 3.0D) + 1;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = 5.5D + i * 3.0D;
        return out;
    }
    private static final Vec3[] TOWERS = {new Vec3(2.0D, 0.0D, -2.7D), new Vec3(2.0D, 0.0D, 2.9D)};
    private static final float TOWER_H = 5.6F;

    public PrismCoasterRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    /** PrismSync arrived: hand the crystals to their coaster. */
    public static void onSync(PrismSync p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getEntity(p.entityId()) instanceof PrismCoasterEntity e) e.onSync(p.state(), p.data());
    }

    @Override
    public ResourceLocation getTextureLocation(PrismCoasterEntity e) {
        return WheelRenderer.WHITE;
    }

    @Override
    public boolean shouldRender(PrismCoasterEntity e, net.minecraft.client.renderer.culling.Frustum f, double x, double y, double z) {
        return true;
    }

    @Override
    public void render(PrismCoasterEntity e, float entityYaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        float time = e.tickCount + partial;
        Vec3 base = e.getPosition(partial);
        int lit = LightTexture.pack(Math.max(LightTexture.block(light), 8), LightTexture.sky(light));
        int st = e.viewState();
        double age = e.age() + partial;
        float charge = e.charge(partial);
        float[] free = e.freeCrystals(partial);
        double cs = e.cartS(partial);
        if (e.groundCache == null) e.groundCache = grounds(e);

        // ---- pass 1: solid
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(WheelRenderer.WHITE));
        pose.pushPose();
        pose.mulPose(Axis.YP.rotation((float) -Math.toRadians(e.getYRot())));
        drawStation(c, pose.last(), lit, time);
        drawDecor(e, c, pose, lit, time);
        pose.popPose();
        drawTrack(e, c, pose, base, lit, partial, time);
        if (free == null) drawCart(e, c, pose, base, cs, charge, 255, true);
        else drawFree(c, pose, base, free, charge, 255);

        // ---- pass 2: see-through
        VertexConsumer tr = buffers.getBuffer(RenderType.beaconBeam(WheelRenderer.WHITE, true));
        drawBridge(e, tr, pose, base, time);
        drawRailGlow(e, tr, pose, base, partial, time);
        if (st == RIDE) {
            // ghostly copies racing behind the cart
            for (int k = 1; k <= 3; k++) {
                double s = cartSAt(age - k * 1.6D);
                if (cs - s > 0.3D) drawCart(e, tr, pose, base, s, 1.0F, (int) (110 / k), false);
            }
        }
        if (st == CHARGE) drawAura(e, tr, pose, base, cs, (float) age, charge);
        if (free != null) drawBeams(tr, pose, base, free);

        // ---- pass 3: glows
        boolean shaders = ShaderCompat.shadersOn();
        RenderType gt = shaders ? RenderType.beaconBeam(ShaderCompat.GLOW_SOFT, true) : ShaderCompat.glow(WheelRenderer.GLOW);
        VertexConsumer glow = buffers.getBuffer(gt);
        Quaternionf cam = this.entityRenderDispatcher.cameraOrientation();
        float fl = 0.85F + 0.15F * Mth.sin(time * 0.6F);
        for (int i = 0; i < FLOATERS.length; i++) {
            halo(pose, glow, cam, e.toWorld(floater(i, time)).subtract(base), 3.0F * fl, i == 0 ? PINK : CYAN, 0.35F);
        }
        for (Vec3 t : TOWERS) halo(pose, glow, cam, e.toWorld(t.add(0.0D, TOWER_H + 0.9D, 0.0D)).subtract(base), 1.4F * fl, VIOLET, 0.5F);
        for (double rs : RINGS) {
            Vec3[] f = e.frame(rs);
            Vec3 ctr = f[0].add(f[3].scale(0.9D));
            for (int k = 0; k < 6; k++) {
                double a = k * Math.PI / 3.0D + time * 0.05D;
                Vec3 q = ctr.add(f[2].scale(Math.cos(a) * 1.7D)).add(f[3].scale(Math.sin(a) * 1.7D));
                halo(pose, glow, cam, q.subtract(base), 0.5F * fl, PINK, 0.45F);
            }
        }
        for (double s = 0.0D; s <= L; s += 2.0D) {
            float l = e.railLit(s, partial);
            if (l > 0.3F) halo(pose, glow, cam, e.frame(s)[0].subtract(base), 0.5F, NEON, 0.35F * l);
        }
        if (free == null) {
            for (int i = 0; i < N; i += 2) {
                Vec3 mid = e.slotWorld(i, cs).add(e.slotAxis(i, cs).scale(SLOT_LEN[i] * 0.5D));
                halo(pose, glow, cam, mid.subtract(base), SLOT_RAD[i] * 4.0F, pieceTip(i), 0.3F * charge);
            }
        } else {
            for (int i = 0; i < N; i++) {
                int o = i * FLOATS;
                Vec3 q = new Vec3(free[o], free[o + 1], free[o + 2]);
                halo(pose, glow, cam, q.subtract(base), 0.5F, pieceTip(i), 0.3F * charge);
                float b = free[o + 6];
                if (b > 0.02F) {
                    int col = PALETTE[Mth.clamp((int) free[o + 10], 0, PALETTE.length - 1)];
                    halo(pose, glow, cam, new Vec3(free[o + 7], free[o + 8], free[o + 9]).subtract(base), 1.4F * b, col, 0.9F * b);
                    halo(pose, glow, cam, q.subtract(base), 0.8F * b, col, 0.8F * b);
                }
            }
        }
        if (st == FLIGHT && age < 10.0D) {
            float f = (float) (1.0D - age / 10.0D);
            halo(pose, glow, cam, e.frame(L)[0].add(0.0D, 0.6D, 0.0D).subtract(base), 6.0F * (0.4F + 0.6F * f), 0xFFE6FF, 0.9F * f);
        }
        if (st == CHARGE) halo(pose, glow, cam, e.frame(cs)[0].add(0.0D, 0.8D, 0.0D).subtract(base), 2.2F * charge * fl, VIOLET, 0.4F * charge);
        super.render(e, entityYaw, partial, pose, buffers, light);
    }

    // ------------------------------------------------------------------ helpers

    private static void halo(PoseStack pose, VertexConsumer glow, Quaternionf cam, Vec3 rel, float size, int rgb, float strength) {
        if (strength <= 0.01F || size <= 0.01F) return;
        pose.pushPose();
        pose.translate(rel.x, rel.y, rel.z);
        pose.mulPose(cam);
        Draw.halo(glow, pose.last(), size, Draw.shade(0xFF000000 | rgb, Math.min(1.0F, strength)));
        pose.popPose();
    }

    private static void beam(VertexConsumer c, PoseStack pose, Vec3 a, Vec3 b, float w, int argb, int light) {
        RocketRideRenderer.beam(c, pose, (float) a.x, (float) a.y, (float) a.z, (float) b.x, (float) b.y, (float) b.z, w, argb, light);
    }

    private static int mix(int a, int b, float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int hue(double h) {
        return Mth.hsvToRgb((float) (h - Math.floor(h)), 0.65F, 1.0F) & 0xFFFFFF;
    }

    private static float hash(int i, int k) {
        double x = Math.sin(i * 12.9898D + k * 78.233D) * 43758.5453D;
        return (float) (x - Math.floor(x));
    }

    private static int pieceBase(int i) {
        return SLOT_KIND[i] == 1 ? STONE2 : mix(VIOLET, PINK, hash(i, 1));
    }

    private static int pieceTip(int i) {
        return SLOT_KIND[i] == 1 ? GOLD : mix(PINK, CYAN, hash(i, 2));
    }

    private double[] grounds(PrismCoasterEntity e) {
        Level level = e.level();
        double[] g = new double[PILLARS.length];
        for (int i = 0; i < PILLARS.length; i++) {
            Vec3 q = e.frame(PILLARS[i])[0];
            g[i] = groundBelow(level, q.x, q.y - 0.5D, q.z, e.getY());
        }
        return g;
    }

    private static double groundBelow(Level level, double x, double fromY, double z, double fallback) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int by = Mth.floor(fromY); by >= Mth.floor(fromY) - 24; by--) {
            p.set(Mth.floor(x), by, Mth.floor(z));
            VoxelShape s = level.getBlockState(p).getCollisionShape(level, p);
            if (!s.isEmpty()) return by + s.max(Direction.Axis.Y);
        }
        return fallback;
    }

    /** Double-sided quad, one colour. */
    private static void quad(VertexConsumer c, PoseStack.Pose p, Vec3 a, Vec3 b, Vec3 cc, Vec3 d, int argb) {
        Vec3[] q = {a, b, cc, d, d, cc, b, a};
        for (Vec3 v : q) {
            c.addVertex(p, (float) v.x, (float) v.y, (float) v.z).setColor(argb).setUv(0.5F, 0.5F)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL).setNormal(p, 0.0F, 1.0F, 0.0F);
        }
    }

    private static void vtx(VertexConsumer c, PoseStack.Pose p, Vec3 v, int argb, int light, Vec3 n) {
        c.addVertex(p, (float) v.x, (float) v.y, (float) v.z).setColor(argb).setUv(0.5F, 0.5F)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, (float) n.x, (float) n.y, (float) n.z);
    }

    /**
     * A mana crystal: a six-sided prism with a point at each end, from {@code b} along {@code ax}; colA at the foot,
     * colB at the point (rgb), alpha 0..255. Both windings when see-through (the pass culls).
     */
    private static void crystal(VertexConsumer c, PoseStack.Pose p, Vec3 b, Vec3 ax, double len, double rad, int colA, int colB, int alpha, boolean both) {
        Vec3 d = ax.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 1.0D, 0.0D) : ax.normalize();
        Vec3 e1 = d.cross(Math.abs(d.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        Vec3 e2 = d.cross(e1).normalize();
        int sides = 6;
        Vec3 bot = b, top = b.add(d.scale(len));
        Vec3 c1 = b.add(d.scale(len * 0.18D)), c2 = b.add(d.scale(len * 0.72D));
        Vec3[] r1 = new Vec3[sides + 1], r2 = new Vec3[sides + 1];
        for (int k = 0; k <= sides; k++) {
            double a = k * Math.PI * 2.0D / sides;
            Vec3 n = e1.scale(Math.cos(a)).add(e2.scale(Math.sin(a)));
            r1[k] = c1.add(n.scale(rad));
            r2[k] = c2.add(n.scale(rad * 0.92D));
        }
        int aA = alpha << 24;
        for (int k = 0; k < sides; k++) {
            float sh = k % 2 == 0 ? 1.0F : 0.78F;
            int lo = aA | (Draw.shade(0xFF000000 | colA, sh) & 0xFFFFFF), hi = aA | (Draw.shade(0xFF000000 | colB, sh) & 0xFFFFFF);
            int mid = aA | (Draw.shade(0xFF000000 | mix(colA, colB, 0.5F), sh) & 0xFFFFFF);
            double a = (k + 0.5D) * Math.PI * 2.0D / sides;
            Vec3 n = e1.scale(Math.cos(a)).add(e2.scale(Math.sin(a)));
            face(c, p, bot, bot, r1[k + 1], r1[k], lo, lo, mid, mid, n, both);
            face(c, p, r1[k], r1[k + 1], r2[k + 1], r2[k], mid, mid, hi, hi, n, both);
            face(c, p, r2[k], r2[k + 1], top, top, hi, hi, 0xFFFFFF | aA, 0xFFFFFF | aA, n, both);
        }
    }

    private static void face(VertexConsumer c, PoseStack.Pose p, Vec3 a, Vec3 b, Vec3 cc, Vec3 d, int ca, int cb, int ccol, int cd, Vec3 n, boolean both) {
        vtx(c, p, a, ca, FULL, n);
        vtx(c, p, b, cb, FULL, n);
        vtx(c, p, cc, ccol, FULL, n);
        vtx(c, p, d, cd, FULL, n);
        if (both) {
            vtx(c, p, d, cd, FULL, n);
            vtx(c, p, cc, ccol, FULL, n);
            vtx(c, p, b, cb, FULL, n);
            vtx(c, p, a, ca, FULL, n);
        }
    }

    // ------------------------------------------------------------------ the station, towers, floating crystals (local space)

    private static void drawStation(VertexConsumer c, PoseStack.Pose p, int lit, float time) {
        float top = (float) STATION_Y - 0.28F;
        Draw.box(c, p, -1.8F, 0.0F, -1.5F, 3.2F, top - 0.1F, 1.5F, 0xFF000000 | STONE, lit);
        Draw.box(c, p, -1.7F, top - 0.1F, -1.4F, 3.1F, top, 1.4F, 0xFF000000 | STONE2, lit);
        // glowing seams round the top and a seam down the middle
        int seam = 0xFF000000 | mix(NEON, PINK, 0.5F + 0.5F * Mth.sin(time * 0.08F));
        Draw.box(c, p, -1.8F, top - 0.14F, 1.48F, 3.2F, top - 0.08F, 1.52F, seam, FULL);
        Draw.box(c, p, -1.8F, top - 0.14F, -1.52F, 3.2F, top - 0.08F, -1.48F, seam, FULL);
        Draw.box(c, p, 3.18F, top - 0.14F, -1.5F, 3.22F, top - 0.08F, 1.5F, seam, FULL);
        Draw.box(c, p, -1.82F, top - 0.14F, -1.5F, -1.78F, top - 0.08F, 1.5F, seam, FULL);
        // gold corner blocks with a little crystal on each
        for (float x : new float[]{-1.8F, 2.85F}) {
            for (float z : new float[]{-1.5F, 1.15F}) {
                Draw.box(c, p, x, 0.0F, z, x + 0.35F, top + 0.12F, z + 0.35F, 0xFF000000 | GOLD, lit);
                Draw.box(c, p, x + 0.1F, top + 0.12F, z + 0.1F, x + 0.25F, top + 0.45F, z + 0.25F, 0xFF000000 | PINK, FULL);
            }
        }
        // buffer stop behind the cart
        Draw.box(c, p, -1.55F, top, -0.65F, -1.3F, top + 0.55F, 0.65F, 0xFF000000 | GOLD, lit);
        Draw.box(c, p, -1.5F, top + 0.55F, -0.35F, -1.35F, top + 0.75F, 0.35F, 0xFF000000 | CYAN, FULL);
    }

    private static Vec3 floater(int i, float time) {
        return FLOATERS[i].add(0.0D, 0.35D * Math.sin(time * 0.04D + i * 2.0D), 0.0D);
    }

    private void drawDecor(PrismCoasterEntity e, VertexConsumer c, PoseStack pose, int lit, float time) {
        PoseStack.Pose p = pose.last();
        // the crystal towers
        for (int t = 0; t < TOWERS.length; t++) {
            Vec3 b = TOWERS[t];
            float x = (float) b.x, z = (float) b.z;
            Draw.box(c, p, x - 0.55F, 0.0F, z - 0.55F, x + 0.55F, 0.6F, z + 0.55F, 0xFF000000 | STONE, lit);
            for (float dx : new float[]{-0.55F, 0.35F}) for (float dz : new float[]{-0.55F, 0.35F}) {
                Draw.box(c, p, x + dx, 0.0F, z + dz, x + dx + 0.2F, 0.7F, z + dz + 0.2F, 0xFF000000 | GOLD, lit);
            }
            Draw.box(c, p, x - 0.28F, 0.6F, z - 0.28F, x + 0.28F, TOWER_H, z + 0.28F, 0xFF000000 | STONE2, lit);
            Draw.box(c, p, x - 0.3F, 0.6F, z - 0.06F, x + 0.3F, TOWER_H, z + 0.06F, 0xFF000000 | mix(NEON, CYAN, 0.5F + 0.5F * Mth.sin(time * 0.1F + t)), FULL);
            for (float y = 1.4F; y < TOWER_H; y += 1.1F) Draw.box(c, p, x - 0.34F, y, z - 0.34F, x + 0.34F, y + 0.14F, z + 0.34F, 0xFF000000 | GOLD, lit);
            Vec3 top = new Vec3(x, TOWER_H + 0.05D, z);
            crystal(c, p, top, new Vec3(0.0D, 1.0D, 0.0D), 1.7D, 0.33D, VIOLET, CYAN, 255, false);
            crystal(c, p, top.add(0.2D, 0.0D, 0.1D), new Vec3(0.5D, 1.0D, 0.2D), 0.9D, 0.18D, PINK, CYAN, 255, false);
            crystal(c, p, top.add(-0.2D, 0.0D, -0.1D), new Vec3(-0.5D, 1.0D, -0.3D), 1.0D, 0.18D, VIOLET, PINK, 255, false);
        }
        // the giant floating crystals, turning slowly, each with three small ones round it
        for (int i = 0; i < FLOATERS.length; i++) {
            Vec3 ctr = floater(i, time);
            double turn = time * 0.012D + i;
            Vec3 ax = new Vec3(Math.cos(turn) * 0.35D, 1.0D, Math.sin(turn) * 0.35D).normalize();
            double len = i == 0 ? 3.2D : 3.8D, rad = i == 0 ? 0.7D : 0.8D;
            crystal(c, p, ctr.subtract(ax.scale(len * 0.5D)), ax, len, rad, i == 0 ? VIOLET : PINK, i == 0 ? PINK : CYAN, 255, false);
            for (int k = 0; k < 3; k++) {
                double a = time * 0.03D + k * Math.PI * 2.0D / 3.0D + i;
                Vec3 q = ctr.add(Math.cos(a) * 2.4D, Math.sin(time * 0.05D + k) * 0.6D, Math.sin(a) * 2.4D);
                crystal(c, p, q, new Vec3(Math.cos(a + 1.0D), 1.0D, Math.sin(a + 1.0D)), 0.8D, 0.17D, PINK, CYAN, 255, false);
            }
        }
    }

    // ------------------------------------------------------------------ the track (world space)

    private void drawTrack(PrismCoasterEntity e, VertexConsumer c, PoseStack pose, Vec3 base, int lit, float partial, float time) {
        int stone = 0xFF000000 | STONE, stone2 = 0xFF000000 | STONE2, deep = 0xFF000000 | DEEP, gold = 0xFF000000 | GOLD;
        double step = 0.5D;
        boolean ride = e.viewState() == RIDE;
        double cart = e.cartS(partial);
        for (double s = 0.0D; s < L - 1.0E-6D; s += step) {
            double s1 = Math.min(L, s + step);
            Vec3[] f0 = e.frame(s), f1 = e.frame(s1);
            float l = e.railLit(s, partial);
            // a pulse of light running ahead of the racing cart
            if (ride && s > cart && s < cart + 3.0D) l = 1.0F;
            int rail = 0xFF000000 | mix(NEON_DIM, mix(NEON, PINK, 0.5F + 0.5F * Mth.sin((float) (time * 0.25F - s * 0.6D))), l);
            for (int sd = -1; sd <= 1; sd += 2) {
                Vec3 a = f0[0].add(f0[2].scale(sd * GAUGE)), b = f1[0].add(f1[2].scale(sd * GAUGE));
                beam(c, pose, a.subtract(base), b.subtract(base), 0.1F, rail, FULL);
                beam(c, pose, a.subtract(f0[3].scale(0.1D)).subtract(base), b.subtract(f1[3].scale(0.1D)).subtract(base), 0.15F, stone, lit);
            }
            beam(c, pose, f0[0].subtract(f0[3].scale(0.32D)).subtract(base), f1[0].subtract(f1[3].scale(0.32D)).subtract(base), 0.24F, deep, lit);
        }
        // ties with gold caps
        for (double s = 0.2D; s <= L; s += 0.75D) {
            Vec3[] f = e.frame(s);
            Vec3 a = f[0].subtract(f[2].scale(0.72D)).subtract(f[3].scale(0.17D)), b = f[0].add(f[2].scale(0.72D)).subtract(f[3].scale(0.17D));
            beam(c, pose, a.subtract(base), b.subtract(base), 0.14F, stone2, lit);
            Vec3 ra = a.subtract(base), rb = b.subtract(base);
            Draw.cube(c, pose.last(), (float) ra.x, (float) ra.y, (float) ra.z, 0.09F, gold, lit);
            Draw.cube(c, pose.last(), (float) rb.x, (float) rb.y, (float) rb.z, 0.09F, gold, lit);
        }
        // pillars down to the ground, gold bands, a small crystal at each top
        for (int i = 0; i < PILLARS.length; i++) {
            Vec3[] f = e.frame(PILLARS[i]);
            Vec3 top = f[0].subtract(0.0D, 0.4D, 0.0D);
            double g = e.groundCache[i];
            if (top.y - g < 0.3D) continue;
            Vec3 bot = new Vec3(top.x, g, top.z);
            beam(c, pose, bot.subtract(base), top.subtract(base), 0.38F, stone, lit);
            for (double y = g + 1.2D; y < top.y - 0.3D; y += 1.6D) {
                Vec3 q = new Vec3(top.x, y, top.z).subtract(base);
                Draw.box(c, pose.last(), (float) q.x - 0.24F, (float) q.y, (float) q.z - 0.24F, (float) q.x + 0.24F, (float) q.y + 0.14F, (float) q.z + 0.24F, gold, lit);
            }
            Vec3 pb = bot.subtract(base);
            Draw.box(c, pose.last(), (float) pb.x - 0.4F, (float) pb.y, (float) pb.z - 0.4F, (float) pb.x + 0.4F, (float) pb.y + 0.3F, (float) pb.z + 0.4F, stone2, lit);
            Vec3 side = top.add(f[2].scale(0.3D)).subtract(0.0D, 0.25D, 0.0D);
            crystal(c, pose.last(), side.subtract(base), f[2].add(0.0D, 1.0D, 0.0D), 0.55D, 0.12D, VIOLET, PINK, 255, false);
        }
        // the rails just end: jagged crystals jut out of their ends
        Vec3[] fe = e.frame(L);
        for (int sd = -1; sd <= 1; sd += 2) {
            Vec3 end = fe[0].add(fe[2].scale(sd * GAUGE));
            crystal(c, pose.last(), end.subtract(base), fe[1].add(fe[3].scale(0.6D)).add(fe[2].scale(sd * 0.3D)), 0.8D, 0.13D, NEON, PINK, 255, false);
            crystal(c, pose.last(), end.subtract(fe[3].scale(0.12D)).subtract(base), fe[1].subtract(fe[3].scale(0.3D)).add(fe[2].scale(sd * 0.5D)), 0.5D, 0.1D, VIOLET, CYAN, 255, false);
        }
        // the rings over the ramp: neon tubes with gold brackets
        for (double rs : RINGS) {
            Vec3[] f = e.frame(rs);
            Vec3 ctr = f[0].add(f[3].scale(0.9D));
            int n = 28;
            for (int k = 0; k < n; k++) {
                double a0 = k * Math.PI * 2.0D / n, a1 = (k + 1) * Math.PI * 2.0D / n;
                Vec3 q0 = ctr.add(f[2].scale(Math.cos(a0) * 1.7D)).add(f[3].scale(Math.sin(a0) * 1.7D));
                Vec3 q1 = ctr.add(f[2].scale(Math.cos(a1) * 1.7D)).add(f[3].scale(Math.sin(a1) * 1.7D));
                int col = 0xFF000000 | mix(PINK, VIOLET, 0.5F + 0.5F * Mth.sin((float) (time * 0.15F + k * 0.45F)));
                beam(c, pose, q0.subtract(base), q1.subtract(base), 0.17F, col, FULL);
                if (k % 4 == 0) {
                    Vec3 r = q0.subtract(base);
                    Draw.cube(c, pose.last(), (float) r.x, (float) r.y, (float) r.z, 0.13F, gold, lit);
                }
            }
        }
    }

    // ------------------------------------------------------------------ the cart

    /** The cart standing at s, whole: its 24 crystals and a gold rim. alpha < 255: a ghostly copy (see-through pass). */
    private void drawCart(PrismCoasterEntity e, VertexConsumer c, PoseStack pose, Vec3 base, double s, float charge, int alpha, boolean solid) {
        float bright = 0.55F + 0.45F * charge;
        for (int i = 0; i < N; i++) {
            Vec3 b = e.slotWorld(i, s).subtract(base);
            Vec3 ax = e.slotAxis(i, s);
            int ca = solid ? Draw.shade(0xFF000000 | pieceBase(i), bright) & 0xFFFFFF : (i % 2 == 0 ? CYAN : PINK);
            int cb = solid ? Draw.shade(0xFF000000 | pieceTip(i), bright) & 0xFFFFFF : 0xFFE6FF;
            crystal(c, pose.last(), b, ax, SLOT_LEN[i], SLOT_RAD[i], ca, cb, alpha, !solid);
        }
        if (!solid) return;
        Vec3[] f = e.frame(s);
        int gold = 0xFF000000 | GOLD;
        double[][] rim = {{-0.8D, -1.0D}, {0.8D, -1.0D}, {0.8D, 1.0D}, {-0.8D, 1.0D}};
        for (double y : new double[]{0.2D, 0.9D}) {
            for (int k = 0; k < 4; k++) {
                double[] a = rim[k], b = rim[(k + 1) % 4];
                Vec3 pa = f[0].add(f[2].scale(a[0])).add(f[3].scale(y)).add(f[1].scale(a[1]));
                Vec3 pb = f[0].add(f[2].scale(b[0])).add(f[3].scale(y)).add(f[1].scale(b[1]));
                beam(c, pose, pa.subtract(base), pb.subtract(base), y < 0.5D ? 0.12F : 0.08F, gold, FULL);
            }
        }
    }

    private void drawFree(VertexConsumer c, PoseStack pose, Vec3 base, float[] d, float charge, int alpha) {
        float bright = 0.55F + 0.45F * charge;
        for (int i = 0; i < N; i++) {
            int o = i * FLOATS;
            Vec3 b = new Vec3(d[o], d[o + 1], d[o + 2]).subtract(base);
            Vec3 ax = new Vec3(d[o + 3], d[o + 4], d[o + 5]);
            float boost = Math.min(1.0F, bright + d[o + 6]);
            int ca = Draw.shade(0xFF000000 | pieceBase(i), boost) & 0xFFFFFF, cb = Draw.shade(0xFF000000 | pieceTip(i), boost) & 0xFFFFFF;
            crystal(c, pose.last(), b, ax, SLOT_LEN[i], SLOT_RAD[i], ca, cb, alpha, false);
        }
    }

    // ------------------------------------------------------------------ see-through effects

    /** The rainbow bridge under the ramp. */
    private void drawBridge(PrismCoasterEntity e, VertexConsumer c, PoseStack pose, Vec3 base, float time) {
        PoseStack.Pose p = pose.last();
        double step = 0.5D;
        for (double s = RAMP_FROM - 2.0D; s < L - 1.0E-6D; s += step) {
            double s1 = Math.min(L, s + step);
            Vec3[] f0 = e.frame(s), f1 = e.frame(s1);
            Vec3 d0 = f0[3].scale(-0.42D), d1 = f1[3].scale(-0.42D);
            float fade = (float) Mth.clamp((s - RAMP_FROM + 2.0D) / 2.0D, 0.0D, 1.0D);
            int argb = ((int) (95 * fade) << 24) | hue(s * 0.06D + time * 0.004D);
            quad(c, p, f0[0].add(d0).subtract(f0[2].scale(0.95D)).subtract(base), f0[0].add(d0).add(f0[2].scale(0.95D)).subtract(base),
                    f1[0].add(d1).add(f1[2].scale(0.95D)).subtract(base), f1[0].add(d1).subtract(f1[2].scale(0.95D)).subtract(base), argb);
            // shining edges
            int edge = ((int) (170 * fade) << 24) | hue(s * 0.06D + time * 0.004D + 0.3D);
            for (int sd = -1; sd <= 1; sd += 2) {
                Vec3 a = f0[0].add(d0).add(f0[2].scale(sd * 0.95D)), b = f1[0].add(d1).add(f1[2].scale(sd * 0.95D));
                beam(c, pose, a.subtract(base), b.subtract(base), 0.06F, edge, FULL);
            }
        }
    }

    /** Neon haze round the lit rails. */
    private void drawRailGlow(PrismCoasterEntity e, VertexConsumer c, PoseStack pose, Vec3 base, float partial, float time) {
        double step = 1.0D;
        for (double s = -1.0D; s < L - 1.0E-6D; s += step) {
            float l = e.railLit(s, partial);
            if (l < 0.3F) continue;
            double s1 = Math.min(L, s + step);
            Vec3[] f0 = e.frame(s), f1 = e.frame(s1);
            int argb = ((int) (60 * l) << 24) | mix(NEON, PINK, 0.5F + 0.5F * Mth.sin((float) (time * 0.2F - s * 0.5D)));
            for (int sd = -1; sd <= 1; sd += 2) {
                Vec3 a = f0[0].add(f0[2].scale(sd * GAUGE)), b = f1[0].add(f1[2].scale(sd * GAUGE));
                beam(c, pose, a.subtract(base), b.subtract(base), 0.28F, argb, FULL);
            }
        }
    }

    /** Energy gathering round the cart: rings turning and rising. */
    private void drawAura(PrismCoasterEntity e, VertexConsumer c, PoseStack pose, Vec3 base, double s, float age, float charge) {
        Vec3[] f = e.frame(s);
        PoseStack.Pose p = pose.last();
        for (int ring = 0; ring < 3; ring++) {
            double h = ((age * 0.03D + ring / 3.0D) % 1.0D);
            double r = 1.5D - 0.4D * h;
            Vec3 ctr = f[0].add(f[3].scale(0.1D + h * 1.8D)).subtract(base);
            int n = 32;
            int col = PALETTE[ring % 3];
            int a = (int) (200 * charge * Math.sin(Math.PI * h));
            for (int k = 0; k < n; k++) {
                if ((k + ring * 3) % 8 == 0) continue;
                double a0 = k * Math.PI * 2.0D / n + age * 0.1D * (ring == 1 ? -1 : 1), a1 = a0 + Math.PI * 2.0D / n;
                Vec3 d0 = f[2].scale(Math.cos(a0)).add(f[1].scale(Math.sin(a0) * 1.2D)), d1 = f[2].scale(Math.cos(a1)).add(f[1].scale(Math.sin(a1) * 1.2D));
                quad(c, p, ctr.add(d0.scale(r - 0.06D)), ctr.add(d0.scale(r + 0.06D)), ctr.add(d1.scale(r + 0.06D)), ctr.add(d1.scale(r - 0.06D)), (a << 24) | col);
            }
        }
    }

    /** The crystals' energy beams: a white core in a coloured sheath. */
    private void drawBeams(VertexConsumer c, PoseStack pose, Vec3 base, float[] d) {
        for (int i = 0; i < N; i++) {
            int o = i * FLOATS;
            float b = d[o + 6];
            if (b <= 0.02F) continue;
            int col = PALETTE[Mth.clamp((int) d[o + 10], 0, PALETTE.length - 1)];
            Vec3 from = new Vec3(d[o], d[o + 1], d[o + 2]).add(new Vec3(d[o + 3], d[o + 4], d[o + 5]).scale(SLOT_LEN[i] * 0.5D)).subtract(base);
            Vec3 to = new Vec3(d[o + 7], d[o + 8], d[o + 9]).subtract(base);
            beam(c, pose, from, to, 0.34F * b, ((int) (140 * b) << 24) | col, FULL);
            beam(c, pose, from, to, 0.09F * b + 0.03F, ((int) (240 * b) << 24) | 0xFFFFFF, FULL);
        }
    }
}

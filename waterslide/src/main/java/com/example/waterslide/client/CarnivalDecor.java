package com.example.waterslide.client;

import com.example.waterslide.SlideAnchorBlockEntity;
import com.example.waterslide.SlideGeometry;
import com.example.waterslide.SlideInstance;
import com.example.waterslide.SlideObstacles;
import com.example.waterslide.SlideSpec;
import com.example.waterslide.WaterSlideMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

/**
 * Dark Carnival decorations of the obstacle slide, in the voxel style of Wheel of Wonder: string lights along both
 * lips, a jester-mask arch over the start, and the animated obstacles (paddle wheel, kicker with a ring of fire,
 * swinging jester hammer, boost hoops). Everything is boxes on one white texture, plus the spiral face on the
 * wheel hubs and camera-facing glow halos: three render batches, a few hundred boxes.
 * Positions come from the slide geometry, and the obstacle timing from SlideObstacles - the same numbers the physics
 * uses, so the hammer hits you exactly when you see it come down.
 */
final class CarnivalDecor {
    private static final ResourceLocation WHITE = rl("dc_white");
    private static final ResourceLocation FACE = rl("dc_face");
    private static final ResourceLocation GLOW = rl("dc_glow");
    private static final ResourceLocation GLOW_SOFT = rl("dc_glow_soft");

    private static final int VIOLET = 0xFF4A2A6A, VIOLET_DARK = 0xFF2A1830, RED = 0xFF8E1424, BLACK = 0xFF1A0D16;
    private static final int GOLD = 0xFFE8B33A, GOLD_DARK = 0xFFA87418, CREAM = 0xFFF6E6D4, IRON = 0xFF3A3040;
    private static final int PUMPKIN = 0xFFFF7A10, MASK = 0xFF9A7650, MASK_DARK = 0xFF6E5236, SPIKE = 0xFFB8301E;
    private static final int[] BULBS = {0xFFC84BFF, 0xFFFF8A1F, 0xFF7CFF3B, 0xFFFF3B3B, 0xFFFFD93B, 0xFF5A8BFF};
    private static final int FULL = LightTexture.FULL_BRIGHT;
    /** One lip bulb every this many geometry samples (0.25 blocks each): every 2 blocks. */
    private static final int BULB_EVERY = 8;

    private static ResourceLocation rl(String n) {
        return ResourceLocation.fromNamespaceAndPath(WaterSlideMod.MODID, "textures/entity/" + n + ".png");
    }

    private CarnivalDecor() {
    }

    // ------------------------------------------------------------------ layout (built once per slide type)

    private static final class Layout {
        final List<double[]> lipBulbs = new ArrayList<>();
        /** Light probes: one per lip bulb, then one per obstacle, then the arch. */
        final List<double[]> probes = new ArrayList<>();
        double[] arch;
        /** Sample indices of the pennant garlands and the balloons ({index, side}); their light probes start here. */
        final List<Integer> garlands = new ArrayList<>();
        final List<int[]> balloons = new ArrayList<>();
        int extraLight0;
    }

    /** True if s is clear of every obstacle (by margin blocks; the tunnel counts its whole length). */
    private static boolean clear(SlideGeometry g, double s, double margin) {
        for (double[] o : g.spec.obstacles()) {
            double half = o[0] == SlideSpec.OB_TUNNEL ? SlideObstacles.TUNNEL_HALF : 0.0D;
            double extra = o[0] == SlideSpec.OB_RAMP ? SlideObstacles.FIRE_AHEAD : 0.0D;
            if (s > o[1] - half - margin && s < o[1] + half + extra + margin) return false;
        }
        return true;
    }

    private static final Layout[] LAYOUTS = new Layout[com.example.waterslide.SlideTypes.count()];

    private static Layout layout(int type, SlideGeometry g) {
        Layout l = LAYOUTS[type];
        if (l != null) return l;
        l = new Layout();
        double rLip = (g.ri + g.ro) * 0.5D;
        for (int i = 2; i < g.n - 2; i += BULB_EVERY) {
            for (int side : new int[]{-1, 1}) {
                double phi = side * g.phi;
                double[] p = pt(g, i, phi, rLip);
                double[] n = scale(tangentPhi(g, i, phi), side);
                double[] b = add(p, scale(n, 0.1D));
                l.lipBulbs.add(b);
                l.probes.add(b);
            }
        }
        for (double[] o : g.spec.obstacles()) {
            int i = index(g, o[1]);
            l.probes.add(new double[]{g.px[i] + g.ux[i] * 1.5D, g.py[i] + g.uy[i] * 1.5D, g.pz[i] + g.uz[i] * 1.5D});
        }
        l.arch = new double[]{0.0D, g.deckY + 2.0D, -0.6D};
        l.probes.add(l.arch);
        l.extraLight0 = l.probes.size();
        double len = g.arcLen[g.n - 1];
        for (double s = 7.0D; s < len - 8.0D; s += 9.0D) {
            if (clear(g, s, 2.5D)) l.garlands.add(index(g, s));
        }
        int side = 1;
        for (double s = 11.0D; s < len - 8.0D; s += 13.0D) {
            if (!clear(g, s, 1.5D)) continue;
            l.balloons.add(new int[]{index(g, s), side});
            side = -side;
        }
        for (int gi : l.garlands) l.probes.add(new double[]{g.px[gi] + g.ux[gi] * 2.5D, g.py[gi] + g.uy[gi] * 2.5D, g.pz[gi] + g.uz[gi] * 2.5D});
        for (int[] b : l.balloons) l.probes.add(pt(g, b[0], b[1] * g.phi, g.ro + 0.3D));
        int last = g.n - 1;
        l.probes.add(new double[]{g.px[last] + g.ux[last] * 2.0D, g.py[last] + g.uy[last] * 2.0D, g.pz[last] + g.uz[last] * 2.0D});
        LAYOUTS[type] = l;
        return l;
    }

    // ------------------------------------------------------------------ render

    /** Called by SlideRenderer with the pose already in slide-local space. */
    static void render(SlideAnchorBlockEntity be, SlideInstance slide, SlideGeometry g, float partial, PoseStack pose,
                       MultiBufferSource buffers, float facingDeg) {
        Level level = be.getLevel();
        if (level == null) return;
        Layout lay = layout(slide.type, g);
        long gt = level.getGameTime();
        if (be.decorLights == null || be.decorLights.length != lay.probes.size() || gt - be.decorLightTick >= 10 || gt < be.decorLightTick) {
            int[] lights = new int[lay.probes.size()];
            for (int i = 0; i < lights.length; i++) lights[i] = LevelRenderer.getLightColor(level, slide.worldBlock(lay.probes.get(i)));
            be.decorLights = lights;
            be.decorLightTick = gt;
        }
        int[] lights = be.decorLights;
        double time = gt + partial;
        int step = (int) (time / 3.0D);
        List<double[]> obs = g.spec.obstacles();
        int obLight0 = lay.lipBulbs.size();
        int archLight = lights[lay.extraLight0 - 1];

        List<Halo> halos = new ArrayList<>();
        List<double[]> faces = new ArrayList<>();
        PoseStack.Pose p = pose.last();
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));

        // ---- string lights along both lips: chasing, six colours
        for (int k = 0; k < lay.lipBulbs.size(); k++) {
            double[] b = lay.lipBulbs.get(k);
            int pos = k / 2;
            boolean on = (pos + step) % 3 != 0;
            int col = BULBS[(pos + step / 8) % BULBS.length];
            cube(c, p, b, 0.07D, on ? col : shade(col, 0.3F), on ? FULL : lights[k]);
            if (on) halos.add(new Halo(b, 0.3F, shade(col, 0.6F)));
        }

        // ---- the arch over the start (only where there is a start platform: not on the middle sections)
        if (g.spec.hasDeck()) arch(c, p, g, time, archLight, halos);
        boolean finishArch = g.spec.hasFinish() || com.example.waterslide.SlideTypes.chainIndex(slide.type) < 0;

        // ---- obstacles
        for (int oi = 0; oi < obs.size(); oi++) {
            double[] o = obs.get(oi);
            int light = lights[obLight0 + oi];
            int i = index(g, o[1]);
            switch ((int) o[0]) {
                case SlideSpec.OB_WHEEL -> wheel(c, p, g, i, time, light, halos, faces);
                case SlideSpec.OB_RAMP -> ramp(c, p, g, i, o[1], time, light, halos);
                case SlideSpec.OB_HAMMER -> hammer(c, p, g, i, oi, time, light, halos);
                case SlideSpec.OB_BUMPERS -> bumpers(c, p, g, o[1], oi, time, light, halos);
                case SlideSpec.OB_SPINNER -> spinner(c, p, g, i, oi, time, light, halos);
                case SlideSpec.OB_TUNNEL -> tunnel(c, p, g, o[1], time, light, halos);
                default -> hoop(c, p, g, i, oi, step, light, halos);
            }
        }

        // ---- pennant garlands over the trough, balloons on the lips, the finish arch
        for (int k = 0; k < lay.garlands.size(); k++) garland(c, p, g, lay.garlands.get(k), k, time, lights[lay.extraLight0 + k]);
        for (int k = 0; k < lay.balloons.size(); k++) balloon(c, p, g, lay.balloons.get(k), k, time, lights[lay.extraLight0 + lay.garlands.size() + k], halos);
        if (finishArch) finish(c, p, g, time, lights[lay.extraLight0 + lay.garlands.size() + lay.balloons.size()], halos);

        // ---- second batch: spiral faces on the wheel hubs
        if (!faces.isEmpty()) {
            VertexConsumer f = buffers.getBuffer(RenderType.entityCutoutNoCull(FACE));
            for (double[] d : faces) disc(f, p, d);
        }

        // ---- third batch: glow halos, facing the camera
        boolean shaders = Glow.shadersOn();
        VertexConsumer glow = buffers.getBuffer(shaders ? RenderType.entityTranslucent(GLOW_SOFT) : RenderType.eyes(GLOW));
        Quaternionf cam = Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation();
        for (Halo h : halos) {
            pose.pushPose();
            pose.translate(h.p[0], h.p[1], h.p[2]);
            // the pose is turned to the slide's facing: undo that before turning to the camera
            pose.mulPose(Axis.YP.rotationDegrees(facingDeg));
            pose.mulPose(cam);
            haloQuad(glow, pose.last(), h.size, shaders ? Glow.toAlpha(h.argb) : h.argb | 0xFF000000);
            pose.popPose();
        }

        // ---- particles, once per game tick
        if (be.decorFxTick != gt) {
            be.decorFxTick = gt;
            particles(level, slide, g, gt);
        }
    }

    private record Halo(double[] p, float size, int argb) {
    }

    // ------------------------------------------------------------------ arch with the jester mask

    private static void arch(VertexConsumer c, PoseStack.Pose p, SlideGeometry g, double time, int light, List<Halo> halos) {
        double y0 = g.deckY, x = g.ro + 0.65D, z = 0.15D, top = y0 + 4.2D;
        for (double s : new double[]{-1.0D, 1.0D}) {
            // striped posts
            for (int k = 0; k < 10; k++) {
                double a = y0 + k * 0.42D;
                box(c, p, s * x - 0.13D, a, z - 0.13D, s * x + 0.13D, a + 0.42D, z + 0.13D, k % 2 == 0 ? RED : BLACK, light);
            }
            box(c, p, s * x - 0.17D, y0, z - 0.17D, s * x + 0.17D, y0 + 0.18D, z + 0.17D, GOLD_DARK, light);
            box(c, p, s * x - 0.17D, top, z - 0.17D, s * x + 0.17D, top + 0.2D, z + 0.17D, GOLD, light);
            // pumpkins at the feet
            double[] pk = {s * (x + 0.45D), y0 + 0.17D, z - 0.4D};
            box(c, p, pk[0] - 0.17D, pk[1] - 0.17D, pk[2] - 0.17D, pk[0] + 0.17D, pk[1] + 0.17D, pk[2] + 0.17D, PUMPKIN, light);
            box(c, p, pk[0] - 0.1D, pk[1] - 0.02D, pk[2] - 0.18D, pk[0] + 0.1D, pk[1] + 0.06D, pk[2] - 0.17D, 0xFFFFE070, FULL);
            halos.add(new Halo(new double[]{pk[0], pk[1], pk[2] - 0.2D}, 0.4F, shade(0xFFFF8A1F, 0.45F)));
        }
        // crossbar (violet with gold trim) carrying a row of bulbs
        box(c, p, -x - 0.2D, top - 0.05D, z - 0.16D, x + 0.2D, top + 0.25D, z + 0.16D, VIOLET, light);
        box(c, p, -x - 0.22D, top - 0.08D, z - 0.18D, x + 0.22D, top - 0.02D, z + 0.18D, GOLD, light);
        int nb = 9;
        for (int k = 0; k < nb; k++) {
            double bx = -x + 2.0D * x * k / (nb - 1);
            double[] b = {bx, top + 0.33D, z};
            int col = BULBS[k % BULBS.length];
            boolean on = ((int) (time / 4.0D) + k) % 2 == 0;
            cube(c, p, b, 0.07D, on ? col : shade(col, 0.3F), on ? FULL : light);
            if (on) halos.add(new Halo(b, 0.3F, shade(col, 0.6F)));
        }
        // the jester: wooden mask, red burning eyes (both sides), a fan crown and a sunburst of spiked bulbs
        double[] m = {0.0D, top + 1.0D, z};
        box(c, p, m[0] - 0.36D, m[1] - 0.45D, m[2] - 0.22D, m[0] + 0.36D, m[1] + 0.45D, m[2] + 0.22D, MASK, light);
        box(c, p, m[0] - 0.39D, m[1] + 0.12D, m[2] - 0.25D, m[0] + 0.39D, m[1] + 0.25D, m[2] + 0.25D, MASK_DARK, light);
        float fl = 0.75F + 0.25F * Mth.sin((float) time * 0.7F) * Mth.sin((float) time * 0.31F + 1.0F);
        int eye = shade(0xFFFF2020, fl);
        for (double sz : new double[]{-1.0D, 1.0D}) {
            double zf = m[2] + sz * 0.22D;
            box(c, p, m[0] - 0.24D, m[1] - 0.02D, Math.min(zf, zf + sz * 0.02D), m[0] - 0.08D, m[1] + 0.1D, Math.max(zf, zf + sz * 0.02D), eye, FULL);
            box(c, p, m[0] + 0.08D, m[1] - 0.02D, Math.min(zf, zf + sz * 0.02D), m[0] + 0.24D, m[1] + 0.1D, Math.max(zf, zf + sz * 0.02D), eye, FULL);
            box(c, p, m[0] - 0.24D, m[1] - 0.33D, Math.min(zf, zf + sz * 0.02D), m[0] + 0.24D, m[1] - 0.25D, Math.max(zf, zf + sz * 0.02D), BLACK, light);
            box(c, p, m[0] - 0.07D, m[1] - 0.15D, Math.min(zf, zf + sz * 0.12D), m[0] + 0.07D, m[1] + 0.12D, Math.max(zf, zf + sz * 0.12D), MASK_DARK, light);
            halos.add(new Halo(new double[]{m[0] - 0.16D, m[1] + 0.04D, zf + sz * 0.04D}, 0.16F * fl, 0xFFB01010));
            halos.add(new Halo(new double[]{m[0] + 0.16D, m[1] + 0.04D, zf + sz * 0.04D}, 0.16F * fl, 0xFFB01010));
        }
        for (int k = 0; k < 7; k++) {
            double th = Math.toRadians(30.0D + k * 20.0D);
            beam(c, p, new double[]{m[0], m[1] + 0.42D, m[2]}, new double[]{m[0] + Math.cos(th) * 0.7D, m[1] + 0.42D + Math.sin(th) * 0.7D, m[2]},
                    0.12D, k % 2 == 0 ? 0xFF7A5634 : GOLD_DARK, light);
        }
        for (int k = 0; k < 10; k++) {
            double th = k * Math.PI * 2.0D / 10.0D + Math.PI / 10.0D;
            double cx = Math.cos(th), cy = Math.sin(th);
            beam(c, p, new double[]{m[0] + cx * 0.45D, m[1] + cy * 0.45D, m[2]}, new double[]{m[0] + cx * 1.35D, m[1] + cy * 1.35D, m[2]}, 0.1D, SPIKE, light);
            double[] mid = {m[0] + cx * 0.9D, m[1] + cy * 0.9D, m[2]}, tip = {m[0] + cx * 1.42D, m[1] + cy * 1.42D, m[2]};
            cube(c, p, mid, 0.07D, 0xFFFF9A30, FULL);
            int tc = k % 2 == 0 ? 0xFF7080FF : 0xFFD070FF;
            cube(c, p, tip, 0.085D, tc, FULL);
            halos.add(new Halo(mid, 0.22F, shade(0xFFFF9A30, 0.55F)));
            halos.add(new Halo(tip, 0.3F * fl + 0.05F, shade(tc, 0.7F)));
        }
        // a red glow behind the head
        halos.add(new Halo(new double[]{m[0], m[1], m[2] - 0.1D}, 1.6F, shade(0xFFFF2A10, 0.2F + 0.08F * fl)));
    }

    // ------------------------------------------------------------------ obstacles

    /** Paddle wheel across the slide, turning so its paddles sweep up the slide against the riders. */
    private static void wheel(VertexConsumer c, PoseStack.Pose p, SlideGeometry g, int i, double time, int light,
                              List<Halo> halos, List<double[]> faces) {
        double[] P = ctr(g, i), T = tan(g, i), S = side(g, i), U = up(g, i);
        double[] C = add(P, scale(U, SlideObstacles.WHEEL_UP));
        double R = SlideObstacles.WHEEL_R, a0 = -time * SlideObstacles.WHEEL_SPIN;
        double half = g.ro + 0.75D;
        // axle, stands and pumpkins
        beam(c, p, add(C, scale(S, -half)), add(C, scale(S, half)), 0.16D, GOLD, light);
        for (double sd : new double[]{-1.0D, 1.0D}) {
            double[] top = add(C, scale(S, sd * half));
            stand(c, p, top, light);
        }
        // paddles: six boards, red and cream
        for (int k = 0; k < 6; k++) {
            double a = a0 + k * Math.PI / 3.0D;
            double[] r = add(scale(T, Math.cos(a)), scale(U, Math.sin(a)));
            double[] n = add(scale(T, -Math.sin(a)), scale(U, Math.cos(a)));
            double[] mid = add(C, scale(r, (0.35D + R) * 0.5D));
            obox(c, p, mid, r, S, n, (R - 0.35D) * 0.5D, 1.0D, 0.06D, k % 2 == 0 ? RED : CREAM, light);
        }
        // two gold rims with chasing bulbs, a hub with the spiral face on both ends
        for (double sd : new double[]{-1.05D, 1.05D}) {
            double[] cs = add(C, scale(S, sd));
            int seg = 16;
            for (int k = 0; k < seg; k++) {
                double a = a0 + k * Math.PI * 2.0D / seg, b = a0 + (k + 1) * Math.PI * 2.0D / seg;
                double[] pa = add(cs, add(scale(T, Math.cos(a) * R), scale(U, Math.sin(a) * R)));
                double[] pb = add(cs, add(scale(T, Math.cos(b) * R), scale(U, Math.sin(b) * R)));
                beam(c, p, pa, pb, 0.13D, k % 2 == 0 ? GOLD : GOLD_DARK, light);
                if (k % 2 == 0) {
                    int col = BULBS[(k / 2) % BULBS.length];
                    double[] bp = add(cs, add(scale(T, Math.cos(a) * (R + 0.12D)), scale(U, Math.sin(a) * (R + 0.12D))));
                    cube(c, p, bp, 0.07D, col, FULL);
                    halos.add(new Halo(bp, 0.26F, shade(col, 0.55F)));
                }
            }
            obox(c, p, cs, T, U, S, 0.32D, 0.32D, 0.08D, RED, light);
            faces.add(new double[]{cs[0] + S[0] * sd * 0.1D, cs[1] + S[1] * sd * 0.1D, cs[2] + S[2] * sd * 0.1D,
                    T[0], T[1], T[2], U[0], U[1], U[2], S[0] * Math.signum(sd), S[1] * Math.signum(sd), S[2] * Math.signum(sd), 0.42D, a0, light});
        }
    }

    /** Hazard-striped kicker on the floor, then a ring of fire to jump through. */
    private static void ramp(VertexConsumer c, PoseStack.Pose p, SlideGeometry g, int i0, double s, double time, int light, List<Halo> halos) {
        for (int k = 0; k < 5; k++) {
            int i = index(g, s - 1.3D + k * 0.3D);
            double[] P = ctr(g, i), T = tan(g, i), S = side(g, i), U = up(g, i);
            double h = 0.07D * (k + 1);
            double[] m = add(P, scale(U, -g.ri + h * 0.5D + 0.02D));
            obox(c, p, m, T, S, U, 0.16D, 1.05D, h * 0.5D, k % 2 == 0 ? GOLD : BLACK, light);
        }
        int i = index(g, s + SlideObstacles.FIRE_AHEAD);
        double[] P = ctr(g, i), T = tan(g, i), S = side(g, i), U = up(g, i);
        double[] F = add(P, scale(U, -g.ri + SlideObstacles.FIRE_UP));
        double R = SlideObstacles.FIRE_R;
        int seg = 14;
        for (int k = 0; k < seg; k++) {
            double a = k * Math.PI * 2.0D / seg, b = (k + 1) * Math.PI * 2.0D / seg;
            double[] pa = add(F, add(scale(S, Math.cos(a) * R), scale(U, Math.sin(a) * R)));
            double[] pb = add(F, add(scale(S, Math.cos(b) * R), scale(U, Math.sin(b) * R)));
            boolean hot = k % 2 == 0;
            beam(c, p, pa, pb, 0.13D, hot ? 0xFFFF6A10 : IRON, hot ? FULL : light);
            if (hot) {
                float fl = 0.75F + 0.25F * Mth.sin((float) time * 1.3F + k * 1.7F);
                halos.add(new Halo(pa, 0.55F * fl, shade(k % 4 == 0 ? 0xFFFF8A1F : 0xFFFF4A10, 0.6F * fl)));
            }
        }
        // gantry holding the ring
        double half = g.ro + 0.6D, gy = SlideObstacles.FIRE_UP - g.ri + R + 1.0D;
        double[] l = add(P, add(scale(S, -half), scale(U, gy))), r = add(P, add(scale(S, half), scale(U, gy)));
        beam(c, p, l, r, 0.18D, VIOLET, light);
        stand(c, p, l, light);
        stand(c, p, r, light);
        beam(c, p, add(F, scale(U, R)), add(P, scale(U, gy)), 0.05D, GOLD_DARK, light);
    }

    /** A gantry with a jester mallet swinging across the slide. */
    private static void hammer(VertexConsumer c, PoseStack.Pose p, SlideGeometry g, int i, int oi, double time, int light, List<Halo> halos) {
        double[] P = ctr(g, i), T = tan(g, i), S = side(g, i), U = up(g, i);
        double half = g.ro + 0.6D;
        double[] pv = add(P, scale(U, SlideObstacles.HAMMER_PIVOT));
        double[] l = add(pv, scale(S, -half)), r = add(pv, scale(S, half));
        box2(c, p, l, r, 0.24D, VIOLET_DARK, light);
        stand(c, p, l, light);
        stand(c, p, r, light);
        double th = SlideObstacles.hammerAngle(time, oi);
        double[] dir = add(scale(S, Math.sin(th)), scale(U, -Math.cos(th)));
        double[] head = add(pv, scale(dir, SlideObstacles.HAMMER_ARM));
        beam(c, p, pv, head, 0.15D, GOLD_DARK, light);
        cube(c, p, pv, 0.16D, GOLD, light);
        double[] n = cross(T, dir);
        obox(c, p, head, T, dir, n, 0.62D, 0.38D, 0.38D, RED, light);
        for (double e : new double[]{-0.45D, 0.45D}) {
            obox(c, p, add(head, scale(T, e)), T, dir, n, 0.06D, 0.42D, 0.42D, GOLD, light);
        }
        // a bell at each end and a bulb that flashes when the hammer is down
        cube(c, p, add(head, scale(T, 0.72D)), 0.12D, GOLD, light);
        cube(c, p, add(head, scale(T, -0.72D)), 0.12D, GOLD, light);
        boolean down = SlideObstacles.hammerDown(time, oi);
        double[] bulb = add(head, scale(dir, -0.45D));
        int col = down ? 0xFFFF2A1A : 0xFFFFD93B;
        cube(c, p, bulb, 0.08D, col, FULL);
        halos.add(new Halo(bulb, down ? 0.55F : 0.3F, shade(col, 0.65F)));
    }

    /** A gold-and-red hoop around the trough with bulbs: a speed boost. */
    private static void hoop(VertexConsumer c, PoseStack.Pose p, SlideGeometry g, int i, int oi, int step, int light, List<Halo> halos) {
        double[] P = ctr(g, i), S = side(g, i), U = up(g, i);
        double R = SlideObstacles.HOOP_R;
        int seg = 16;
        for (int k = 0; k < seg; k++) {
            double a = k * Math.PI * 2.0D / seg, b = (k + 1) * Math.PI * 2.0D / seg;
            double[] pa = add(P, add(scale(S, Math.cos(a) * R), scale(U, Math.sin(a) * R)));
            double[] pb = add(P, add(scale(S, Math.cos(b) * R), scale(U, Math.sin(b) * R)));
            beam(c, p, pa, pb, 0.16D, k % 2 == 0 ? GOLD : RED, light);
            if (k % 2 == 0 && Math.sin(a) > -0.6D) {
                int col = BULBS[(k / 2 + oi) % BULBS.length];
                boolean on = (k / 2 + step + oi) % 2 == 0;
                double[] bp = add(P, add(scale(S, Math.cos(a) * (R + 0.14D)), scale(U, Math.sin(a) * (R + 0.14D))));
                cube(c, p, bp, 0.075D, on ? col : shade(col, 0.3F), on ? FULL : light);
                if (on) halos.add(new Halo(bp, 0.32F, shade(col, 0.6F)));
            }
        }
    }

    /** Two spring-loaded clown heads on one wall, bobbing in and out (side = index parity, as in the physics). */
    private static void bumpers(VertexConsumer c, PoseStack.Pose p, SlideGeometry g, double s, int oi, double time, int light, List<Halo> halos) {
        double side = oi % 2 == 0 ? 1.0D : -1.0D;   // the wall the heads sit on (they push riders to the other side)
        for (int h = 0; h < 2; h++) {
            int i = index(g, s - 0.5D + h * 1.0D);
            double[] P = ctr(g, i), T = tan(g, i), S = side(g, i), U = up(g, i);
            double phi = side * g.phi * 0.62D;
            double[] wall = pt(g, i, phi, g.ri);
            double[] in = norm(sub(P, wall));
            double bob = 0.35D + 0.25D * Math.sin(time * 0.35D + h * 2.1D + oi);
            double[] head = add(wall, scale(in, 0.55D + bob));
            // spring: alternating gold coils from the wall to the head
            int coils = 5;
            for (int k = 0; k < coils; k++) {
                double[] a = add(wall, scale(in, (0.55D + bob) * k / coils)), b = add(wall, scale(in, (0.55D + bob) * (k + 1) / coils));
                beam(c, p, a, b, k % 2 == 0 ? 0.16D : 0.1D, k % 2 == 0 ? GOLD : GOLD_DARK, light);
            }
            double[] n = cross(T, in);
            // ruffled collar, face, red nose, black eyes and a little violet hat
            obox(c, p, add(head, scale(in, -0.18D)), in, T, n, 0.06D, 0.36D, 0.36D, h % 2 == 0 ? RED : VIOLET, light);
            obox(c, p, head, in, T, n, 0.22D, 0.25D, 0.25D, CREAM, light);
            double[] nose = add(head, scale(in, 0.26D));
            cube(c, p, nose, 0.08D, 0xFFFF2020, FULL);
            halos.add(new Halo(nose, 0.22F, shade(0xFFFF2020, 0.6F)));
            for (double e : new double[]{-0.1D, 0.1D}) {
                obox(c, p, add(add(head, scale(in, 0.22D)), add(scale(T, e), scale(U, 0.1D))), in, T, n, 0.02D, 0.04D, 0.04D, BLACK, light);
            }
            double[] hatUp = norm(cross(in, T));
            if (dot(hatUp, U) < 0) hatUp = scale(hatUp, -1.0D);
            obox(c, p, add(head, scale(hatUp, 0.3D)), hatUp, T, cross(hatUp, T), 0.12D, 0.14D, 0.14D, VIOLET_DARK, light);
            cube(c, p, add(head, scale(hatUp, 0.47D)), 0.05D, BULBS[(h + oi) % BULBS.length], FULL);
        }
    }

    /** Turnstile: a shaft from a gantry down into the trough with two striped bars turning around it. */
    private static void spinner(VertexConsumer c, PoseStack.Pose p, SlideGeometry g, int i, int oi, double time, int light, List<Halo> halos) {
        double[] P = ctr(g, i), T = tan(g, i), S = side(g, i), U = up(g, i);
        double half = g.ro + 0.6D, top = 3.0D;
        double[] l = add(P, add(scale(S, -half), scale(U, top))), r = add(P, add(scale(S, half), scale(U, top)));
        beam(c, p, l, r, 0.24D, VIOLET_DARK, light);
        stand(c, p, l, light);
        stand(c, p, r, light);
        double[] hub = add(P, scale(U, -g.ri + SlideObstacles.SPINNER_UP));
        beam(c, p, add(P, scale(U, top)), hub, 0.14D, GOLD_DARK, light);
        cube(c, p, hub, 0.2D, GOLD, light);
        double a = SlideObstacles.spinnerAngle(time, oi);
        double[] d = add(scale(T, Math.cos(a)), scale(S, Math.sin(a)));
        double R = SlideObstacles.SPINNER_ARM;
        for (double sg : new double[]{-1.0D, 1.0D}) {
            for (int k = 0; k < 4; k++) {
                double[] a0 = add(hub, scale(d, sg * (0.2D + (R - 0.2D) * k / 4.0D)));
                double[] a1 = add(hub, scale(d, sg * (0.2D + (R - 0.2D) * (k + 1) / 4.0D)));
                beam(c, p, a0, a1, 0.18D, k % 2 == 0 ? RED : CREAM, light);
            }
            double[] tip = add(hub, scale(d, sg * (R + 0.1D)));
            boolean blocks = SlideObstacles.spinnerBlocks(time, oi);
            int col = blocks ? 0xFFFF2A1A : 0xFF7CFF3B;
            cube(c, p, tip, 0.1D, col, FULL);
            halos.add(new Halo(tip, 0.35F, shade(col, 0.6F)));
        }
    }

    /** Tunnel of terror: dark ribbed arches over the trough with blinking eyes inside. */
    private static void tunnel(VertexConsumer c, PoseStack.Pose p, SlideGeometry g, double s, double time, int light, List<Halo> halos) {
        double R = g.ro + 0.55D;
        int arches = 8;
        double a0 = -g.phi - 0.15D, a1 = g.phi + 0.15D;
        for (int k = 0; k < arches; k++) {
            double sk = s - SlideObstacles.TUNNEL_HALF + 2.0D * SlideObstacles.TUNNEL_HALF * k / (arches - 1);
            int i = index(g, sk);
            int seg = 10;
            // the arch goes over the top: from the left lip, through straight up, to the right lip (angle measured from
            // straight down, so the top is at PI)
            for (int q = 0; q < seg; q++) {
                double u0 = q / (double) seg, u1 = (q + 1) / (double) seg;
                double b0 = a1 + (2.0D * Math.PI - (a1 - a0)) * u0, b1 = a1 + (2.0D * Math.PI - (a1 - a0)) * u1;
                beam(c, p, pt(g, i, b0, R), pt(g, i, b1, R), 0.22D, q % 2 == 0 ? VIOLET_DARK : BLACK, light);
            }
            // a pair of eyes on every other arch, blinking now and then
            if (k % 2 == 1) {
                boolean blink = ((int) (time / 7.0D) + k * 3) % 9 == 0;
                if (!blink) {
                    int col = k % 4 == 1 ? 0xFFFFE040 : 0xFF7CFF3B;
                    for (double e : new double[]{-0.22D, 0.22D}) {
                        double[] eye = add(pt(g, i, Math.PI + e, R - 0.15D), scale(tan(g, i), -0.12D));
                        cube(c, p, eye, 0.07D, col, FULL);
                        halos.add(new Halo(eye, 0.2F, shade(col, 0.55F)));
                    }
                }
            }
        }
    }

    /** A string of triangular pennants hanging across the trough, swaying a little. */
    private static void garland(VertexConsumer c, PoseStack.Pose p, SlideGeometry g, int i, int k, double time, int light) {
        double[] P = ctr(g, i), T = tan(g, i), S = side(g, i), U = up(g, i);
        double half = g.ro + 0.2D, h = 2.0D;
        double[] l = add(P, add(scale(S, -half), scale(U, h))), r = add(P, add(scale(S, half), scale(U, h)));
        // little striped poles on the lips carry the string
        beam(c, p, pt(g, i, -g.phi, g.ro), l, 0.08D, VIOLET, light);
        beam(c, p, pt(g, i, g.phi, g.ro), r, 0.08D, VIOLET, light);
        cube(c, p, l, 0.07D, GOLD, light);
        cube(c, p, r, 0.07D, GOLD, light);
        int flags = 7;
        double sway = Math.sin(time * 0.08D + k) * 0.12D;
        double[] prev = l;
        for (int f = 0; f <= flags + 1; f++) {
            double t = f / (double) (flags + 1), bell = 4.0D * t * (1.0D - t);
            // sags 0.6 in the middle and swings a little along the slide
            double[] q = add(P, add(scale(S, -half + 2.0D * half * t), add(scale(U, h - 0.6D * bell), scale(T, sway * bell))));
            beam(c, p, prev, q, 0.03D, GOLD_DARK, light);
            if (f > 0 && f <= flags) {
                int col = BULBS[(f + k) % BULBS.length];
                // pennant: three shrinking boxes make a triangle hanging down
                for (int z = 0; z < 3; z++) {
                    double w = 0.16D - z * 0.05D;
                    obox(c, p, add(q, scale(U, -0.08D - z * 0.1D)), S, U, T, w, 0.05D, 0.012D, shade(col, 0.85F), light);
                }
            }
            prev = q;
        }
        beam(c, p, prev, r, 0.03D, GOLD_DARK, light);
    }

    /** A balloon tied to the lip, bobbing on its string. */
    private static void balloon(VertexConsumer c, PoseStack.Pose p, SlideGeometry g, int[] b, int k, double time, int light, List<Halo> halos) {
        int i = b[0];
        double phi = b[1] * g.phi;
        double[] base = pt(g, i, phi, (g.ri + g.ro) * 0.5D);
        double bob = Math.sin(time * 0.06D + k * 1.3D) * 0.15D;
        double[] top = add(base, new double[]{Math.sin(time * 0.04D + k) * 0.15D, 2.1D + bob, Math.cos(time * 0.05D + k) * 0.15D});
        beam(c, p, base, top, 0.02D, CREAM, light);
        int col = BULBS[k % BULBS.length];
        double[] m = add(top, new double[]{0.0D, 0.32D, 0.0D});
        box(c, p, m[0] - 0.26D, m[1] - 0.3D, m[2] - 0.26D, m[0] + 0.26D, m[1] + 0.3D, m[2] + 0.26D, col, light);
        box(c, p, m[0] - 0.18D, m[1] - 0.38D, m[2] - 0.18D, m[0] + 0.18D, m[1] + 0.38D, m[2] + 0.18D, col, light);
        box(c, p, m[0] - 0.05D, m[1] - 0.44D, m[2] - 0.05D, m[0] + 0.05D, m[1] - 0.36D, m[2] + 0.05D, shade(col, 0.7F), light);
        cube(c, p, add(m, new double[]{-0.12D, 0.14D, -0.2D}), 0.05D, 0xFFFFFFFF, FULL);
    }

    /** Finish arch over the run-out: checkered banner, bulbs, two jack-o'-lanterns. */
    private static void finish(VertexConsumer c, PoseStack.Pose p, SlideGeometry g, double time, int light, List<Halo> halos) {
        int i = index(g, g.arcLen[g.n - 1] - 2.0D);
        double[] P = ctr(g, i), T = tan(g, i), S = side(g, i), U = up(g, i);
        double half = g.ro + 0.65D, top = 3.2D;
        double[] l = add(P, add(scale(S, -half), scale(U, top))), r = add(P, add(scale(S, half), scale(U, top)));
        stand(c, p, l, light);
        stand(c, p, r, light);
        beam(c, p, l, r, 0.3D, VIOLET, light);
        // checkered banner below the bar
        int n = 12;
        for (int k = 0; k < n; k++) {
            for (int row = 0; row < 2; row++) {
                double t = (k + 0.5D) / n;
                double[] q = add(P, add(scale(S, -half + 2.0D * half * t), scale(U, top - 0.3D - row * 0.26D)));
                obox(c, p, q, S, U, T, half / n, 0.13D, 0.03D, (k + row) % 2 == 0 ? 0xFFF0F0F0 : BLACK, light);
            }
        }
        for (int k = 0; k < 9; k++) {
            double t = k / 8.0D;
            double[] b = add(P, add(scale(S, -half + 2.0D * half * t), scale(U, top + 0.26D)));
            int col = BULBS[k % BULBS.length];
            boolean on = ((int) (time / 3.0D) + k) % 2 == 0;
            cube(c, p, b, 0.08D, on ? col : shade(col, 0.3F), on ? FULL : light);
            if (on) halos.add(new Halo(b, 0.32F, shade(col, 0.6F)));
        }
        for (double[] end : new double[][]{l, r}) {
            double[] pk = add(end, scale(U, 0.35D));
            box(c, p, pk[0] - 0.2D, pk[1] - 0.18D, pk[2] - 0.2D, pk[0] + 0.2D, pk[1] + 0.18D, pk[2] + 0.2D, PUMPKIN, light);
            halos.add(new Halo(pk, 0.5F, shade(0xFFFF8A1F, 0.45F)));
        }
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** A striped violet stand from a point down to the ground (local y = 0), with a pumpkin at its foot. */
    private static void stand(VertexConsumer c, PoseStack.Pose p, double[] top, int light) {
        if (top[1] < 0.3D) return;
        double h = top[1];
        int seg = Math.max(1, (int) Math.round(h / 0.6D));
        for (int k = 0; k < seg; k++) {
            double y0 = h * k / seg, y1 = h * (k + 1) / seg;
            box(c, p, top[0] - 0.11D, y0, top[2] - 0.11D, top[0] + 0.11D, y1, top[2] + 0.11D, k % 2 == 0 ? VIOLET : BLACK, light);
        }
        box(c, p, top[0] - 0.2D, 0.0D, top[2] - 0.2D, top[0] + 0.2D, 0.12D, top[2] + 0.2D, GOLD_DARK, light);
    }

    // ------------------------------------------------------------------ particles

    private static void particles(Level level, SlideInstance slide, SlideGeometry g, long gt) {
        RandomSource r = level.getRandom();
        List<double[]> obs = g.spec.obstacles();
        for (int oi = 0; oi < obs.size(); oi++) {
            double[] o = obs.get(oi);
            int kind = (int) o[0];
            if (kind == SlideSpec.OB_RAMP) {
                int i = index(g, o[1] + SlideObstacles.FIRE_AHEAD);
                double[] P = ctr(g, i), S = side(g, i), U = up(g, i);
                double[] F = add(P, scale(U, -g.ri + SlideObstacles.FIRE_UP));
                for (int k = 0; k < 2; k++) {
                    double a = r.nextDouble() * Math.PI * 2.0D;
                    double[] q = add(F, add(scale(S, Math.cos(a) * SlideObstacles.FIRE_R), scale(U, Math.sin(a) * SlideObstacles.FIRE_R)));
                    double[] w = slide.toWorld(q[0], q[1], q[2]);
                    level.addParticle(k == 0 ? ParticleTypes.FLAME : ParticleTypes.SOUL_FIRE_FLAME, w[0], w[1], w[2], 0.0D, 0.02D, 0.0D);
                }
                if (gt % 3 == 0) {
                    double[] w = slide.toWorld(F[0] + U[0] * SlideObstacles.FIRE_R, F[1] + U[1] * SlideObstacles.FIRE_R, F[2] + U[2] * SlideObstacles.FIRE_R);
                    level.addParticle(ParticleTypes.SMOKE, w[0], w[1], w[2], 0.0D, 0.04D, 0.0D);
                }
            } else if (kind == SlideSpec.OB_HAMMER && SlideObstacles.hammerDown(gt, oi) && gt % 2 == 0) {
                int i = index(g, o[1]);
                double[] P = ctr(g, i), S = side(g, i), U = up(g, i);
                double th = SlideObstacles.hammerAngle(gt, oi);
                double[] dir = add(scale(S, Math.sin(th)), scale(U, -Math.cos(th)));
                double[] h = add(add(P, scale(U, SlideObstacles.HAMMER_PIVOT)), scale(dir, SlideObstacles.HAMMER_ARM));
                double[] w = slide.toWorld(h[0], h[1], h[2]);
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, w[0], w[1], w[2], (r.nextDouble() - 0.5D) * 0.2D, 0.05D, (r.nextDouble() - 0.5D) * 0.2D);
            } else if (kind == SlideSpec.OB_WHEEL && gt % 3 == 0) {
                int i = index(g, o[1]);
                double[] P = ctr(g, i), U = up(g, i);
                double[] b = add(P, scale(U, -g.ri + 0.1D));
                double[] w = slide.toWorld(b[0], b[1], b[2]);
                level.addParticle(ParticleTypes.SPLASH, w[0], w[1], w[2], 0.0D, 0.1D, 0.0D);
            }
        }
        if (gt % 10 == 0 && g.spec.hasDeck()) {
            double[] w = slide.toWorld((r.nextDouble() - 0.5D) * 1.2D, g.deckY + 5.6D, 0.15D);
            level.addParticle(ParticleTypes.WITCH, w[0], w[1], w[2], 0.0D, 0.02D, 0.0D);
        }
        // tunnel of terror: ghostly souls and smoke drifting inside
        for (int oi = 0; oi < obs.size(); oi++) {
            double[] o = obs.get(oi);
            if (o[0] != SlideSpec.OB_TUNNEL || gt % 4 != 0) continue;
            int i = index(g, o[1] + (r.nextDouble() * 2.0D - 1.0D) * SlideObstacles.TUNNEL_HALF);
            double[] q = pt(g, i, (r.nextDouble() - 0.5D) * 1.6D, g.ri * 0.4D);
            double[] w = slide.toWorld(q[0], q[1], q[2]);
            level.addParticle(gt % 8 == 0 ? ParticleTypes.SOUL : ParticleTypes.SMOKE, w[0], w[1], w[2], 0.0D, 0.01D, 0.0D);
        }
        // finish arch: a confetti fountain now and then
        if (gt % 5 == 0 && (g.spec.hasFinish() || com.example.waterslide.SlideTypes.chainIndex(slide.type) < 0)) {
            int i = index(g, g.arcLen[g.n - 1] - 2.0D);
            double side = r.nextBoolean() ? 1.0D : -1.0D;
            double[] q = add(ctr(g, i), add(scale(side(g, i), side * (g.ro + 0.65D)), scale(up(g, i), 3.4D)));
            double[] w = slide.toWorld(q[0], q[1], q[2]);
            int col = BULBS[r.nextInt(BULBS.length)];
            level.addParticle(new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(((col >> 16) & 255) / 255.0F,
                    ((col >> 8) & 255) / 255.0F, (col & 255) / 255.0F), 1.2F), w[0], w[1], w[2], 0.0D, 0.0D, 0.0D);
            if (gt % 20 == 0) level.addParticle(ParticleTypes.FIREWORK, w[0], w[1], w[2], (r.nextDouble() - 0.5D) * 0.1D, 0.15D, (r.nextDouble() - 0.5D) * 0.1D);
        }
        // balloons and garlands: an occasional note from the carnival organ
        if (gt % 40 == 0) {
            int i = index(g, r.nextDouble() * g.arcLen[g.n - 1]);
            double[] q = add(ctr(g, i), scale(up(g, i), 1.5D));
            double[] w = slide.toWorld(q[0], q[1], q[2]);
            level.addParticle(ParticleTypes.NOTE, w[0], w[1], w[2], r.nextDouble(), 0.0D, 0.0D);
        }
    }

    // ------------------------------------------------------------------ geometry access

    static int index(SlideGeometry g, double s) {
        return Mth.clamp((int) Math.round(s / g.step), 0, g.n - 1);
    }

    private static double[] ctr(SlideGeometry g, int i) {
        return new double[]{g.px[i], g.py[i], g.pz[i]};
    }

    private static double[] tan(SlideGeometry g, int i) {
        return new double[]{g.tx[i], g.ty[i], g.tz[i]};
    }

    private static double[] side(SlideGeometry g, int i) {
        return new double[]{g.sx[i], g.sy[i], g.sz[i]};
    }

    private static double[] up(SlideGeometry g, int i) {
        return new double[]{g.ux[i], g.uy[i], g.uz[i]};
    }

    private static double[] pt(SlideGeometry g, int i, double phi, double r) {
        double s = Math.sin(phi) * r, u = -Math.cos(phi) * r;
        return new double[]{g.px[i] + g.sx[i] * s + g.ux[i] * u, g.py[i] + g.sy[i] * s + g.uy[i] * u, g.pz[i] + g.sz[i] * s + g.uz[i] * u};
    }

    private static double[] tangentPhi(SlideGeometry g, int i, double phi) {
        double s = Math.cos(phi), u = Math.sin(phi);
        return new double[]{g.sx[i] * s + g.ux[i] * u, g.sy[i] * s + g.uy[i] * u, g.sz[i] * s + g.uz[i] * u};
    }

    // ------------------------------------------------------------------ drawing helpers

    private static void v(VertexConsumer c, PoseStack.Pose p, double x, double y, double z, int argb, float u, float vv, int light, double nx, double ny, double nz) {
        c.addVertex(p, (float) x, (float) y, (float) z).setColor(argb).setUv(u, vv).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(p, (float) nx, (float) ny, (float) nz);
    }

    /** Box along three unit axes (a, b, n) around centre m with half sizes ha, hb, hn. */
    private static void obox(VertexConsumer c, PoseStack.Pose p, double[] m, double[] a, double[] b, double[] n,
                             double ha, double hb, double hn, int col, int light) {
        double[][] ax = {a, b, n};
        double[] h = {ha, hb, hn};
        for (int f = 0; f < 3; f++) {
            double[] d = ax[f], e1 = ax[(f + 1) % 3], e2 = ax[(f + 2) % 3];
            double h0 = h[f], h1 = h[(f + 1) % 3], h2 = h[(f + 2) % 3];
            int shadeCol = shade(col, f == 0 ? 0.9F : (f == 1 ? 1.0F : 0.78F));
            for (double sg : new double[]{1.0D, -1.0D}) {
                double[] fc = add(m, scale(d, sg * h0));
                double[] q0 = add(fc, add(scale(e1, -h1), scale(e2, -h2)));
                double[] q1 = add(fc, add(scale(e1, h1), scale(e2, -h2)));
                double[] q2 = add(fc, add(scale(e1, h1), scale(e2, h2)));
                double[] q3 = add(fc, add(scale(e1, -h1), scale(e2, h2)));
                double nx = d[0] * sg, ny = d[1] * sg, nz = d[2] * sg;
                v(c, p, q0[0], q0[1], q0[2], shadeCol, 0, 1, light, nx, ny, nz);
                v(c, p, q1[0], q1[1], q1[2], shadeCol, 1, 1, light, nx, ny, nz);
                v(c, p, q2[0], q2[1], q2[2], shadeCol, 1, 0, light, nx, ny, nz);
                v(c, p, q3[0], q3[1], q3[2], shadeCol, 0, 0, light, nx, ny, nz);
            }
        }
    }

    private static final double[] X = {1, 0, 0}, Y = {0, 1, 0}, Z = {0, 0, 1};

    private static void box(VertexConsumer c, PoseStack.Pose p, double x0, double y0, double z0, double x1, double y1, double z1, int col, int light) {
        obox(c, p, new double[]{(x0 + x1) * 0.5D, (y0 + y1) * 0.5D, (z0 + z1) * 0.5D}, X, Y, Z,
                Math.abs(x1 - x0) * 0.5D, Math.abs(y1 - y0) * 0.5D, Math.abs(z1 - z0) * 0.5D, col, light);
    }

    private static void cube(VertexConsumer c, PoseStack.Pose p, double[] m, double h, int col, int light) {
        obox(c, p, m, X, Y, Z, h, h, h, col, light);
    }

    /** Square beam of width w from a to b. */
    private static void beam(VertexConsumer c, PoseStack.Pose p, double[] a, double[] b, double w, int col, int light) {
        double[] d = sub(b, a);
        double len = len(d);
        if (len < 1.0E-4D) return;
        d = scale(d, 1.0D / len);
        double[] ref = Math.abs(d[1]) < 0.9D ? Y : X;
        double[] e1 = norm(cross(d, ref)), e2 = cross(d, e1);
        obox(c, p, scale(add(a, b), 0.5D), d, e1, e2, len * 0.5D, w * 0.5D, w * 0.5D, col, light);
    }

    /** Horizontal-ish crossbar of square section (same as beam, kept for readability). */
    private static void box2(VertexConsumer c, PoseStack.Pose p, double[] a, double[] b, double w, int col, int light) {
        beam(c, p, a, b, w, col, light);
    }

    /** Spiral face disc: {cx,cy,cz, ax.., ay.., normal.., radius, angle, light}. */
    private static void disc(VertexConsumer c, PoseStack.Pose p, double[] d) {
        double[] m = {d[0], d[1], d[2]}, a = {d[3], d[4], d[5]}, b = {d[6], d[7], d[8]}, n = {d[9], d[10], d[11]};
        double r = d[12], ang = d[13];
        int light = (int) d[14];
        double ca = Math.cos(ang), sa = Math.sin(ang);
        double[] e1 = add(scale(a, ca), scale(b, sa)), e2 = add(scale(a, -sa), scale(b, ca));
        double[] q0 = add(m, add(scale(e1, -r), scale(e2, -r))), q1 = add(m, add(scale(e1, r), scale(e2, -r)));
        double[] q2 = add(m, add(scale(e1, r), scale(e2, r))), q3 = add(m, add(scale(e1, -r), scale(e2, r)));
        v(c, p, q0[0], q0[1], q0[2], 0xFFFFFFFF, 0, 1, light, n[0], n[1], n[2]);
        v(c, p, q1[0], q1[1], q1[2], 0xFFFFFFFF, 1, 1, light, n[0], n[1], n[2]);
        v(c, p, q2[0], q2[1], q2[2], 0xFFFFFFFF, 1, 0, light, n[0], n[1], n[2]);
        v(c, p, q3[0], q3[1], q3[2], 0xFFFFFFFF, 0, 0, light, n[0], n[1], n[2]);
    }

    private static void haloQuad(VertexConsumer c, PoseStack.Pose p, float s, int argb) {
        if ((argb >>> 24) == 0) return;
        int l = 0xF000F0;
        v(c, p, -s, -s, 0, argb, 0, 1, l, 0, 0, 1);
        v(c, p, s, -s, 0, argb, 1, 1, l, 0, 0, 1);
        v(c, p, s, s, 0, argb, 1, 0, l, 0, 0, 1);
        v(c, p, -s, s, 0, argb, 0, 0, l, 0, 0, 1);
    }

    static int shade(int argb, float f) {
        int a = argb >>> 24, r = (int) (((argb >> 16) & 255) * f), g = (int) (((argb >> 8) & 255) * f), b = (int) ((argb & 255) * f);
        return (a << 24) | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] scale(double[] a, double s) {
        return new double[]{a[0] * s, a[1] * s, a[2] * s};
    }

    private static double len(double[] a) {
        return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
    }

    private static double[] norm(double[] a) {
        return scale(a, 1.0D / len(a));
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    /** Shader packs: additive glow turns into dark discs there, so use soft translucent glow with alpha instead. */
    static final class Glow {
        private static boolean resolved, cached;
        private static Object api;
        private static java.lang.reflect.Method inUse;
        private static long checkedAt;

        static boolean shadersOn() {
            long now = System.nanoTime();
            if (checkedAt != 0L && now - checkedAt < 500_000_000L) return cached;
            checkedAt = now;
            if (!resolved) {
                resolved = true;
                try {
                    Class<?> cl = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    api = cl.getMethod("getInstance").invoke(null);
                    inUse = cl.getMethod("isShaderPackInUse");
                } catch (Throwable ignored) {
                    api = null;
                }
            }
            try {
                cached = api != null && Boolean.TRUE.equals(inUse.invoke(api));
            } catch (Throwable t) {
                cached = false;
            }
            return cached;
        }

        /** "Darker = weaker" additive colour -> full-brightness colour with the weakness in alpha. */
        static int toAlpha(int argb) {
            int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
            int m = Math.max(r, Math.max(g, b));
            if (m <= 0) return 0;
            float k = 255.0F / m;
            int a = Math.min(255, Math.round(m * 0.95F));
            return (a << 24) | (Math.min(255, Math.round(r * k)) << 16) | (Math.min(255, Math.round(g * k)) << 8) | Math.min(255, Math.round(b * k));
        }
    }
}

package com.example.waterslide.client;

import com.example.waterslide.SlideGeometry;
import it.unimi.dsi.fastutil.floats.FloatArrayList;

import java.util.ArrayList;
import java.util.List;

/**
 * The slide's 3D model, generated once from SlideGeometry (local space).
 * Vertex layout (9 floats): x y z u v nx ny nz probe — probe = index of the light sample used for that vertex.
 * Two meshes: solid (fibreglass trough, ribs, lips, steel supports, concrete footings, deck, ladder)
 * and water (thin translucent stream along the floor, animated by switching texture frames).
 */
public final class SlideMesh {
    private static final SlideMesh[] CACHE = new SlideMesh[com.example.waterslide.SlideTypes.count()];

    // texture columns in slide.png (64 px wide)
    private static final int INNER = 0, OUTER = 16, RIB = 32, METAL = 40, CONCRETE = 48, DECK = 56;
    private static final int[] WIDTH = new int[64];

    static {
        for (int i = 0; i < 64; i++) WIDTH[i] = i < 32 ? 16 : 8;
    }

    private static final int NI = 28;     // segments across the inner surface
    private static final int NO = 18;     // segments across the outer surface
    private static final double RIB_OUT = 0.13, RIB_HALF = 0.15, BEAD = 0.16;
    private static final int RING_EVERY = 4;   // one ring per block of slide (centreline)
    private static final int RIB_EVERY = 4;    // one rib every 4 blocks

    /** Floats per vertex: x y z u v nx ny nz probe r g b. */
    public static final int STRIDE = 12;

    public final float[] solid;
    public final float[] water;
    public final List<double[]> probes = new ArrayList<>();

    private final SlideGeometry g;
    /** Current vertex tint (r,g,b 0..1): trough colour / stripe, white for steel and concrete. */
    private float cr = 1, cg = 1, cb = 1;
    private final FloatArrayList solidBuf = new FloatArrayList();
    private final FloatArrayList waterBuf = new FloatArrayList();

    /** Built the first time a slide of this type is drawn. */
    public static SlideMesh get(int type) {
        int t = type >= 0 && type < CACHE.length ? type : 0;
        if (CACHE[t] == null) CACHE[t] = new SlideMesh(com.example.waterslide.SlideTypes.get(t));
        return CACHE[t];
    }

    private SlideMesh(SlideGeometry geo) {
        g = geo;
        buildProbes();
        List<Integer> rings = new ArrayList<>();
        for (int i = 0; i < g.n; i += RING_EVERY) rings.add(i);
        if (rings.get(rings.size() - 1) != g.n - 1) rings.add(g.n - 1);

        for (int r = 0; r + 1 < rings.size(); r++) {
            int a = rings.get(r), b = rings.get(r + 1);
            tint(a, 1.0f);
            arcStrip(a, b, g.ri, NI, true, INNER);
            arcStrip(a, b, g.ro, NO, false, OUTER);
            lip(a, b, 1);
            lip(a, b, -1);
            water(a, b);
        }
        tint(rings.get(0), 0.9f);
        endCap(rings.get(0), -1);
        endCap(rings.get(rings.size() - 1), 1);
        for (int r = RIB_EVERY; r < rings.size() - 2; r += RIB_EVERY) {
            tint(rings.get(r), 0.85f);
            rib(rings.get(r));
        }
        // steel, concrete and the platform keep their texture colours (or the type's support tint)
        int st = g.spec.supportTint();
        cr = ((st >> 16) & 255) / 255f;
        cg = ((st >> 8) & 255) / 255f;
        cb = (st & 255) / 255f;
        supports();

        solid = solidBuf.toFloatArray();
        water = waterBuf.toFloatArray();
    }

    /** Trough colour for the piece starting at sample i: base colour, or the stripe colour on alternate bands. */
    private void tint(int i, float shade) {
        com.example.waterslide.SlideSpec s = g.spec;
        int rgb = s.color();
        if (s.stripeLength() > 0 && ((int) Math.floor(g.arcLen[i] / s.stripeLength()) & 1) == 1) rgb = s.stripeColor();
        cr = ((rgb >> 16) & 255) / 255f * shade;
        cg = ((rgb >> 8) & 255) / 255f * shade;
        cb = (rgb & 255) / 255f * shade;
    }

    // ------------------------------------------------------------------ trough

    private double[] pt(int i, double phi, double r) {
        double s = Math.sin(phi) * r, u = -Math.cos(phi) * r;
        return new double[]{g.px[i] + g.sx[i] * s + g.ux[i] * u, g.py[i] + g.sy[i] * s + g.uy[i] * u, g.pz[i] + g.sz[i] * s + g.uz[i] * u};
    }

    private double[] pt(int i, double phi, double r, double alongOffset) {
        double[] p = pt(i, phi, r);
        return new double[]{p[0] + g.tx[i] * alongOffset, p[1] + g.ty[i] * alongOffset, p[2] + g.tz[i] * alongOffset};
    }

    /** Outward normal of the circle at phi. */
    private double[] radial(int i, double phi) {
        double s = Math.sin(phi), u = -Math.cos(phi);
        return new double[]{g.sx[i] * s + g.ux[i] * u, g.sy[i] * s + g.uy[i] * u, g.sz[i] * s + g.uz[i] * u};
    }

    private double[] tangentPhi(int i, double phi) {
        double s = Math.cos(phi), u = Math.sin(phi);
        return new double[]{g.sx[i] * s + g.ux[i] * u, g.sy[i] * s + g.uy[i] * u, g.sz[i] * s + g.uz[i] * u};
    }

    private void arcStrip(int a, int b, double r, int segs, boolean inner, int mat) {
        for (int k = 0; k < segs; k++) {
            double p0 = -g.phi + 2 * g.phi * k / segs;
            double p1 = -g.phi + 2 * g.phi * (k + 1) / segs;
            float u0 = u(mat, (double) k / segs), u1 = u(mat, (double) (k + 1) / segs);
            double sign = inner ? -1 : 1;
            quad(solidBuf,
                    pt(a, p0, r), scale(radial(a, p0), sign), u0, 0.2f,
                    pt(a, p1, r), scale(radial(a, p1), sign), u1, 0.2f,
                    pt(b, p1, r), scale(radial(b, p1), sign), u1, 0.8f,
                    pt(b, p0, r), scale(radial(b, p0), sign), u0, 0.8f);
        }
    }

    /** Flat top of the wall plus a small rolled bead on the outside, like a fibreglass flange. */
    private void lip(int a, int b, int side) {
        double phi = side * g.phi;
        double[] na = scale(tangentPhi(a, phi), side), nb = scale(tangentPhi(b, phi), side);
        float uu = u(RIB, 0.5);
        quad(solidBuf, pt(a, phi, g.ri), na, uu, 0.2f, pt(a, phi, g.ro + BEAD), na, uu, 0.2f,
                pt(b, phi, g.ro + BEAD), nb, uu, 0.8f, pt(b, phi, g.ri), nb, uu, 0.8f);
        // bead: short outer face and underside of the flange
        double phiDown = phi - side * 0.06;
        double[] oa = radial(a, phi), ob = radial(b, phi);
        quad(solidBuf, pt(a, phi, g.ro + BEAD), oa, uu, 0.2f, pt(a, phiDown, g.ro + BEAD), oa, uu, 0.2f,
                pt(b, phiDown, g.ro + BEAD), ob, uu, 0.8f, pt(b, phi, g.ro + BEAD), ob, uu, 0.8f);
        double[] da = scale(tangentPhi(a, phi), -side), db = scale(tangentPhi(b, phi), -side);
        quad(solidBuf, pt(a, phiDown, g.ro + BEAD), da, uu, 0.2f, pt(a, phiDown, g.ro), da, uu, 0.2f,
                pt(b, phiDown, g.ro), db, uu, 0.8f, pt(b, phiDown, g.ro + BEAD), db, uu, 0.8f);
    }

    private void endCap(int i, int dir) {
        double[] n = {g.tx[i] * dir, g.ty[i] * dir, g.tz[i] * dir};
        float uu = u(RIB, 0.5);
        for (int k = 0; k < NI; k++) {
            double p0 = -g.phi + 2 * g.phi * k / NI;
            double p1 = -g.phi + 2 * g.phi * (k + 1) / NI;
            quad(solidBuf, pt(i, p0, g.ri), n, uu, 0.2f, pt(i, p1, g.ri), n, uu, 0.2f,
                    pt(i, p1, g.ro), n, uu, 0.8f, pt(i, p0, g.ro), n, uu, 0.8f);
        }
    }

    /** Stiffening rib around the outside of the trough. */
    private void rib(int i) {
        double r0 = g.ro, r1 = g.ro + RIB_OUT;
        double[] back = {-g.tx[i], -g.ty[i], -g.tz[i]}, fwd = {g.tx[i], g.ty[i], g.tz[i]};
        for (int k = 0; k < NO; k++) {
            double p0 = -g.phi + 2 * g.phi * k / NO;
            double p1 = -g.phi + 2 * g.phi * (k + 1) / NO;
            float u0 = u(RIB, (double) k / NO), u1 = u(RIB, (double) (k + 1) / NO);
            double[] n0 = radial(i, p0), n1 = radial(i, p1);
            quad(solidBuf, pt(i, p0, r1, -RIB_HALF), n0, u0, 0.2f, pt(i, p1, r1, -RIB_HALF), n1, u1, 0.2f,
                    pt(i, p1, r1, RIB_HALF), n1, u1, 0.8f, pt(i, p0, r1, RIB_HALF), n0, u0, 0.8f);
            quad(solidBuf, pt(i, p0, r0, -RIB_HALF), back, u0, 0.2f, pt(i, p1, r0, -RIB_HALF), back, u1, 0.2f,
                    pt(i, p1, r1, -RIB_HALF), back, u1, 0.8f, pt(i, p0, r1, -RIB_HALF), back, u0, 0.8f);
            quad(solidBuf, pt(i, p0, r0, RIB_HALF), fwd, u0, 0.2f, pt(i, p0, r1, RIB_HALF), fwd, u0, 0.8f,
                    pt(i, p1, r1, RIB_HALF), fwd, u1, 0.8f, pt(i, p1, r0, RIB_HALF), fwd, u1, 0.2f);
        }
    }

    /** Thin water stream on the floor, sitting where moving water would ride (higher on the outer wall in curves). */
    private void water(int a, int b) {
        int segs = 8;
        double w = 0.42;
        double ca = waterCenter(a), cb = waterCenter(b);
        double r = g.ri - 0.012;
        for (int k = 0; k < segs; k++) {
            double t0 = (double) k / segs, t1 = (double) (k + 1) / segs;
            double a0 = ca - w + 2 * w * t0, a1 = ca - w + 2 * w * t1, b0 = cb - w + 2 * w * t0, b1 = cb - w + 2 * w * t1;
            float u0 = (float) (0.004 + t0 * 0.242), u1 = (float) (0.004 + t1 * 0.242);
            quad(waterBuf, pt(a, a0, r), scale(radial(a, a0), -1), u0, 0f, pt(a, a1, r), scale(radial(a, a1), -1), u1, 0f,
                    pt(b, b1, r), scale(radial(b, b1), -1), u1, 1f, pt(b, b0, r), scale(radial(b, b0), -1), u0, 1f);
        }
    }

    private double waterCenter(int i) {
        double lat = g.targetLat(0.6, g.kappa[i], g.bank[i]);
        double limit = g.phi - 0.42 - 0.08;   // keep the whole stream inside the walls
        return SlideGeometry.clamp(Math.asin(SlideGeometry.clamp(lat / g.ri, -0.95, 0.95)), -limit, limit);
    }

    // ------------------------------------------------------------------ supports

    private void supports() {
        for (double[] p : g.poles) {
            cylinder(p[0], p[1], p[2], p[3], p[4], METAL, 12);
            cylinder(p[0], p[1], p[3] - 0.05, p[3] + 0.12, p[4] + 0.08, METAL, 12);   // cap
        }
        for (double[] f : g.footings) cylinder(f[0], f[1], f[2], f[3], f[4], CONCRETE, 12);
        for (double[] b : g.beams) beam(b[0], b[1], b[2], b[3], b[4], b[5], b[6], METAL);
        for (double[] b : g.boxes) box(b[0], b[1], b[2], b[3], b[4], b[5], b[6] > 0.5 ? DECK : METAL);
    }

    private void cylinder(double cx, double cz, double y0, double y1, double r, int mat, int sides) {
        for (int k = 0; k < sides; k++) {
            double a0 = Math.PI * 2 * k / sides, a1 = Math.PI * 2 * (k + 1) / sides;
            double[] n0 = {Math.cos(a0), 0, Math.sin(a0)}, n1 = {Math.cos(a1), 0, Math.sin(a1)};
            float u0 = u(mat, (double) k / sides), u1 = u(mat, (double) (k + 1) / sides);
            double[] p00 = {cx + n0[0] * r, y0, cz + n0[2] * r}, p01 = {cx + n1[0] * r, y0, cz + n1[2] * r};
            double[] p10 = {cx + n0[0] * r, y1, cz + n0[2] * r}, p11 = {cx + n1[0] * r, y1, cz + n1[2] * r};
            quad(solidBuf, p00, n0, u0, 0.9f, p10, n0, u0, 0.1f, p11, n1, u1, 0.1f, p01, n1, u1, 0.9f);
            double[] up = {0, 1, 0}, c = {cx, y1, cz};
            float um = u(mat, 0.5);
            quad(solidBuf, c, up, um, 0.5f, p11, up, u1, 0.5f, p10, up, u0, 0.5f, c, up, um, 0.5f);
        }
    }

    private void beam(double x0, double y0, double z0, double x1, double y1, double z1, double r, int mat) {
        double[] d = SlideGeometryAccess.norm(x1 - x0, y1 - y0, z1 - z0);
        double[] ref = Math.abs(d[1]) < 0.9 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
        double[] e1 = SlideGeometryAccess.norm(SlideGeometryAccess.cross(d, ref));
        double[] e2 = SlideGeometryAccess.cross(d, e1);
        double[][] corner = new double[4][];
        double[][] normal = {e1, e2, scale(e1, -1), scale(e2, -1)};
        for (int k = 0; k < 4; k++) {
            double ca = (k == 0 || k == 3) ? 1 : -1, cb = (k < 2) ? 1 : -1;
            corner[k] = new double[]{(e1[0] * ca + e2[0] * cb) * r, (e1[1] * ca + e2[1] * cb) * r, (e1[2] * ca + e2[2] * cb) * r};
        }
        // faces between corners k and k+1 (corner order: (+,+) (-,+) (-,-) (+,-))
        double[][] faceN = {e2, scale(e1, -1), scale(e2, -1), e1};
        for (int k = 0; k < 4; k++) {
            double[] c0 = corner[k], c1 = corner[(k + 1) % 4];
            double[] n = faceN[k];
            float u0 = u(mat, k / 4.0), u1 = u(mat, (k + 1) / 4.0);
            quad(solidBuf, add(x0, y0, z0, c0), n, u0, 0.9f, add(x0, y0, z0, c1), n, u1, 0.9f,
                    add(x1, y1, z1, c1), n, u1, 0.1f, add(x1, y1, z1, c0), n, u0, 0.1f);
        }
        double[] nd = scale(d, -1);
        float um = u(mat, 0.5);
        quad(solidBuf, add(x0, y0, z0, corner[0]), nd, um, 0.5f, add(x0, y0, z0, corner[3]), nd, um, 0.5f,
                add(x0, y0, z0, corner[2]), nd, um, 0.5f, add(x0, y0, z0, corner[1]), nd, um, 0.5f);
        quad(solidBuf, add(x1, y1, z1, corner[0]), d, um, 0.5f, add(x1, y1, z1, corner[1]), d, um, 0.5f,
                add(x1, y1, z1, corner[2]), d, um, 0.5f, add(x1, y1, z1, corner[3]), d, um, 0.5f);
    }

    private void box(double x0, double y0, double z0, double x1, double y1, double z1, int mat) {
        float a = u(mat, 0.1), b = u(mat, 0.9);
        double[][] n = {{0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        quad(solidBuf, new double[]{x0, y1, z0}, n[0], a, 0.1f, new double[]{x0, y1, z1}, n[0], a, 0.9f, new double[]{x1, y1, z1}, n[0], b, 0.9f, new double[]{x1, y1, z0}, n[0], b, 0.1f);
        quad(solidBuf, new double[]{x0, y0, z0}, n[1], a, 0.1f, new double[]{x1, y0, z0}, n[1], b, 0.1f, new double[]{x1, y0, z1}, n[1], b, 0.9f, new double[]{x0, y0, z1}, n[1], a, 0.9f);
        quad(solidBuf, new double[]{x0, y0, z0}, n[2], a, 0.9f, new double[]{x0, y1, z0}, n[2], a, 0.1f, new double[]{x1, y1, z0}, n[2], b, 0.1f, new double[]{x1, y0, z0}, n[2], b, 0.9f);
        quad(solidBuf, new double[]{x0, y0, z1}, n[3], a, 0.9f, new double[]{x1, y0, z1}, n[3], b, 0.9f, new double[]{x1, y1, z1}, n[3], b, 0.1f, new double[]{x0, y1, z1}, n[3], a, 0.1f);
        quad(solidBuf, new double[]{x0, y0, z0}, n[4], a, 0.9f, new double[]{x0, y0, z1}, n[4], b, 0.9f, new double[]{x0, y1, z1}, n[4], b, 0.1f, new double[]{x0, y1, z0}, n[4], a, 0.1f);
        quad(solidBuf, new double[]{x1, y0, z0}, n[5], a, 0.9f, new double[]{x1, y1, z0}, n[5], a, 0.1f, new double[]{x1, y1, z1}, n[5], b, 0.1f, new double[]{x1, y0, z1}, n[5], b, 0.9f);
    }

    // ------------------------------------------------------------------ light probes

    private void buildProbes() {
        for (int i = 0; i < g.n; i += 4) {
            probes.add(new double[]{g.px[i] + g.ux[i] * 0.2, g.py[i] + g.uy[i] * 0.2, g.pz[i] + g.uz[i] * 0.2});
        }
        for (double[] p : g.poles) {
            for (double y = 0.5; y < p[3]; y += 3.0) probes.add(new double[]{p[0] + 1.0, y, p[1]});
        }
        for (double[] f : g.footings) probes.add(new double[]{f[0] + f[4] + 0.3, 0.5, f[1]});
        double dx = (g.deckX0 + g.deckX1) * 0.5, dz = (g.deckZ0 + g.deckZ1) * 0.5;
        probes.add(new double[]{dx, g.deckY + 0.5, dz});
        probes.add(new double[]{dx, g.deckY - 1.0, dz});
    }

    private float probe(double[] p) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < probes.size(); i++) {
            double[] q = probes.get(i);
            double d = (p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1]) + (p[2] - q[2]) * (p[2] - q[2]);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ helpers

    private static float u(int col, double t) {
        int w = WIDTH[col];
        return (float) ((col + 0.5 + t * (w - 1)) / 64.0);
    }

    private void quad(FloatArrayList buf, double[] p0, double[] n0, float u0, float v0, double[] p1, double[] n1, float u1, float v1,
                      double[] p2, double[] n2, float u2, float v2, double[] p3, double[] n3, float u3, float v3) {
        vertex(buf, p0, n0, u0, v0);
        vertex(buf, p1, n1, u1, v1);
        vertex(buf, p2, n2, u2, v2);
        vertex(buf, p3, n3, u3, v3);
    }

    private void vertex(FloatArrayList buf, double[] p, double[] n, float u, float v) {
        buf.add((float) p[0]);
        buf.add((float) p[1]);
        buf.add((float) p[2]);
        buf.add(u);
        buf.add(v);
        buf.add((float) n[0]);
        buf.add((float) n[1]);
        buf.add((float) n[2]);
        buf.add(probe(p));
        int wt = g.spec.waterTint();
        buf.add(buf == waterBuf ? ((wt >> 16) & 255) / 255f : cr);
        buf.add(buf == waterBuf ? ((wt >> 8) & 255) / 255f : cg);
        buf.add(buf == waterBuf ? (wt & 255) / 255f : cb);
    }

    private static double[] scale(double[] v, double s) {
        return new double[]{v[0] * s, v[1] * s, v[2] * s};
    }

    private static double[] add(double x, double y, double z, double[] o) {
        return new double[]{x + o[0], y + o[1], z + o[2]};
    }

    /** Small vector helpers (kept here so the client mesh doesn't depend on package-private geometry code). */
    private static final class SlideGeometryAccess {
        static double[] norm(double x, double y, double z) {
            double l = Math.sqrt(x * x + y * y + z * z);
            return new double[]{x / l, y / l, z / l};
        }

        static double[] norm(double[] v) {
            return norm(v[0], v[1], v[2]);
        }

        static double[] cross(double[] a, double[] b) {
            return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
        }
    }
}

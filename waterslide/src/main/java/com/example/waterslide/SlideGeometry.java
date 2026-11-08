package com.example.waterslide;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape of one slide type, built once from its SlideSpec (no model files).
 *
 * Local space: origin = centre of the anchor block at ground level, +Z = away from the player who placed it.
 * The trough is an open "U": a circular arc of inner radius ri from -phi to +phi (0 = straight down), banked into
 * curves. The centreline (px,py,pz) is the centre of that circle.
 * The renderer, the collision voxels and the slide physics all use this, so they always agree.
 */
public final class SlideGeometry {
    /** Gap between the smooth inner surface and the voxel collision behind it. */
    public static final double COLLISION_GAP = 0.14;
    /** Collision voxel resolution: 4 per block. */
    public static final int RES = 4;

    public final SlideSpec spec;
    public final double ri, ro, phi, latMax;
    public final int n;
    public final double step;
    public final double length;
    public final double[] px, py, pz, tx, ty, tz, sx, sy, sz, ux, uy, uz, kappa, bank, arcLen;
    public final double deckY, deckX0, deckX1, deckZ0, deckZ1;

    /** Supports, all in local space. */
    public final List<double[]> beams = new ArrayList<>();      // x0,y0,z0,x1,y1,z1,radius
    public final List<double[]> poles = new ArrayList<>();      // x,z,y0,y1,radius
    public final List<double[]> footings = new ArrayList<>();   // x,z,y0,y1,radius
    public final List<double[]> boxes = new ArrayList<>();      // x0,y0,z0,x1,y1,z1, material(0 metal, 1 deck)

    /** Collision voxels (packed local voxel indices, RES per block). */
    final LongArrayList troughVox = new LongArrayList();
    final LongArrayList solidVox = new LongArrayList();

    /** Route pieces resolved to positions: {kind, s0, len, x0, z0, hx, hz, cx, cz, radius, sign, grade, flags}. */
    private final List<double[]> pieces = new ArrayList<>();

    public SlideGeometry(SlideSpec spec) {
        this(spec, true);
    }

    /** @param full false = route and frames only (no supports, no collision voxels): to measure a section quickly */
    SlideGeometry(SlideSpec spec, boolean full) {
        this.spec = spec;
        ri = spec.ri;
        ro = spec.ro;
        phi = Math.toRadians(spec.phiDeg);
        latMax = ri * Math.sin(phi - Math.toRadians(14));
        resolveRoute();
        length = spec.length();
        int samples = (int) Math.ceil(length / 0.25);
        int m = samples * 5 + 1;
        double h = length / (m - 1);
        step = h * 5;

        // ---- horizontal route and smoothed slope
        double[] fx = new double[m], fz = new double[m], g = new double[m], fy = new double[m];
        for (int i = 0; i < m; i++) {
            double[] r = route(i * h);
            fx[i] = r[0];
            fz[i] = r[1];
            g[i] = r[2];
        }
        g = smooth(g, (int) Math.round(spec.gradeSmooth / h));
        fy[m - 1] = spec.endBottom + ri;
        for (int i = m - 2; i >= 0; i--) fy[i] = fy[i + 1] + (g[i] + g[i + 1]) * 0.5 * h;

        // ---- frames on the fine grid
        double[] ftx = new double[m], fty = new double[m], ftz = new double[m];
        for (int i = 0; i < m; i++) {
            int a = Math.max(0, i - 1), b = Math.min(m - 1, i + 1);
            double dx = fx[b] - fx[a], dy = fy[b] - fy[a], dz = fz[b] - fz[a];
            double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
            ftx[i] = dx / l;
            fty[i] = dy / l;
            ftz[i] = dz / l;
        }
        double[] fk = new double[m];
        for (int i = 0; i < m; i++) {
            int a = Math.max(0, i - 1), b = Math.min(m - 1, i + 1);
            double[] s0 = side(ftx[i], ftz[i]);
            double dtx = ftx[b] - ftx[a], dtz = ftz[b] - ftz[a];
            fk[i] = (dtx * s0[0] + dtz * s0[2]) / ((b - a) * h);
        }
        fk = smooth(fk, (int) Math.round(Math.max(2.0, ri * 1.4) / h));
        double[] fsx = new double[m], fsy = new double[m], fsz = new double[m], fux = new double[m], fuy = new double[m], fuz = new double[m], fb = new double[m];
        for (int i = 0; i < m; i++) {
            double b = clamp(fk[i] * spec.bankPerCurve, -0.45, 0.45);
            fb[i] = b;
            double[] s0 = side(ftx[i], ftz[i]);
            double[] u0 = cross(s0[0], s0[1], s0[2], ftx[i], fty[i], ftz[i]);
            double cb = Math.cos(b), sb = Math.sin(b);
            fux[i] = u0[0] * cb + s0[0] * sb;
            fuy[i] = u0[1] * cb + s0[1] * sb;
            fuz[i] = u0[2] * cb + s0[2] * sb;
            fsx[i] = s0[0] * cb - u0[0] * sb;
            fsy[i] = s0[1] * cb - u0[1] * sb;
            fsz[i] = s0[2] * cb - u0[2] * sb;
        }

        // ---- public samples every 5 fine steps
        n = samples + 1;
        px = new double[n]; py = new double[n]; pz = new double[n];
        tx = new double[n]; ty = new double[n]; tz = new double[n];
        sx = new double[n]; sy = new double[n]; sz = new double[n];
        ux = new double[n]; uy = new double[n]; uz = new double[n];
        kappa = new double[n]; bank = new double[n]; arcLen = new double[n];
        for (int k = 0; k < n; k++) {
            int i = k * 5;
            px[k] = fx[i]; py[k] = fy[i]; pz[k] = fz[i];
            tx[k] = ftx[i]; ty[k] = fty[i]; tz[k] = ftz[i];
            sx[k] = fsx[i]; sy[k] = fsy[i]; sz[k] = fsz[i];
            ux[k] = fux[i]; uy[k] = fuy[i]; uz[k] = fuz[i];
            kappa[k] = fk[i]; bank[k] = fb[i]; arcLen[k] = i * h;
        }
        deckY = fy[0] - ri;
        double halfW = ro + spec.deckExtraWidth;
        deckX0 = spec.startX - halfW;
        deckX1 = spec.startX + halfW;
        deckZ0 = spec.startZ - spec.deckLength;
        deckZ1 = spec.startZ + 0.3;

        if (!full) return;
        buildSupports();
        buildVoxels(m, fx, fy, fz, fsx, fsy, fsz, fux, fuy, fuz);
    }

    // ------------------------------------------------------------------ route

    private void resolveRoute() {
        double x = spec.startX, z = spec.startZ, hx = 0, hz = 1, s = 0;
        for (double[] seg : spec.segments) {
            if (seg[0] == 0) {
                pieces.add(new double[]{0, s, seg[1], x, z, hx, hz, 0, 0, 0, 0, seg[3], seg[4]});
                x += hx * seg[1];
                z += hz * seg[1];
                s += seg[1];
            } else {
                double r = seg[1], ang = Math.toRadians(seg[2]), sign = Math.signum(ang);
                double lx = hz, lz = -hx;                         // left of heading
                double cx = x + lx * r * sign, cz = z + lz * r * sign;
                double len = r * Math.abs(ang);
                pieces.add(new double[]{1, s, len, x, z, hx, hz, cx, cz, r, sign, seg[3], seg[4]});
                double a = -sign * len / r;
                double[] p = rot(x - cx, z - cz, a);
                x = cx + p[0];
                z = cz + p[1];
                double[] hh = rot(hx, hz, a);
                hx = hh[0];
                hz = hh[1];
                s += len;
            }
        }
    }

    /** {x, z, grade} at horizontal distance s along the route. */
    private double[] route(double s) {
        double[] pc = pieces.get(pieces.size() - 1);
        for (double[] p : pieces) {
            if (s < p[1] + p[2]) {
                pc = p;
                break;
            }
        }
        double t = Math.max(0, s - pc[1]);
        if (pc[0] == 0) return new double[]{pc[3] + pc[5] * t, pc[4] + pc[6] * t, pc[11]};
        double[] p = rot(pc[3] - pc[7], pc[4] - pc[8], -pc[10] * t / pc[9]);
        return new double[]{pc[7] + p[0], pc[8] + p[1], pc[11]};
    }

    private static double[] rot(double x, double z, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{x * c - z * s, x * s + z * c};
    }

    /** Centreline position at arc length s (interpolated from samples). */
    public double[] posAt(double s) {
        double f = clamp(s / step, 0, n - 1);
        int i = Math.min(n - 2, (int) f);
        double t = f - i;
        return new double[]{px[i] + (px[i + 1] - px[i]) * t, py[i] + (py[i + 1] - py[i]) * t, pz[i] + (pz[i + 1] - pz[i]) * t};
    }

    // ------------------------------------------------------------------ supports (classic) and start platform (all)

    private void buildSupports() {
        for (double[] pc : pieces) {
            int flags = (int) pc[12];
            if (pc[0] == 1 && (flags & SlideSpec.POLE) != 0) {
                double cx = pc[7], cz = pc[8], r = pc[9];
                double top = posAt(pc[1])[1] - ro - 0.2;
                poles.add(new double[]{cx, cz, 0.0, top + 0.8, 0.5});
                footings.add(new double[]{cx, cz, 0.0, 1.2, 0.95});
                int quarters = (int) Math.round(pc[2] / r / (Math.PI / 2));
                for (int q = 0; q <= quarters; q++) {
                    double s = pc[1] + q * (Math.PI / 2) * r;
                    if (s > length - 1.0) continue;
                    double[] p = posAt(s);
                    double yb = p[1] - ro - 0.2;
                    double dx = p[0] - cx, dz = p[2] - cz, l = Math.hypot(dx, dz);
                    beams.add(new double[]{cx, yb, cz, cx + dx / l * (r + 2.5), yb, cz + dz / l * (r + 2.5), 0.18});
                    double qx = -dz / l * 3.0, qz = dx / l * 3.0, mx = cx + dx / l * r, mz = cz + dz / l * r;
                    beams.add(new double[]{mx - qx, yb - 0.06, mz - qz, mx + qx, yb - 0.06, mz + qz, 0.14});
                    if (yb - 6.0 > 1.5) beams.add(new double[]{cx, yb - 6.0, cz, cx + dx / l * 9.0, yb, cz + dz / l * 9.0, 0.12});
                }
            }
            if (pc[0] == 0 && (flags & SlideSpec.LEGS) != 0) {
                for (double t = 4.0; t < pc[2] - 1.0; t += 8.0) {
                    double[] p = posAt(pc[1] + t);
                    double top = p[1] - ro - 0.06;
                    double qx = pc[6] * 2.4, qz = -pc[5] * 2.4;
                    for (double side : new double[]{-1, 1}) {
                        if (top > 0.2) beams.add(new double[]{p[0] + qx * side, 0.0, p[2] + qz * side, p[0] + qx * side, top + 0.3, p[2] + qz * side, 0.12});
                        footings.add(new double[]{p[0] + qx * side, p[2] + qz * side, 0.0, 0.3, 0.3});
                    }
                    beams.add(new double[]{p[0] - qx * 1.35, top - 0.06, p[2] - qz * 1.35, p[0] + qx * 1.35, top - 0.06, p[2] + qz * 1.35, 0.13});
                }
            }
        }
        // start platform with railings (hangs at the top of the slide, no legs); continuation sections have none
        if (!spec.hasDeck()) return;
        boxes.add(new double[]{deckX0, deckY - 0.3, deckZ0, deckX1, deckY, deckZ1, 1});
        boxes.add(new double[]{deckX0, deckY - 0.45, deckZ0, deckX1, deckY - 0.3, deckZ0 + 0.2, 0});
        boxes.add(new double[]{deckX0, deckY - 0.45, deckZ1 - 0.2, deckX1, deckY - 0.3, deckZ1, 0});
        for (double x : new double[]{deckX0 + 0.1, deckX1 - 0.1}) {
            for (double z : new double[]{deckZ0 + 0.1, deckZ1 - 0.1, (deckZ0 + deckZ1) * 0.5}) {
                boxes.add(new double[]{x - 0.06, deckY, z - 0.06, x + 0.06, deckY + 1.1, z + 0.06, 0});
            }
        }
        for (double rh : new double[]{0.55, 1.1}) {
            boxes.add(new double[]{deckX0 + 0.04, deckY + rh - 0.05, deckZ0 + 0.04, deckX0 + 0.16, deckY + rh + 0.05, deckZ1 - 0.04, 0});
            boxes.add(new double[]{deckX1 - 0.16, deckY + rh - 0.05, deckZ0 + 0.04, deckX1 - 0.04, deckY + rh + 0.05, deckZ1 - 0.04, 0});
            boxes.add(new double[]{deckX0 + 0.04, deckY + rh - 0.05, deckZ0 + 0.04, deckX1 - 0.04, deckY + rh + 0.05, deckZ0 + 0.16, 0});
        }
    }

    // ------------------------------------------------------------------ projection (physics)

    public static final class Proj {
        public int i;
        public double cx, cy, cz, tx, ty, tz, sx, sy, sz, ux, uy, uz;
        public double along, lat, up, kappa, bank;
        public boolean beforeStart, afterEnd;
    }

    /** Closest point of the centreline to a local point. hint = last index or -1. */
    public Proj project(double x, double y, double z, int hint) {
        int best = nearest(x, y, z, hint < 0 ? 0 : Math.max(0, hint - 24), hint < 0 ? n - 1 : Math.min(n - 1, hint + 24));
        if (hint >= 0 && dist2(best, x, y, z) > 9.0) best = nearest(x, y, z, 0, n - 1);
        int j = Math.min(best, n - 2);
        double t = segT(j, x, y, z);
        if (t < 0 && j > 0 && best == j) {
            j--;
            t = segT(j, x, y, z);
        }
        Proj p = new Proj();
        p.beforeStart = j == 0 && t < 0;
        p.afterEnd = j == n - 2 && t > 1;
        t = clamp(t, 0, 1);
        p.i = t < 0.5 ? j : j + 1;
        p.cx = lerp(px[j], px[j + 1], t); p.cy = lerp(py[j], py[j + 1], t); p.cz = lerp(pz[j], pz[j + 1], t);
        double[] tv = norm(lerp(tx[j], tx[j + 1], t), lerp(ty[j], ty[j + 1], t), lerp(tz[j], tz[j + 1], t));
        double[] sv = norm(lerp(sx[j], sx[j + 1], t), lerp(sy[j], sy[j + 1], t), lerp(sz[j], sz[j + 1], t));
        double[] uv = norm(lerp(ux[j], ux[j + 1], t), lerp(uy[j], uy[j + 1], t), lerp(uz[j], uz[j + 1], t));
        p.tx = tv[0]; p.ty = tv[1]; p.tz = tv[2];
        p.sx = sv[0]; p.sy = sv[1]; p.sz = sv[2];
        p.ux = uv[0]; p.uy = uv[1]; p.uz = uv[2];
        p.kappa = lerp(kappa[j], kappa[j + 1], t);
        p.bank = lerp(bank[j], bank[j + 1], t);
        double dx = x - p.cx, dy = y - p.cy, dz = z - p.cz;
        p.along = dx * p.tx + dy * p.ty + dz * p.tz;
        p.lat = dx * p.sx + dy * p.sy + dz * p.sz;
        p.up = dx * p.ux + dy * p.uy + dz * p.uz;
        return p;
    }

    private int nearest(double x, double y, double z, int a, int b) {
        int best = a;
        double bd = Double.MAX_VALUE;
        for (int i = a; i <= b; i++) {
            double d = dist2(i, x, y, z);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private double dist2(int i, double x, double y, double z) {
        double dx = x - px[i], dy = y - py[i], dz = z - pz[i];
        return dx * dx + dy * dy + dz * dz;
    }

    private double segT(int j, double x, double y, double z) {
        double ax = px[j + 1] - px[j], ay = py[j + 1] - py[j], az = pz[j + 1] - pz[j];
        return ((x - px[j]) * ax + (y - py[j]) * ay + (z - pz[j]) * az) / (ax * ax + ay * ay + az * az);
    }

    /** Where a rider settles across the trough (banked frame) for a given speed (blocks/tick) and curve. */
    public double targetLat(double speed, double kappa, double bank) {
        double aLat = -kappa * speed * speed;
        double g = 0.08;
        // effective gravity (gravity + centrifugal) in the banked frame; the rider rests where it points
        double gS = aLat * Math.cos(bank) + g * Math.sin(bank);
        double gU = aLat * Math.sin(bank) - g * Math.cos(bank);
        double l = Math.sqrt(gS * gS + gU * gU);
        return clamp(ri * gS / l, -latMax, latMax);
    }

    // ------------------------------------------------------------------ collision voxels

    private void buildVoxels(int m, double[] fx, double[] fy, double[] fz, double[] fsx, double[] fsy, double[] fsz,
                             double[] fux, double[] fuy, double[] fuz) {
        // The collision shell sits a little BEHIND the visible inner surface: ragdolls are held on the smooth
        // surface by SableSlidePush, the blocky voxels are only a safety net they normally never touch.
        LongOpenHashSet trough = new LongOpenHashSet();
        int steps = (int) Math.ceil(2 * phi * (ro + 0.1) / 0.06);
        double[] radii = {ri + COLLISION_GAP, ri + COLLISION_GAP + 0.12, ro + 0.06};
        for (int i = 0; i < m; i += 2) {
            for (int k = 0; k <= steps; k++) {
                double a = -phi + 2 * phi * k / steps;
                double sp = Math.sin(a), cp = -Math.cos(a);
                for (double r : radii) {
                    double x = fx[i] + fsx[i] * r * sp + fux[i] * r * cp;
                    double y = fy[i] + fsy[i] * r * sp + fuy[i] * r * cp;
                    double z = fz[i] + fsz[i] * r * sp + fuz[i] * r * cp;
                    trough.add(vox(x, y, z));
                }
            }
        }
        troughVox.addAll(trough);

        LongOpenHashSet solid = new LongOpenHashSet();
        for (double[] p : poles) fillCylinder(solid, p[0], p[1], p[2], p[3], p[4] + 0.03);
        for (double[] f : footings) if (f[4] > 0.5) fillCylinder(solid, f[0], f[1], f[2], f[3], f[4]);
        if (spec.hasDeck()) {
            fillBox(solid, deckX0, deckY - 0.25, deckZ0, deckX1, deckY, deckZ1);
            fillBox(solid, deckX0, deckY, deckZ0 + 0.1, deckX0 + 0.25, deckY + 1.0, deckZ1);   // railings stop you falling off
            fillBox(solid, deckX1 - 0.25, deckY, deckZ0 + 0.1, deckX1, deckY + 1.0, deckZ1);
            fillBox(solid, deckX0, deckY, deckZ0, deckX1, deckY + 1.0, deckZ0 + 0.25);
        }
        solid.removeAll(trough);
        solidVox.addAll(solid);
    }

    private static void fillCylinder(LongOpenHashSet set, double cx, double cz, double y0, double y1, double r) {
        double st = 1.0 / RES / 2;
        for (double y = y0 + st; y < y1; y += st * 2) {
            for (double x = cx - r; x <= cx + r; x += st) {
                for (double z = cz - r; z <= cz + r; z += st) {
                    if ((x - cx) * (x - cx) + (z - cz) * (z - cz) <= r * r) set.add(vox(x, y, z));
                }
            }
        }
    }

    private static void fillBox(LongOpenHashSet set, double x0, double y0, double z0, double x1, double y1, double z1) {
        double st = 1.0 / RES / 2;
        for (double y = y0 + 0.01; y < y1; y += st) {
            for (double x = x0 + 0.01; x < x1; x += st) {
                for (double z = z0 + 0.01; z < z1; z += st) set.add(vox(x, y, z));
            }
        }
    }

    /** Local point -> packed voxel index (grid aligned with the world block grid). */
    static long vox(double x, double y, double z) {
        int vx = (int) Math.floor((x + 0.5) * RES), vy = (int) Math.floor(y * RES), vz = (int) Math.floor((z + 0.5) * RES);
        return ((long) (vx + 4096) << 26) | ((long) (vy + 4096) << 13) | (vz + 4096);
    }

    static int voxX(long k) {
        return (int) ((k >> 26) & 8191) - 4096;
    }

    static int voxY(long k) {
        return (int) ((k >> 13) & 8191) - 4096;
    }

    static int voxZ(long k) {
        return (int) (k & 8191) - 4096;
    }

    // ------------------------------------------------------------------ math

    private static double[] side(double tx, double tz) {
        // T x Up, normalised (horizontal)
        double x = -tz, z = tx;
        double l = Math.hypot(x, z);
        return new double[]{x / l, 0.0, z / l};
    }

    static double[] cross(double ax, double ay, double az, double bx, double by, double bz) {
        return new double[]{ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
    }

    static double[] norm(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    private static double[] smooth(double[] v, int w) {
        double[] out = new double[v.length];
        double sum = 0;
        int lo = 0, hi = -1;
        for (int i = 0; i < v.length; i++) {
            int a = Math.max(0, i - w), b = Math.min(v.length - 1, i + w);
            while (hi < b) sum += v[++hi];
            while (lo < a) sum -= v[lo++];
            out[i] = sum / (hi - lo + 1);
        }
        return out;
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public static double clamp(double v, double a, double b) {
        return v < a ? a : (v > b ? b : v);
    }
}

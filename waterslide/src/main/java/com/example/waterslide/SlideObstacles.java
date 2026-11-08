package com.example.waterslide;

import java.util.List;

/**
 * Timing and reach of the Dark Carnival obstacles, shared by the rider physics, the ragdoll push and the renderer
 * (so what you see is what hits you). Positions are distances along the route (same units as SlideGeometry.arcLen).
 */
public final class SlideObstacles {
    /** Half length of each kind's zone along the slide. */
    private static final double[] HALF = {1.1D, 0.7D, 1.0D, 0.7D, 0.9D, 0.9D, 0.5D};
    public static final float SPINNER_SPIN = 0.07F;    // rad per tick
    public static final double SPINNER_UP = 1.2D, SPINNER_ARM = 2.0D;
    /** Tunnel of terror: arches from s - TUNNEL_HALF to s + TUNNEL_HALF. */
    public static final double TUNNEL_HALF = 7.0D;
    /** After a ramp: the stretch where ragdolls fly freely (no "don't bounce out" damping). */
    public static final double JUMP_FLIGHT = 9.0D;

    // shared animation
    public static final float WHEEL_SPIN = 0.13F;      // rad per tick
    public static final int HAMMER_PERIOD = 46;        // ticks per full swing
    public static final float HAMMER_AMP = 1.05F;      // rad
    /** The hammer head is down in the trough while |angle| is below this. */
    public static final float HAMMER_HIT = 0.36F;

    // ride geometry of the decorations (relative to the trough centre, blocks)
    public static final double WHEEL_UP = 1.3D, WHEEL_R = 2.0D;
    public static final double HAMMER_PIVOT = 3.4D, HAMMER_ARM = 3.9D;
    public static final double HOOP_R = 2.95D;
    /** The ring of fire stands this far after the kicker (the slow slide jumps short). */
    public static final double FIRE_AHEAD = 3.0D, FIRE_UP = 1.6D, FIRE_R = 1.5D;

    private SlideObstacles() {
    }

    /** Index of the obstacle whose zone contains s, or -1. */
    public static int at(SlideSpec spec, double s) {
        List<double[]> obs = spec.obstacles;
        for (int i = 0; i < obs.size(); i++) {
            double[] o = obs.get(i);
            if (Math.abs(s - o[1]) <= HALF[(int) o[0]]) return i;
        }
        return -1;
    }

    /** True in the free-flight stretch right after a ramp. */
    public static boolean inJumpFlight(SlideSpec spec, double s) {
        for (double[] o : spec.obstacles) {
            if (o[0] == SlideSpec.OB_RAMP && s > o[1] && s < o[1] + JUMP_FLIGHT) return true;
        }
        return false;
    }

    /** Swing angle of hammer number idx (radians, 0 = hanging straight down into the trough). */
    public static double hammerAngle(double time, int idx) {
        return HAMMER_AMP * Math.sin((time + idx * 13.0D) * Math.PI * 2.0D / HAMMER_PERIOD);
    }

    /** Direction the hammer is swinging in (+1 / -1 across the slide). */
    public static double hammerDir(double time, int idx) {
        return Math.cos((time + idx * 13.0D) * Math.PI * 2.0D / HAMMER_PERIOD) >= 0.0D ? 1.0D : -1.0D;
    }

    /** Turnstile angle (bars along the slide at 0 and PI). */
    public static double spinnerAngle(double time, int idx) {
        return time * SPINNER_SPIN + idx * 0.7D;
    }

    /** The turnstile's bars stand across the slide right now. */
    public static boolean spinnerBlocks(double time, int idx) {
        return Math.abs(Math.sin(spinnerAngle(time, idx))) > 0.45D;
    }

    public static boolean hammerDown(double time, int idx) {
        return Math.abs(hammerAngle(time, idx)) < HAMMER_HIT;
    }

    // ------------------------------------------------------------------ real contact with the moving parts

    /** Obstacle kinds whose moving parts collide by their actual shape (the others act by zone). */
    public static boolean isSolid(int kind) {
        return kind == SlideSpec.OB_WHEEL || kind == SlideSpec.OB_HAMMER || kind == SlideSpec.OB_SPINNER || kind == SlideSpec.OB_BUMPERS;
    }

    /** Contact with a moving part: normal (out of the part, local space) and the part's own velocity there (blocks/tick). */
    public static final class Hit {
        public double nx, ny, nz, vx, vy, vz;

        Hit(double[] n, double[] v) {
            nx = n[0];
            ny = n[1];
            nz = n[2];
            vx = v[0];
            vy = v[1];
            vz = v[2];
        }
    }

    public static int index(SlideGeometry g, double s) {
        return Math.max(0, Math.min(g.n - 1, (int) Math.round(s / g.step)));
    }

    /** Clown head h (0/1) of a bumper pair: {position, push direction} in local space. */
    public static double[][] bumperHead(SlideGeometry g, double s, int ob, int h, double time) {
        double side = ob % 2 == 0 ? 1.0D : -1.0D;   // wall the heads sit on; they push riders to the other side
        int i = index(g, s - 0.5D + h);
        double phi = side * g.phi * 0.62D;
        double sp = Math.sin(phi) * g.ri, up = -Math.cos(phi) * g.ri;
        double[] wall = {g.px[i] + g.sx[i] * sp + g.ux[i] * up, g.py[i] + g.sy[i] * sp + g.uy[i] * up, g.pz[i] + g.sz[i] * sp + g.uz[i] * up};
        double[] in = norm(g.px[i] - wall[0], g.py[i] - wall[1], g.pz[i] - wall[2]);
        double bob = 0.35D + 0.25D * Math.sin(time * 0.35D + h * 2.1D + ob);
        double ext = 0.55D + bob;
        return new double[][]{{wall[0] + in[0] * ext, wall[1] + in[1] * ext, wall[2] + in[2] * ext}, in,
                {Math.cos(time * 0.35D + h * 2.1D + ob) * 0.25D * 0.35D}};
    }

    /**
     * Contact between a sphere (centre x,y,z local, radius r) and obstacle number ob at time t, or null.
     * Shapes match what the renderer draws: six wheel paddles, the hammer head, the two turnstile bars, the clown heads.
     */
    public static Hit contact(SlideGeometry g, int ob, double t, double x, double y, double z, double r) {
        double[] o = g.spec.obstacles.get(ob);
        int kind = (int) o[0];
        if (!isSolid(kind)) return null;
        int i = index(g, o[1]);
        double[] P = {g.px[i], g.py[i], g.pz[i]}, T = {g.tx[i], g.ty[i], g.tz[i]}, S = {g.sx[i], g.sy[i], g.sz[i]}, U = {g.ux[i], g.uy[i], g.uz[i]};
        double[] p = {x, y, z};
        switch (kind) {
            case SlideSpec.OB_WHEEL -> {
                double[] c = add(P, scale(U, WHEEL_UP));
                double[] rel = sub(p, c);
                if (Math.abs(dot(rel, S)) > 1.0D + r) return null;
                double dt = dot(rel, T), du = dot(rel, U);
                for (int k = 0; k < 6; k++) {
                    double a = -t * WHEEL_SPIN + k * Math.PI / 3.0D, ca = Math.cos(a), sa = Math.sin(a);
                    double along = Math.max(0.35D, Math.min(WHEEL_R, dt * ca + du * sa));
                    double ex = dt - along * ca, ey = du - along * sa, d = Math.sqrt(ex * ex + ey * ey);
                    if (d < r + 0.12D) {
                        double nx = d > 1.0E-4D ? ex / d : -sa, ny = d > 1.0E-4D ? ey / d : ca;
                        double w = -WHEEL_SPIN * along;   // paddle speed at that point
                        return new Hit(add(scale(T, nx), scale(U, ny)), add(scale(T, -sa * w), scale(U, ca * w)));
                    }
                }
                return null;
            }
            case SlideSpec.OB_HAMMER -> {
                double th = hammerAngle(t, ob);
                double[] dir = add(scale(S, Math.sin(th)), scale(U, -Math.cos(th)));
                double[] head = add(add(P, scale(U, HAMMER_PIVOT)), scale(dir, HAMMER_ARM));
                double[] rel = sub(p, head);
                // the head is a short barrel along the slide: test against its axis segment
                double along = Math.max(-0.6D, Math.min(0.6D, dot(rel, T)));
                double[] e = sub(rel, scale(T, along));
                double d = len(e);
                if (d >= r + 0.4D) return null;
                double dth = HAMMER_AMP * Math.cos((t + ob * 13.0D) * Math.PI * 2.0D / HAMMER_PERIOD) * Math.PI * 2.0D / HAMMER_PERIOD;
                double[] v = scale(add(scale(S, Math.cos(th)), scale(U, Math.sin(th))), HAMMER_ARM * dth);
                double[] n = d > 1.0E-4D ? scale(e, 1.0D / d) : norm(v[0], v[1], v[2]);
                return new Hit(n, v);
            }
            case SlideSpec.OB_SPINNER -> {
                double[] hub = add(P, scale(U, -g.ri + SPINNER_UP));
                double a = spinnerAngle(t, ob);
                double[] d = add(scale(T, Math.cos(a)), scale(S, Math.sin(a)));
                double[] rel = sub(p, hub);
                double along = Math.max(-SPINNER_ARM, Math.min(SPINNER_ARM, dot(rel, d)));
                double[] e = sub(rel, scale(d, along));
                double dist = len(e);
                if (dist >= r + 0.14D) return null;
                double[] v = scale(add(scale(T, -Math.sin(a)), scale(S, Math.cos(a))), SPINNER_SPIN * along);
                double[] n = dist > 1.0E-4D ? scale(e, 1.0D / dist) : norm(v[0] + 1.0E-6D, v[1], v[2]);
                return new Hit(n, v);
            }
            default -> {
                for (int h = 0; h < 2; h++) {
                    double[][] hb = bumperHead(g, o[1], ob, h, t);
                    double[] rel = sub(p, hb[0]);
                    double d = len(rel);
                    if (d >= r + 0.32D) continue;
                    double[] n = d > 1.0E-4D ? scale(rel, 1.0D / d) : hb[1];
                    // a spring head: always shoves along its spring, plus how fast it is popping out right now
                    double[] v = scale(hb[1], 0.12D + Math.max(0.0D, hb[2][0]));
                    return new Hit(norm(n[0] + hb[1][0], n[1] + hb[1][1], n[2] + hb[1][2]), v);
                }
                return null;
            }
        }
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

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double len(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    private static double[] norm(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return l < 1.0E-9D ? new double[]{0.0D, 1.0D, 0.0D} : new double[]{x / l, y / l, z / l};
    }
}

package com.example.wheelofwonder.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Orbiting close-up camera around a flying ragdoll (same behaviour as the Wheel of Wonder / kickmod follow camera):
 * always looks at the body, slowly circles it while it flies (with a gentle rise/sink and in/out), slides to a free
 * angle when blocks get in the way, and calms down once the body lies still.
 */
final class FollowOrbit {
    private static final double RADIUS = 2.6D;
    private static final double HEIGHT = 0.9D;
    private static final double DRIFT = Math.toRadians(38.0D);
    private static final double HEIGHT_WAVE = 0.55D;
    private static final double RADIUS_WAVE = 0.35D;

    Vec3 pos, look;
    boolean still;
    private double angle, target, height, heightTarget, radius;
    private Vec3 bodySmooth;
    private int plannedTick = -1;
    private double drift, time;

    void reset() {
        bodySmooth = null;
        still = false;
        pos = look = null;
    }

    /** @param startFrom where the camera was before (the orbit starts on that side of the body) */
    void update(Minecraft mc, Vec3 body, double extent, Vec3 startFrom, float dt, int tick) {
        if (bodySmooth == null || bodySmooth.distanceTo(body) > 12.0D) {
            Vec3 s = (startFrom != null ? startFrom : body.add(1, 0, 0)).subtract(body).multiply(1.0D, 0.0D, 1.0D);
            s = s.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : s.normalize();
            angle = target = Math.atan2(s.x, s.z);
            height = heightTarget = HEIGHT;
            radius = RADIUS;
            bodySmooth = body;
            plannedTick = -1;
            drift = 0.0D;
            time = 0.0D;
        }
        time += dt;
        double fov = Math.toRadians(Mth.clamp(mc.options.fov().get(), 30, 110));
        double fit = (extent + 0.6D) / Math.tan(fov / 2.0D);
        double wantRadius = Mth.clamp(Math.max(RADIUS, fit), RADIUS, 7.0D);
        radius += (wantRadius - radius) * (1.0D - Math.exp(-dt * 3.0D));

        // the camera's anchor follows the body closely (a fast fall must never leave it behind)
        bodySmooth = bodySmooth.add(body.subtract(bodySmooth).scale(1.0D - Math.exp(-dt * 12.0D)));
        Vec3 lag = bodySmooth.subtract(body);
        if (lag.length() > 0.5D) bodySmooth = body.add(lag.normalize().scale(0.5D));

        double wantDrift = still ? 0.0D : DRIFT;
        drift += (wantDrift - drift) * (1.0D - Math.exp(-dt * (still ? 1.5D : 1.0D)));
        target += drift * dt;
        double fly = drift / DRIFT;

        Vec3 rayFrom = CamUtil.freeStart(mc, body.add(0.0D, 0.3D, 0.0D));
        if (plannedTick != tick) {
            plannedTick = tick;
            plan(mc, body, rayFrom);
        }
        double da = Mth.wrapDegrees(Math.toDegrees(target - angle));
        angle += Math.toRadians(da) * (1.0D - Math.exp(-dt * 2.5D));
        height += (heightTarget - height) * (1.0D - Math.exp(-dt * 2.5D));

        double hWave = HEIGHT_WAVE * fly * Math.sin(time * 1.1D);
        double rWave = RADIUS_WAVE * fly * Math.sin(time * 0.7D + 1.3D);
        Vec3 want = point(bodySmooth, angle, Math.max(0.3D, height + hWave), radius + rWave);
        pos = CamUtil.safeCamera(mc, rayFrom, want);
        look = body;
    }

    private static Vec3 point(Vec3 centre, double angle, double height, double radius) {
        double h = Math.sqrt(Math.max(0.0D, radius * radius - height * height * 0.25D));
        return centre.add(Math.sin(angle) * h, height, Math.cos(angle) * h);
    }

    private void plan(Minecraft mc, Vec3 centre, Vec3 rayFrom) {
        double[] heights = {HEIGHT, HEIGHT + 0.8D, HEIGHT + 1.6D};
        double base = target;
        for (int step = 0; step <= 8; step++) {
            for (int sign : step == 0 ? new int[]{1} : new int[]{1, -1}) {
                double a = base + sign * step * Math.toRadians(22.5D);
                for (double h : heights) {
                    Vec3 cam = point(centre, a, h, radius);
                    Vec3 beyond = rayFrom.add(cam.subtract(rayFrom).scale(1.15D));
                    if (CamUtil.freeLength(mc, rayFrom, beyond, 0.0D) >= beyond.distanceTo(rayFrom) - 1.0E-3D) {
                        target = a;
                        heightTarget = h;
                        return;
                    }
                }
            }
        }
    }
}

package com.example.waterslide;

import dev.leo.sableplayerragdoll.api.RagdollAPI;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.ModList;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Water for Sable ragdolls (players and NPCs).
 *
 * The voxel collision of the slide sits slightly behind the visible surface. Every tick each ragdoll part inside the
 * trough is held on the SMOOTH inner surface instead: any velocity into the wall is removed and a part that sinks in
 * is pushed back out, so parts glide over a perfectly round trough and never hit the voxel steps.
 * On top of that they are pushed along the slide (ice-like speed), steered across it the way riders are
 * (higher on the outer wall in curves, with damping so they don't rock from wall to wall) and kept from bouncing out.
 * Only loaded when Sable is installed; only looks at slides with a player within 96 blocks.
 */
final class SableSlidePush {
    private static final boolean RAGDOLL_MOD = ModList.get().isLoaded("sable_player_ragdoll");
    // m/s and m/s per tick
    private static final double MAX = 33.0D;
    private static final double ACC = 2.0D;
    private static final double WALL_MAX = 39.0D;
    private static final double WALL_ACC = 2.5D;
    private static final double MAX_UP = 2.0D;
    private static final double SPIN_DAMP = 0.12D;

    private SableSlidePush() {
    }

    static void tick(ServerLevel level) {
        if (SlideRegistry.list(false).isEmpty()) return;
        SubLevelContainer container;
        try {
            container = SubLevelContainer.getContainer(level);
        } catch (Throwable t) {
            return;
        }
        if (container == null) return;
        for (SlideInstance slide : SlideRegistry.list(false)) {
            if (!slide.dim.equals(level.dimension())) continue;
            AABB b = slide.box;
            // a player near any part of the slide (long sections reach far from their centre)
            double reach = 96.0D + Math.max(b.getXsize(), b.getZsize()) * 0.5D;
            if (!level.hasNearbyAlivePlayer((b.minX + b.maxX) * 0.5, (b.minY + b.maxY) * 0.5, (b.minZ + b.maxZ) * 0.5, reach)) continue;
            BoundingBox3d area = new BoundingBox3d(b.minX, b.minY, b.minZ, b.maxX, b.maxY + 1, b.maxZ);
            java.util.Set<Object> mobParts = null;
            for (SubLevel s : container.queryIntersecting(area)) {
                if (!(s instanceof ServerSubLevel ss) || s.isRemoved()) continue;
                if (mobParts == null) mobParts = mobRagdollParts();
                if (mobParts.contains(s) || isBody(s)) push(slide, ss, level.getGameTime());
            }
        }
    }

    // ---- mob / NPC ragdolls: their parts live in MobRagdollAssembly.RAGDOLL_STATES (not covered by isRagdollSubLevel)
    private static boolean mobResolved;
    private static java.util.Map<?, ?> mobStates;
    private static java.lang.reflect.Method stateParts, partSubLevel;

    private static synchronized void resolveMob() {
        if (mobResolved) return;
        mobResolved = true;
        if (!RAGDOLL_MOD) return;
        try {
            Class<?> asm = Class.forName("dev.leo.sableplayerragdoll.mob.MobRagdollAssembly");
            java.lang.reflect.Field f = asm.getDeclaredField("RAGDOLL_STATES");
            f.setAccessible(true);
            mobStates = (java.util.Map<?, ?>) f.get(null);
            stateParts = Class.forName("dev.leo.sableplayerragdoll.mob.MobRagdollAssembly$RagdollState").getDeclaredMethod("parts");
            stateParts.setAccessible(true);
            partSubLevel = Class.forName("dev.leo.sableplayerragdoll.mob.MobRagdollAssembly$SpawnedPart").getDeclaredMethod("subLevel");
            partSubLevel.setAccessible(true);
        } catch (Throwable t) {
            mobStates = null;
        }
    }

    /** All physics bodies that currently belong to a mob/NPC ragdoll (identity set, built once per slide per tick). */
    private static java.util.Set<Object> mobRagdollParts() {
        resolveMob();
        java.util.Set<Object> out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (mobStates == null) return out;
        try {
            for (Object state : mobStates.values().toArray()) {
                for (Object part : (java.util.List<?>) stateParts.invoke(state)) {
                    Object sub = partSubLevel.invoke(part);
                    if (sub != null) out.add(sub);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** True if this mob is currently a Sable ragdoll (its body is simulated by physics, not by the entity). */
    static boolean isRagdolledMob(net.minecraft.world.entity.Entity e) {
        if (!RAGDOLL_MOD || !(e instanceof net.minecraft.world.entity.LivingEntity le)) return false;
        try {
            return RagdollAPI.isMobRagdolled(le);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Ragdoll parts, plus any other small loose physics body (it is a water slide after all). */
    private static boolean isBody(SubLevel s) {
        if (RAGDOLL_MOD) {
            try {
                if (RagdollAPI.isRagdollSubLevel(s)) return true;
            } catch (Throwable ignored) {
            }
        }
        BoundingBox3dc bb = s.boundingBox();
        return bb.maxX() - bb.minX() < 1.6D && bb.maxY() - bb.minY() < 1.6D && bb.maxZ() - bb.minZ() < 1.6D;
    }

    private static void push(SlideInstance slide, ServerSubLevel s, long time) {
        BoundingBox3dc bb = s.boundingBox();
        Vector3d c = bb.center();
        double[] l = slide.toLocal(c.x, c.y, c.z);
        SlideGeometry geo = slide.geo;
        SlideGeometry.Proj p = geo.project(l[0], l[1], l[2], -1);
        double ri = geo.ri;
        if (p.beforeStart || p.afterEnd || Math.abs(p.lat) > ri + 0.3 || p.up < -ri - 0.4 || p.up > ri * 0.9) return;

        RigidBodyHandle h = RigidBodyHandle.of(s);
        if (h == null || !h.isValid()) return;
        Vector3dc wv = h.getLinearVelocity();
        double[] v = slide.dirToLocal(wv.x(), wv.y(), wv.z());
        double vT = v[0] * p.tx + v[1] * p.ty + v[2] * p.tz;
        double vS = v[0] * p.sx + v[1] * p.sy + v[2] * p.sz;
        double vU = v[0] * p.ux + v[1] * p.uy + v[2] * p.uz;
        double dT = 0, dS = 0, dU = 0;

        // ---- smooth surface: keep the part inside the round trough without touching the voxels behind it
        double size = Math.min(bb.maxX() - bb.minX(), Math.min(bb.maxY() - bb.minY(), bb.maxZ() - bb.minZ()));
        double radius = SlideGeometry.clamp(size * 0.5, 0.08, 1.0);
        double r = Math.sqrt(p.lat * p.lat + p.up * p.up);
        double phi = Math.atan2(p.lat, -p.up);   // 0 = straight down in the trough
        if (r > 1e-4 && Math.abs(phi) <= geo.phi + 0.05) {
            double nS = p.lat / r, nU = p.up / r;  // outward normal in the cross-section
            double pen = r - (ri - radius);
            if (pen > -0.04) {
                double vN = vS * nS + vU * nU;
                double fix = vN > 0 ? vN : 0;     // stop motion into the wall (frictionless contact)
                if (pen > 0) fix += Math.min(pen * 10.0, 4.0);   // push back out if it sank in
                dS -= nS * fix;
                dU -= nU * fix;
                vS -= nS * fix;
                vU -= nU * fix;
            }
        }

        // ---- water: along the slide, ice-like speed; faster while riding a wall
        boolean onWall = Math.abs(p.lat) > ri * 0.42D;
        double ks = geo.spec.speedScale();
        double max = (onWall ? WALL_MAX : MAX) * ks;
        double acc = (onWall ? WALL_ACC : ACC) * ks;
        if (vT < max) dT += Math.min(acc, max - vT);
        // slow slides (speedScale < 1): water drag also brakes bodies that come in too fast
        else if (ks < 1.0D && vT > max * 1.1D) dT -= (vT - max) * 0.15D;

        // ---- across: settle where gravity + centrifugal force point, damped so it doesn't rock wall to wall
        double target = geo.targetLat(Math.max(vT, 0.0D) / 20.0D, p.kappa, p.bank);
        dS += (target - p.lat) * 0.8D - vS * 0.35D;
        // near the rim and moving outwards: steer back in (the walls are low)
        if (Math.abs(Math.atan2(p.lat, -p.up)) > geo.phi - 0.3D && p.lat * vS > 0) dS -= vS * 0.9D;

        // ---- Dark Carnival obstacles (impulses in m/s; ragdolls feel them every tick they are in the zone)
        SlideSpec spec = geo.spec;
        boolean flight = false;
        if (!spec.obstacles.isEmpty()) {
            double along = geo.arcLen[p.i];
            flight = SlideObstacles.inJumpFlight(spec, along);
            int ob = SlideObstacles.at(spec, along);
            if (ob >= 0) {
                switch ((int) spec.obstacles.get(ob)[0]) {
                    case SlideSpec.OB_RAMP -> {
                        if (vU < 7.0D) dU += 7.0D - vU;
                        flight = true;
                    }
                    // the moving parts collide by contact (below)
                    case SlideSpec.OB_WHEEL, SlideSpec.OB_HAMMER, SlideSpec.OB_BUMPERS, SlideSpec.OB_SPINNER, SlideSpec.OB_TUNNEL -> {
                    }
                    default -> dT += 1.5D * ks;   // boost hoop
                }
            }
            // ---- real contact of this body part with paddles, hammer head, turnstile bars and clown heads:
            //      the velocity into the part is reflected (springy) and the part's own motion is passed on
            for (int k = 0; k < spec.obstacles.size(); k++) {
                double[] o = spec.obstacles.get(k);
                if (!SlideObstacles.isSolid((int) o[0]) || Math.abs(along - o[1]) > 4.0D) continue;
                SlideObstacles.Hit hit = SlideObstacles.contact(geo, k, time, l[0], l[1], l[2], radius);
                if (hit == null) continue;
                double[] n = {hit.nx, hit.ny, hit.nz};
                // part velocity: blocks/tick -> m/s
                double hx = hit.vx * 20.0D, hy = hit.vy * 20.0D, hz = hit.vz * 20.0D;
                double rel = (v[0] - hx) * n[0] + (v[1] - hy) * n[1] + (v[2] - hz) * n[2];
                double push = (rel < 0.0D ? -1.6D * rel : 0.0D) + 2.0D;
                double ax = n[0] * push + hx * 0.6D, ay = n[1] * push + hy * 0.6D + 1.0D, az = n[2] * push + hz * 0.6D;
                dT += ax * p.tx + ay * p.ty + az * p.tz;
                dS += ax * p.sx + ay * p.sy + az * p.sz;
                dU += ax * p.ux + ay * p.uy + az * p.uz;
                flight = true;   // let it fly off the part, no anti-bounce damping this tick
                break;
            }
        }

        // ---- never bounce up and out (except right after a kicker ramp: that jump is the point)
        if (vU > MAX_UP && !flight) dU -= (vU - MAX_UP) * 0.6D;

        double[] dv = slide.dirToWorld(p.tx * dT + p.sx * dS + p.ux * dU, p.ty * dT + p.sy * dS + p.uy * dU, p.tz * dT + p.sz * dS + p.uz * dU);
        // calm the tumbling a little: spinning limbs are what clip the walls and slow the body down
        Vector3dc w = h.getAngularVelocity();
        h.addLinearAndAngularVelocity(new Vector3d(dv[0], dv[1], dv[2]), new Vector3d(w).mul(-SPIN_DAMP));
    }
}

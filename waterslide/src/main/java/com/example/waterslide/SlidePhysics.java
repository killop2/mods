package com.example.waterslide;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Smooth slide physics for players, mobs and NPCs.
 * Each tick an entity inside a slide is projected onto the trough's curve; when it touches the curved floor it is
 * kept on it, accelerated by the slope plus a little "water" push, and it rides up the outer wall in the curves
 * (gravity + centrifugal force, limited so it never goes over the edge).
 * Runs where the entity is simulated: the client for your own player, the server for everything else.
 */
public final class SlidePhysics {
    private static final double GRAVITY = 0.08D;
    // ice-like: very little drag, a steady water push
    private static final double WATER_PUSH = 0.018D;
    private static final double WALL_PUSH = 0.015D;
    private static final double DRAG = 0.997D;
    private static final double MAX_SPEED = 1.72D;       // blocks/tick (34 m/s)
    private static final double MAX_SPEED_WALL = 2.0D;

    private static final class Ride {
        int idx = -1;
        /** Last obstacle that acted on this rider (-1 = none; reset after leaving its zone). */
        int lastOb = -1;
        /** Ticks until the next obstacle contact can register (one bounce per hit). */
        int hitCd;
        /** Slide the projection hint belongs to. */
        SlideInstance slide;
        boolean grounded;
        double vT, vS;
    }

    private static final boolean SABLE = net.neoforged.fml.ModList.get().isLoaded("sable");
    private static final Map<Entity, Ride> RIDES = Collections.synchronizedMap(new WeakHashMap<>());

    private SlidePhysics() {
    }

    public static boolean isRiding(Entity e) {
        return RIDES.containsKey(e);
    }

    public static void onEntityTick(EntityTickEvent.Post event) {
        Entity e = event.getEntity();
        Level level = e.level();
        List<SlideInstance> slides = SlideRegistry.list(level.isClientSide());
        if (slides.isEmpty()) {
            if (!RIDES.isEmpty()) RIDES.remove(e);
            return;
        }
        boolean simulate = e instanceof Player p ? level.isClientSide() && p.isLocalPlayer() && !p.getAbilities().flying && !p.isSpectator()
                : !level.isClientSide();
        if (!simulate || e.isPassenger() || e.noPhysics || e.isNoGravity()
                || (SABLE && !level.isClientSide() && SableSlidePush.isRagdolledMob(e))) {
            RIDES.remove(e);
            return;
        }
        AABB bb = e.getBoundingBox();
        // Several slides can overlap where the sections of a long slide meet: the first one that really holds the
        // entity wins (the previous section says "after the end" there, the next one takes over).
        for (SlideInstance s : slides) {
            if (s.dim.equals(level.dimension()) && s.box.intersects(bb) && step(e, s)) return;
        }
        RIDES.remove(e);   // left the trough (or never was in it); momentum is kept
    }

    /** @return false if the entity is not in this slide's trough */
    private static boolean step(Entity e, SlideInstance slide) {
        double[] l = slide.toLocal(e.getX(), e.getY(), e.getZ());
        Ride ride = RIDES.get(e);
        SlideGeometry geo = slide.geo;
        SlideGeometry.Proj p = geo.project(l[0], l[1], l[2], ride == null || ride.slide != slide ? -1 : ride.idx);

        double ri = geo.ri;
        if (p.beforeStart || p.afterEnd || Math.abs(p.lat) > ri + 0.15 || p.up < -ri - 0.6 || p.up > ri + 1.4) return false;
        double lat = SlideGeometry.clamp(p.lat, -geo.latMax, geo.latMax);
        double surf = -Math.sqrt(ri * ri - lat * lat);
        if (p.up < surf - 0.7) return false;   // below the trough (walking under it)
        // client: a slide whose anchor chunk is gone is still known; make sure the trough is really there
        if (e.level().isClientSide() && !troughExists(e.level(), slide, p, lat, surf)) return false;
        if (ride == null) {
            ride = new Ride();
            RIDES.put(e, ride);
        }
        if (ride.slide != slide) {
            ride.slide = slide;
            ride.lastOb = -1;
        }
        ride.idx = p.i;

        Vec3 v = e.getDeltaMovement();
        double[] vl = slide.dirToLocal(v.x, v.y, v.z);
        double up = p.up;

        // ---- moving obstacles: real contact with paddles, hammer head, turnstile bars, clown heads
        if (ride.hitCd > 0) {
            ride.hitCd--;
        } else if (collide(e, slide, geo, ride, l, vl)) {
            return true;
        }
        if (up > surf + 0.12) {
            // in the air above the floor (jumped or falling in): let vanilla gravity work, trough collision is off
            ride.grounded = false;
            return true;
        }
        if (!ride.grounded) {
            ride.vT = vl[0] * p.tx + vl[1] * p.ty + vl[2] * p.tz;
            ride.vS = vl[0] * p.sx + vl[1] * p.sy + vl[2] * p.sz;
            ride.grounded = true;
        }

        // speed along the slide
        double slope = -p.ty;
        boolean onWall = Math.abs(lat) > geo.ri * 0.42D;
        double k = geo.spec.speedScale();
        double vT = ride.vT + (GRAVITY * slope + WATER_PUSH + (onWall ? WALL_PUSH : 0.0D)) * k;
        vT *= DRAG;
        vT = SlideGeometry.clamp(vT, 0.03D, (onWall ? MAX_SPEED_WALL : MAX_SPEED) * k);

        // sideways: settle where gravity + centrifugal force point
        double target = geo.targetLat(vT, p.kappa, p.bank);
        double vS = ride.vS + (target - lat) * 0.22D - ride.vS * 0.45D;
        vS = SlideGeometry.clamp(vS, -0.2D, 0.2D);

        // Dark Carnival obstacles: each acts once per pass
        double hop = 0.0D;
        if (!geo.spec.obstacles.isEmpty()) {
            int ob = SlideObstacles.at(geo.spec, geo.arcLen[p.i]);
            if (ob < 0) {
                ride.lastOb = -1;
            } else if (ride.lastOb != ob) {
                double time = e.level().getGameTime();
                switch ((int) geo.spec.obstacles.get(ob)[0]) {
                    case SlideSpec.OB_RAMP -> {
                        // kicker: off you fly, through the ring of fire
                        hop = 0.34D;
                        ride.lastOb = ob;
                        sound(e, SoundEvents.FIREWORK_ROCKET_LAUNCH, 1.0F, 0.8F);
                        sound(e, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, 0.6F, 1.2F);
                    }
                    // wheel, hammer, turnstile and clown heads hit by real contact (collide), not by zone
                    case SlideSpec.OB_WHEEL, SlideSpec.OB_HAMMER, SlideSpec.OB_BUMPERS, SlideSpec.OB_SPINNER, SlideSpec.OB_TUNNEL -> {
                    }
                    default -> {
                        // boost hoop
                        vT = Math.min(vT + 0.2D * k, MAX_SPEED_WALL * k);
                        ride.lastOb = ob;
                        sound(e, SoundEvents.NOTE_BLOCK_CHIME.value(), 0.9F, 1.0F + 0.25F * (ob % 3));
                    }
                }
            }
        }
        ride.vT = vT;
        ride.vS = vS;

        // sit on the curved floor
        double nx = p.cx + p.tx * p.along + p.sx * lat + p.ux * surf;
        double ny = p.cy + p.ty * p.along + p.sy * lat + p.uy * surf;
        double nz = p.cz + p.tz * p.along + p.sz * lat + p.uz * surf;
        double[] w = slide.toWorld(nx, ny, nz);
        double[] wv = slide.dirToWorld(p.tx * vT + p.sx * vS, p.ty * vT + p.sy * vS, p.tz * vT + p.sz * vS);

        e.setPos(w[0], w[1], w[2]);
        e.resetFallDistance();
        if (hop > 0.0D) {
            // thrown up off the floor: vanilla gravity takes over until it lands in the trough again
            double[] hu = slide.dirToWorld(p.ux * hop, p.uy * hop, p.uz * hop);
            e.setDeltaMovement(wv[0] + hu[0], wv[1] + hu[1], wv[2] + hu[2]);
            e.setOnGround(false);
            ride.grounded = false;
            return true;
        }
        e.setDeltaMovement(wv[0], wv[1], wv[2]);
        e.setOnGround(true);
        return true;
    }

    /** Body points tested against the obstacles: lower and upper half of the entity, as spheres. */
    private static final double[] BODY_POINTS = {0.35D, 0.75D};

    /**
     * Contact with the moving obstacle parts. The entity bounces off the part (its velocity into the part is
     * reflected) and is carried along with the part's own motion, then flies freely until it lands again.
     * @return true if it was hit this tick
     */
    private static boolean collide(Entity e, SlideInstance slide, SlideGeometry geo, Ride ride, double[] l, double[] vl) {
        java.util.List<double[]> obs = geo.spec.obstacles;
        if (obs.isEmpty()) return false;
        double s = geo.arcLen[ride.idx];
        double time = e.level().getGameTime();
        double h = e.getBbHeight(), r = Math.max(0.3D, e.getBbWidth() * 0.6D);
        for (int ob = 0; ob < obs.size(); ob++) {
            double[] o = obs.get(ob);
            if (!SlideObstacles.isSolid((int) o[0]) || Math.abs(s - o[1]) > 4.0D) continue;
            for (double f : BODY_POINTS) {
                SlideObstacles.Hit hit = SlideObstacles.contact(geo, ob, time, l[0], l[1] + h * f, l[2], r);
                if (hit == null) continue;
                // velocity relative to the part, reflected off it (a springy bounce), plus the part's own push
                double rx = vl[0] - hit.vx, ry = vl[1] - hit.vy, rz = vl[2] - hit.vz;
                double vn = rx * hit.nx + ry * hit.ny + rz * hit.nz;
                double bounce = vn < 0.0D ? -1.6D * vn : 0.0D;
                double kick = 0.12D;
                double nx = vl[0] + hit.nx * (bounce + kick) + hit.vx * 0.6D;
                double ny = vl[1] + hit.ny * (bounce + kick) + hit.vy * 0.6D + 0.12D;
                double nz = vl[2] + hit.nz * (bounce + kick) + hit.vz * 0.6D;
                double[] wv = slide.dirToWorld(nx, ny, nz);
                double[] push = slide.dirToWorld(hit.nx * 0.08D, hit.ny * 0.08D, hit.nz * 0.08D);
                e.setPos(e.getX() + push[0], e.getY() + push[1], e.getZ() + push[2]);
                e.setDeltaMovement(wv[0], wv[1], wv[2]);
                e.setOnGround(false);
                e.resetFallDistance();
                ride.grounded = false;
                ride.hitCd = 8;
                switch ((int) o[0]) {
                    case SlideSpec.OB_WHEEL -> {
                        sound(e, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, 0.6F, 1.4F);
                        sound(e, SoundEvents.GENERIC_SPLASH, 0.8F, 1.0F);
                    }
                    case SlideSpec.OB_HAMMER -> {
                        sound(e, SoundEvents.ANVIL_LAND, 0.5F, 1.5F);
                        sound(e, SoundEvents.NOTE_BLOCK_BELL.value(), 1.0F, 0.6F);
                    }
                    case SlideSpec.OB_SPINNER -> sound(e, SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, 0.5F, 1.3F);
                    default -> {
                        sound(e, SoundEvents.SLIME_JUMP, 0.9F, 0.8F);
                        sound(e, SoundEvents.NOTE_BLOCK_BIT.value(), 0.8F, 1.6F);
                    }
                }
                return true;
            }
        }
        return false;
    }

    /** The floor cell under the entity is still a slide block (a broken slide leaves no ghost trough behind). */
    private static boolean troughExists(Level level, SlideInstance slide, SlideGeometry.Proj p, double lat, double surf) {
        double r = (slide.geo.ri + SlideGeometry.COLLISION_GAP + 0.1D) / slide.geo.ri;
        double x = p.cx + p.tx * p.along + (p.sx * lat + p.ux * surf) * r;
        double y = p.cy + p.ty * p.along + (p.sy * lat + p.uy * surf) * r;
        double z = p.cz + p.tz * p.along + (p.sz * lat + p.uz * surf) * r;
        double[] w = slide.toWorld(x, y, z);
        net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.containing(w[0], w[1], w[2]);
        if (!level.isLoaded(bp)) return true;
        return level.getBlockState(bp).getBlock() instanceof SlidePartBlock;
    }

    private static void sound(Entity e, net.minecraft.sounds.SoundEvent s, float volume, float pitch) {
        // client (your own player): heard by you; server (mobs, NPCs): heard by everybody
        e.level().playSound(e instanceof Player p ? p : null, e.getX(), e.getY(), e.getZ(), s, net.minecraft.sounds.SoundSource.NEUTRAL, volume, pitch);
    }
}

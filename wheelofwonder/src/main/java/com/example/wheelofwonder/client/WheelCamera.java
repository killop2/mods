package com.example.wheelofwonder.client;

import com.example.wheelofwonder.WheelConfig;
import com.example.wheelofwonder.WheelEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;

/**
 * Cinematic camera for your own wheel:
 *  1) blends in from the normal view, side view of the wheel while it rolls, stops, levitates and catches NPCs
 *     (close-up once NPCs stick to it),
 *  2) pulls back for the explosion,
 *  3) follows ONE of the thrown ragdolls with an orbiting close-up, like kickmod after a kick, until it lies still,
 *  4) blends back to the player.
 * The HUD is hidden and movement frozen the whole time (ClientSetup). Shift skips it.
 * Purely a client effect: when no wheel of yours is out, vanilla and other mods keep full control of the camera.
 */
public final class WheelCamera {
    private static final float IN = 10.0F;
    private static final float BACK = 20.0F;
    /** Without a ragdoll to follow, the camera stays this long after the explosion (ticks). */
    private static final int AFTER_BLAST = 38;
    /** Show the explosion itself this long before switching to the ragdoll (ticks). */
    private static final int BLAST_HOLD = 14;
    /** Blend from the explosion view to the follow camera, ticks. */
    private static final float TO_FOLLOW = 14.0F;
    // follow camera, same feel as kickmod
    private static final int HOLD_STILL = 60;
    private static final double ORBIT_RADIUS = 2.6D;
    private static final double ORBIT_HEIGHT = 0.9D;
    /** Turn around the flying body, rad/s; eases in after the switch and out once the body lies still. */
    private static final double ORBIT_DRIFT = Math.toRadians(38.0D);
    /** While flying the camera also rises/sinks and moves in/out a little (blocks). */
    private static final double HEIGHT_WAVE = 0.55D;
    private static final double RADIUS_WAVE = 0.35D;
    private static final int FOLLOW_MIN = 20;
    private static final int FOLLOW_MAX = 160;
    private static final double STILL_SPEED = 0.6D;
    private static final int STILL_WINDOW = 10;

    public record CameraPose(Vec3 pos, float yaw, float pitch, boolean detached) {
    }

    private static WheelEntity wheel;
    private static boolean active;
    private static int ticks;
    private static int blastTicks = -1;
    private static int endTicks = -1;
    private static double side = 1.0D;
    private static Vec3 lastCenter, lastAxle;
    private static Vec3 smooth, lookSmooth;
    private static double dist = -1.0D, height;
    private static Vec3 lastPos, lastLook;
    private static Vec3 returnFromPos, returnFromLook;
    private static long lastFrame;

    // followed ragdoll
    private static int followId = Integer.MIN_VALUE;
    private static Vec3 bodyLatest, bodyPrev, bodyCur;
    private static final ArrayDeque<Vec3> HISTORY = new ArrayDeque<>();
    private static int followStart = -1;
    private static int holdUntil = -1;
    private static boolean bodyStill;
    private static Vec3 fromPos, fromLook;      // camera pose when the follow started (start of the blend)
    private static Vec3 followPos, followLook;
    private static double orbitAngle, orbitTarget, orbitHeight, orbitHeightTarget, orbitRadius;
    private static Vec3 bodySmooth;
    private static int orbitPlannedTick = -1;
    private static double driftRate, followTime;

    private WheelCamera() {
    }

    /** Called from the wheel's client tick. Starts the camera when a new wheel of ours appears. */
    public static void onWheelTick(WheelEntity w) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || !WheelConfig.CINEMATIC.get()) return;
        if (w.owner().isEmpty() || !w.owner().get().equals(p.getUUID())) return;
        if (w == wheel || w.phase() == WheelEntity.BLOWN || w.tickCount > 10) return;
        if (active && endTicks < 0) return;
        start(w, p);
    }

    private static void start(WheelEntity w, LocalPlayer p) {
        wheel = w;
        active = true;
        ticks = 0;
        blastTicks = -1;
        endTicks = -1;
        lastCenter = w.center();
        lastAxle = w.axle();
        // look from the side of the wheel where the player stands
        side = p.getEyePosition().subtract(lastCenter).dot(lastAxle) >= 0.0D ? 1.0D : -1.0D;
        smooth = lookSmooth = null;
        dist = -1.0D;
        lastPos = lastLook = null;
        returnFromPos = returnFromLook = null;
        lastFrame = 0L;
        followId = Integer.MIN_VALUE;
        bodyLatest = bodyPrev = bodyCur = null;
        HISTORY.clear();
        followStart = holdUntil = -1;
        bodyStill = false;
        followPos = followLook = bodySmooth = null;
        orbitPlannedTick = -1;
        RagdollView.stop();
    }

    public static boolean isActive() {
        return active;
    }

    /** Movement is frozen while the camera shows the wheel and the ragdoll (not while it blends back). */
    public static boolean isLocked() {
        return active && endTicks < 0;
    }

    public static void finish() {
        active = false;
        wheel = null;
        followId = Integer.MIN_VALUE;
        RagdollView.stop();
    }

    /** Server track of the ragdoll chosen at the explosion. */
    public static void onFollow(int entityId, double x, double y, double z) {
        if (!active || endTicks >= 0) return;
        Vec3 pos = new Vec3(x, y, z);
        if (followId == Integer.MIN_VALUE) {
            // never follow something far from the explosion (Sable storage coordinates, unloaded area = only sky)
            if (lastCenter != null && pos.distanceTo(lastCenter) > 16.0D) return;
            followId = entityId;
            RagdollView.track(entityId);
            bodyLatest = pos;
            return;
        }
        if (entityId != followId) return;
        if (bodyLatest != null && pos.distanceTo(bodyLatest) > 10.0D) return;
        bodyLatest = pos;
    }

    static void tick() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            finish();
            return;
        }
        ticks++;
        boolean gone = wheel == null || wheel.isRemoved() || wheel.level() != mc.level;
        if (!gone) {
            lastCenter = wheel.center();
            lastAxle = wheel.axle();
            if (wheel.phase() == WheelEntity.BLOWN && blastTicks < 0) blastTicks = 0;
        }
        if (blastTicks >= 0) blastTicks++;

        bodyPrev = bodyCur == null ? bodyLatest : bodyCur;
        bodyCur = bodyLatest;

        if (endTicks < 0) {
            if (followStart < 0 && followId != Integer.MIN_VALUE && bodyCur != null && blastTicks >= BLAST_HOLD) {
                followStart = ticks;
                fromPos = lastPos;
                fromLook = lastLook;
            }
            boolean end = mc.options.keyShift.isDown() || ticks > 20 * 60 || (gone && blastTicks < 0);
            if (followStart < 0) {
                if (blastTicks > AFTER_BLAST) end = true;
            } else {
                if (bodyCur != null) {
                    HISTORY.addLast(bodyCur);
                    while (HISTORY.size() > STILL_WINDOW + 1) HISTORY.removeFirst();
                }
                int since = ticks - followStart;
                boolean still = HISTORY.size() > STILL_WINDOW
                        && HISTORY.getLast().distanceTo(HISTORY.getFirst()) / (STILL_WINDOW / 20.0D) < STILL_SPEED;
                if (holdUntil < 0 && since >= FOLLOW_MIN && still) {
                    // the body lies still: show it a little longer, then return
                    bodyStill = true;
                    holdUntil = ticks + HOLD_STILL;
                }
                if (since >= FOLLOW_MAX || (holdUntil >= 0 && ticks >= holdUntil)) end = true;
            }
            if (end) {
                // hard cut straight back to the player (no flight back across the map); HUD and controls return at once
                finish();
                return;
            }
        }
        if (endTicks >= 0 && ++endTicks > BACK) finish();
    }

    /** Called at the end of Camera.setup; null = leave the vanilla camera alone. */
    public static CameraPose camera(Vec3 vanillaPos, float vanillaYaw, float vanillaPitch, float partial) {
        if (!active || lastCenter == null) return null;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return null;
        RagdollView.beginFrame();
        long now = System.nanoTime();
        float dt = lastFrame == 0L ? 0.0F : Math.min(0.1F, (now - lastFrame) / 1.0E9F);
        lastFrame = now;
        float t = ticks + partial;
        Vec3 normLook = vanillaPos.add(Vec3.directionFromRotation(vanillaPitch, vanillaYaw).scale(6.0D));

        Vec3 pos, look;
        boolean detached = true;
        if (endTicks < 0 && followStart < 0) {
            // 1-2) the wheel
            Vec3[] side = sideCamera(mc, partial, dt);
            float a = ease(Math.min(1.0F, t / IN));
            pos = lerp(vanillaPos, side[0], a);
            look = lerp(normLook, side[1], a);
        } else if (endTicks < 0) {
            // 3) the thrown ragdoll, blended in from the explosion view
            updateFollow(mc, body(partial), dt);
            float a = ease(Math.min(1.0F, (t - followStart) / TO_FOLLOW));
            Vec3 fp = fromPos != null ? fromPos : followPos, fl = fromLook != null ? fromLook : followLook;
            pos = lerp(fp, followPos, a);
            look = lerp(fl, followLook, a);
        } else {
            // 4) back to the player
            if (returnFromPos == null) {
                returnFromPos = lastPos != null ? lastPos : vanillaPos;
                returnFromLook = lastLook != null ? lastLook : normLook;
            }
            float a = ease(Math.min(1.0F, (endTicks + partial) / BACK));
            pos = lerp(returnFromPos, vanillaPos, a);
            look = lerp(returnFromLook, normLook, a);
            if (a > 0.92F && mc.options.getCameraType().isFirstPerson()) detached = false;
        }
        if (endTicks < 0) {
            lastPos = pos;
            lastLook = look;
        }
        Vec3 d = look.subtract(pos);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) (-(Mth.atan2(d.y, h) * Mth.RAD_TO_DEG));
        return new CameraPose(pos, yaw, pitch, detached);
    }

    // ------------------------------------------------------------------ wheel side view

    /** @return {camera position, look target} of the side view of the wheel */
    private static Vec3[] sideCamera(Minecraft mc, float partial, float dt) {
        Vec3 center = lastCenter;
        if (wheel != null && !wheel.isRemoved()) center = wheel.getPosition(partial).add(0.0D, WheelEntity.RADIUS, 0.0D);

        // distance: normal while rolling/hovering, close-up once NPCs stick to the face, pulled back for the blast
        boolean closeUp = wheel != null && !wheel.isRemoved() && wheel.caught() > 0 && blastTicks < 0;
        double wantDist = closeUp ? WheelConfig.CLOSE_DISTANCE.get() : WheelConfig.CAMERA_DISTANCE.get();
        double wantHeight = closeUp ? 0.6D : 1.4D;
        if (blastTicks >= 0) {
            wantDist = WheelConfig.CAMERA_DISTANCE.get() + 4.0D;
            wantHeight = 2.6D;
        }
        double k = 1.0D - Math.exp(-dt * (blastTicks >= 0 ? 4.0D : 2.5D));
        if (dist < 0.0D) {
            dist = wantDist;
            height = wantHeight;
        } else {
            dist += (wantDist - dist) * k;
            height += (wantHeight - height) * k;
        }
        lookSmooth = lookSmooth == null ? center : lookSmooth.lerp(center, 1.0D - Math.exp(-dt * 14.0D));
        Vec3 want = lookSmooth.add(lastAxle.scale(side * dist)).add(0.0D, height, 0.0D);
        // Only real world blocks stop the camera: Sable puts the ragdoll bodies into level.clip(), and hitting the
        // spinning NPCs on the wheel made the camera jump right into them.
        want = clearOfBlocks(mc, lookSmooth, want, WheelEntity.RADIUS * 0.5D, WheelEntity.RADIUS + 0.8D);
        smooth = smooth == null ? want : smooth.lerp(want, 1.0D - Math.exp(-dt * 6.0D));
        return new Vec3[]{smooth, lookSmooth};
    }

    // ------------------------------------------------------------------ ragdoll follow (as in kickmod)

    private static Vec3 body(float partial) {
        if (bodyCur == null) return bodyLatest != null ? bodyLatest : lastCenter;
        return bodyPrev == null ? bodyCur : lerp(bodyPrev, bodyCur, partial);
    }

    /**
     * Close-up orbit around the ragdoll. It always looks at the body; its angle around the body moves smoothly to
     * the nearest free spot when blocks get in the way. While the body flies the camera also turns slowly around it.
     */
    private static void updateFollow(Minecraft mc, Vec3 serverBody, float dt) {
        // aim where the body was actually drawn (RagdollView); the server track is the fallback
        Vec3 drawn = RagdollView.torso();
        if (drawn != null && drawn.distanceTo(serverBody) > 6.0D) drawn = null;
        Vec3 body = drawn != null ? drawn : serverBody;
        double extent = drawn != null ? RagdollView.extent() : 1.4D;

        if (bodySmooth == null || bodySmooth.distanceTo(body) > 12.0D) {
            // start on the side the explosion camera was on
            Vec3 from = fromPos != null ? fromPos : body.add(lastAxle.scale(side));
            Vec3 s = from.subtract(body).multiply(1.0D, 0.0D, 1.0D);
            s = s.lengthSqr() < 1.0E-4D ? lastAxle.scale(side) : s.normalize();
            orbitAngle = orbitTarget = Math.atan2(s.x, s.z);
            orbitHeight = orbitHeightTarget = ORBIT_HEIGHT;
            orbitRadius = ORBIT_RADIUS;
            bodySmooth = body;
            orbitPlannedTick = -1;
            driftRate = 0.0D;
            followTime = 0.0D;
        }
        followTime += dt;
        double fov = Math.toRadians(Mth.clamp(mc.options.fov().get(), 30, 110));
        double fit = (extent + 0.6D) / Math.tan(fov / 2.0D);
        double wantRadius = Mth.clamp(Math.max(ORBIT_RADIUS, fit), ORBIT_RADIUS, 7.0D);
        orbitRadius += (wantRadius - orbitRadius) * (1.0D - Math.exp(-dt * 3.0D));

        bodySmooth = bodySmooth.add(body.subtract(bodySmooth).scale(1.0D - Math.exp(-dt * 6.0D)));
        Vec3 lag = bodySmooth.subtract(body);
        if (lag.length() > 1.0D) bodySmooth = body.add(lag.normalize());

        // fly around the body while it is in the air: speed eases in/out, never a sudden start or stop
        double wantDrift = bodyStill ? 0.0D : ORBIT_DRIFT;
        driftRate += (wantDrift - driftRate) * (1.0D - Math.exp(-dt * (bodyStill ? 1.5D : 1.0D)));
        orbitTarget += driftRate * dt;
        double fly = driftRate / ORBIT_DRIFT;
        Vec3 rayFrom = freeStart(mc, body.add(0.0D, 0.3D, 0.0D));
        if (orbitPlannedTick != ticks) {
            orbitPlannedTick = ticks;
            planOrbit(mc, body, rayFrom);
        }
        double da = Mth.wrapDegrees(Math.toDegrees(orbitTarget - orbitAngle));
        orbitAngle += Math.toRadians(da) * (1.0D - Math.exp(-dt * 2.5D));
        orbitHeight += (orbitHeightTarget - orbitHeight) * (1.0D - Math.exp(-dt * 2.5D));

        // slow rise/sink and in/out "breathing" while flying, so the shot never feels frozen
        double hWave = HEIGHT_WAVE * fly * Math.sin(followTime * 1.1D);
        double rWave = RADIUS_WAVE * fly * Math.sin(followTime * 0.7D + 1.3D);
        Vec3 want = orbitPoint(bodySmooth, orbitAngle, Math.max(0.3D, orbitHeight + hWave), orbitRadius + rWave);
        followPos = safeCamera(mc, rayFrom, want);
        followLook = body;
    }

    private static Vec3 freeStart(Minecraft mc, Vec3 from) {
        for (int i = 0; i < 6; i++) {
            BlockPos bp = BlockPos.containing(from);
            if (mc.level.getBlockState(bp).getCollisionShape(mc.level, bp).isEmpty()) return from;
            from = from.add(0.0D, 0.25D, 0.0D);
        }
        return from;
    }

    /** Pulls the camera towards {@code from} if any of 8 slightly offset rays hits a world block (like vanilla). */
    private static Vec3 safeCamera(Minecraft mc, Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 1.0E-3D) return to;
        double best = len;
        for (int i = 0; i < 8; i++) {
            double ox = ((i & 1) * 2 - 1) * 0.1D, oy = ((i >> 1 & 1) * 2 - 1) * 0.1D, oz = ((i >> 2 & 1) * 2 - 1) * 0.1D;
            double free = freeLength(mc, from.add(ox, oy, oz), to.add(ox, oy, oz), 0.0D);
            if (free < best) best = free;
        }
        if (best >= len) return to;
        return from.add(d.scale(Math.max(0.3D, best - 0.2D) / len));
    }

    private static Vec3 orbitPoint(Vec3 centre, double angle, double height, double radius) {
        double h = Math.sqrt(Math.max(0.0D, radius * radius - height * height * 0.25D));
        return centre.add(Math.sin(angle) * h, height, Math.cos(angle) * h);
    }

    private static void planOrbit(Minecraft mc, Vec3 centre, Vec3 rayFrom) {
        double[] heights = {ORBIT_HEIGHT, ORBIT_HEIGHT + 0.8D, ORBIT_HEIGHT + 1.6D};
        double base = orbitTarget;
        for (int step = 0; step <= 8; step++) {
            for (int sign : step == 0 ? new int[]{1} : new int[]{1, -1}) {
                double a = base + sign * step * Math.toRadians(22.5D);
                for (double h : heights) {
                    Vec3 cam = orbitPoint(centre, a, h, orbitRadius);
                    Vec3 beyond = rayFrom.add(cam.subtract(rayFrom).scale(1.15D));
                    if (freeLength(mc, rayFrom, beyond, 0.0D) >= beyond.distanceTo(rayFrom) - 1.0E-3D) {
                        orbitTarget = a;
                        orbitHeightTarget = h;
                        return;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ world-only block tests

    /** Free distance from {@code from} towards {@code to} before a solid world block (Sable bodies are ignored). */
    private static double freeLength(Minecraft mc, Vec3 from, Vec3 to, double startAt) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 1.0E-3D) return len;
        Vec3 n = d.scale(1.0D / len);
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (double s = startAt; s <= len; s += 0.1D) {
            Vec3 q = from.add(n.scale(s));
            bp.set(Mth.floor(q.x), Mth.floor(q.y), Mth.floor(q.z));
            BlockState st = mc.level.getBlockState(bp);
            if (!st.getCollisionShape(mc.level, bp).isEmpty() && st.isSolidRender(mc.level, bp)) return s;
        }
        return len;
    }

    private static Vec3 clearOfBlocks(Minecraft mc, Vec3 from, Vec3 to, double startAt, double minDist) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 1.0E-3D) return to;
        double free = freeLength(mc, from, to, startAt);
        if (free >= len) return to;
        return from.add(d.scale(Math.max(minDist, free - 0.4D) / len));
    }

    private static float ease(float x) {
        x = Mth.clamp(x, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return a.add(b.subtract(a).scale(t));
    }
}

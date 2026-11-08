package com.example.wheelofwonder.client;

import com.example.wheelofwonder.DaggerRequest;
import com.example.wheelofwonder.DaggerState;
import com.example.wheelofwonder.ImpalementArtsItem;
import com.example.wheelofwonder.WheelConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

/**
 * Client director of Impalement Arts: the six daggers (their whole choreography is computed here from time) and the
 * camera.
 *  SPIN   - close view from behind and to the side of the thrower: the daggers appear around him and circle faster
 *           and faster, then fan out over his shoulders and aim at the NPC,
 *  FLIGHT - they fly at once, each on its own curve; the camera chases them and arrives beside the NPC, low,
 *           exactly as they hit,
 *  STUCK  - hit-stop: close side view of the NPC with the daggers in it,
 *  FOLLOW - the NPC flies off as a ragdoll with the daggers stuck in it: free orbit camera on the body (mouse turns,
 *           wheel zooms) until Ctrl, then a hard cut back to the player; the daggers vanish then.
 * HUD hidden and movement locked meanwhile (ClientSetup). Shift during the wind-up cancels.
 */
public final class DaggerController {
    static final int N = 6;
    private static final int SPIN = 0, FLIGHT = 1, STUCK = 2, FOLLOW = 3;
    private static final int SHOT_SPIN = 0, SHOT_FLIGHT = 1, SHOT_IMPACT = 2, SHOT_FOLLOW = 3;
    private static final float ENTRY_BLEND = 12.0F, FOLLOW_BLEND = 8.0F;
    private static final int TIMEOUT = 200;
    static final float SCALE = 1.2F;

    private static boolean active;
    private static int phase, ticks, phaseTicks;
    private static int targetId;
    private static int spinTicks = 50, flightTicks = 20, stopTicks = 9;
    private static Vec3 target, chest, head, dir, dirH, side, rightD, upD;
    private static double halfDepth = 0.35D, bodyW = 0.45D;
    private static final Vec3[] START = new Vec3[N], END = new Vec3[N], CTRL = new Vec3[N], STUCK_DIR = new Vec3[N];
    private static final Vec3[] FAN = new Vec3[N];
    // after the launch: the dagger bound to a ragdoll part (key) with its pose in that part's frame
    static final Object[] BOUND = new Object[N];
    static final Matrix4f[] LOCAL = new Matrix4f[N];
    private static Vec3 launchEntityPos;
    private static int launchTick = -1;
    // body tracking (FOLLOW)
    private static Vec3 bodyLatest, bodyPrev, bodyCur;
    private static Vec3 impactCam, impactLook;
    // camera blending
    private static int shot = -1;
    private static float shotStart;
    private static Vec3 lastPos, lastLook, blendFromPos, blendFromLook;
    private static float blendLen = ENTRY_BLEND;
    private static long lastFrame;
    private static boolean ctrlArmed;

    private DaggerController() {
    }

    public static boolean isActive() {
        return active;
    }

    static boolean isTarget(Entity e) {
        return e instanceof LivingEntity le && le.isAlive() && !(e instanceof Player) && !(e instanceof ArmorStand)
                && !e.isSpectator() && !e.isInvisible();
    }

    // ------------------------------------------------------------------ start

    /** Right click with the daggers. @return true if the click is used up (the throw starts, or there is no target) */
    public static boolean tryStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (active || ClientSetup.cinematic() || p == null || mc.level == null || !ImpalementArtsItem.isDaggers(p.getMainHandItem())) return false;
        if (p.getCooldowns().isOnCooldown(p.getMainHandItem().getItem())) return true;
        Entity t = pick(mc, p);
        if (t == null) {
            p.displayClientMessage(Component.translatable("wheelofwonder.msg.no_target", (int) Math.round(radius())), true);
            return true;
        }
        active = true;
        phase = SPIN;
        ticks = phaseTicks = 0;
        targetId = t.getId();
        spinTicks = Math.max(6, (int) Math.round(WheelConfig.DAGGER_SPIN.get() * 20.0D));
        bodyW = Math.max(0.3D, t.getBbWidth() * 0.5D);
        halfDepth = t.getBbWidth() * 0.5D * 0.8D;
        launchTick = -1;
        launchEntityPos = null;
        for (int i = 0; i < N; i++) {
            BOUND[i] = null;
            LOCAL[i] = null;
            START[i] = END[i] = CTRL[i] = STUCK_DIR[i] = null;
        }
        // after the reset: this fills END and FAN
        setTarget(t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D), p, true);
        bodyLatest = bodyPrev = bodyCur = null;
        shot = -1;
        lastPos = lastLook = null;
        lastFrame = 0L;
        ctrlArmed = false;
        DaggerStick.track(targetId);
        DaggerFx.begin(chest);
        face(p, target);
        PacketDistributor.sendToServer(new DaggerRequest(DaggerRequest.START, targetId));
        return true;
    }

    private static double radius() {
        return WheelConfig.DAGGER_RADIUS.get();
    }

    /**
     * The NPC in the aimed direction: the one closest to the crosshair within 30 degrees (a little preference for
     * near ones); if none is that close to it, the nearest one in front. Only NPCs the daggers can reach (no wall).
     */
    private static Entity pick(Minecraft mc, LocalPlayer p) {
        double r = radius();
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1.0F);
        Entity best = null, front = null;
        double bestScore = Double.MAX_VALUE, frontDist = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(r), DaggerController::isTarget)) {
            Vec3 c = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
            Vec3 d = c.subtract(eye);
            double dist = d.length();
            if (dist > r || dist < 0.5D) continue;
            if (CamUtil.freeLength(mc, eye, c, 0.0D) < dist - 0.8D) continue;
            double ang = Math.toDegrees(Math.acos(Mth.clamp(look.dot(d.scale(1.0D / dist)), -1.0D, 1.0D)));
            if (ang < 30.0D) {
                double score = ang + dist * 0.12D;
                if (score < bestScore) {
                    bestScore = score;
                    best = e;
                }
            } else if (ang < 80.0D && dist < frontDist) {
                frontDist = dist;
                front = e;
            }
        }
        return best != null ? best : front;
    }

    private static void setTarget(Vec3 t, LocalPlayer p, boolean initial) {
        target = t;
        chest = p.position().add(0.0D, 1.2D, 0.0D);
        Vec3 d = target.subtract(chest);
        dir = d.lengthSqr() < 1.0E-4D ? p.getLookAngle() : d.normalize();
        Vec3 h = dir.multiply(1.0D, 0.0D, 1.0D);
        dirH = h.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 0.0D, 1.0D) : h.normalize();
        Vec3 r = dir.cross(new Vec3(0.0D, 1.0D, 0.0D));
        rightD = r.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : r.normalize();
        upD = rightD.cross(dir).normalize();
        Minecraft mc = Minecraft.getInstance();
        Vec3 right = new Vec3(-dirH.z, 0.0D, dirH.x);
        if (initial) {
            // film from the side with more room around the thrower
            Vec3 c1 = chest.add(right.scale(2.5D)), c2 = chest.add(right.scale(-2.5D));
            side = CamUtil.freeLength(mc, chest, c1, 0.0D) >= CamUtil.freeLength(mc, chest, c2, 0.0D) ? right : right.scale(-1.0D);
        }
        // where each dagger goes in (the guard ends on the body's surface, the blade inside)
        double[][] off = {{-0.55D, 0.55D}, {0.0D, 0.8D}, {0.55D, 0.55D}, {-0.45D, 0.0D}, {0.45D, 0.0D}, {0.0D, 0.25D}};
        for (int i = 0; i < N; i++) {
            END[i] = target.add(rightD.scale(off[i][0] * bodyW)).add(upD.scale(off[i][1] * bodyW)).subtract(dir.scale(halfDepth + 0.05D));
        }
        // before the throw they rise over the thrower's head into a ring facing the target
        head = chest.add(0.0D, 1.45D, 0.0D);
        for (int i = 0; i < N; i++) {
            double a = Math.PI * 2.0D * i / N + Math.PI * 0.5D;
            FAN[i] = head.add(rightD.scale(Math.cos(a) * 0.7D)).add(upD.scale(Math.sin(a) * 0.55D));
        }
        // impact view: beside the NPC and a little below, slightly towards the thrower, looking at the hit
        Vec3 want = target.add(side.scale(2.5D)).subtract(dir.scale(1.3D)).add(0.0D, -0.5D, 0.0D);
        impactCam = CamUtil.clearOfBlocks(mc, target, CamUtil.freeStart(mc, want), 1.0D);
        impactLook = target.subtract(dir.scale(0.35D));
    }

    // ------------------------------------------------------------------ server events

    public static void onState(int st, int id, double x, double y, double z, int t) {
        if (!active || id != targetId) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Vec3 pos = new Vec3(x, y, z);
        switch (st) {
            case DaggerState.SPIN -> spinTicks = Math.max(6, t);
            case DaggerState.THROW -> {
                if (phase != SPIN || p == null) return;
                // the NPC may have shuffled a little: aim at where the server says it stands
                setTarget(pos, p, false);
                for (int i = 0; i < N; i++) {
                    Pose s = spinPose(i, spinTicks);
                    START[i] = s.pos;
                    Vec3 mid = START[i].add(END[i]).scale(0.5D);
                    double dist = START[i].distanceTo(END[i]);
                    // each one bows out of the ring a little and converges on the target
                    Vec3 out = FAN[i].subtract(head);
                    out = out.lengthSqr() < 1.0E-4D ? upD : out.normalize().add(0.0D, 0.35D, 0.0D).normalize();
                    CTRL[i] = mid.add(out.scale(Math.min(2.6D, 0.12D * dist + 0.35D)));
                    STUCK_DIR[i] = END[i].subtract(CTRL[i]).normalize().add(jitter(i)).normalize();
                }
                flightTicks = Math.max(4, t);
                setPhase(FLIGHT);
                p.swing(InteractionHand.MAIN_HAND);
                DaggerFx.thrown(chest, dir);
            }
            case DaggerState.HIT -> {
                if (phase != SPIN && phase != FLIGHT) return;
                if (phase == SPIN) return;   // never seen the throw: wait for END
                stopTicks = Math.max(1, t);
                setPhase(STUCK);
                DaggerFx.impact(target, dir);
            }
            case DaggerState.LAUNCH -> {
                if (phase != STUCK && phase != FLIGHT) return;
                setPhase(FOLLOW);
                launchTick = ticks;
                Entity e = mc.level != null ? mc.level.getEntity(targetId) : null;
                launchEntityPos = e != null ? e.position() : null;
                bodyLatest = bodyPrev = bodyCur = pos;
                RagdollView.track(targetId);
                if (p != null) startFreeCamera(p);
            }
            case DaggerState.TRACK -> {
                if (bodyLatest == null || pos.distanceTo(bodyLatest) < 10.0D) bodyLatest = pos;
            }
            case DaggerState.END -> finish(false);
            default -> {
            }
        }
    }

    private static Vec3 jitter(int i) {
        double a = i * 2.39996D;
        return upD.scale(Math.sin(a) * 0.12D).add(rightD.scale(Math.cos(a) * 0.12D));
    }

    private static void setPhase(int ph) {
        phase = ph;
        phaseTicks = 0;
    }

    static void tick() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isAlive()) {
            finish(true);
            return;
        }
        ticks++;
        phaseTicks++;
        bodyPrev = bodyCur == null ? bodyLatest : bodyCur;
        bodyCur = bodyLatest;
        switch (phase) {
            case SPIN -> {
                face(p, target);
                if (mc.options.keyShift.isDown()) {
                    finish(true);
                    return;
                }
                if (phaseTicks > spinTicks + TIMEOUT) finish(true);
            }
            case FLIGHT, STUCK -> {
                face(p, target);
                if (phaseTicks > flightTicks + TIMEOUT) finish(true);
            }
            default -> {
                long win = mc.getWindow().getWindow();
                boolean ctrl = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT_CONTROL) || InputConstants.isKeyDown(win, GLFW.GLFW_KEY_RIGHT_CONTROL);
                // only a new press counts (Ctrl may still be held from sprinting)
                if (!ctrl) ctrlArmed = true;
                else if (ctrlArmed && mc.screen == null) finish(false);
            }
        }
        DaggerFx.tickDaggers(phase == SPIN ? 0 : phase == FLIGHT ? 1 : 2);
    }

    static void input(Input in) {
        in.forwardImpulse = 0.0F;
        in.leftImpulse = 0.0F;
        in.up = false;
        in.down = false;
        in.left = false;
        in.right = false;
        in.jumping = false;
        in.shiftKeyDown = false;
    }

    public static void finish(boolean cancel) {
        if (!active) return;
        active = false;
        PacketDistributor.sendToServer(new DaggerRequest(cancel ? DaggerRequest.CANCEL : DaggerRequest.DONE, targetId));
        RagdollView.stop();
        DaggerStick.stop();
        DaggerFx.end();
    }

    private static void face(LocalPlayer p, Vec3 at) {
        if (at == null) return;
        Vec3 d = at.subtract(p.getEyePosition());
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        p.setYRot(yaw);
        p.yRotO = yaw;
        p.setYHeadRot(yaw);
        p.yHeadRotO = yaw;
        p.yBodyRot = yaw;
        p.yBodyRotO = yaw;
    }

    // ------------------------------------------------------------------ dagger choreography

    /** A dagger's place: guard position, blade direction, roll about the blade. */
    record Pose(Vec3 pos, Vec3 fwd, float roll) {
    }

    /** Time in ticks since the current phase began. */
    static float phaseTime(float partial) {
        return phaseTicks + partial;
    }

    static int phase() {
        return phase;
    }

    /** 0 while circling, rising to 1 as the daggers go up over the head and turn to the target. */
    private static float aimProgress(float s) {
        float aim = Math.min(22.0F, spinTicks * 0.55F);
        return CamUtil.ease((s - (spinTicks - aim)) / aim);
    }

    private static Pose spinPose(int i, float s) {
        float appear = CamUtil.ease(s / 8.0F);
        double radius = 0.25D + 0.95D * appear;
        double phi = 0.1D * s + 0.005D * Math.min(s, 60.0F) * s;
        double th = Math.PI * 2.0D * i / N + phi;
        Vec3 orbit = chest.add(Math.cos(th) * radius, 0.12D * Math.sin(2.0D * th + s * 0.3D), Math.sin(th) * radius);
        Vec3 tangent = new Vec3(-Math.sin(th), -0.12D, Math.cos(th)).normalize();
        // the last moments: they fan out over the shoulders and turn to the target
        if (FAN[i] == null || END[i] == null) return new Pose(orbit, tangent, (float) (th * 0.5D));
        float a = aimProgress(s);
        Vec3 pos = CamUtil.lerp(orbit, FAN[i], a);
        Vec3 aimDir = END[i].subtract(FAN[i]).normalize();
        Vec3 fwd = CamUtil.lerp(tangent, aimDir, a);
        if (fwd.lengthSqr() < 1.0E-4D) fwd = aimDir;
        return new Pose(pos, fwd.normalize(), (float) (th * 0.5D) * (1.0F - a));
    }

    private static Pose flightPose(int i, float t) {
        double u = Mth.clamp(t / flightTicks, 0.0F, 1.0F);
        if (u >= 1.0D) return new Pose(END[i], STUCK_DIR[i], 0.0F);
        double e = Math.pow(u, 1.15D);
        Vec3 a = START[i], c = CTRL[i], b = END[i];
        Vec3 pos = a.scale((1 - e) * (1 - e)).add(c.scale(2 * (1 - e) * e)).add(b.scale(e * e));
        Vec3 tan = c.subtract(a).scale(2 * (1 - e)).add(b.subtract(c).scale(2 * e));
        Vec3 fwd = tan.lengthSqr() < 1.0E-6D ? dir : tan.normalize();
        // spiralling in flight, settling as they arrive
        return new Pose(pos, fwd, (float) (t * 0.9D * (1.0D - u)));
    }

    /**
     * Camera-relative frame of dagger i this frame, or null if it has none. Stuck daggers bound to a ragdoll part
     * follow that part; before the body exists they stay where they went in (or follow the plain entity).
     */
    static Matrix4f matrix(int i, float partial, Vec3 cam) {
        if (!active || chest == null) return null;
        float t = phaseTime(partial);
        Pose pose;
        if (phase == SPIN) {
            pose = spinPose(i, t);
        } else if (phase == FLIGHT) {
            if (START[i] == null) return null;
            pose = flightPose(i, t);
        } else {
            if (END[i] == null) return null;
            if (BOUND[i] != null) {
                DaggerStick.Part part = DaggerStick.part(BOUND[i]);
                if (part != null) {
                    Vec3 corr = part.cam.subtract(cam);
                    return new Matrix4f().translation((float) corr.x, (float) corr.y, (float) corr.z).mul(part.m).mul(LOCAL[i]);
                }
            }
            Vec3 at = END[i];
            if (phase == FOLLOW && launchEntityPos != null && launchTick >= 0 && ticks - launchTick > 15) {
                // no ragdoll body came (Sable missing): ride along with the entity
                Entity e = Minecraft.getInstance().level.getEntity(targetId);
                if (e != null) at = at.add(e.getPosition(partial).subtract(launchEntityPos));
            }
            pose = new Pose(at, STUCK_DIR[i], 0.0F);
        }
        return frame(pose, cam);
    }

    static Matrix4f frame(Pose pose, Vec3 cam) {
        Vec3 z = pose.fwd;
        Vec3 up = Math.abs(z.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 x = up.cross(z).normalize();
        Vec3 y = z.cross(x);
        double cr = Math.cos(pose.roll), sr = Math.sin(pose.roll);
        Vec3 xr = x.scale(cr).add(y.scale(sr)), yr = y.scale(cr).subtract(x.scale(sr));
        Vec3 o = pose.pos.subtract(cam);
        Matrix4f m = new Matrix4f(
                (float) xr.x, (float) xr.y, (float) xr.z, 0.0F,
                (float) yr.x, (float) yr.y, (float) yr.z, 0.0F,
                (float) z.x, (float) z.y, (float) z.z, 0.0F,
                (float) o.x, (float) o.y, (float) o.z, 1.0F);
        return m.scale(SCALE);
    }

    /** After the launch: ties every unbound stuck dagger to the ragdoll part nearest to its blade. */
    static void bindToParts(Vec3 cam) {
        if (phase != FOLLOW) return;
        var parts = DaggerStick.recent();
        if (parts.isEmpty()) return;
        for (int i = 0; i < N; i++) {
            if (BOUND[i] != null || END[i] == null) continue;
            Matrix4f d = matrix(i, 0.0F, cam);
            if (d == null) continue;
            Vec3 blade = END[i].add(STUCK_DIR[i].scale(0.3D));
            Object best = null;
            double bestD = Double.MAX_VALUE;
            for (var en : parts.entrySet()) {
                double dd = DaggerStick.centre(en.getValue()).distanceTo(blade);
                if (dd < bestD) {
                    bestD = dd;
                    best = en.getKey();
                }
            }
            if (best == null) continue;
            DaggerStick.Part part = parts.get(best);
            // d is relative to the current camera; the part's matrix to the camera it was drawn with
            Vec3 shift = cam.subtract(part.cam);
            Matrix4f dRel = new Matrix4f().translation((float) shift.x, (float) shift.y, (float) shift.z).mul(d);
            LOCAL[i] = new Matrix4f(part.m).invert().mul(dRel);
            BOUND[i] = best;
        }
    }

    // ------------------------------------------------------------------ camera

    private static final double ZOOM_MIN = 2.0D, ZOOM_MAX = 14.0D, ZOOM_STEP = 0.7D;
    private static double followDist = 5.0D, followDistSmooth = 5.0D;
    private static Vec3 freeAnchor, freeDead, freeAnchor1;
    private static double freeColl = -1.0D, freeExtent = -1.0D, freeLift = -1.0D;

    private static void startFreeCamera(LocalPlayer p) {
        Vec3 body = bodyLatest != null ? bodyLatest : target;
        Vec3 from = lastPos != null ? lastPos : impactCam;
        if (body == null || from == null) return;
        Vec3 d = body.subtract(from);
        double len = d.length();
        if (len < 1.0E-3D) return;
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) (-(Mth.atan2(d.y, h) * Mth.RAD_TO_DEG));
        p.setYRot(yaw);
        p.yRotO = yaw;
        p.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
        p.xRotO = p.getXRot();
        followDist = followDistSmooth = Mth.clamp(len + 1.5D, 3.0D, 9.0D);
        freeAnchor = freeDead = freeAnchor1 = null;
        freeColl = freeExtent = freeLift = -1.0D;
        ctrlArmed = false;
    }

    public static boolean onScroll(double delta) {
        if (!active) return false;
        if (phase == FOLLOW && delta != 0.0D) {
            followDist = Mth.clamp(followDist - Math.signum(delta) * ZOOM_STEP * Math.max(1.0D, Math.abs(delta)), ZOOM_MIN, ZOOM_MAX);
        }
        return true;
    }

    private static Vec3 body(float partial) {
        Vec3 server = bodyCur == null ? bodyLatest : (bodyPrev == null ? bodyCur : CamUtil.lerp(bodyPrev, bodyCur, partial));
        Vec3 drawn = RagdollView.stableTorso();
        if (drawn != null && (server == null || drawn.distanceTo(server) < 16.0D)) return drawn;
        return server;
    }

    private static int currentShot() {
        return switch (phase) {
            case SPIN -> SHOT_SPIN;
            case FLIGHT -> SHOT_FLIGHT;
            case STUCK -> SHOT_IMPACT;
            default -> SHOT_FOLLOW;
        };
    }

    /** Centre of the flying daggers. */
    private static Vec3 lead(float partial) {
        Vec3 s = Vec3.ZERO;
        int n = 0;
        for (int i = 0; i < N; i++) {
            if (START[i] == null) continue;
            s = s.add(flightPose(i, phaseTime(partial)).pos);
            n++;
        }
        return n == 0 ? chest : s.scale(1.0D / n);
    }

    public static WheelCamera.CameraPose camera(Vec3 vanillaPos, float vanillaYaw, float vanillaPitch, float partial) {
        if (!active) return null;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || chest == null) return null;
        RagdollView.beginFrame();
        long now = System.nanoTime();
        float dt = lastFrame == 0L ? 0.0F : Math.min(0.1F, (now - lastFrame) / 1.0E9F);
        lastFrame = now;
        float time = ticks + partial;

        int s = currentShot();
        if (s != shot) {
            boolean first = shot < 0;
            blendFromPos = first ? vanillaPos : lastPos;
            blendFromLook = first ? vanillaPos.add(Vec3.directionFromRotation(vanillaPitch, vanillaYaw).scale(6.0D)) : lastLook;
            blendLen = first ? ENTRY_BLEND : (s == SHOT_FOLLOW ? FOLLOW_BLEND : s == SHOT_IMPACT ? 3.0F : 6.0F);
            shot = s;
            shotStart = time;
        }
        float t = time - shotStart;
        Vec3 pos, look;
        Vec3 right = new Vec3(-dirH.z, 0.0D, dirH.x);
        switch (s) {
            case SHOT_SPIN -> {
                // close, from behind and to the side of the thrower: him and the circling daggers, the target beyond;
                // a slow drift around him
                double drift = Math.toRadians(14.0D) * CamUtil.ease(t / Math.max(20.0F, spinTicks));
                // when the daggers rise over the head, the camera goes up and back a little with them
                float rise = aimProgress(phaseTime(partial));
                Vec3 back = dirH.scale(-1.0D);
                Vec3 off = back.scale(2.9D + 0.5D * rise).add(side.scale(1.35D));
                double c = Math.cos(drift), sn = Math.sin(drift) * (side.dot(right) >= 0.0D ? 1.0D : -1.0D);
                off = new Vec3(off.x * c - off.z * sn, 0.0D, off.x * sn + off.z * c);
                Vec3 want = chest.add(off).add(0.0D, 0.45D + 1.05D * rise, 0.0D);
                pos = CamUtil.clearOfBlocks(mc, chest, CamUtil.freeStart(mc, want), 1.0D);
                look = CamUtil.lerp(chest.add(dirH.scale(1.3D)).add(0.0D, 0.1D, 0.0D), head.add(dirH.scale(0.9D)).add(0.0D, -0.15D, 0.0D), 0.8D * rise);
            }
            case SHOT_FLIGHT -> {
                // chasing the daggers, then arriving beside the NPC just as they hit
                Vec3 g = lead(partial);
                double u = Mth.clamp(phaseTime(partial) / flightTicks, 0.0F, 1.0F);
                Vec3 chase = g.subtract(dir.scale(2.6D)).add(side.scale(0.9D)).add(0.0D, 0.55D, 0.0D);
                chase = CamUtil.clearOfBlocks(mc, g, CamUtil.freeStart(mc, chase), 0.8D);
                Vec3 chaseLook = g.add(dir.scale(3.0D));
                float m = CamUtil.ease((float) ((u - 0.3D) / 0.7D));
                pos = CamUtil.lerp(chase, impactCam, m);
                look = CamUtil.lerp(chaseLook, impactLook, m);
            }
            case SHOT_IMPACT -> {
                // hit-stop: a slow push in on the NPC with the daggers in it
                float a = CamUtil.ease(t / Math.max(4.0F, stopTicks));
                pos = CamUtil.lerp(impactCam, target, 0.12D * a);
                look = impactLook;
            }
            default -> {
                Vec3 body = body(partial);
                if (body == null) body = target;
                // free camera around the flying ragdoll (same as Walrus PUNCH!): the mouse turns the view around it
                followDistSmooth += (followDist - followDistSmooth) * (1.0D - Math.exp(-dt * 10.0D));
                if (freeDead == null || body.distanceTo(freeDead) > 0.05D) freeDead = body;
                if (freeAnchor1 == null || freeAnchor1.distanceTo(freeDead) > 6.0D) {
                    freeAnchor1 = freeAnchor = freeDead;
                } else {
                    double k = 1.0D - Math.exp(-dt * 12.0D);
                    freeAnchor1 = freeAnchor1.lerp(freeDead, k);
                    freeAnchor = freeAnchor.lerp(freeAnchor1, k);
                }
                Vec3 view = Vec3.directionFromRotation(p.getViewXRot(partial), p.getViewYRot(partial));
                double extent = Math.min(3.0D, RagdollView.torso() != null ? RagdollView.extent() : 1.0D);
                if (freeExtent < 0.0D) freeExtent = extent;
                else freeExtent += (extent - freeExtent) * (1.0D - Math.exp(-dt * (extent > freeExtent ? 2.0D : 0.4D)));
                double want = Math.max(followDistSmooth, freeExtent + 1.0D);
                double needLift = 0.8D;
                Vec3 probe = freeAnchor.add(0.0D, needLift, 0.0D);
                if (CamUtil.solid(mc, BlockPos.containing(probe))) needLift = CamUtil.freeStart(mc, probe).y - freeAnchor.y;
                freeLift = freeLift < 0.0D ? needLift : freeLift + (needLift - freeLift) * (1.0D - Math.exp(-dt * 4.0D));
                Vec3 pivot = freeAnchor.add(0.0D, freeLift, 0.0D);
                double allowed = CamUtil.safeCamera(mc, pivot, pivot.subtract(view.scale(want))).distanceTo(pivot);
                if (freeColl < 0.0D) freeColl = allowed;
                else if (allowed < freeColl) freeColl = Mth.clamp(freeColl + (allowed - freeColl) * (1.0D - Math.exp(-dt * 30.0D)), allowed, allowed + 0.15D);
                else freeColl += (allowed - freeColl) * (1.0D - Math.exp(-dt * 3.0D));
                pos = pivot.subtract(view.scale(freeColl));
                look = pivot.add(view);
            }
        }
        float b = CamUtil.ease(t / blendLen);
        if (b < 1.0F && blendFromPos != null) {
            pos = CamUtil.lerp(blendFromPos, pos, b);
            if (s != SHOT_FOLLOW) look = CamUtil.lerp(blendFromLook, look, b);
        }
        lastPos = pos;
        lastLook = look;
        return CamUtil.pose(pos, look, true);
    }
}

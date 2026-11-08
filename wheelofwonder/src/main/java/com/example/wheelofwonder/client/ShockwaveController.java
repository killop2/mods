package com.example.wheelofwonder.client;

import com.example.wheelofwonder.ShockRequest;
import com.example.wheelofwonder.ShockState;
import com.example.wheelofwonder.ShockwaveItem;
import com.example.wheelofwonder.ShockwaveServer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Client director of Shockwave!: the hero's swing and the camera.
 *  WINDUP - from the side and above: the hero swings the spear up over his head and slams it into the ground,
 *  WAVE   - the camera glides along with the rift, looking a little ahead of its front,
 *  CATCH  - the rift caught an NPC: the camera eases over to it and holds it while it is pulled into the vortex,
 *  FOLLOW - it is blasted away as a ragdoll: the camera goes on from exactly where it was, as a free orbit around
 *           the body (mouse turns, wheel zooms). Ctrl at any time: back to the hero at once.
 * HUD hidden and movement locked meanwhile (ClientSetup).
 */
public final class ShockwaveController {
    private static final int WINDUP = 0, WAVE = 1, CATCH = 2, FOLLOW = 3;
    private static final int W = ShockwaveServer.WINDUP_TICKS;

    private static boolean active;
    private static int phase, ticks, phaseTicks;
    private static Vec3 dirH = new Vec3(0.0D, 0.0D, 1.0D), side = new Vec3(1.0D, 0.0D, 0.0D);
    private static Vec3 frontLatest, frontPrev, frontCur;
    private static int focusId = -1;
    private static Vec3 catchPos;
    private static int endAt = -1;
    private static Vec3 bodyLatest, bodyPrev, bodyCur;
    private static Vec3 camPos, camLook, lastPos;
    private static long lastFrame;
    private static boolean ctrlArmed;
    /** Player age (ticks) at the click: the swing animation runs from there; -1 = none. */
    private static float poseStart = -1.0F;

    private ShockwaveController() {
    }

    public static boolean isActive() {
        return active;
    }

    // ------------------------------------------------------------------ start

    /** Right click with the spear. @return true if the click is used up */
    public static boolean tryStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (active || ClientSetup.cinematic() || p == null || mc.level == null || !ShockwaveItem.isShockwave(p.getMainHandItem())) return false;
        if (p.getCooldowns().isOnCooldown(p.getMainHandItem().getItem())) return true;
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-4D) flat = Vec3.directionFromRotation(0.0F, p.getYRot());
        dirH = flat.normalize();
        // film from the side with more room
        Vec3 right = new Vec3(-dirH.z, 0.0D, dirH.x);
        Vec3 chest = p.position().add(0.0D, 1.2D, 0.0D);
        double fr = CamUtil.freeLength(mc, chest, chest.add(right.scale(5.0D)).add(0.0D, 2.5D, 0.0D), 0.0D);
        double fl = CamUtil.freeLength(mc, chest, chest.add(right.scale(-5.0D)).add(0.0D, 2.5D, 0.0D), 0.0D);
        side = fr >= fl ? right : right.scale(-1.0D);
        active = true;
        phase = WINDUP;
        ticks = phaseTicks = 0;
        frontLatest = frontPrev = frontCur = null;
        focusId = -1;
        catchPos = null;
        endAt = -1;
        bodyLatest = bodyPrev = bodyCur = null;
        camPos = camLook = lastPos = lastLook = null;
        shot = -1;
        lastFrame = 0L;
        ctrlArmed = false;
        freeAnchor = freeDead = freeAnchor1 = null;
        freeColl = freeExtent = freeLift = -1.0D;
        poseStart = p.tickCount;
        face(p);
        ShockwaveFx.windup(p);
        PacketDistributor.sendToServer(new ShockRequest(ShockRequest.START));
        return true;
    }

    // ------------------------------------------------------------------ server events

    public static void onState(ShockState s) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || s.casterId() != p.getId()) return;
        Vec3 pos = new Vec3(s.x(), s.y(), s.z());
        switch (s.phase()) {
            case ShockState.SLAM -> {
                if (phase == WINDUP) setPhase(WAVE);
                frontLatest = frontCur = frontPrev = pos;
                Vec3 d = new Vec3(s.dx(), 0.0D, s.dz());
                if (d.lengthSqr() > 1.0E-4D) dirH = d.normalize();
            }
            case ShockState.FRONT -> {
                if (phase == WINDUP) setPhase(WAVE);
                frontLatest = pos;
            }
            case ShockState.CATCH -> {
                if (phase == WINDUP || phase == WAVE) {
                    focusId = s.entityId();
                    catchPos = pos;
                    endAt = -1;
                    setPhase(CATCH);
                }
            }
            case ShockState.LAUNCH -> {
                if (s.entityId() == focusId && phase != FOLLOW) {
                    setPhase(FOLLOW);
                    bodyLatest = bodyPrev = bodyCur = pos;
                    RagdollView.track(focusId);
                    startFreeCamera(p);
                }
            }
            case ShockState.TRACK -> {
                if (s.entityId() == focusId && (bodyLatest == null || pos.distanceTo(bodyLatest) < 10.0D)) bodyLatest = pos;
            }
            case ShockState.END -> {
                if (phase == WINDUP) finish();                 // refused
                else if (phase != FOLLOW) endAt = ticks + 25;  // nobody to follow: a moment to watch, then back
            }
            default -> {
            }
        }
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
            finish();
            return;
        }
        ticks++;
        phaseTicks++;
        frontPrev = frontCur == null ? frontLatest : frontCur;
        frontCur = frontLatest;
        bodyPrev = bodyCur == null ? bodyLatest : bodyCur;
        bodyCur = bodyLatest;
        long win = mc.getWindow().getWindow();
        boolean ctrl = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT_CONTROL) || InputConstants.isKeyDown(win, GLFW.GLFW_KEY_RIGHT_CONTROL);
        // only a new press counts (Ctrl may still be held from sprinting)
        if (!ctrl) ctrlArmed = true;
        else if (ctrlArmed && mc.screen == null) {
            finish();
            return;
        }
        if (phase == WINDUP || phase == WAVE) face(p);
        if (phase == WINDUP && phaseTicks > W + 60) finish();   // the server never answered
        if (endAt >= 0 && ticks >= endAt) finish();
        if (phase == CATCH && phaseTicks > 200) finish();
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

    public static void finish() {
        if (!active) return;
        active = false;
        PacketDistributor.sendToServer(new ShockRequest(ShockRequest.DONE));
        RagdollView.stop();
    }

    private static void face(LocalPlayer p) {
        float yaw = (float) (Mth.atan2(dirH.z, dirH.x) * Mth.RAD_TO_DEG) - 90.0F;
        p.setYRot(yaw);
        p.yRotO = yaw;
        p.setYHeadRot(yaw);
        p.yHeadRotO = yaw;
        p.yBodyRot = yaw;
        p.yBodyRotO = yaw;
    }

    // ------------------------------------------------------------------ the swing (HumanoidModelMixin)

    /**
     * Both hands on the spear: it goes up over the head, then comes down hard and stays pointing at the ground for a
     * moment before the arms return to normal.
     */
    public static void applyPose(HumanoidModel<?> m, LivingEntity e, float ageInTicks) {
        if (poseStart < 0.0F) return;
        Minecraft mc = Minecraft.getInstance();
        if (e != mc.player) return;
        float t = ageInTicks - poseStart;
        if (t < 0.0F || t > W + 16.0F) {
            if (t > W + 16.0F || t < -5.0F) poseStart = -1.0F;
            return;
        }
        final float up = -2.95F, down = -0.45F, raise = W - 5.0F;
        float target, w, lean;
        if (t < raise) {
            float k = CamUtil.ease(t / raise);
            target = Mth.lerp(k, -0.5F, up);
            w = CamUtil.ease(t / 2.5F);
            lean = -0.18F * k;
        } else if (t < W) {
            float k = (t - raise) / (W - raise);
            k = k * k;
            target = Mth.lerp(k, up, down);
            w = 1.0F;
            lean = Mth.lerp(k, -0.18F, 0.4F);
        } else if (t < W + 7.0F) {
            target = down;
            w = 1.0F;
            lean = 0.4F;
        } else {
            target = down;
            w = 1.0F - CamUtil.ease((t - W - 7.0F) / 9.0F);
            lean = 0.4F;
        }
        m.rightArm.xRot = Mth.lerp(w, m.rightArm.xRot, target);
        m.leftArm.xRot = Mth.lerp(w, m.leftArm.xRot, target);
        m.rightArm.yRot = Mth.lerp(w, m.rightArm.yRot, -0.3F);
        m.leftArm.yRot = Mth.lerp(w, m.leftArm.yRot, 0.3F);
        m.rightArm.zRot = Mth.lerp(w, m.rightArm.zRot, 0.0F);
        m.leftArm.zRot = Mth.lerp(w, m.leftArm.zRot, 0.0F);
        // the head follows the swing a little
        m.head.xRot = Mth.lerp(w, m.head.xRot, lean * 0.8F);
    }

    // ------------------------------------------------------------------ camera

    private static final double ZOOM_MIN = 2.0D, ZOOM_MAX = 16.0D, ZOOM_STEP = 0.8D, LIFT = 0.8D;
    private static double followDist = 6.0D, followDistSmooth = 6.0D;
    private static Vec3 freeAnchor, freeDead, freeAnchor1;
    private static double freeColl = -1.0D, freeExtent = -1.0D, freeLift = -1.0D;

    /** The free camera starts exactly where the shot camera was: the view is turned from there onto the body. */
    private static void startFreeCamera(LocalPlayer p) {
        Vec3 body = bodyLatest;
        Vec3 from = lastPos;
        freeAnchor = freeDead = freeAnchor1 = null;
        freeColl = freeExtent = freeLift = -1.0D;
        if (body == null || from == null) {
            followDist = followDistSmooth = 6.0D;
            return;
        }
        Vec3 d = body.add(0.0D, LIFT, 0.0D).subtract(from);
        double len = d.length();
        if (len < 1.0E-3D) return;
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) (-(Mth.atan2(d.y, h) * Mth.RAD_TO_DEG));
        p.setYRot(yaw);
        p.yRotO = yaw;
        p.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
        p.xRotO = p.getXRot();
        followDist = followDistSmooth = Mth.clamp(len, ZOOM_MIN, ZOOM_MAX);
    }

    public static boolean onScroll(double delta) {
        if (!active) return false;
        if (phase == FOLLOW && delta != 0.0D) {
            followDist = Mth.clamp(followDist - Math.signum(delta) * ZOOM_STEP * Math.max(1.0D, Math.abs(delta)), ZOOM_MIN, ZOOM_MAX);
        }
        return true;
    }

    private static Vec3 front(float partial) {
        if (frontCur == null) return frontLatest;
        return frontPrev == null ? frontCur : CamUtil.lerp(frontPrev, frontCur, partial);
    }

    private static Vec3 body(float partial) {
        Vec3 server = bodyCur == null ? bodyLatest : (bodyPrev == null ? bodyCur : CamUtil.lerp(bodyPrev, bodyCur, partial));
        Vec3 drawn = RagdollView.stableTorso();
        if (drawn != null && (server == null || drawn.distanceTo(server) < 16.0D)) return drawn;
        return server;
    }

    // shots, the same way Impalement Arts films its throw: each phase has its own framing and the cut between two
    // shots is an eased blend over a few ticks
    private static final float ENTRY_BLEND = 12.0F, WAVE_BLEND = 7.0F, CATCH_BLEND = 16.0F, FOLLOW_BLEND = 8.0F;
    private static int shot = -1;
    private static float shotStart, blendLen = ENTRY_BLEND;
    private static Vec3 blendFromPos, blendFromLook, lastLook;

    public static WheelCamera.CameraPose camera(Vec3 vanillaPos, float vanillaYaw, float vanillaPitch, float partial) {
        if (!active) return null;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return null;
        RagdollView.beginFrame();
        long now = System.nanoTime();
        float dt = lastFrame == 0L ? 0.0F : Math.min(0.1F, (now - lastFrame) / 1.0E9F);
        lastFrame = now;
        float time = ticks + partial;
        if (phase != shot) {
            boolean first = shot < 0;
            blendFromPos = first || lastPos == null ? vanillaPos : lastPos;
            blendFromLook = first || lastLook == null ? vanillaPos.add(Vec3.directionFromRotation(vanillaPitch, vanillaYaw).scale(6.0D)) : lastLook;
            blendLen = first ? ENTRY_BLEND : phase == WAVE ? WAVE_BLEND : phase == CATCH ? CATCH_BLEND : FOLLOW_BLEND;
            shot = phase;
            shotStart = time;
        }
        float t = time - shotStart;
        Vec3 chest = p.getPosition(partial).add(0.0D, 1.2D, 0.0D);
        Vec3 right = new Vec3(-dirH.z, 0.0D, dirH.x);
        Vec3 pos, look;
        switch (phase) {
            case WINDUP -> {
                // close, from behind and to the side of the hero, a little low: him, the spear going up over his head
                // and the ground in front; a slow drift around him, and up a bit with the swing
                float k = CamUtil.ease((phaseTicks + partial) / W);
                double drift = Math.toRadians(16.0D) * k;
                Vec3 off = dirH.scale(-(2.9D + 0.4D * k)).add(side.scale(1.35D));
                double c = Math.cos(drift), sn = Math.sin(drift) * (side.dot(right) >= 0.0D ? 1.0D : -1.0D);
                off = new Vec3(off.x * c - off.z * sn, 0.0D, off.x * sn + off.z * c);
                Vec3 want = chest.add(off).add(0.0D, 0.35D + 0.6D * k, 0.0D);
                pos = CamUtil.clearOfBlocks(mc, chest, CamUtil.freeStart(mc, want), 1.0D);
                look = chest.add(dirH.scale(1.3D + 1.4D * k)).add(0.0D, 0.25D - 0.55D * k, 0.0D);
            }
            case WAVE -> {
                // chasing the rift: low and close behind its front, looking on ahead of it
                Vec3 g = front(partial);
                if (g == null) g = chest.add(dirH.scale(1.4D)).add(0.0D, -1.2D, 0.0D);
                Vec3 want = g.subtract(dirH.scale(2.8D)).add(side.scale(0.9D)).add(0.0D, 1.15D, 0.0D);
                pos = CamUtil.clearOfBlocks(mc, g.add(0.0D, 0.6D, 0.0D), CamUtil.freeStart(mc, want), 0.8D);
                look = g.add(dirH.scale(3.0D)).add(0.0D, 0.5D, 0.0D);
            }
            case CATCH -> {
                // the whole vortex in frame: from the side and a little above, far enough to see every NPC whirling
                // round; the camera slowly circles the scene and creeps in as it winds up
                Entity e = mc.level.getEntity(focusId);
                Vec3 o = catchPos != null ? catchPos : e != null ? e.getPosition(partial).add(0.0D, e.getBbHeight() * 0.5D, 0.0D) : chest;
                float u = Math.min(1.0F, (phaseTicks + partial) / 70.0F);
                double ang = Math.toRadians(-20.0D + 50.0D * CamUtil.ease(u));
                Vec3 out = side.scale(Math.cos(ang)).add(dirH.scale(-Math.sin(ang)));
                double dist = 7.6D - 1.6D * CamUtil.ease(u);
                Vec3 want = o.add(out.scale(dist)).add(0.0D, 2.4D - 0.5D * u, 0.0D);
                Vec3 centre = o.add(0.0D, 0.7D, 0.0D);
                pos = CamUtil.clearOfBlocks(mc, centre, CamUtil.freeStart(mc, want), 1.5D);
                look = centre;
            }
            default -> {
                // the ragdoll: free orbit (mouse turns, wheel zooms), started from where the shot camera stood
                pos = follow(mc, p, partial, dt);
                if (pos == null) pos = lastPos != null ? lastPos : chest;
                look = pos.add(Vec3.directionFromRotation(p.getViewXRot(partial), p.getViewYRot(partial)));
            }
        }
        float b = CamUtil.ease(t / blendLen);
        if (b < 1.0F && blendFromPos != null) {
            pos = CamUtil.lerp(blendFromPos, pos, b);
            if (phase != FOLLOW) look = CamUtil.lerp(blendFromLook, look, b);
        }
        if (phase != FOLLOW) pos = CamUtil.roomyAlong(mc, pos, look);
        lastPos = pos;
        lastLook = look;
        return CamUtil.pose(pos, look, true);
    }

    /** Free orbit around the flying ragdoll (mouse turns, wheel zooms); never inside blocks. */
    private static Vec3 follow(Minecraft mc, LocalPlayer p, float partial, float dt) {
        Vec3 body = body(partial);
        if (body == null) return null;
        followDistSmooth += (followDist - followDistSmooth) * (1.0D - Math.exp(-dt * 8.0D));
        if (freeDead == null || body.distanceTo(freeDead) > 0.05D) freeDead = body;
        if (freeAnchor1 == null || freeAnchor1.distanceTo(freeDead) > 8.0D) {
            freeAnchor1 = freeAnchor = freeDead;
        } else {
            double k = 1.0D - Math.exp(-dt * 14.0D);
            freeAnchor1 = freeAnchor1.lerp(freeDead, k);
            freeAnchor = freeAnchor.lerp(freeAnchor1, k);
        }
        Vec3 view = Vec3.directionFromRotation(p.getViewXRot(partial), p.getViewYRot(partial));
        double extent = Math.min(3.0D, RagdollView.torso() != null ? RagdollView.extent() : 1.0D);
        if (freeExtent < 0.0D) freeExtent = extent;
        else freeExtent += (extent - freeExtent) * (1.0D - Math.exp(-dt * (extent > freeExtent ? 2.0D : 0.4D)));
        double want = Math.max(followDistSmooth, freeExtent + 1.0D);
        double needLift = LIFT;
        Vec3 probe = freeAnchor.add(0.0D, needLift, 0.0D);
        if (CamUtil.solid(mc, BlockPos.containing(probe))) needLift = CamUtil.freeStart(mc, probe).y - freeAnchor.y;
        Vec3 under = freeAnchor.add(0.0D, needLift - 0.45D, 0.0D);
        if (CamUtil.solid(mc, BlockPos.containing(under))) needLift = Math.max(needLift, CamUtil.freeStart(mc, under).y + 0.45D - freeAnchor.y);
        freeLift = freeLift < 0.0D || needLift > freeLift ? needLift : freeLift + (needLift - freeLift) * (1.0D - Math.exp(-dt * 4.0D));
        Vec3 pivot = freeAnchor.add(0.0D, freeLift, 0.0D);
        double allowed = CamUtil.safeCamera(mc, pivot, pivot.subtract(view.scale(want))).distanceTo(pivot);
        if (freeColl < 0.0D) freeColl = allowed;
        else if (allowed < freeColl) freeColl = Mth.clamp(freeColl + (allowed - freeColl) * (1.0D - Math.exp(-dt * 30.0D)), allowed, allowed + 0.05D);
        else freeColl += (allowed - freeColl) * (1.0D - Math.exp(-dt * 3.0D));
        return CamUtil.roomyAlong(mc, pivot.subtract(view.scale(freeColl)), pivot);
    }
}

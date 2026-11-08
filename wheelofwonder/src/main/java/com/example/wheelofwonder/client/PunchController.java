package com.example.wheelofwonder.client;

import com.example.wheelofwonder.PunchRequest;
import com.example.wheelofwonder.PunchState;
import com.example.wheelofwonder.WalrusGlovesItem;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;

/**
 * Client director of the Walrus PUNCH! combo: drives the puncher's leap and the camera.
 *  CLOSE  - close-up of the NPC from the side and slightly below as the uppercut lands,
 *  RISE   - the camera stays on the ragdoll and follows it slowly up into the air,
 *  PREP   - low angle on the player crouching to jump,
 *  LEAP   - the player runs under the body and shoots up to it (same low camera, looking up),
 *  STRIKE - side view at the top: the punch, with a hit-stop,
 *  FOLLOW - orbit on the falling ragdoll as in Wheel of Wonder; then a hard cut back to the player.
 * Every change of camera angle is a smooth blend, except the final cut back to the player.
 * HUD hidden and movement locked meanwhile (ClientSetup). Shift skips it (not mid-air).
 */
public final class PunchController {
    private static final int CLOSE = 0, RISE = 1, PREP = 2, LEAP = 3, STRIKE = 4, FOLLOW = 5, INTRO = 6;
    // shots (camera angles)
    private static final int SHOT_CLOSE = 0, SHOT_RISE = 1, SHOT_TOP = 2, SHOT_STRIKE = 3, SHOT_FOLLOW = 4, SHOT_INTRO = 5;

    /** The server holds the NPC 2 s before the uppercut; if its HIT never comes, give up after this. */
    private static final int INTRO_TIMEOUT = 120;
    private static final int CLOSE_TICKS = 36;
    private static final int PREP_TICKS = 18;
    private static final int LEAP_TICKS = 40;
    /** The camera starts gliding to the punch angle this many ticks before the fist arrives. */
    private static final int STRIKE_LEAD = 16;
    private static final int HITSTOP = 32;
    private static final float BLEND = 20.0F;
    private static final float ENTRY_BLEND = 14.0F;
    private static final float FOLLOW_BLEND = 8.0F;
    private static final int RISE_TIMEOUT = 320;
    private static final int FOLLOW_MAX = 240;
    private static final int HOLD_STILL = 60;
    private static final double STILL_SPEED = 0.6D;
    private static final int STILL_WINDOW = 10;
    private static final double REACH = 4.5D;

    private static boolean active;
    private static int phase, ticks, phaseTicks;
    private static int targetId;
    private static boolean confirmed, gravityOff;
    private static Vec3 impactOrigin, playerStart, dirH, side;
    private static Vec3 apex, apexDir, strikeFeet, launchSpot, leapFrom;
    private static Vec3 closePos, topCam, strikeCam, strikeMid, introCam, introLook;
    private static Vec3 bodyLatest, bodyPrev, bodyCur;
    private static final ArrayDeque<Vec3> HISTORY = new ArrayDeque<>();
    private static int holdUntil = -1;
    private static Vec3 riseCam, riseLook;
    // camera blending between shots
    private static int shot = -1;
    private static float shotStart;
    private static Vec3 lastPos, lastLook, blendFromPos, blendFromLook;
    private static float blendLen = BLEND;
    private static long lastFrame;
    private static final FollowOrbit ORBIT = new FollowOrbit();

    private PunchController() {
    }

    public static boolean isActive() {
        return active;
    }

    static boolean isPunchable(Entity e) {
        return e instanceof LivingEntity le && le.isAlive() && !(e instanceof Player) && !(e instanceof ArmorStand)
                && !e.isSpectator() && !e.isInvisible();
    }

    /** Left click on an entity with the gloves. @return true if the combo starts (the vanilla attack is cancelled) */
    public static boolean tryStart(Entity e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (active || WheelCamera.isActive() || p == null || mc.level == null || !WalrusGlovesItem.isGloves(p.getMainHandItem())
                || p.getCooldowns().isOnCooldown(p.getMainHandItem().getItem()) || !isPunchable(e)) return false;
        LivingEntity t = (LivingEntity) e;
        if (Math.sqrt(t.getBoundingBox().distanceToSqr(p.getEyePosition())) > REACH + 0.5D) return false;

        active = true;
        phase = INTRO;
        ticks = phaseTicks = 0;
        targetId = t.getId();
        confirmed = false;
        impactOrigin = t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D);
        playerStart = p.position();
        Vec3 d = impactOrigin.subtract(playerStart).multiply(1.0D, 0.0D, 1.0D);
        dirH = d.lengthSqr() < 1.0E-4D ? p.getLookAngle().multiply(1.0D, 0.0D, 1.0D).normalize() : d.normalize();
        Vec3 right = new Vec3(-dirH.z, 0.0D, dirH.x);
        // film from the side with more free space
        Vec3 c1 = impactOrigin.add(right.scale(2.2D)), c2 = impactOrigin.add(right.scale(-2.2D));
        side = CamUtil.freeLength(mc, impactOrigin, c1, 0.0D) >= CamUtil.freeLength(mc, impactOrigin, c2, 0.0D) ? right : right.scale(-1.0D);
        // close-up from the side and slightly below, on the player's side of the NPC
        Vec3 want = impactOrigin.add(side.scale(2.2D)).subtract(dirH.scale(0.6D)).add(0.0D, -0.5D, 0.0D);
        closePos = CamUtil.clearOfBlocks(mc, impactOrigin, CamUtil.freeStart(mc, want), 0.8D);
        // intro: wide side view of both, the player and the NPC
        Vec3 pc = p.position().add(0.0D, 1.0D, 0.0D);
        introLook = pc.add(impactOrigin).scale(0.5D);
        double spread = pc.distanceTo(impactOrigin);
        Vec3 iw = introLook.add(side.scale(4.5D + spread * 0.6D)).add(0.0D, 0.9D, 0.0D);
        introCam = CamUtil.clearOfBlocks(mc, introLook, CamUtil.freeStart(mc, iw), 2.0D);
        bodyLatest = bodyPrev = bodyCur = impactOrigin;
        HISTORY.clear();
        holdUntil = -1;
        apex = strikeFeet = launchSpot = topCam = strikeCam = null;
        riseCam = riseLook = null;
        shot = -1;
        lastPos = lastLook = null;
        lastFrame = 0L;
        ORBIT.reset();
        RagdollView.track(targetId);

        face(p, impactOrigin);
        // the server holds the NPC for the intro, then lands the uppercut and answers HIT
        PacketDistributor.sendToServer(new PunchRequest(PunchRequest.START, targetId));
        return true;
    }

    /** The uppercut lands (server HIT): swing, trail, close-up. */
    private static void onHit(Vec3 center) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        confirmed = true;
        bodyLatest = bodyPrev = bodyCur = center;
        impactOrigin = center;
        setPhase(CLOSE);
        if (p == null) return;
        face(p, center);
        p.swing(InteractionHand.MAIN_HAND);
        PunchFx.uppercut(p.position().add(0.0D, 0.9D, 0.0D).add(side.scale(0.3D)), center.add(0.0D, 0.8D, 0.0D), dirH);
    }

    public static void onState(int st, int id, double x, double y, double z) {
        if (!active || id != targetId && st != PunchState.END) return;
        Vec3 pos = new Vec3(x, y, z);
        switch (st) {
            case PunchState.HIT -> {
                if (phase == INTRO) onHit(pos);
            }
            case PunchState.TRACK -> {
                if (bodyLatest == null || pos.distanceTo(bodyLatest) < 10.0D) bodyLatest = pos;
            }
            case PunchState.APEX -> {
                if (phase == CLOSE || phase == RISE) startPrep(pos);
            }
            case PunchState.END -> {
                if (phase <= RISE || phase == INTRO) finish(false);
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ where to jump

    /** The player's 0.6 x 1.8 box fits at these feet. */
    private static boolean boxFree(Minecraft mc, Vec3 feet) {
        for (double dy : new double[]{0.1D, 0.9D, 1.7D}) {
            for (double dx : new double[]{-0.3D, 0.3D}) {
                for (double dz : new double[]{-0.3D, 0.3D}) {
                    if (!mc.level.getBlockState(BlockPos.containing(feet.x + dx, feet.y + dy, feet.z + dz))
                            .getCollisionShape(mc.level, BlockPos.containing(feet.x + dx, feet.y + dy, feet.z + dz)).isEmpty()) return false;
                }
            }
        }
        return true;
    }

    /** Nothing solid in the column above (x, z) from y0 up to y1 + player height. */
    private static boolean columnFree(Minecraft mc, double x, double z, double y0, double y1) {
        for (double y = y0 + 0.1D; y <= y1 + 1.8D; y += 0.5D) {
            for (double dx : new double[]{-0.3D, 0.3D}) {
                for (double dz : new double[]{-0.3D, 0.3D}) {
                    BlockPos bp = BlockPos.containing(x + dx, y, z + dz);
                    if (!mc.level.getBlockState(bp).getCollisionShape(mc.level, bp).isEmpty()) return false;
                }
            }
        }
        return true;
    }

    /**
     * Spot next to the hanging body for the punch: the player's box must fit there, and preferably the whole column
     * below it must be free, so the player can jump straight up to it (even if blocks are above where he stands now).
     * Tries 16 directions around the body, starting from the player's side.
     */
    private static void pickStrikeSpot(Minecraft mc, LocalPlayer p) {
        double base = Math.atan2(-apexDir.z, -apexDir.x);
        Vec3 fallback = null;
        for (double dist : new double[]{1.15D, 0.8D, 1.5D}) {
            for (int k = 0; k < 16; k++) {
                double a = base + ((k + 1) / 2) * (k % 2 == 0 ? 1 : -1) * Math.PI / 8.0D;
                Vec3 off = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
                Vec3 feet = apex.add(off.scale(dist)).add(0.0D, -1.35D, 0.0D);
                if (!boxFree(mc, feet)) continue;
                if (columnFree(mc, feet.x, feet.z, playerStart.y, feet.y)) {
                    strikeFeet = feet;
                    apexDir = off.scale(-1.0D);
                    return;
                }
                if (fallback == null) {
                    fallback = feet;
                }
            }
        }
        if (fallback != null) {
            strikeFeet = fallback;
            Vec3 d = apex.subtract(fallback).multiply(1.0D, 0.0D, 1.0D);
            if (d.lengthSqr() > 1.0E-4D) apexDir = d.normalize();
        } else {
            // nowhere to stand next to it: punch from right below
            strikeFeet = apex.add(0.0D, -2.3D, 0.0D);
        }
    }

    private static void startPrep(Vec3 top) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        apex = top;
        Vec3 d = apex.subtract(p.position()).multiply(1.0D, 0.0D, 1.0D);
        apexDir = d.lengthSqr() < 0.04D ? dirH : d.normalize();
        pickStrikeSpot(mc, p);
        // first run under the spot (on the ground), then jump straight up
        launchSpot = new Vec3(strikeFeet.x, playerStart.y, strikeFeet.z);
        // while the player jumps the camera stays on the hanging ragdoll: from above and to the side, looking down
        // at it, so the player flies into the frame from below (under a low ceiling it stays lower)
        Vec3 want = apex.add(side.scale(3.4D)).subtract(apexDir.scale(1.0D)).add(0.0D, 2.8D, 0.0D);
        topCam = CamUtil.clearOfBlocks(mc, apex, want, 1.5D);
        // side view at the top for the punch
        strikeMid = strikeFeet.add(0.0D, 1.4D, 0.0D).add(apex).scale(0.5D);
        Vec3 sc = strikeMid.add(side.scale(3.8D)).subtract(apexDir.scale(0.4D)).add(0.0D, 0.25D, 0.0D);
        strikeCam = CamUtil.clearOfBlocks(mc, strikeMid, sc, 1.5D);
        setPhase(PREP);
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
        if (mc.options.keyShift.isDown() && phase != PREP && phase != LEAP && phase != STRIKE && phase != FOLLOW) {
            finish(true);
            return;
        }
        switch (phase) {
            case INTRO -> {
                face(p, impactOrigin);
                if (phaseTicks > INTRO_TIMEOUT) finish(true);
            }
            case CLOSE -> {
                if (phaseTicks >= CLOSE_TICKS) setPhase(RISE);
            }
            case RISE -> {
                if (phaseTicks > RISE_TIMEOUT) finish(true);
            }
            case PREP -> {
                face(p, apex);
                if (phaseTicks >= PREP_TICKS) {
                    setPhase(LEAP);
                    leapFrom = p.position();
                    p.setNoGravity(true);
                    gravityOff = true;
                    mc.level.playLocalSound(p.getX(), p.getY(), p.getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.0F, 0.6F, false);
                    mc.level.playLocalSound(p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 0.5F, false);
                    PunchFx.takeoff(p.position());
                }
            }
            case LEAP -> {
                float t = Math.min(1.0F, phaseTicks / (float) LEAP_TICKS);
                place(p, leapPos(t));
                face(p, apex);
                if (t > RUN_PART) PunchFx.leapPoint(fist(p));
                if (phaseTicks >= LEAP_TICKS) {
                    setPhase(STRIKE);
                    place(p, strikeFeet);
                    p.swing(InteractionHand.MAIN_HAND);
                    PacketDistributor.sendToServer(new PunchRequest(PunchRequest.STRIKE, targetId));
                    PunchFx.strike(fist(p), apex, apexDir);
                }
            }
            case STRIKE -> {
                place(p, strikeFeet);
                if (phaseTicks >= HITSTOP) {
                    // the pause is over: the server lets the body fall right now, the camera goes with it
                    PacketDistributor.sendToServer(new PunchRequest(PunchRequest.RELEASE, targetId));
                    setPhase(FOLLOW);
                    p.setNoGravity(false);
                    gravityOff = false;
                    p.setDeltaMovement(0.0D, -0.2D, 0.0D);
                    PunchFx.cometStart();
                    startFreeCamera(p);
                }
            }
            default -> {
                if (phaseTicks < 70 && bodyCur != null) PunchFx.cometPoint(bodyCur);
                else PunchFx.cometEnd();
                if (bodyCur != null) {
                    HISTORY.addLast(bodyCur);
                    while (HISTORY.size() > STILL_WINDOW + 1) HISTORY.removeFirst();
                }
                boolean still = HISTORY.size() > STILL_WINDOW
                        && HISTORY.getLast().distanceTo(HISTORY.getFirst()) / (STILL_WINDOW / 20.0D) < STILL_SPEED;
                if (still) PunchFx.cometEnd();
                if (holdUntil < 0 && phaseTicks >= 20 && still) {
                    ORBIT.still = true;
                    holdUntil = ticks + HOLD_STILL;
                }
                // free camera: the scene lasts until the player presses Ctrl (then a hard cut back to the player)
                long win = mc.getWindow().getWindow();
                boolean ctrl = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT_CONTROL) || InputConstants.isKeyDown(win, GLFW.GLFW_KEY_RIGHT_CONTROL);
                // only a new press counts (Ctrl may still be held from sprinting when the free camera starts)
                if (!ctrl) ctrlArmed = true;
                else if (ctrlArmed && mc.screen == null) finish(false);
            }
        }
    }

    private static final double ZOOM_MIN = 2.0D, ZOOM_MAX = 14.0D, ZOOM_STEP = 0.7D;
    private static double followDist = 4.5D, followDistSmooth = 4.5D;
    /** free camera: smoothed body anchor and the smoothed distance allowed by walls (-1 = not measured yet) */
    private static Vec3 freeAnchor;
    private static double freeColl = -1.0D, freeExtent = -1.0D, freeLift = -1.0D;
    private static Vec3 freeDead, freeAnchor1;
    private static boolean ctrlArmed;

    /**
     * Hands the camera to the mouse: the player's look is turned to the current view direction (so nothing jumps)
     * and the zoom starts at the current distance from the body.
     */
    private static void startFreeCamera(LocalPlayer p) {
        Vec3 body = bodyCur != null ? bodyCur : apex;
        Vec3 from = lastPos != null ? lastPos : strikeCam;
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
        followDist = followDistSmooth = Mth.clamp(len, 2.5D, 9.0D);
        freeAnchor = null;
        freeColl = -1.0D;
        freeExtent = -1.0D;
        freeLift = -1.0D;
        freeDead = freeAnchor1 = null;
        ctrlArmed = false;
    }

    /** Mouse wheel while the combo runs: zoom on the falling ragdoll (and never switch hotbar slots meanwhile). */
    public static boolean onScroll(double delta) {
        if (!active) return false;
        if (phase == FOLLOW && delta != 0.0D) {
            followDist = Mth.clamp(followDist - Math.signum(delta) * ZOOM_STEP * Math.max(1.0D, Math.abs(delta)), ZOOM_MIN, ZOOM_MAX);
        }
        return true;
    }

    /** Share of the leap spent running to the launch spot (0 if the player already stands there). */
    private static final float RUN_PART = 0.3F;

    private static Vec3 leapPos(float t) {
        double run = leapFrom.multiply(1.0D, 0.0D, 1.0D).distanceTo(launchSpot.multiply(1.0D, 0.0D, 1.0D));
        float runPart = run < 0.3D ? 0.0F : RUN_PART;
        if (t < runPart) {
            // dash on the ground to the spot under the body, with a little hop
            double s = CamUtil.ease(t / runPart);
            double hop = Math.sin(s * Math.PI) * 0.35D;
            return new Vec3(Mth.lerp(s, leapFrom.x, launchSpot.x), Mth.lerp(s, leapFrom.y, launchSpot.y) + hop, Mth.lerp(s, leapFrom.z, launchSpot.z));
        }
        double u = runPart >= 1.0F ? 1.0D : (t - runPart) / (1.0D - runPart);
        double v = 1.0D - Math.pow(1.0D - u, 2.4D);
        Vec3 from = runPart > 0.0F ? launchSpot : leapFrom;
        return new Vec3(Mth.lerp(CamUtil.ease((float) u), from.x, strikeFeet.x), Mth.lerp(v, from.y, strikeFeet.y), Mth.lerp(CamUtil.ease((float) u), from.z, strikeFeet.z));
    }

    private static Vec3 fist(LocalPlayer p) {
        Vec3 d = apexDir != null ? apexDir : dirH;
        return p.position().add(0.0D, 1.45D, 0.0D).add(d.scale(0.45D)).add(new Vec3(-d.z, 0.0D, d.x).scale(0.3D));
    }

    private static void place(LocalPlayer p, Vec3 pos) {
        p.setPos(pos.x, pos.y, pos.z);
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0.0F;
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

    static void input(Input in) {
        in.forwardImpulse = 0.0F;
        in.leftImpulse = 0.0F;
        in.up = false;
        in.down = false;
        in.left = false;
        in.right = false;
        in.jumping = false;
        // crouch before the jump
        in.shiftKeyDown = phase == PREP;
    }

    public static void finish(boolean cancel) {
        if (!active) return;
        active = false;
        Minecraft mc = Minecraft.getInstance();
        if (gravityOff && mc.player != null) mc.player.setNoGravity(false);
        gravityOff = false;
        // tell the server the camera is done with the body (it keeps tracking it until then)
        PacketDistributor.sendToServer(new PunchRequest(cancel ? PunchRequest.CANCEL : PunchRequest.DONE, targetId));
        RagdollView.stop();
        // no effect may stay behind in the world
        PunchFx.stopAll();
    }

    // ------------------------------------------------------------------ camera

    private static Vec3 body(float partial) {
        Vec3 server = bodyCur == null ? bodyLatest : (bodyPrev == null ? bodyCur : CamUtil.lerp(bodyPrev, bodyCur, partial));
        // where the body is actually drawn wins (that is what the player sees); only an absurd distance from the
        // server track (Sable's far-away storage coordinates right after a launch) falls back to the track
        // (only for models with a real torso part, see RagdollView.stableTorso)
        Vec3 drawn = RagdollView.stableTorso();
        if (drawn != null && (server == null || drawn.distanceTo(server) < 16.0D)) return drawn;
        return server;
    }

    private static int currentShot() {
        return switch (phase) {
            case INTRO -> SHOT_INTRO;
            case CLOSE -> SHOT_CLOSE;
            case RISE -> SHOT_RISE;
            case PREP -> SHOT_TOP;
            case LEAP -> phaseTicks >= LEAP_TICKS - STRIKE_LEAD ? SHOT_STRIKE : SHOT_TOP;
            case STRIKE -> SHOT_STRIKE;
            default -> SHOT_FOLLOW;
        };
    }

    public static WheelCamera.CameraPose camera(Vec3 vanillaPos, float vanillaYaw, float vanillaPitch, float partial) {
        if (!active) return null;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return null;
        RagdollView.beginFrame();
        long now = System.nanoTime();
        float dt = lastFrame == 0L ? 0.0F : Math.min(0.1F, (now - lastFrame) / 1.0E9F);
        lastFrame = now;
        float time = ticks + partial;
        Vec3 body = body(partial);
        if (body == null) body = impactOrigin;

        int s = currentShot();
        if (s != shot) {
            // every new angle is blended in from where the camera is now (the first one from the player's view)
            boolean first = shot < 0;
            blendFromPos = first ? vanillaPos : lastPos;
            blendFromLook = first ? vanillaPos.add(Vec3.directionFromRotation(vanillaPitch, vanillaYaw).scale(6.0D)) : lastLook;
            // onto the falling body the switch is quick, it must not fall out of the frame meanwhile
            blendLen = first ? ENTRY_BLEND : (s == SHOT_FOLLOW ? FOLLOW_BLEND : BLEND);
            if (s == SHOT_FOLLOW) ORBIT.reset();
            shot = s;
            shotStart = time;
            if (s == SHOT_RISE) {
                riseCam = lastPos;
                riseLook = lastLook;
            }
        }
        float t = time - shotStart;
        Vec3 pos, look;
        switch (s) {
            case SHOT_INTRO -> {
                // wide side view of the scene with a very slow drift in
                float a = CamUtil.ease(t / 40.0F);
                pos = CamUtil.lerp(introCam, introLook, 0.08D * a);
                look = introLook;
            }
            case SHOT_CLOSE -> {
                // close-up of the hit with a slow push-in; the view keeps the NPC centred as it starts to rise
                float a = CamUtil.ease(t / CLOSE_TICKS);
                pos = CamUtil.lerp(closePos, impactOrigin, 0.18D * a);
                look = CamUtil.lerp(impactOrigin.add(0.0D, 0.1D, 0.0D), body, 0.6D + 0.4D * a);
            }
            case SHOT_RISE -> {
                // low beside the launch spot, looking up at the slowly rising body
                double ground = playerStart.y;
                Vec3 want = new Vec3(body.x, 0.0D, body.z).add(side.scale(5.5D)).subtract(dirH.scale(0.8D));
                want = new Vec3(want.x, ground + 1.0D + Math.max(0.0D, body.y - ground) * 0.3D, want.z);
                want = CamUtil.clearOfBlocks(mc, body, CamUtil.freeStart(mc, want), 1.5D);
                riseCam = riseCam == null ? want : riseCam.lerp(want, 1.0D - Math.exp(-dt * 2.0D));
                riseLook = riseLook == null ? body : riseLook.lerp(body, 1.0D - Math.exp(-dt * 8.0D));
                pos = riseCam;
                look = riseLook;
            }
            case SHOT_TOP -> {
                // top-side view on the ragdoll with a slow drift in; aims a little below it, where the player comes from
                float a = CamUtil.ease(t / (PREP_TICKS + LEAP_TICKS - STRIKE_LEAD));
                pos = CamUtil.lerp(topCam, body, 0.15D * a);
                look = body.add(0.0D, -0.7D, 0.0D);
            }
            case SHOT_STRIKE -> {
                // side view of the top, slow push-in; looks between the fist and the body
                float a = CamUtil.ease(t / (STRIKE_LEAD + HITSTOP));
                pos = CamUtil.lerp(strikeCam, strikeMid, 0.2D * a);
                look = CamUtil.lerp(p.getPosition(partial).add(0.0D, 1.4D, 0.0D), strikeMid, 0.5D + 0.5D * a);
            }
            default -> {
                // Free camera around the falling NPC, like the player's own ragdoll on G: the mouse turns the view
                // around the body (it turns the player's look, which the camera reads), the wheel zooms, and the
                // view always points at the body.
                followDistSmooth += (followDist - followDistSmooth) * (1.0D - Math.exp(-dt * 10.0D));
                // The anchor: a dead zone swallows the tiny shivering of a body lying on the ground (physics contacts),
                // then two smoothing stages in a row turn every remaining step into a soft glide.
                if (freeDead == null || body.distanceTo(freeDead) > 0.05D) freeDead = body;
                if (freeAnchor1 == null || freeAnchor1.distanceTo(freeDead) > 6.0D) {
                    freeAnchor1 = freeAnchor = freeDead;
                } else {
                    double k = 1.0D - Math.exp(-dt * 12.0D);
                    freeAnchor1 = freeAnchor1.lerp(freeDead, k);
                    freeAnchor = freeAnchor.lerp(freeAnchor1, k);
                }
                Vec3 dir = Vec3.directionFromRotation(p.getViewXRot(partial), p.getViewYRot(partial));
                // never closer than the body's own size (inside its parts the view flickers)
                // (smoothed: with long-limbed models the measured size changes as limbs leave the screen)
                double extent = Math.min(3.0D, RagdollView.torso() != null ? RagdollView.extent() : 1.0D);
                if (freeExtent < 0.0D) freeExtent = extent;
                else freeExtent += (extent - freeExtent) * (1.0D - Math.exp(-dt * (extent > freeExtent ? 2.0D : 0.4D)));
                double want = Math.max(followDistSmooth, freeExtent + 1.0D);
                // Walls: the allowed distance is measured every frame, but the camera only moves in quickly and
                // goes back out slowly, so rays that graze the ground or leaves do not make it jump back and forth.
                // The camera turns around a fixed pivot 0.8 above the body. A body lying on the ground used to put the
                // pivot into the ground every other frame, and lifting it out in 0.25 steps shook the whole view;
                // now the lift only changes when the pivot really is inside a block, and then glides.
                double needLift = 0.8D;
                Vec3 probe = freeAnchor.add(0.0D, needLift, 0.0D);
                if (CamUtil.solid(mc, BlockPos.containing(probe))) needLift = CamUtil.freeStart(mc, probe).y - freeAnchor.y;
                freeLift = freeLift < 0.0D ? needLift : freeLift + (needLift - freeLift) * (1.0D - Math.exp(-dt * 4.0D));
                Vec3 pivot = freeAnchor.add(0.0D, freeLift, 0.0D);
                double allowed = CamUtil.safeCamera(mc, pivot, pivot.subtract(dir.scale(want))).distanceTo(pivot);
                if (freeColl < 0.0D) freeColl = allowed;
                else if (allowed < freeColl) freeColl = Mth.clamp(freeColl + (allowed - freeColl) * (1.0D - Math.exp(-dt * 30.0D)), allowed, allowed + 0.15D);
                else freeColl += (allowed - freeColl) * (1.0D - Math.exp(-dt * 3.0D));
                pos = pivot.subtract(dir.scale(freeColl));
                // looking along the mouse direction through the pivot: the image only moves when the mouse does
                look = pivot.add(dir);
            }
        }
        float b = CamUtil.ease(t / blendLen);
        if (b < 1.0F && blendFromPos != null) {
            pos = CamUtil.lerp(blendFromPos, pos, b);
            // on the falling ragdoll the view always points straight at it, only the position glides
            if (s != SHOT_FOLLOW) look = CamUtil.lerp(blendFromLook, look, b);
        }
        lastPos = pos;
        lastLook = look;
        return CamUtil.pose(pos, look, true);
    }
}

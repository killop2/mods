package com.example.wheelofwonder.client;

import com.example.wheelofwonder.StrangeEntity;
import com.example.wheelofwonder.StrangeHammerItem;
import com.example.wheelofwonder.StrangeRequest;
import com.example.wheelofwonder.StrangeServer;
import com.example.wheelofwonder.StrangeState;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Strange's Hammer on the client: the right click and the camera.
 *  SHOT   - from the side and above: the target in the middle, the sorcerer and his portals beside it; a slow
 *           drift round the scene; the wheel brings it nearer or farther,
 *  FOLLOW - from the hammer's blow the camera goes straight on from where it is as a free orbit round the flying
 *           body (mouse turns, wheel zooms).
 * Ctrl: back to the hero at once. HUD hidden and movement locked meanwhile (ClientSetup).
 */
public final class StrangeController {
    private static final int SHOT = 0, FOLLOW = 1;
    private static final float ENTRY_BLEND = 14.0F, FOLLOW_BLEND = 8.0F;

    private static boolean active, ctrlArmed;
    private static int phase, ticks, phaseTicks, targetId = -1, actId = -1, swingUntil = -1;
    private static Vec3 anchor, dirH = new Vec3(0.0D, 0.0D, 1.0D), side = new Vec3(1.0D, 0.0D, 0.0D);
    private static Vec3 bodyLatest, bodyPrev, bodyCur, lastPos, lastLook, blendFromPos, blendFromLook;
    private static int shot = -1;
    private static float shotStart, blendLen = ENTRY_BLEND;
    private static long lastFrame;
    private static double shotZoom = 1.0D, shotZoomSmooth = 1.0D;

    private StrangeController() {
    }

    public static boolean isActive() {
        return active;
    }

    // ------------------------------------------------------------------ input

    /** Right click with the hammer. @return true if the click is used up */
    public static boolean tryStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !StrangeHammerItem.isHammer(p.getMainHandItem())) return false;
        if (active || ClientSetup.cinematic() || p.getCooldowns().isOnCooldown(p.getMainHandItem().getItem())) return true;
        Entity t = pick(mc, p);
        if (t == null) {
            p.displayClientMessage(Component.translatable("wheelofwonder.msg.no_target", (int) StrangeServer.RANGE), true);
            return true;
        }
        targetId = t.getId();
        actId = -1;
        anchor = t.position();
        Vec3 d = anchor.subtract(p.position()).multiply(1.0D, 0.0D, 1.0D);
        dirH = d.lengthSqr() < 1.0E-4D ? p.getViewVector(1.0F).multiply(1.0D, 0.0D, 1.0D).normalize() : d.normalize();
        Vec3 right = new Vec3(-dirH.z, 0.0D, dirH.x);
        Vec3 c = anchor.add(0.0D, 4.0D, 0.0D);
        double fr = CamUtil.freeLength(mc, c, c.add(right.scale(9.0D)).add(0.0D, 4.0D, 0.0D), 0.0D);
        double fl = CamUtil.freeLength(mc, c, c.add(right.scale(-9.0D)).add(0.0D, 4.0D, 0.0D), 0.0D);
        int sign = fr >= fl ? 1 : -1;
        side = right.scale(sign);
        active = true;
        ctrlArmed = false;
        phase = SHOT;
        ticks = phaseTicks = 0;
        bodyLatest = bodyPrev = bodyCur = null;
        lastPos = lastLook = null;
        shot = -1;
        lastFrame = 0L;
        shotZoom = shotZoomSmooth = 1.0D;
        freeAnchor = freeDead = freeAnchor1 = null;
        freeColl = freeExtent = freeLift = -1.0D;
        swingUntil = p.tickCount + 24;
        PacketDistributor.sendToServer(new StrangeRequest(StrangeRequest.START, targetId, sign));
        // the hero raises the hammer: a ring of sparks round him
        for (int i = 0; i < 30; i++) {
            double a = i * Math.PI * 2.0D / 30.0D;
            mc.level.addParticle(i % 2 == 0 ? ParticleTypes.SMALL_FLAME : ParticleTypes.ELECTRIC_SPARK, p.getX() + Math.cos(a) * 0.8D, p.getY() + 1.2D,
                    p.getZ() + Math.sin(a) * 0.8D, -Math.sin(a) * 0.15D, 0.05D, Math.cos(a) * 0.15D);
        }
        return true;
    }

    private static Entity pick(Minecraft mc, LocalPlayer p) {
        double r = StrangeServer.RANGE;
        Vec3 eye = p.getEyePosition(), look = p.getViewVector(1.0F);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(r), x -> x instanceof LivingEntity le && le.isAlive()
                && !(x instanceof Player) && !(x instanceof ArmorStand) && !x.isSpectator())) {
            Vec3 c = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
            Vec3 d = c.subtract(eye);
            double len = d.length();
            if (len > r || len < 1.0D) continue;
            double ang = Math.toDegrees(Math.acos(Mth.clamp(look.dot(d.scale(1.0D / len)), -1.0D, 1.0D)));
            if (ang > 25.0D || CamUtil.freeLength(mc, eye, c, 0.0D) < len - 0.8D) continue;
            double score = ang + len * 0.12D;
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ server events

    public static void onState(StrangeState s) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!active || p == null) return;
        Vec3 pos = new Vec3(s.x(), s.y(), s.z());
        switch (s.phase()) {
            case StrangeState.START -> {
                actId = s.actId();
                anchor = pos;
            }
            case StrangeState.CAPTURE -> {
                if (s.targetId() != targetId || phase == FOLLOW) return;
                bodyLatest = bodyPrev = bodyCur = pos;
                RagdollView.track(targetId);
                phase = FOLLOW;
                phaseTicks = 0;
                startFreeCamera(p);
            }
            case StrangeState.TRACK -> {
                if (s.targetId() == targetId && (bodyLatest == null || pos.distanceTo(bodyLatest) < 10.0D)) bodyLatest = pos;
            }
            case StrangeState.END -> finish();
            default -> {
            }
        }
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
        bodyPrev = bodyCur == null ? bodyLatest : bodyCur;
        bodyCur = bodyLatest;
        long win = mc.getWindow().getWindow();
        boolean ctrl = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT_CONTROL) || InputConstants.isKeyDown(win, GLFW.GLFW_KEY_RIGHT_CONTROL);
        if (!ctrl) ctrlArmed = true;
        else if (ctrlArmed && mc.screen == null) {
            finish();
            return;
        }
        if (phase == SHOT && phaseTicks > StrangeEntity.HIT + 60) finish();
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
        PacketDistributor.sendToServer(new StrangeRequest(StrangeRequest.DONE, targetId, 0));
        RagdollView.stop();
    }

    /** Pose hook: the hero raises the hammer high while calling the sorcerer. */
    public static void applyPose(HumanoidModel<?> m, LivingEntity e, float ageInTicks) {
        if (swingUntil < 0 || e != Minecraft.getInstance().player) return;
        float left = swingUntil - ageInTicks;
        if (left < 0.0F) {
            swingUntil = -1;
            return;
        }
        float w = Math.min(1.0F, left / 6.0F) * Math.min(1.0F, (24.0F - left) / 4.0F);
        m.rightArm.xRot = Mth.lerp(w, m.rightArm.xRot, -2.9F);
        m.rightArm.zRot = Mth.lerp(w, m.rightArm.zRot, -0.2F);
        m.leftArm.xRot = Mth.lerp(w, m.leftArm.xRot, -1.3F);
        m.leftArm.zRot = Mth.lerp(w, m.leftArm.zRot, 0.3F);
    }

    // ------------------------------------------------------------------ camera

    private static final double ZOOM_MIN = 2.0D, ZOOM_MAX = 18.0D, ZOOM_STEP = 0.8D, LIFT = 0.8D;
    private static double followDist = 7.0D, followDistSmooth = 7.0D;
    private static Vec3 freeAnchor, freeDead, freeAnchor1;
    private static double freeColl = -1.0D, freeExtent = -1.0D, freeLift = -1.0D;

    public static boolean onScroll(double delta) {
        if (!active) return false;
        if (delta == 0.0D) return true;
        if (phase == FOLLOW) {
            followDist = Mth.clamp(followDist - Math.signum(delta) * ZOOM_STEP * Math.max(1.0D, Math.abs(delta)), ZOOM_MIN, ZOOM_MAX);
        } else {
            shotZoom = Mth.clamp(shotZoom * (delta > 0.0D ? 0.88D : 1.0D / 0.88D), 0.35D, 2.2D);
        }
        return true;
    }

    /** The free camera starts exactly where the shot camera stood: the view is turned from there onto the body. */
    private static void startFreeCamera(LocalPlayer p) {
        freeAnchor = freeDead = freeAnchor1 = null;
        freeColl = freeExtent = freeLift = -1.0D;
        Vec3 body = bodyLatest, from = lastPos;
        if (body == null || from == null) {
            followDist = followDistSmooth = 8.0D;
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
        followDist = followDistSmooth = Mth.clamp(len, 4.0D, 14.0D);
    }

    private static Vec3 body(float partial) {
        Vec3 server = bodyCur == null ? bodyLatest : (bodyPrev == null ? bodyCur : CamUtil.lerp(bodyPrev, bodyCur, partial));
        Vec3 drawn = RagdollView.stableTorso();
        if (drawn != null && (server == null || drawn.distanceTo(server) < 16.0D)) return drawn;
        return server;
    }

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
        int s = phase;
        if (s != shot) {
            boolean first = shot < 0;
            blendFromPos = first || lastPos == null ? vanillaPos : lastPos;
            blendFromLook = first || lastLook == null ? vanillaPos.add(Vec3.directionFromRotation(vanillaPitch, vanillaYaw).scale(6.0D)) : lastLook;
            blendLen = first ? ENTRY_BLEND : FOLLOW_BLEND;
            shot = s;
            shotStart = time;
        }
        float t = time - shotStart;
        Vec3 pos, look;
        if (s == SHOT) {
            shotZoomSmooth += (shotZoom - shotZoomSmooth) * (1.0D - Math.exp(-dt * 8.0D));
            // from the side and above: the target, with the sorcerer and (later) the hammer's portal beside it
            Entity target = mc.level.getEntity(targetId);
            Vec3 tc = target != null ? target.getPosition(partial).add(0.0D, target.getBbHeight() * 0.5D, 0.0D) : anchor.add(0.0D, 1.0D, 0.0D);
            Vec3 focus = tc;
            if (mc.level.getEntity(actId) instanceof StrangeEntity act) {
                double st = act.tickCount + partial;
                Vec3 bodyNow = st >= StrangeEntity.SPIKE0 ? act.body(st) : tc;
                Vec3 sorcerer = act.strangeFeet(st).add(0.0D, 1.2D, 0.0D);
                double w = Mth.clamp((st - StrangeEntity.P2_OPEN0 + 20.0D) / 20.0D, 0.0D, 1.0D);
                Vec3 mid = sorcerer.lerp(act.hammerPortal(), 0.5D * w);
                focus = bodyNow.lerp(mid, 0.3D);
            }
            double orbit = Math.toRadians(-10.0D + 25.0D * Math.min(1.0D, time / 160.0D));
            Vec3 out = side.scale(Math.cos(orbit)).add(dirH.scale(-Math.sin(orbit) - 0.6D)).normalize();
            Vec3 want = focus.add(out.scale(15.0D * shotZoomSmooth)).add(0.0D, 6.5D * shotZoomSmooth, 0.0D);
            pos = CamUtil.clearOfBlocks(mc, focus, CamUtil.freeStart(mc, want), 2.0D);
            look = focus;
        } else {
            pos = follow(mc, p, partial, dt);
            if (pos == null) pos = lastPos != null ? lastPos : vanillaPos;
            look = pos.add(Vec3.directionFromRotation(p.getViewXRot(partial), p.getViewYRot(partial)));
        }
        float b = CamUtil.ease(t / blendLen);
        if (b < 1.0F && blendFromPos != null) {
            pos = CamUtil.lerp(blendFromPos, pos, b);
            if (s != FOLLOW) look = CamUtil.lerp(blendFromLook, look, b);
        }
        if (s != FOLLOW) pos = CamUtil.roomyAlong(mc, pos, look);
        lastPos = pos;
        lastLook = look;
        return CamUtil.pose(pos, look, true);
    }

    /** Free orbit round the body (mouse turns, wheel zooms); never inside blocks. */
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

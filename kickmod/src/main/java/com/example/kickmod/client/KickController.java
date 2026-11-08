package com.example.kickmod.client;

import com.example.kickmod.KickPayload;
import com.example.kickmod.KickServer;
import com.example.kickmod.TrackPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Client state machine of one hit:
 *  wind-up (side camera) -> impact (server ragdolls the target) -> free camera on the ragdoll -> Ctrl -> back to you.
 *
 * The free camera works like the Walrus PUNCH gloves of Wheel of Wonder: the mouse turns the view around the body,
 * the mouse wheel zooms, walls never cut into the view, and a new press of Ctrl cuts back to the player.
 * The player cannot move during the whole sequence; mouse look is locked only until the hit lands.
 */
public final class KickController {
    /** Max distance to the target when pressing the key, blocks. */
    private static final double REACH = 3.5D;
    /** Blend from the normal view to the side camera, ticks. */
    private static final float ENTER = 10.0F;
    /** The side camera holds this long before the hit animation starts (1 s). */
    private static final float WAIT = 20.0F;
    /** Glide from the side camera onto the ragdoll, ticks. */
    private static final float TO_FOLLOW = 10.0F;
    /** Free camera zoom (distance from the body), blocks. */
    private static final double ZOOM_MIN = 2.0D, ZOOM_MAX = 14.0D, ZOOM_STEP = 0.7D;

    private static boolean active;
    private static int ticks;
    private static boolean impactSent;
    private static int targetId;
    private static LivingEntity target;
    private static CameraType savedCamera;
    /** direction of the kick; the player's body keeps it while the mouse turns the free camera */
    private static float kickYaw;
    // the hit chosen for this press (random, never the same twice in a row)
    private static AnimConfig.Entry entry;
    private static KickAnim anim;
    private static float slow = 1.0F;
    private static String lastFile;

    // side camera (fixed during the wind-up)
    private static Vec3 sidePos, sideLook;
    private static Vec3 sideDir;
    // tracked ragdoll body
    private static Vec3 bodyPrev, bodyCur, bodyLatest, impactOrigin;
    private static boolean drawnMismatchLogged;
    private static long lastFrameNanos;
    // last camera pose we produced (start of the glide onto the ragdoll)
    private static Vec3 lastPos, lastLook;
    private static Vec3 freeFromPos, freeFromLook;

    // free camera on the ragdoll (same as the gloves): zoom, smoothed anchor, wall distance, pivot lift
    private static boolean freeCam;
    private static boolean ctrlArmed;
    private static double followDist = 4.5D, followDistSmooth = 4.5D;
    private static Vec3 freeAnchor, freeAnchor1, freeDead;
    private static double freeColl = -1.0D, freeExtent = -1.0D, freeLift = -1.0D;

    private static final float[] POSE = new float[KickAnim.CHANNELS];

    private KickController() {
    }

    public static boolean isActive() {
        return active;
    }

    /** Movement is frozen for the whole sequence (the camera may be far from the player). */
    public static boolean isLocked() {
        return active;
    }

    /** Mouse look is frozen until the hit lands; then the mouse turns the free camera. */
    public static boolean isLookLocked() {
        return active && !freeCam;
    }

    // ------------------------------------------------------------------ start / tick

    public static void tryStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (active || p == null || mc.level == null || p.isSpectator() || !p.isAlive() || mc.screen != null) return;
        LivingEntity t = findTarget(mc, p);
        if (t == null) {
            p.displayClientMessage(Component.translatable("kickmod.msg.no_target"), true);
            return;
        }
        if (!pickAnimation(p)) return;
        active = true;
        ticks = 0;
        impactSent = false;
        target = t;
        targetId = t.getId();
        bodyPrev = bodyCur = bodyLatest = impactOrigin = null;
        RagdollView.track(targetId);
        drawnMismatchLogged = false;
        lastFrameNanos = 0L;
        lastPos = lastLook = null;
        freeFromPos = freeFromLook = null;
        freeCam = false;

        // face the target
        Vec3 d = t.position().subtract(p.position());
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        kickYaw = yaw;
        p.setYRot(yaw);
        p.yRotO = yaw;
        p.setYHeadRot(yaw);
        p.yHeadRotO = yaw;
        p.yBodyRot = yaw;
        p.yBodyRotO = yaw;
        p.setXRot(0.0F);
        p.xRotO = 0.0F;

        computeSideCamera(mc, p, t);
        savedCamera = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        PacketDistributor.sendToServer(new KickPayload(targetId, KickPayload.START));
    }

    public static void tick() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isAlive()) {
            finish();
            return;
        }
        ticks++;
        // the body keeps facing where the kick went (in the free camera the mouse only turns the view)
        p.yBodyRot = kickYaw;
        p.yBodyRotO = kickYaw;
        p.setYHeadRot(kickYaw);
        p.yHeadRotO = kickYaw;

        if (!impactSent && ticks >= impactTick()) {
            impactSent = true;
            // remember where the body is NOW: after the launch Sable moves the hidden entity away,
            // so the entity position must never be used for the camera again
            impactOrigin = target != null ? target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D) : sideLook;
            bodyLatest = impactOrigin;
            PacketDistributor.sendToServer(new KickPayload(targetId, KickPayload.IMPACT,
                    (float) entry.launch.forward, (float) entry.launch.up));
            startFreeCamera(p);
        }
        bodyPrev = bodyCur == null ? bodyLatest : bodyCur;
        bodyCur = bodyLatest;

        if (freeCam) {
            // the scene lasts until the player presses Ctrl; only a new press counts
            // (Ctrl may still be held from sprinting when the free camera starts)
            long win = mc.getWindow().getWindow();
            boolean ctrl = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT_CONTROL) || InputConstants.isKeyDown(win, GLFW.GLFW_KEY_RIGHT_CONTROL);
            if (!ctrl) ctrlArmed = true;
            else if (ctrlArmed && mc.screen == null) finish();
        }
    }

    /** Ticks from the key press to the moment the hit lands. */
    private static float impactTick() {
        return WAIT + anim.impactTicks() * slow;
    }

    /** Random enabled animation from config/kickmod/animations.json, not the same as last time. */
    private static boolean pickAnimation(LocalPlayer p) {
        AnimConfig.Root cfg = AnimConfig.load();
        KickAnim.clearCache(); // pick up edited / added .anim files
        java.util.List<AnimConfig.Entry> ok = new java.util.ArrayList<>();
        for (AnimConfig.Entry x : cfg.animations) {
            if (x != null && x.enabled && x.file != null && KickAnim.get(x.file) != null) ok.add(x);
        }
        if (ok.isEmpty()) {
            p.displayClientMessage(Component.literal("[kickmod] no animations enabled in config/kickmod/animations.json"), true);
            return false;
        }
        if (ok.size() > 1) ok.removeIf(x -> x.file.equals(lastFile));
        entry = ok.get(p.getRandom().nextInt(ok.size()));
        anim = KickAnim.get(entry.file);
        slow = (float) entry.slow;
        lastFile = entry.file;
        return true;
    }

    /**
     * Hands the camera to the mouse: the player's look is turned to the direction from the side camera to the body
     * (so nothing jumps) and the zoom starts at that distance.
     */
    private static void startFreeCamera(LocalPlayer p) {
        Vec3 body = impactOrigin;
        Vec3 from = lastPos != null ? lastPos : sidePos;
        freeCam = true;
        ctrlArmed = false;
        freeFromPos = from;
        freeFromLook = lastLook != null ? lastLook : sideLook;
        freeAnchor = freeAnchor1 = freeDead = null;
        freeColl = freeExtent = freeLift = -1.0D;
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
    }

    /** Mouse wheel during the sequence: zoom the free camera (and never switch hotbar slots meanwhile). */
    public static boolean onScroll(double delta) {
        if (!active) return false;
        if (freeCam && delta != 0.0D) {
            followDist = Mth.clamp(followDist - Math.signum(delta) * ZOOM_STEP * Math.max(1.0D, Math.abs(delta)), ZOOM_MIN, ZOOM_MAX);
        }
        return true;
    }

    public static void onTrack(TrackPayload p) {
        if (!active || p.entityId() != targetId) return;
        Vec3 pos = new Vec3(p.x(), p.y(), p.z());
        // never send the camera somewhere far away (unloaded area = only sky)
        // (the server already drops impossible jumps; this only guards against a glitch per tick)
        if (impactOrigin == null || (bodyLatest != null && pos.distanceTo(bodyLatest) > 10.0D)) return;
        bodyLatest = pos;
    }

    public static void finish() {
        if (!active) return;
        active = false;
        freeCam = false;
        target = null;
        RagdollView.stop();
        // the server keeps sending the body position until the camera is done with it
        if (impactSent) PacketDistributor.sendToServer(new KickPayload(targetId, KickPayload.DONE));
        Minecraft mc = Minecraft.getInstance();
        if (savedCamera != null) mc.options.setCameraType(savedCamera);
        savedCamera = null;
    }

    private static LivingEntity findTarget(Minecraft mc, LocalPlayer p) {
        HitResult hr = mc.hitResult;
        if (hr instanceof EntityHitResult ehr && KickServer.isKickable(ehr.getEntity())
                && Math.sqrt(ehr.getEntity().getBoundingBox().distanceToSqr(p.getEyePosition())) <= REACH + 0.5D) {
            return (LivingEntity) ehr.getEntity();
        }
        Vec3 look = p.getLookAngle().multiply(1.0D, 0.0D, 1.0D).normalize();
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(REACH + 1.0D), KickServer::isKickable)) {
            Vec3 to = e.position().subtract(p.position()).multiply(1.0D, 0.0D, 1.0D);
            // distance to the edge of the hitbox, not to its centre: big NPCs (Mommy, CatNap) are wide
            double dist = Math.sqrt(e.getBoundingBox().distanceToSqr(p.position().add(0.0D, 0.5D, 0.0D)));
            if (dist > REACH || p.getY() < e.getY() - 2.0D || p.getY() > e.getBoundingBox().maxY) continue;
            double dot = dist < 1.0E-3D ? 1.0D : to.scale(1.0D / dist).dot(look);
            if (dot < 0.6D || !p.hasLineOfSight(e)) continue;
            double score = dist * (2.0D - dot);
            if (score < bestScore) {
                bestScore = score;
                best = (LivingEntity) e;
            }
        }
        return best;
    }

    private static void computeSideCamera(Minecraft mc, LocalPlayer p, LivingEntity t) {
        Vec3 pp = p.position(), tp = t.position();
        Vec3 dir = tp.subtract(pp).multiply(1.0D, 0.0D, 1.0D);
        dir = dir.lengthSqr() < 1.0E-4D ? p.getLookAngle().multiply(1.0D, 0.0D, 1.0D).normalize() : dir.normalize();
        sideDir = dir;
        AnimConfig.Camera cam = entry.camera;
        Vec3 ground = pp.add(tp).scale(0.5D);
        Vec3 mid = ground.add(0.0D, 1.0D, 0.0D);
        // the further apart the two are, the further the camera, so both stay in frame
        double dist = Math.max(1.5D, cam.distance) + Math.max(0.0D, pp.distanceTo(tp) - 1.0D) * 0.8D;
        Vec3 side = new Vec3(-dir.z, 0.0D, dir.x); // attacker's right
        String which = cam.side == null ? "auto" : cam.side.toLowerCase(java.util.Locale.ROOT);
        int[] sides = which.equals("right") ? new int[]{1} : which.equals("left") ? new int[]{-1} : new int[]{1, -1};
        Vec3 bestPos = null;
        double bestFree = -1.0D;
        for (int s : sides) {
            Vec3 want = mid.add(side.scale(s * dist)).add(0.0D, cam.height, 0.0D).add(dir.scale(cam.forward));
            Vec3 pos = clip(mc, p, mid, want);
            double free = pos.distanceTo(mid);
            if (free > bestFree + 0.25D) {
                bestFree = free;
                bestPos = pos;
            }
        }
        sidePos = bestPos;
        sideLook = ground.add(0.0D, cam.lookHeight, 0.0D);
    }

    /** Camera position between from and to that does not go through blocks. */
    private static Vec3 clip(Minecraft mc, Entity p, Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 1.0E-3D) return to;
        HitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p));
        if (!(hit instanceof BlockHitResult bh) || hit.getType() == HitResult.Type.MISS) return to;
        double hitDist = hit.getLocation().distanceTo(from);
        // Sable also reports hits on its physics parts (the ragdoll itself), sometimes in its far-away
        // storage coordinates. Only real world blocks on the segment may stop the camera.
        if (hitDist > len + 0.01D || mc.level.getBlockState(bh.getBlockPos()).isAir()
                || bh.getBlockPos().distSqr(BlockPos.containing(hit.getLocation())) > 4.0D) {
            return to;
        }
        double free = Mth.clamp(hitDist - 0.3D, 0.5D, len);
        return from.add(d.scale(free / len));
    }

    // ------------------------------------------------------------------ animation pose

    /** Kick pose for the local player, or null when the player is not kicking. */
    public static float[] pose(Entity e) {
        if (!active) return null;
        Minecraft mc = Minecraft.getInstance();
        if (e != mc.player) return null;
        float t = (ticks + mc.getTimer().getGameTimeDeltaPartialTick(true) - WAIT) / slow;
        if (t >= anim.lengthTicks()) return null;
        // during the wait the player stands in the first (guard) pose of the kick
        return anim.sample(Math.max(0.0F, t), POSE);
    }

    // ------------------------------------------------------------------ camera

    public record CameraPose(Vec3 pos, float yaw, float pitch, boolean detached) {
    }

    /**
     * Called at the end of Camera.setup.
     * @param vanillaPos/yaw/pitch what vanilla computed this frame (start of the first blend)
     */
    public static CameraPose camera(Vec3 vanillaPos, float vanillaYaw, float vanillaPitch, float partial) {
        if (!active || sidePos == null) return null;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return null;
        RagdollView.beginFrame();
        float t = ticks + partial;
        float impact = impactTick();

        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0.0F : Math.min(0.1F, (now - lastFrameNanos) / 1.0E9F);
        lastFrameNanos = now;

        Vec3 pos;
        Vec3 look;
        if (!freeCam || t < impact) {
            // 1) side camera, entered smoothly from the normal view
            Vec3 normLook = vanillaPos.add(Vec3.directionFromRotation(vanillaPitch, vanillaYaw).scale(6.0D));
            float a = ease(t / ENTER);
            pos = lerp(normalViewPos(p, vanillaPos, partial), sidePos, a);
            look = lerp(normLook, sideLook, a);
        } else {
            // 2) free camera on the ragdoll, glided into from the side camera (no cut)
            Vec3[] free = freeCamera(mc, p, body(partial), partial, dt);
            pos = free[0];
            look = free[1];
            float a = ease((t - impact) / TO_FOLLOW);
            if (a < 1.0F && freeFromPos != null) {
                pos = lerp(freeFromPos, pos, a);
                look = lerp(freeFromLook, look, a);
            }
        }
        lastPos = pos;
        lastLook = look;
        Vec3 d = look.subtract(pos);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) (-(Mth.atan2(d.y, h) * Mth.RAD_TO_DEG));
        return new CameraPose(pos, yaw, pitch, true);
    }

    /** Where the camera normally is in the perspective the player had before the kick. */
    private static Vec3 normalViewPos(LocalPlayer p, Vec3 vanillaPos, float partial) {
        if (savedCamera == null || savedCamera.isFirstPerson()) return p.getEyePosition(partial);
        return vanillaPos;
    }

    /** The body: where the ragdoll was actually drawn (RagdollView), else the server track. */
    private static Vec3 body(float partial) {
        Vec3 server;
        if (bodyCur == null) server = impactOrigin != null ? impactOrigin : sideLook;
        else server = bodyPrev == null ? bodyCur : lerp(bodyPrev, bodyCur, partial);
        Vec3 drawn = RagdollView.stableTorso();
        if (drawn != null && drawn.distanceTo(server) > 16.0D) {
            // not where the server says the body is: do not trust it (logged once per kick for diagnosis)
            if (!drawnMismatchLogged) {
                drawnMismatchLogged = true;
                com.mojang.logging.LogUtils.getLogger().info("[kickmod] drawn ragdoll {} far from tracked body {}, using the track", drawn, server);
            }
            drawn = null;
        }
        return drawn != null ? drawn : server;
    }

    /**
     * Free camera around the ragdoll (Walrus PUNCH gloves of Wheel of Wonder): the mouse turns the view around the
     * body (it turns the player's look, which the camera reads), the wheel zooms, the view always passes through
     * the body. @return {position, look-at point}
     */
    private static Vec3[] freeCamera(Minecraft mc, LocalPlayer p, Vec3 body, float partial, float dt) {
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
        // never closer than the body's own size (inside its parts the view flickers); smoothed, because with
        // long-limbed models the measured size changes as limbs leave the screen
        double extent = Math.min(3.0D, RagdollView.torso() != null ? RagdollView.extent() : 1.0D);
        if (freeExtent < 0.0D) freeExtent = extent;
        else freeExtent += (extent - freeExtent) * (1.0D - Math.exp(-dt * (extent > freeExtent ? 2.0D : 0.4D)));
        double want = Math.max(followDistSmooth, freeExtent + 1.0D);
        // The camera turns around a pivot 0.8 above the body; if that point is inside a block (a body lying on
        // the ground), the pivot is lifted out of it, smoothly.
        double needLift = 0.8D;
        Vec3 probe = freeAnchor.add(0.0D, needLift, 0.0D);
        if (solid(mc, BlockPos.containing(probe))) needLift = freeStart(mc, probe).y - freeAnchor.y;
        freeLift = freeLift < 0.0D ? needLift : freeLift + (needLift - freeLift) * (1.0D - Math.exp(-dt * 4.0D));
        Vec3 pivot = freeAnchor.add(0.0D, freeLift, 0.0D);
        // Walls: measured every frame, but the camera moves in quickly and back out slowly, so rays that graze
        // the ground or leaves do not make it jump back and forth.
        double allowed = safeCamera(mc, pivot, pivot.subtract(dir.scale(want))).distanceTo(pivot);
        if (freeColl < 0.0D) freeColl = allowed;
        else if (allowed < freeColl) freeColl = Mth.clamp(freeColl + (allowed - freeColl) * (1.0D - Math.exp(-dt * 30.0D)), allowed, allowed + 0.15D);
        else freeColl += (allowed - freeColl) * (1.0D - Math.exp(-dt * 3.0D));
        Vec3 pos = pivot.subtract(dir.scale(freeColl));
        // looking along the mouse direction through the pivot: the image only moves when the mouse does
        return new Vec3[]{pos, pivot.add(dir)};
    }

    private static boolean solid(Minecraft mc, BlockPos bp) {
        return !mc.level.getBlockState(bp).getCollisionShape(mc.level, bp).isEmpty();
    }

    /** Ray start above the body that is not inside a solid block (a body lying on the ground may sink in a bit). */
    private static Vec3 freeStart(Minecraft mc, Vec3 from) {
        for (int i = 0; i < 6; i++) {
            if (!solid(mc, BlockPos.containing(from))) return from;
            from = from.add(0.0D, 0.25D, 0.0D);
        }
        return from;
    }

    /**
     * Pulls the camera towards {@code from} if any of 8 slightly offset rays (the corners of a small box around
     * the camera, as vanilla does) hits a block, so the near plane never cuts into walls or the ground.
     */
    private static Vec3 safeCamera(Minecraft mc, Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 1.0E-3D) return to;
        double best = len;
        for (int i = 0; i < 8; i++) {
            double ox = ((i & 1) * 2 - 1) * 0.1D, oy = ((i >> 1 & 1) * 2 - 1) * 0.1D, oz = ((i >> 2 & 1) * 2 - 1) * 0.1D;
            Vec3 a = from.add(ox, oy, oz), b = to.add(ox, oy, oz);
            HitResult hit = mc.level.clip(new ClipContext(a, b, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult bh)) continue;
            // Sable reports hits on its own physics parts too, sometimes far away: only real world blocks count
            if (mc.level.getBlockState(bh.getBlockPos()).isAir() || bh.getBlockPos().distSqr(BlockPos.containing(hit.getLocation())) > 4.0D) continue;
            double dist = hit.getLocation().distanceTo(a);
            if (dist < best) best = dist;
        }
        if (best >= len) return to;
        return from.add(d.scale(Math.max(0.3D, best - 0.2D) / len));
    }

    private static float ease(float x) {
        x = Mth.clamp(x, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return a.add(b.subtract(a).scale(t));
    }
}

package com.example.wheelofwonder.client;

import com.example.wheelofwonder.ChainRequest;
import com.example.wheelofwonder.SinisterChainItem;
import com.example.wheelofwonder.WheelEntity;
import com.example.wheelofwonder.WhirlServer;
import com.example.wheelofwonder.WhirlState;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Whirlwind Whip on the client.
 *  - Every whirl around (any caster): its NPCs are drawn exactly on the ring every frame (the server's positions
 *    come a few ticks late), leaning out with the speed, head to the hero, and the chains run from the hero's raised
 *    hand to their collars (ChainClient asks here). The hero raises his arm and turns with the whirl.
 *  - The caster's own camera: from the side and above, the whole ring in view, slowly circling (like Shockwave!);
 *    after the release it follows one NPC as a free orbit (mouse turns, wheel zooms). Ctrl: back to the player.
 */
public final class WhirlClient {
    private static final class Spin {
        final int caster;
        final int[] ids;
        final Vec3[] from;
        final float start;

        Spin(int caster, int[] ids, Vec3[] from, float start) {
            this.caster = caster;
            this.ids = ids;
            this.from = from;
            this.start = start;
        }
    }

    private static final Map<Integer, Spin> SPINS = new HashMap<>();
    /** NPC id -> {its spin's caster, its place on the ring} */
    private static final Map<Integer, int[]> MEMBERS = new HashMap<>();

    private WhirlClient() {
    }

    // ------------------------------------------------------------------ input

    /** Alt + right click with the chain. @return true if the click is used up */
    public static boolean tryStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || !SinisterChainItem.isChain(p.getMainHandItem())) return false;
        long win = mc.getWindow().getWindow();
        boolean alt = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT_ALT) || InputConstants.isKeyDown(win, GLFW.GLFW_KEY_RIGHT_ALT);
        if (!alt) return false;
        if (!ClientSetup.cinematic() && !p.getCooldowns().isOnCooldown(p.getMainHandItem().getItem())) {
            PacketDistributor.sendToServer(new ChainRequest(ChainRequest.WHIRL, -1, 0));
        }
        return true;
    }

    // ------------------------------------------------------------------ server events

    public static void onState(WhirlState s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        LocalPlayer p = mc.player;
        boolean mine = p != null && s.casterId() == p.getId();
        switch (s.phase()) {
            case WhirlState.START -> {
                Entity caster = mc.level.getEntity(s.casterId());
                Vec3[] from = new Vec3[s.ids().length];
                for (int i = 0; i < from.length; i++) {
                    Entity e = mc.level.getEntity(s.ids()[i]);
                    if (e instanceof LivingEntity le) from[i] = le.position().add(0.0D, WhirlServer.neckHeight(le), 0.0D);
                    else from[i] = caster != null ? caster.position().add(0.0D, 1.5D, 0.0D) : new Vec3(s.x(), s.y() + 1.5D, s.z());
                }
                stopSpin(s.casterId());
                Spin sp = new Spin(s.casterId(), s.ids(), from, mc.level.getGameTime());
                SPINS.put(s.casterId(), sp);
                for (int i = 0; i < s.ids().length; i++) MEMBERS.put(s.ids()[i], new int[]{s.casterId(), i});
                if (mine) startCamera(p);
            }
            case WhirlState.RELEASE -> {
                Spin sp = SPINS.get(s.casterId());
                if (sp != null) releaseFx(mc, sp);
                stopSpin(s.casterId());
                if (mine && active && phase == WHIRL) setPhase(WAIT);
            }
            case WhirlState.LAUNCH -> {
                if (mine && active && phase != FOLLOW) {
                    focusId = s.entityId();
                    bodyLatest = bodyPrev = bodyCur = new Vec3(s.x(), s.y(), s.z());
                    RagdollView.track(focusId);
                    setPhase(FOLLOW);
                    startFreeCamera(p);
                }
            }
            case WhirlState.TRACK -> {
                Vec3 pos = new Vec3(s.x(), s.y(), s.z());
                if (mine && s.entityId() == focusId && (bodyLatest == null || pos.distanceTo(bodyLatest) < 10.0D)) bodyLatest = pos;
            }
            case WhirlState.END -> {
                stopSpin(s.casterId());
                if (mine) finish();
            }
            default -> {
            }
        }
    }

    private static void stopSpin(int caster) {
        Spin sp = SPINS.remove(caster);
        if (sp != null) for (int id : sp.ids) MEMBERS.remove(id);
    }

    static void clear() {
        SPINS.clear();
        MEMBERS.clear();
        finish();
    }

    // ------------------------------------------------------------------ the ring (all clients)

    private static double time(Spin sp, float partial) {
        Minecraft mc = Minecraft.getInstance();
        double t = mc.level.getGameTime() + partial - sp.start;
        return Mth.clamp(t, 0.0D, WhirlServer.GATHER + WhirlServer.SPIN + 4.0D);
    }

    /** Where this NPC's collar is drawn right now (it is on a whirling ring), or null. */
    public static Vec3 neck(Entity e, float partial) {
        int[] m = MEMBERS.get(e.getId());
        if (m == null) return null;
        Spin sp = SPINS.get(m[0]);
        Minecraft mc = Minecraft.getInstance();
        Entity caster = sp != null ? mc.level.getEntity(sp.caster) : null;
        if (caster == null) return null;
        double t = time(sp, partial);
        Vec3 ring = caster.getPosition(partial).add(WhirlServer.neckOffset(m[1], sp.ids.length, t));
        return sp.from[m[1]].lerp(ring, WhirlServer.gather(t));
    }

    /** The hero's hand swinging the chains overhead. */
    private static Vec3 hand(Spin sp, Entity caster, float partial) {
        double t = time(sp, partial);
        double a = WhirlServer.angle(t);
        double r = 0.25D + 0.2D * WhirlServer.progress(t);
        return caster.getPosition(partial).add(Math.cos(a) * r, 2.15D, Math.sin(a) * r);
    }

    /** Extra chain links: from every whirling hero's hand to each of his NPCs. */
    static List<Vec3[]> handLinks(Minecraft mc, float partial) {
        List<Vec3[]> out = new ArrayList<>();
        for (Spin sp : SPINS.values()) {
            Entity caster = mc.level.getEntity(sp.caster);
            if (caster == null) continue;
            Vec3 h = hand(sp, caster, partial);
            for (int id : sp.ids) {
                Entity e = mc.level.getEntity(id);
                Vec3 n = e != null ? neck(e, partial) : null;
                if (n != null) out.add(new Vec3[]{h, n});
            }
        }
        return out;
    }

    static boolean whirling(int id) {
        return MEMBERS.containsKey(id);
    }

    /**
     * Render hook: an NPC on a ring is moved to its place on the ring and turned so its head points at the hero and
     * its body streams outwards (more the faster it goes), pivoting round its collar.
     */
    static void placeBody(LivingEntity e, PoseStack ps, float partial) {
        int[] m = MEMBERS.get(e.getId());
        if (m == null) return;
        Spin sp = SPINS.get(m[0]);
        if (sp == null) return;
        Vec3 neck = neck(e, partial);
        if (neck == null) return;
        double t = time(sp, partial);
        double nh = WhirlServer.neckHeight(e);
        Vec3 actual = e.getPosition(partial);
        ps.translate(neck.x - actual.x, neck.y - nh - actual.y, neck.z - actual.z);
        double a = WhirlServer.angle(t) + m[1] * Math.PI * 2.0D / sp.ids.length;
        float lean = (float) (WhirlServer.lean(t) * WhirlServer.gather(t));
        ps.translate(0.0D, nh, 0.0D);
        // about the swing's tangent: the head goes in towards the hero, the feet out
        ps.mulPose(new Quaternionf().rotationAxis(lean, (float) -Math.sin(a), 0.0F, (float) Math.cos(a)));
        ps.translate(0.0D, -nh, 0.0D);
    }

    /** Pose hook: the whirling hero holds the chains up over his head and swings them round. */
    public static void applyPose(HumanoidModel<?> model, LivingEntity e, float ageInTicks) {
        Spin sp = SPINS.get(e.getId());
        if (sp == null) return;
        float partial = ageInTicks - (float) Math.floor(ageInTicks);
        double t = time(sp, partial);
        float w = (float) Math.min(1.0D, t / 6.0D);
        float circle = (float) WhirlServer.angle(t) * 2.0F;
        model.rightArm.xRot = Mth.lerp(w, model.rightArm.xRot, -2.9F + 0.15F * Mth.sin(circle));
        model.rightArm.zRot = Mth.lerp(w, model.rightArm.zRot, 0.15F * Mth.cos(circle));
        model.rightArm.yRot = Mth.lerp(w, model.rightArm.yRot, 0.0F);
        // the other arm out for balance
        model.leftArm.zRot = Mth.lerp(w, model.leftArm.zRot, -1.1F);
        model.leftArm.xRot = Mth.lerp(w, model.leftArm.xRot, -0.2F);
        model.head.xRot = Mth.lerp(w, model.head.xRot, -0.25F);
    }

    /** Burst of hellfire at every collar when the chains snap. */
    private static void releaseFx(Minecraft mc, Spin sp) {
        RandomSource r = mc.level.getRandom();
        for (int id : sp.ids) {
            Entity e = mc.level.getEntity(id);
            Vec3 n = e != null ? neck(e, 1.0F) : null;
            if (n == null) continue;
            for (int i = 0; i < 12; i++) {
                mc.level.addParticle(i % 3 == 0 ? ParticleTypes.LAVA : WheelEntity.dust(i % 2 == 0 ? 0xFF5A14 : 0xB04BFF, 1.3F),
                        n.x, n.y, n.z, r.nextGaussian() * 0.2D, r.nextDouble() * 0.2D, r.nextGaussian() * 0.2D);
            }
        }
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (!SPINS.isEmpty()) clear();
            return;
        }
        RandomSource r = mc.level.getRandom();
        for (Spin sp : new ArrayList<>(SPINS.values())) {
            Entity caster = mc.level.getEntity(sp.caster);
            double t = time(sp, 0.0F);
            if (caster == null || mc.level.getGameTime() - sp.start > WhirlServer.GATHER + WhirlServer.SPIN + 60) {
                stopSpin(sp.caster);
                continue;
            }
            // the hero turns with the whirl, facing his first NPC
            if (caster instanceof LivingEntity le) {
                float yaw = (float) Math.toDegrees(WhirlServer.angle(t)) - 90.0F;
                le.yBodyRotO = le.yBodyRot;
                le.yHeadRotO = le.yHeadRot;
                le.yBodyRot = yaw;
                le.setYHeadRot(yaw);
                if (le == mc.player) {
                    le.yRotO = le.getYRot();
                    le.setYRot(yaw);
                }
            }
            // a ring of embers and violet sparks streams off the whirl
            double prog = WhirlServer.progress(t);
            if (prog > 0.2D) {
                for (int id : sp.ids) {
                    Entity e = mc.level.getEntity(id);
                    Vec3 n = e != null ? neck(e, 0.0F) : null;
                    if (n == null || r.nextFloat() > 0.5F + 0.5F * prog) continue;
                    mc.level.addParticle(r.nextBoolean() ? ParticleTypes.SMALL_FLAME : WheelEntity.dust(r.nextBoolean() ? 0xFF5A14 : 0xB04BFF, 0.9F),
                            n.x + r.nextGaussian() * 0.3D, n.y + r.nextGaussian() * 0.3D, n.z + r.nextGaussian() * 0.3D, 0.0D, 0.0D, 0.0D);
                }
                if (r.nextFloat() < prog) {
                    double a = r.nextDouble() * Math.PI * 2.0D, rad = WhirlServer.radius(t) * (0.6D + r.nextDouble() * 0.5D);
                    Vec3 c = caster.position();
                    mc.level.addParticle(ParticleTypes.CLOUD, c.x + Math.cos(a) * rad, c.y + 0.15D, c.z + Math.sin(a) * rad, -Math.sin(a) * 0.15D, 0.02D, Math.cos(a) * 0.15D);
                }
            }
        }
        cameraTick(mc);
    }

    // ------------------------------------------------------------------ the caster's camera

    private static final int WHIRL = 0, WAIT = 1, FOLLOW = 2;
    private static final float ENTRY_BLEND = 12.0F, FOLLOW_BLEND = 8.0F;
    private static boolean active, ctrlArmed;
    private static int phase, ticks, phaseTicks, focusId = -1;
    private static Vec3 side = new Vec3(1.0D, 0.0D, 0.0D), back = new Vec3(0.0D, 0.0D, -1.0D);
    private static Vec3 bodyLatest, bodyPrev, bodyCur, lastPos, lastLook, blendFromPos, blendFromLook;
    private static int shot = -1;
    private static float shotStart, blendLen = ENTRY_BLEND;
    private static long lastFrame;

    public static boolean isActive() {
        return active;
    }

    private static void startCamera(LocalPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-4D) flat = new Vec3(0.0D, 0.0D, 1.0D);
        flat = flat.normalize();
        Vec3 right = new Vec3(-flat.z, 0.0D, flat.x);
        Vec3 c = p.position().add(0.0D, 2.0D, 0.0D);
        double fr = CamUtil.freeLength(mc, c, c.add(right.scale(10.0D)).add(0.0D, 4.0D, 0.0D), 0.0D);
        double fl = CamUtil.freeLength(mc, c, c.add(right.scale(-10.0D)).add(0.0D, 4.0D, 0.0D), 0.0D);
        side = fr >= fl ? right : right.scale(-1.0D);
        back = flat.scale(-1.0D);
        active = true;
        ctrlArmed = false;
        phase = WHIRL;
        ticks = phaseTicks = 0;
        focusId = -1;
        bodyLatest = bodyPrev = bodyCur = null;
        lastPos = lastLook = null;
        shot = -1;
        lastFrame = 0L;
        freeAnchor = freeDead = freeAnchor1 = null;
        freeColl = freeExtent = freeLift = -1.0D;
    }

    private static void setPhase(int ph) {
        phase = ph;
        phaseTicks = 0;
    }

    public static void finish() {
        if (!active) return;
        active = false;
        PacketDistributor.sendToServer(new ChainRequest(ChainRequest.WHIRL_DONE, -1, 0));
        RagdollView.stop();
    }

    private static void cameraTick(Minecraft mc) {
        if (!active) return;
        LocalPlayer p = mc.player;
        if (p == null || !p.isAlive()) {
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
        if (phase == WAIT && phaseTicks > 40) finish();
        if (phase == WHIRL && phaseTicks > WhirlServer.GATHER + WhirlServer.SPIN + 60) finish();
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

    private static final double ZOOM_MIN = 2.0D, ZOOM_MAX = 16.0D, ZOOM_STEP = 0.8D, LIFT = 0.8D;
    private static double followDist = 6.0D, followDistSmooth = 6.0D;
    private static Vec3 freeAnchor, freeDead, freeAnchor1;
    private static double freeColl = -1.0D, freeExtent = -1.0D, freeLift = -1.0D;

    public static boolean onScroll(double delta) {
        if (!active) return false;
        if (phase == FOLLOW && delta != 0.0D) {
            followDist = Mth.clamp(followDist - Math.signum(delta) * ZOOM_STEP * Math.max(1.0D, Math.abs(delta)), ZOOM_MIN, ZOOM_MAX);
        }
        return true;
    }

    /** The free camera starts where the whirl camera was: the view is turned from there onto the body. */
    private static void startFreeCamera(LocalPlayer p) {
        freeAnchor = freeDead = freeAnchor1 = null;
        freeColl = freeExtent = freeLift = -1.0D;
        Vec3 body = bodyLatest, from = lastPos;
        if (body == null || from == null) {
            followDist = followDistSmooth = 7.0D;
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
        followDist = followDistSmooth = Mth.clamp(len, 4.0D, 12.0D);
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
        int s = phase == FOLLOW ? FOLLOW : WHIRL;
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
        if (s == WHIRL) {
            // from the side and above: the hero in the middle and the whole whirling ring around him; the camera
            // circles slowly and draws back as the ring widens
            Spin sp = SPINS.get(p.getId());
            double prog = sp != null ? WhirlServer.progress(time(sp, partial)) : 1.0D;
            Vec3 centre = p.getPosition(partial).add(0.0D, 1.6D, 0.0D);
            double orbit = Math.toRadians(-15.0D + 45.0D * Math.min(1.0D, (ticks + partial) / (WhirlServer.GATHER + WhirlServer.SPIN)));
            Vec3 dir = side.scale(Math.cos(orbit)).add(back.scale(Math.sin(orbit))).normalize();
            double dist = 7.5D + 1.8D * prog;
            Vec3 want = centre.add(dir.scale(dist)).add(0.0D, 3.0D + 0.6D * prog, 0.0D);
            pos = CamUtil.clearOfBlocks(mc, centre, CamUtil.freeStart(mc, want), 1.5D);
            look = centre.add(0.0D, 0.2D, 0.0D);
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

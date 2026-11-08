package com.example.wheelofwonder.client;

import com.example.wheelofwonder.BallistaEntity;
import com.example.wheelofwonder.BallistaRequest;
import com.example.wheelofwonder.BallistaState;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Free camera on the NPC shot from the ballista, like the player's own ragdoll camera (G):
 * the mouse turns the view around the NPC, the wheel zooms, blocks never get between them, Ctrl cuts back to you.
 * It starts at your own eyes (the view is turned towards the NPC and the distance is your distance to it), so the
 * move onto the NPC is one continuous glide, no cut. It stays on the NPC in the bucket, through the shot and the
 * flight, and on the ragdoll lying wherever it landed.
 */
public final class BallistaCamera {
    private static final double ZOOM_MIN = 2.0D, ZOOM_MAX = 20.0D, ZOOM_STEP = 0.8D, START_ZOOM = 6.0D;
    /** The view turns around a point this far above the body's centre. */
    private static final double LIFT = 0.5D;

    private static boolean active;
    private static int targetId;
    private static boolean launched;
    private static Vec3 bodyLatest, bodyPrev, bodyCur;
    private static double speed;
    private static double followDist = START_ZOOM, followDistSmooth = START_ZOOM;
    private static Vec3 anchor, anchor1, dead;
    private static double coll = -1.0D, extentS = -1.0D, lift = -1.0D;
    private static boolean ctrlArmed;
    private static long lastFrame;
    /** When the camera started gliding over to a new target (0 = not gliding). */
    private static long switchAt;
    private static final double SWITCH_SECONDS = 1.1D;

    private BallistaCamera() {
    }

    public static boolean isActive() {
        return active;
    }

    private static boolean loadable(Entity e) {
        return e instanceof LivingEntity le && le.isAlive() && !(e instanceof Player) && !(e instanceof ArmorStand)
                && !e.isSpectator() && !(e.getVehicle() instanceof com.example.wheelofwonder.NpcLauncher);
    }

    /** A free ballista or rocket ride within its own load radius (server configs are synced to the client). */
    private static boolean freeLauncherNear(Minecraft mc, Entity npc) {
        return com.example.wheelofwonder.BallistaServer.nearestLauncher(mc.level, npc) != null;
    }

    /** Numpad 7: the NPC you look at (or the nearest one around you) gets into the nearest free ride. */
    static void requestLoad() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || ClientSetup.cinematic()) return;
        Entity target = null;
        if (mc.hitResult instanceof EntityHitResult ehr && loadable(ehr.getEntity()) && ehr.getEntity().distanceTo(p) <= 8.0D) {
            target = ehr.getEntity();
        } else {
            double best = Double.MAX_VALUE;
            for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(6.0D), BallistaCamera::loadable)) {
                double d = e.distanceTo(p);
                // prefer NPCs that have a free ride near them
                if (d < best && d <= 6.0D && freeLauncherNear(mc, e)) {
                    best = d;
                    target = e;
                }
            }
        }
        if (target == null) {
            p.displayClientMessage(Component.translatable("wheelofwonder.msg.no_npc"), true);
            return;
        }
        if (!freeLauncherNear(mc, target)) {
            p.displayClientMessage(Component.translatable("wheelofwonder.msg.no_ballista",
                    (int) Math.round(com.example.wheelofwonder.WheelConfig.BALLISTA_RADIUS.get()),
                    (int) Math.round(com.example.wheelofwonder.WheelConfig.ROCKET_RADIUS.get())), true);
            return;
        }
        // the camera is on the NPC at once; if the server finds no ballista it answers REJECTED and we cut back
        start(target.getId(), target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D));
        PacketDistributor.sendToServer(new BallistaRequest(BallistaRequest.LOAD, target.getId()));
    }

    /** Telekinesis Orb let go: the free camera goes onto that body now (the server's LAUNCH and TRACK follow). */
    public static void followNow(int id, Vec3 pos) {
        if (!active && !ClientSetup.cinematic()) start(id, pos);
    }

    public static void onState(int st, int id, double x, double y, double z) {
        Vec3 pos = new Vec3(x, y, z);
        if (st == BallistaState.SEATED) {
            if (!active && !ClientSetup.cinematic()) start(id, pos);
            return;
        }
        if (st == BallistaState.FOLLOW) {
            // Chaos Meteor: straight onto the meteor, a bit farther out (it is big)
            if (!active && !ClientSetup.cinematic()) {
                start(id, pos);
                followDist = followDistSmooth = 11.0D;
            }
            return;
        }
        if (st == BallistaState.RETARGET) {
            // the meteor hit an NPC: the same free camera glides over to that NPC (the view keeps its angle and zoom)
            if (!active) return;
            targetId = id;
            launched = false;
            bodyLatest = bodyPrev = bodyCur = null;
            switchAt = System.nanoTime();
            RagdollView.track(id);
            if (followDist > 8.0D) followDist = 8.0D;
            return;
        }
        if (!active || id != targetId) return;
        if (st == BallistaState.REJECTED) {
            finish();
            return;
        }
        if (st == BallistaState.LAUNCH) {
            launched = true;
            bodyLatest = bodyPrev = bodyCur = pos;
        } else if (st == BallistaState.TRACK) {
            if (!launched) {
                launched = true;
                bodyLatest = bodyPrev = bodyCur = pos;
            } else if (bodyLatest == null || pos.distanceTo(bodyLatest) < 10.0D) {
                bodyLatest = pos;
            }
        }
    }

    private static void start(int id, Vec3 npcCentre) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        Entity e = mc.level.getEntity(id);
        Vec3 centre = e != null ? e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D) : npcCentre;
        active = true;
        targetId = id;
        launched = false;
        bodyLatest = bodyPrev = bodyCur = null;
        speed = 0.0D;
        anchor = anchor1 = dead = null;
        coll = extentS = lift = -1.0D;
        ctrlArmed = false;
        lastFrame = 0L;
        switchAt = 0L;
        RagdollView.track(id);
        // turn your view onto the NPC; the camera starts exactly at your eyes and glides in from there
        Vec3 pivot = centre.add(0.0D, LIFT, 0.0D);
        Vec3 d = pivot.subtract(p.getEyePosition());
        double len = d.length();
        if (len > 1.0E-3D) {
            double h = Math.sqrt(d.x * d.x + d.z * d.z);
            float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
            float pitch = (float) (-(Mth.atan2(d.y, h) * Mth.RAD_TO_DEG));
            p.setYRot(yaw);
            p.yRotO = yaw;
            p.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
            p.xRotO = p.getXRot();
        }
        // straight onto the NPC: the camera starts at its orbit distance, no glide from the player's eyes
        followDist = followDistSmooth = START_ZOOM;
    }

    static void tick() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isAlive()) {
            finish();
            return;
        }
        bodyPrev = bodyCur == null ? bodyLatest : bodyCur;
        bodyCur = bodyLatest;
        if (bodyPrev != null && bodyCur != null) speed += (bodyCur.distanceTo(bodyPrev) * 20.0D - speed) * 0.5D;
        long win = mc.getWindow().getWindow();
        boolean ctrl = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT_CONTROL) || InputConstants.isKeyDown(win, GLFW.GLFW_KEY_RIGHT_CONTROL);
        // only a new press counts (Ctrl may still be held from sprinting)
        if (!ctrl) ctrlArmed = true;
        else if (ctrlArmed && mc.screen == null) finish();
    }

    static boolean onScroll(double delta) {
        if (!active) return false;
        if (delta != 0.0D) {
            followDist = Mth.clamp(followDist - Math.signum(delta) * ZOOM_STEP * Math.max(1.0D, Math.abs(delta)), ZOOM_MIN, ZOOM_MAX);
        }
        return true;
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
        PacketDistributor.sendToServer(new BallistaRequest(BallistaRequest.DONE, targetId));
        RagdollView.stop();
    }

    /** Where the NPC is: the entity in the bucket before the shot, the ragdoll after it. */
    private static Vec3 body(Minecraft mc, float partial) {
        if (!launched) {
            Entity e = mc.level.getEntity(targetId);
            com.example.wheelofwonder.RangerRideEntity ranger = e != null ? com.example.wheelofwonder.RangerRideEntity.rideOf(e) : null;
            if (ranger != null) {
                // in a Ranger gondola: follow the gondola itself (the NPC is drawn there)
                Vec3 g0 = ranger.gondolaWorld(partial - 0.5F), g1 = ranger.gondolaWorld(partial + 0.5F);
                speed = g1.distanceTo(g0) * 20.0D;
                return ranger.gondolaWorld(partial);
            }
            com.example.wheelofwonder.PrismCoasterEntity prism = e != null ? com.example.wheelofwonder.PrismCoasterEntity.rideOf(e) : null;
            if (prism != null) {
                // in the Prism Splitter's cart: follow the cart (the NPC is drawn there)
                Vec3 g0 = prism.riderCentre(partial - 0.5F), g1 = prism.riderCentre(partial + 0.5F);
                speed = g1.distanceTo(g0) * 20.0D;
                return prism.riderCentre(partial);
            }
            if (e != null) {
                speed = e.getDeltaMovement().length() * 20.0D + e.position().distanceTo(new Vec3(e.xo, e.yo, e.zo)) * 20.0D;
                return e.getPosition(partial).add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
            }
            return anchor;
        }
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
        Vec3 body = body(mc, partial);
        if (body == null) return null;

        followDistSmooth += (followDist - followDistSmooth) * (1.0D - Math.exp(-dt * 4.0D));
        // Anchor: a dead zone swallows the shivering of a body at rest; two smoothing stages turn the rest into a
        // glide. The faster the body flies, the tighter the smoothing, so the NPC stays centred in flight.
        double fast = Math.min(1.0D, speed / 3.0D);
        if (dead == null || body.distanceTo(dead) > 0.05D * (1.0D - fast)) dead = body;
        double rate = Math.min(220.0D, 12.0D + speed * 6.0D);
        // gliding to a new target: the follow starts loose and tightens, so the view eases over instead of cutting
        if (switchAt != 0L) {
            double s = (now - switchAt) / 1.0E9D / SWITCH_SECONDS;
            if (s >= 1.0D) switchAt = 0L;
            else rate *= 0.05D + 0.95D * CamUtil.ease((float) s);
        }
        if (anchor1 == null || (switchAt == 0L && anchor1.distanceTo(dead) > 8.0D)) {
            anchor1 = anchor = dead;
        } else {
            double k = 1.0D - Math.exp(-dt * rate);
            anchor1 = anchor1.lerp(dead, k);
            anchor = anchor.lerp(anchor1, k);
        }
        Vec3 dir = Vec3.directionFromRotation(p.getViewXRot(partial), p.getViewYRot(partial));
        // never closer than the ragdoll's own size (inside its parts the view flickers)
        double extent = launched ? Math.min(3.0D, RagdollView.torso() != null ? RagdollView.extent() : 1.0D) : 0.6D;
        if (extentS < 0.0D) extentS = extent;
        else extentS += (extent - extentS) * (1.0D - Math.exp(-dt * (extent > extentS ? 2.0D : 0.4D)));
        double want = launched ? Math.max(followDistSmooth, extentS + 1.0D) : followDistSmooth;
        // pivot above the body, lifted smoothly out of blocks (a body lying on the ground)
        double needLift = LIFT;
        Vec3 probe = anchor.add(0.0D, needLift, 0.0D);
        if (CamUtil.solid(mc, BlockPos.containing(probe))) needLift = CamUtil.freeStart(mc, probe).y - anchor.y;
        // and some room below the pivot, so the camera under it is not in the ground
        Vec3 under = anchor.add(0.0D, needLift - 0.45D, 0.0D);
        if (CamUtil.solid(mc, BlockPos.containing(under))) needLift = Math.max(needLift, CamUtil.freeStart(mc, under).y + 0.45D - anchor.y);
        // up at once (never inside blocks), down smoothly
        lift = lift < 0.0D || needLift > lift ? needLift : lift + (needLift - lift) * (1.0D - Math.exp(-dt * 4.0D));
        Vec3 pivot = anchor.add(0.0D, lift, 0.0D);
        // walls: in fast, out slowly, so grazing rays do not make the camera pump
        double allowed = CamUtil.safeCamera(mc, pivot, pivot.subtract(dir.scale(want))).distanceTo(pivot);
        if (coll < 0.0D) coll = allowed;
        else if (allowed < coll) coll = Mth.clamp(coll + (allowed - coll) * (1.0D - Math.exp(-dt * 30.0D)), allowed, allowed + 0.05D);
        else coll += (allowed - coll) * (1.0D - Math.exp(-dt * 3.0D));
        // the lens itself must have room too (a ray grazing the ground leaves it right at the surface)
        Vec3 pos = CamUtil.roomyAlong(mc, pivot.subtract(dir.scale(coll)), pivot);
        return CamUtil.pose(pos, pivot.add(dir), true);
    }
}

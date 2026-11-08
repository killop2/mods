package com.example.wheelofwonder;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Telekinesis Orb, server side. A right click on an NPC and a giant green hand takes it over, with no more help from
 * the player, for about five and a half seconds:
 * LIFT    - the ragdoll rises up under the hand, turning slowly,
 * STRETCH - its arms and legs are pulled out, spread like a puppet's, it hangs and sways,
 * WHIRL   - the hand swings it round in a circle under itself, faster and faster, the limbs flung outwards and the
 *           body tumbling,
 * FLING   - it lets go: the body flies off far, up and along its swing.
 * The caster's camera is on the body all the time (BallistaCamera: LAUNCH, then TRACK every tick; after the throw
 * BallistaServer goes on tracking it), until Ctrl.
 */
public final class TelekinesisServer {
    public static final double GRAB_RANGE = 40.0D;
    public static final int LIFT = 24, STRETCH = 26, WHIRL = 56, FLING_AT = LIFT + STRETCH + WHIRL;
    /** How high over the NPC's centre it is lifted, and the radius of the whirl. */
    public static final double LIFT_H = 4.5D, WHIRL_R = 2.6D;

    private static final class Hold {
        final ServerPlayer player;
        final LivingEntity mob;
        final Vec3 start, top;
        int age;
        double angle, prevAngle;
        Vec3 lastTarget, last;

        Hold(ServerPlayer player, LivingEntity mob, Vec3 start) {
            this.player = player;
            this.mob = mob;
            this.start = start;
            this.top = start.add(0.0D, LIFT_H, 0.0D);
            this.lastTarget = start;
            this.last = start;
        }
    }

    private static final Map<UUID, Hold> HOLDS = new HashMap<>();

    private TelekinesisServer() {
    }

    public static void handleRequest(TeleRequest req, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer p)) return;
        if (req.action() == TeleRequest.GRAB) grab(p, req.entityId());
    }

    private static void grab(ServerPlayer p, int id) {
        ServerLevel level = p.serverLevel();
        Entity e = level.getEntity(id);
        var item = p.getMainHandItem();
        if (HOLDS.containsKey(p.getUUID()) || !TelekinesisOrbItem.isOrb(item) || p.getCooldowns().isOnCooldown(item.getItem())
                || !BallistaServer.loadable(e) || e.distanceTo(p) > GRAB_RANGE + 2.0D || WheelMod.held(e) || !WheelRagdolls.available()) {
            PacketDistributor.sendToPlayer(p, new TeleState(TeleState.END, id, 0, 0, 0));
            PacketDistributor.sendToPlayer(p, new BallistaState(BallistaState.REJECTED, id, p.getX(), p.getY(), p.getZ()));
            return;
        }
        LivingEntity mob = (LivingEntity) e;
        mob.addTag(WheelMod.RAGDOLL_TAG);
        if (WheelConfig.LIMP.get()) mob.addTag(WheelMod.KICKMOD_LIMP_TAG);
        if (!WheelRagdolls.launch(level, mob, new Vec3(0.0D, 3.0D, 0.0D), new Vec3(0.0D, 0.6D, 0.0D), WheelConfig.RAGDOLL_SECONDS.get())) {
            mob.removeTag(WheelMod.RAGDOLL_TAG);
            mob.removeTag(WheelMod.KICKMOD_LIMP_TAG);
            PacketDistributor.sendToPlayer(p, new TeleState(TeleState.END, id, 0, 0, 0));
            PacketDistributor.sendToPlayer(p, new BallistaState(BallistaState.REJECTED, id, p.getX(), p.getY(), p.getZ()));
            return;
        }
        WheelMod.hold(mob);
        Vec3 c = mob.position().add(0.0D, mob.getBbHeight() * 0.5D, 0.0D);
        Hold h = new Hold(p, mob, c);
        HOLDS.put(p.getUUID(), h);
        // a long cooldown while it runs (the normal one is set when it is thrown)
        p.getCooldowns().addCooldown(item.getItem(), 20 * 30);
        broadcast(level, new TeleState(TeleState.GRABBED, id, h.top.x, h.top.y, h.top.z), h.top);
        // the caster's camera goes onto the body
        PacketDistributor.sendToPlayer(p, new BallistaState(BallistaState.LAUNCH, id, c.x, c.y, c.z));
        level.playSound(null, c.x, c.y, c.z, SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 1.3F, 0.9F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.PLAYERS, 1.0F, 1.2F);
        level.playSound(null, h.top.x, h.top.y, h.top.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.8F, 1.5F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, c.x, c.y, c.z, 30, 0.5D, 0.8D, 0.5D, 0.1D);
        level.sendParticles(WheelEntity.dust(0x1FBF3F, 1.6F), c.x, c.y - 0.8D, c.z, 30, 0.8D, 0.1D, 0.8D, 0.0D);
    }

    private static double ease(double x) {
        x = Mth.clamp(x, 0.0D, 1.0D);
        return x * x * (3.0D - 2.0D * x);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        Iterator<Hold> it = HOLDS.values().iterator();
        while (it.hasNext()) {
            Hold h = it.next();
            ServerPlayer p = h.player;
            LivingEntity mob = h.mob;
            h.age++;
            boolean lost = p.isRemoved() || mob.isRemoved() || !mob.isAlive() || mob.level() != p.level()
                    || (h.age > 20 && !WheelRagdolls.isRagdolled(mob) && !WheelRagdolls.isPending(mob));
            ServerLevel level = (ServerLevel) mob.level();
            if (lost) {
                it.remove();
                WheelMod.unhold(mob);
                broadcast(level, new TeleState(TeleState.END, mob.getId(), 0, 0, 0), h.last);
                if (!p.isRemoved()) endCooldown(p);
                continue;
            }
            mob.fallDistance = 0.0F;
            if (h.age >= FLING_AT) {
                it.remove();
                fling(h, level);
                continue;
            }
            int a = h.age;
            Vec3 target;
            Vec3 spin;
            double stretch;
            if (a < LIFT) {
                target = h.start.lerp(h.top, ease(a / (double) LIFT));
                spin = new Vec3(0.0D, 1.2D, 0.0D);
                stretch = 0.0D;
            } else if (a < LIFT + STRETCH) {
                double u = (a - LIFT) / (double) STRETCH;
                target = h.top.add(0.0D, 0.15D * Math.sin(a * 0.3D), 0.0D);
                spin = new Vec3(0.3D * Math.sin(a * 0.2D), 0.8D, 0.3D * Math.cos(a * 0.2D));
                stretch = 3.0D + 4.0D * ease(u);
            } else {
                // the whirl: round in a circle under the hand, faster and faster
                double u = (a - LIFT - STRETCH) / (double) WHIRL;
                double r = WHIRL_R * ease(Math.min(1.0D, u * 3.0D));
                h.prevAngle = h.angle;
                h.angle += 0.06D + 0.42D * u * u;
                target = h.top.add(Math.cos(h.angle) * r, 0.35D * Math.sin(h.angle * 2.0D) * u, Math.sin(h.angle) * r);
                Vec3 tan = new Vec3(-Math.sin(h.angle), 0.0D, Math.cos(h.angle));
                spin = tan.scale(2.0D + 9.0D * u).add(0.0D, 1.5D, 0.0D);
                stretch = 5.0D + 4.0D * u;
            }
            Vec3 pathVel = target.subtract(h.lastTarget).scale(20.0D);
            h.lastTarget = target;
            if (WheelRagdolls.isRagdolled(mob)) {
                WheelRagdolls.holdRootMoving(mob, target, pathVel, spin, 45.0D, 16.0D);
                // arms and legs pulled out from the body (and outwards from the circle while it whirls)
                List<WheelRagdolls.Body> bodies = WheelRagdolls.bodies(mob);
                Vec3 root = WheelRagdolls.rootPosition(mob);
                if (root != null && bodies.size() > 1 && stretch > 0.0D) {
                    Vec3 outward = root.subtract(h.top).multiply(1.0D, 0.0D, 1.0D);
                    outward = outward.lengthSqr() < 1.0E-4D ? Vec3.ZERO : outward.normalize();
                    for (int i = 1; i < bodies.size(); i++) {
                        Vec3 bp = WheelRagdolls.bodyPosition(bodies.get(i));
                        if (bp == null) continue;
                        Vec3 d = bp.subtract(root);
                        if (d.lengthSqr() < 1.0E-4D) continue;
                        Vec3 pull = d.normalize().scale(stretch).add(outward.scale(stretch * 0.4D));
                        WheelRagdolls.setVelocity(bodies.get(i), pathVel.add(pull), Vec3.ZERO);
                    }
                }
                Vec3 pos = root != null ? root : target;
                if (pos.distanceTo(h.last) < 8.0D) h.last = pos;
            }
            PacketDistributor.sendToPlayer(p, new BallistaState(BallistaState.TRACK, mob.getId(), h.last.x, h.last.y, h.last.z));
            broadcast(level, new TeleState(TeleState.TRACK, mob.getId(), h.last.x, h.last.y, h.last.z), h.last);
            if (a % 5 == 0) WheelMod.keepLoaded(level, h.last);
            // sounds of the act
            if (a == LIFT) level.playSound(null, h.last.x, h.last.y, h.last.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.4F, 0.8F);
            if (a == LIFT + 6) level.playSound(null, h.last.x, h.last.y, h.last.z, SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1.2F, 0.6F);
            if (a >= LIFT + STRETCH) {
                double u = (a - LIFT - STRETCH) / (double) WHIRL;
                int period = Math.max(3, (int) Math.round(12 - 9 * u));
                if (a % period == 0) level.playSound(null, h.last.x, h.last.y, h.last.z, SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 0.8F + 0.5F * (float) u, 0.8F + 0.6F * (float) u);
            }
            if (a == FLING_AT - 16) level.playSound(null, h.last.x, h.last.y, h.last.z, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 0.9F, 1.5F);
        }
    }

    /** The hand lets go: far away, up and along the swing, one rigid tumbling body; the camera stays on it. */
    private static void fling(Hold h, ServerLevel level) {
        LivingEntity mob = h.mob;
        ServerPlayer p = h.player;
        WheelMod.unhold(mob);
        Vec3 tan = new Vec3(-Math.sin(h.angle), 0.0D, Math.cos(h.angle));
        Vec3 vel = tan.scale(30.0D).add(0.0D, 15.0D, 0.0D);
        Vec3 axis = new Vec3(0.0D, 1.0D, 0.0D).cross(tan);
        Vec3 spin = axis.normalize().scale(-2.0D);
        BallistaServer.launch(level, p.isRemoved() ? null : p, mob, vel, spin, 0x1FBF3F, 0x9CFF4A);
        Vec3 c = h.last;
        broadcast(level, new TeleState(TeleState.FLING, mob.getId(), c.x, c.y, c.z), c);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 1.3F, 1.2F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 0.6F, 1.5F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 1.4F, 0.9F);
        level.sendParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(WheelEntity.dust(0x1FBF3F, 1.8F), c.x, c.y, c.z, 36, 0.6D, 0.6D, 0.6D, 0.0D);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, c.x, c.y, c.z, 24, 0.6D, 0.6D, 0.6D, 0.1D);
        if (!p.isRemoved()) endCooldown(p);
    }

    private static void endCooldown(ServerPlayer p) {
        var item = WheelMod.ORB_ITEM.get();
        p.getCooldowns().removeCooldown(item);
        p.getCooldowns().addCooldown(item, 40);
    }

    private static void broadcast(ServerLevel level, TeleState s, Vec3 at) {
        PacketDistributor.sendToPlayersNear(level, null, at.x, at.y, at.z, 128.0D, s);
    }

    public static void onServerStopping() {
        HOLDS.clear();
    }
}

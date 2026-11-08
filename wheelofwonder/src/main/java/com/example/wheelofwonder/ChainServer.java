package com.example.wheelofwonder;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Sinister Chain, server side.
 *
 * A chain is a row of NPCs: the first one chained is the leader, every next one is fastened (by a hand or a leg) to
 * the one before it with a chain of fixed length. Every NPC can be in one chain only, once. The leader goes where it
 * wants; every other member is pulled after the one before it as soon as its chain is taut, so the whole row follows
 * the leader. The chain never breaks: the pull just gets stronger the farther it is stretched.
 *
 * Walking members are pushed along the chain and told to walk after the one before them. Members lying as a ragdoll
 * are dragged over the ground: the pull is added to EVERY part of the body by the same amount, so the parts move
 * together and the joints are not torn (only the speed along the chain is raised; sideways sliding, falling and the
 * spin of the parts stay as the physics has them).
 *
 * The chains are stored in the NPCs themselves (persistent data: chain id, place in the row, hand or leg, owner),
 * so they come back with the world.
 */
public final class ChainServer {
    static final String TAG = "wheelofwonder_chain";

    static final class Chain {
        final UUID id;
        UUID owner;
        /** members by their place in the row (gaps are fine: a dead member just drops out) */
        final TreeMap<Integer, LivingEntity> members = new TreeMap<>();
        final Map<LivingEntity, Integer> parts = new HashMap<>();
        int nextIndex;

        Chain(UUID id) {
            this.id = id;
        }

        List<LivingEntity> row() {
            List<LivingEntity> r = new ArrayList<>();
            for (LivingEntity e : members.values()) if (!e.isRemoved() && e.isAlive()) r.add(e);
            return r;
        }
    }

    private static final Map<UUID, Chain> CHAINS = new HashMap<>();
    /** each player's chain that the next NPC is added to */
    private static final Map<UUID, UUID> ACTIVE = new HashMap<>();
    private static int tick;

    private ChainServer() {
    }

    static double length() {
        return WheelConfig.CHAIN_LENGTH.get();
    }

    static boolean chainable(Entity e) {
        return e instanceof LivingEntity le && le.isAlive() && !(e instanceof Player) && !(e instanceof ArmorStand) && !e.isSpectator();
    }

    static boolean isChained(LivingEntity e) {
        return e.getPersistentData().contains(TAG);
    }

    // ------------------------------------------------------------------ requests

    /** The player's chains (Whirlwind Whip). */
    static List<Chain> chainsOf(UUID owner) {
        List<Chain> r = new ArrayList<>();
        for (Chain c : CHAINS.values()) if (owner.equals(c.owner)) r.add(c);
        return r;
    }

    /** Whirlwind Whip let go: these chains break apart (the NPCs are free). */
    static void breakChains(ServerLevel level, List<Chain> chains) {
        for (Chain c : chains) if (CHAINS.containsKey(c.id)) release(level, c);
    }

    public static void handleRequest(ChainRequest req, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer p)) return;
        if (req.action() == ChainRequest.WHIRL_DONE) {
            WhirlServer.done(p);
            return;
        }
        if (!SinisterChainItem.isChain(p.getMainHandItem())) return;
        ServerLevel level = p.serverLevel();
        switch (req.action()) {
            case ChainRequest.ATTACH -> attach(p, level, level.getEntity(req.entityId()), req.part());
            case ChainRequest.RELEASE -> {
                Entity e = level.getEntity(req.entityId());
                if (e instanceof LivingEntity le && isChained(le)) {
                    Chain c = CHAINS.get(le.getPersistentData().getCompound(TAG).getUUID("Id"));
                    if (c != null) release(level, c);
                    p.displayClientMessage(Component.translatable("wheelofwonder.msg.chain_released"), true);
                }
            }
            case ChainRequest.CLOSE -> {
                if (ACTIVE.remove(p.getUUID()) != null) p.displayClientMessage(Component.translatable("wheelofwonder.msg.chain_closed"), true);
            }
            case ChainRequest.WHIRL -> WhirlServer.start(p);
            case ChainRequest.WHIRL_DONE -> WhirlServer.done(p);
            case ChainRequest.RELEASE_ALL -> {
                int n = 0;
                for (Chain c : new ArrayList<>(CHAINS.values())) if (p.getUUID().equals(c.owner)) { release(level, c); n++; }
                ACTIVE.remove(p.getUUID());
                p.displayClientMessage(Component.translatable("wheelofwonder.msg.chain_released_all", n), true);
            }
            default -> {
            }
        }
    }

    private static void attach(ServerPlayer p, ServerLevel level, Entity e, int part) {
        if (!chainable(e) || e.distanceTo(p) > p.entityInteractionRange() + 3.0D) return;
        LivingEntity mob = (LivingEntity) e;
        if (isChained(mob)) {
            // one chain per NPC, once
            p.displayClientMessage(Component.translatable("wheelofwonder.msg.chain_already"), true);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.CHAIN_HIT, SoundSource.PLAYERS, 0.6F, 0.6F);
            return;
        }
        Chain c = null;
        UUID active = ACTIVE.get(p.getUUID());
        if (active != null) c = CHAINS.get(active);
        if (c != null && c.row().isEmpty()) {
            CHAINS.remove(c.id);
            c = null;
        }
        boolean leader = c == null;
        if (leader) {
            c = new Chain(UUID.randomUUID());
            c.owner = p.getUUID();
            CHAINS.put(c.id, c);
            ACTIVE.put(p.getUUID(), c.id);
        }
        int index = c.nextIndex++;
        c.members.put(index, mob);
        c.parts.put(mob, part);
        CompoundTag t = new CompoundTag();
        t.putUUID("Id", c.id);
        t.putInt("Index", index);
        t.putByte("Part", (byte) part);
        t.putUUID("Owner", p.getUUID());
        mob.getPersistentData().put(TAG, t);
        if (mob instanceof Mob m) m.setPersistenceRequired();
        // sound and a burst of hellfire at the shackle
        Vec3 at = anchor(mob, part);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1.3F, 0.7F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.35F, 1.6F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.7F, 0.7F);
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI * 2 / 24;
            level.sendParticles(i % 3 == 0 ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME, at.x, at.y, at.z, 0, Math.cos(a), 0.15D, Math.sin(a), 0.12D);
        }
        level.sendParticles(WheelEntity.dust(0xB04BFF, 1.4F), at.x, at.y, at.z, 12, 0.15D, 0.15D, 0.15D, 0.0D);
        level.sendParticles(ParticleTypes.LAVA, at.x, at.y, at.z, 4, 0.1D, 0.1D, 0.1D, 0.0D);
        p.displayClientMessage(Component.translatable(leader ? "wheelofwonder.msg.chain_leader" : "wheelofwonder.msg.chain_linked", c.row().size()), true);
    }

    private static void release(ServerLevel level, Chain c) {
        for (LivingEntity e : c.members.values()) {
            e.getPersistentData().remove(TAG);
            Vec3 at = anchor(e, c.parts.getOrDefault(e, 0));
            if (e.level() == level) {
                level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 6, 0.1D, 0.1D, 0.1D, 0.02D);
                level.playSound(null, at.x, at.y, at.z, SoundEvents.CHAIN_BREAK, SoundSource.PLAYERS, 0.8F, 0.8F);
            }
        }
        CHAINS.remove(c.id);
        ACTIVE.values().removeIf(id -> id.equals(c.id));
    }

    /** NPCs loaded from the save (or a reloaded chunk) go back into their chains. */
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof LivingEntity le) || !isChained(le)) return;
        CompoundTag t = le.getPersistentData().getCompound(TAG);
        if (!t.hasUUID("Id")) return;
        Chain c = CHAINS.computeIfAbsent(t.getUUID("Id"), Chain::new);
        if (t.hasUUID("Owner")) c.owner = t.getUUID("Owner");
        int index = t.getInt("Index");
        c.members.put(index, le);
        c.parts.put(le, (int) t.getByte("Part"));
        c.nextIndex = Math.max(c.nextIndex, index + 1);
    }

    // ------------------------------------------------------------------ physics

    /** Where the chain is fastened: round the neck (the lock in front of the collar); a ragdoll: its body. */
    static Vec3 anchor(LivingEntity e, int part) {
        if (WheelRagdolls.isRagdolled(e)) {
            Vec3 r = WheelRagdolls.rootPosition(e);
            if (r != null) return r;
        }
        return neck(e.position(), e.yBodyRot, e.getEyeHeight(), e.getBbWidth());
    }

    /** The front of the collar: a little under the eyes, in front of the neck. */
    public static Vec3 neck(Vec3 pos, float bodyYaw, double eye, double width) {
        double yaw = Math.toRadians(bodyYaw);
        Vec3 fwd = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        return pos.add(0.0D, eye * 0.86D, 0.0D).add(fwd.scale(width * 0.3D));
    }

    /** The chain was stretched past the limit: every NPC of the row falls as a ragdoll, flung a little along it. */
    private static void ragdollAll(ServerLevel level, Chain c, Vec3 pull) {
        if (!WheelRagdolls.available()) return;
        int seconds = WheelConfig.RAGDOLL_SECONDS.get();
        for (LivingEntity e : c.row()) {
            if (WheelRagdolls.isRagdolled(e) || WheelRagdolls.isPending(e)) continue;
            // sitting in the ballista / rocket or being shot: the ride decides when it flies
            if (WheelMod.held(e) || e.isPassenger()) continue;
            e.addTag(WheelMod.RAGDOLL_TAG);
            Vec3 v = pull.scale(4.0D).add(0.0D, 2.0D, 0.0D);
            Vec3 spin = new Vec3(level.random.nextGaussian(), level.random.nextGaussian() * 0.5D, level.random.nextGaussian()).scale(1.5D);
            if (!WheelRagdolls.launch(level, e, v, spin, seconds)) e.removeTag(WheelMod.RAGDOLL_TAG);
            Vec3 at = centre(e);
            level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 14, 0.25D, 0.3D, 0.25D, 0.05D);
            level.sendParticles(ParticleTypes.LAVA, at.x, at.y, at.z, 3, 0.2D, 0.2D, 0.2D, 0.0D);
        }
        Vec3 c0 = centre(c.row().get(0));
        level.playSound(null, c0.x, c0.y, c0.z, SoundEvents.CHAIN_BREAK, SoundSource.NEUTRAL, 1.2F, 0.5F);
        level.playSound(null, c0.x, c0.y, c0.z, SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, 0.6F, 0.6F);
        level.playSound(null, c0.x, c0.y, c0.z, SoundEvents.BLAZE_SHOOT, SoundSource.NEUTRAL, 0.8F, 0.6F);
    }

    /** The point that is pulled: the ragdoll's body, or the NPC's middle. */
    static Vec3 centre(LivingEntity e) {
        if (WheelRagdolls.isRagdolled(e)) {
            Vec3 r = WheelRagdolls.rootPosition(e);
            if (r != null) return r;
        }
        return e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
    }

    private static boolean emptySent = true;

    public static void onServerTick(ServerTickEvent.Post event) {
        if (CHAINS.isEmpty()) {
            // tell the clients once that there are no chains any more
            if (!emptySent) {
                ChainSync none = new ChainSync((float) length(), List.of());
                for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) PacketDistributor.sendToPlayer(p, none);
                emptySent = true;
            }
            return;
        }
        emptySent = false;
        tick++;
        double len = length();
        List<List<ChainSync.Member>> sync = new ArrayList<>();
        ServerLevel anyLevel = null;
        Iterator<Chain> it = CHAINS.values().iterator();
        while (it.hasNext()) {
            Chain c = it.next();
            // dead members leave the row for good (the gap closes, the chain stays whole); unloaded ones come back
            c.members.values().removeIf(e -> e.isRemoved() && e.getRemovalReason() != null && e.getRemovalReason().shouldDestroy());
            c.members.values().removeIf(e -> !e.isRemoved() && !e.isAlive());
            List<LivingEntity> row = c.row();
            if (c.members.isEmpty()) {
                it.remove();
                continue;
            }
            List<ChainSync.Member> ms = new ArrayList<>();
            int n = row.size();
            Node[] st = new Node[n];
            for (int i = 0; i < n; i++) st[i] = new Node(row.get(i));
            // anyone pulling a chain past the limit (5 blocks) brings the whole row down as ragdolls
            // (NPCs held by a ride - ballista, rocket, a shot in flight - stay with the ride)
            double snap = WheelConfig.CHAIN_SNAP.get();
            for (int i = 1; i < n; i++) {
                if (st[i - 1].e.level() != st[i].e.level() || !(st[i].e.level() instanceof ServerLevel level)) continue;
                Vec3 d = st[i - 1].pos.subtract(st[i].pos);
                if (d.length() > snap) {
                    ragdollAll(level, c, d.normalize());
                    break;
                }
            }
            // the chain: stiff distance constraints solved on the velocities, both ways along the row, twice
            for (int iter = 0; iter < 2; iter++) {
                for (int i = 1; i < n; i++) constrain(st[i - 1], st[i], len);
                for (int i = n - 1; i >= 1; i--) constrain(st[i - 1], st[i], len);
            }
            for (int i = 0; i < n; i++) {
                Node s = st[i];
                LivingEntity cur = s.e;
                if (!(cur.level() instanceof ServerLevel level)) continue;
                anyLevel = level;
                apply(level, s);
                boolean rag = s.rag;
                Vec3 a = rag ? s.pos : cur.position();
                ms.add(new ChainSync.Member(cur.getId(), c.parts.getOrDefault(cur, 0), rag, a.x, a.y, a.z));
            }
            if (ms.size() > 0) sync.add(ms);
        }
        ChainSync msg = new ChainSync((float) len, sync);
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) PacketDistributor.sendToPlayer(p, msg);
    }

    /**
     * One chained NPC in this tick's solve: where it is, how fast it goes (m/s) and how easily the chain moves it
     * (its inverse mass). The faster something flies, the heavier it gets: a body shot off by the ballista or the
     * rocket keeps its flight and drags the others after it, a body lying still is pulled along easily. NPCs held by
     * a ride (sitting in the ballista or rocket, or a shot in flight) are not moved by the chain at all: the ride leads.
     * Walking NPCs resist more than ragdolls (they walk on purpose).
     */
    static final class Node {
        final LivingEntity e;
        final boolean rag, held;
        final List<WheelRagdolls.Body> bodies;
        final Vec3 pos;
        Vec3 vel, dv = Vec3.ZERO, pulledTo;
        final double w;
        double pull;

        Node(LivingEntity e) {
            this.e = e;
            this.held = WheelMod.held(e) || e.isPassenger();
            this.rag = WheelRagdolls.isRagdolled(e);
            this.bodies = this.rag ? WheelRagdolls.bodies(e) : List.of();
            this.pos = centre(e);
            this.vel = this.rag ? (this.bodies.isEmpty() ? Vec3.ZERO : WheelRagdolls.velocity(this.bodies.get(0))) : e.getDeltaMovement().scale(20.0D);
            double speed = this.vel.length();
            double base = this.held || (this.rag && this.bodies.isEmpty()) ? 0.0D : (this.rag ? 1.0D : 0.35D);
            this.w = base / (1.0D + (speed / 5.0D) * (speed / 5.0D));
        }
    }

    /**
     * Keeps two neighbours no farther apart than the chain's length: if the chain is taut and they still move apart
     * (or are already too far), the speed of moving apart is taken away - split between them by their inverse
     * masses - and a little more, so the slack is taken in (no springy bouncing, no stretching in flight).
     */
    private static void constrain(Node a, Node b, double len) {
        if (a.e.level() != b.e.level()) return;
        Vec3 d = a.pos.subtract(b.pos);
        double dist = d.length();
        if (dist <= len || dist < 1.0E-4D) return;
        double wsum = a.w + b.w;
        if (wsum <= 1.0E-6D) return;
        Vec3 dir = d.scale(1.0D / dist);                    // from b to a
        double excess = dist - len;
        double sep = a.vel.subtract(b.vel).dot(dir);        // > 0: they move apart
        double target = -Math.min(12.0D, excess * 6.0D);    // close the overshoot within a few ticks
        if (sep <= target) return;
        double j = Math.min(60.0D, sep - target);
        Vec3 da = dir.scale(-j * a.w / wsum), db = dir.scale(j * b.w / wsum);
        // slow NPCs are not thrown into the air by a pull (only a hop), unless the other end is really above
        boolean slow = a.vel.length() < 6.0D && b.vel.length() < 6.0D;
        if (slow) {
            da = flatUp(da, b.pos.y - a.pos.y);
            db = flatUp(db, a.pos.y - b.pos.y);
        }
        a.vel = a.vel.add(da);
        a.dv = a.dv.add(da);
        b.vel = b.vel.add(db);
        b.dv = b.dv.add(db);
        if (da.length() > a.pull) { a.pull = da.length(); a.pulledTo = b.pos; }
        if (db.length() > b.pull) { b.pull = db.length(); b.pulledTo = a.pos; }
    }

    /** Applies the chain's push: to every part of a ragdoll alike (they stay together), or to a walking NPC. */
    private static void apply(ServerLevel level, Node s) {
        LivingEntity e = s.e;
        e.fallDistance = 0.0F;
        if (s.held || s.dv.lengthSqr() < 1.0E-6D) return;
        if (s.rag) {
            for (WheelRagdolls.Body b : s.bodies) WheelRagdolls.addVelocity(b, s.dv);
            if (tick % 6 == 0 && s.vel.length() < 8.0D) scrape(level, s.pos);
        } else {
            Vec3 add = s.dv.scale(1.0D / 20.0D);
            boolean fast = s.vel.length() > 6.0D;
            Vec3 dm = e.getDeltaMovement();
            e.setDeltaMovement(dm.add(add.x, fast ? add.y : Math.max(-0.1D, Math.min(add.y, 0.12D)), add.z));
            e.hurtMarked = true;
            if (e instanceof Mob m && s.pulledTo != null && tick % 8 == 0) {
                m.getNavigation().moveTo(s.pulledTo.x, s.pulledTo.y - e.getBbHeight() * 0.5D, s.pulledTo.z, 1.3D);
                m.getLookControl().setLookAt(s.pulledTo.x, s.pulledTo.y, s.pulledTo.z);
            }
        }
        if (s.pull > 2.0D && tick % 14 == 0) {
            level.playSound(null, s.pos.x, s.pos.y, s.pos.z, SoundEvents.CHAIN_STEP, SoundSource.NEUTRAL, 0.6F, 0.7F + level.random.nextFloat() * 0.3F);
        }
    }

    /** A pull is not allowed to throw a body up into the air (only a hop), unless the other end is really higher. */
    private static Vec3 flatUp(Vec3 dv, double rise) {
        return dv.y > 0.0D && rise < 1.0D ? new Vec3(dv.x, Math.min(dv.y, 1.5D), dv.z) : dv;
    }

    private static Vec3 rootVelocity(LivingEntity e) {
        List<WheelRagdolls.Body> b = WheelRagdolls.bodies(e);
        return b.isEmpty() ? Vec3.ZERO : WheelRagdolls.velocity(b.get(0));
    }

    /** Sparks and dust where a body is dragged over the ground. */
    private static void scrape(ServerLevel level, Vec3 at) {
        level.sendParticles(ParticleTypes.SMALL_FLAME, at.x, at.y - 0.2D, at.z, 2, 0.15D, 0.05D, 0.15D, 0.01D);
        level.sendParticles(ParticleTypes.SMOKE, at.x, at.y - 0.3D, at.z, 1, 0.15D, 0.02D, 0.15D, 0.01D);
    }

    public static void onServerStopping() {
        CHAINS.clear();
        ACTIVE.clear();
    }

    static int clampPart(int p) {
        return Mth.clamp(p, 0, 1);
    }
}

package com.example.huggynpc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * What Verity does, for every Verity in the world (a Custom NPC that looks like Verity, or the spawn-egg mob):
 * - with no other NPC around it just idles (Custom NPCs / its own goals);
 * - another NPC within 12 blocks: it goes for it in hops and jumps onto its head, and keeps bouncing there. It does
 *   no damage. Every landing on the head has a 30% chance to take the head's place: the two become one NPC that
 *   wears Verity as its head (tag MERGED_TAG, drawn by the client) and Verity itself is gone;
 * - when it becomes a ragdoll (or dies) it crumbles into the little cubes it is made of (the client does the cubes;
 *   the ragdoll body stays invisible), and the same happens to a Verity head when its NPC goes down.
 * The hops are flown here on the server along a fixed arc (the head is a moving target, the arc follows it).
 */
public final class VerityBrain {
    public static final String VERITY_TAG = "huggynpc_verity", MERGED_TAG = "huggynpc_verity_head";
    private static final double SEEK = 12.0D, KEEP = 16.0D, MERGE_CHANCE = 0.3D;

    private static final class Actor {
        final LivingEntity e;
        LivingEntity target;
        boolean onHead, crumbled, finalHop, small;
        int cooldown, jumpAge = -1, jumpT, crumbleAt = -1, seekIn;
        Vec3 from, to, lastBody;
        double height;

        Actor(LivingEntity e) {
            this.e = e;
        }
    }

    private static final Map<UUID, Actor> ACTORS = new HashMap<>();
    private static final Map<UUID, LivingEntity> MERGED = new HashMap<>();
    private static int tick;

    private VerityBrain() {
    }

    static void init() {
        NeoForge.EVENT_BUS.addListener(VerityBrain::onTick);
        NeoForge.EVENT_BUS.addListener(VerityBrain::onTrack);
        NeoForge.EVENT_BUS.addListener(VerityBrain::onDeath);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> {
            ACTORS.clear();
            MERGED.clear();
        });
    }

    // ------------------------------------------------------------------ who is Verity

    private static Method getEntity;
    private static boolean lookupFailed;

    /** A Custom NPC whose look (Model -> Entities) is Verity. */
    private static boolean showsVerity(Entity npc) {
        if (lookupFailed || !npc.getClass().getName().startsWith("noppes.npcs.")) return false;
        try {
            Object data = npc.getClass().getField("modelData").get(npc);
            if (data == null) return false;
            if (getEntity == null || !getEntity.getDeclaringClass().isInstance(data)) {
                for (Method m : data.getClass().getMethods()) if (m.getName().equals("getEntity") && m.getParameterCount() == 1) getEntity = m;
                if (getEntity == null) {
                    lookupFailed = true;
                    return false;
                }
            }
            return getEntity.invoke(data, npc) instanceof VerityEntity;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isVerity(Entity e) {
        return e instanceof VerityEntity || e.getTags().contains(VERITY_TAG);
    }

    private static boolean down(LivingEntity e) {
        return e.getTags().contains(NpcCombat.DOWN_TAG) || RagdollBridge.isRagdolled(e) || RagdollBridge.isPending(e);
    }

    // ------------------------------------------------------------------ tick

    private static void onTick(ServerTickEvent.Post event) {
        tick++;
        if (tick % 10 == 0) {
            for (ServerLevel level : event.getServer().getAllLevels()) scan(level);
        }
        // a copy: an actor may leave the map while it acts (it merges into a head, it dies)
        for (Actor a : new java.util.ArrayList<>(ACTORS.values())) {
            if (a.e.isRemoved() || !a.e.isAlive()) {
                ACTORS.remove(a.e.getUUID(), a);
                continue;
            }
            try {
                act(a);
                if (!a.e.isRemoved()) updateSize(a);
            } catch (RuntimeException ex) {
                // one misbehaving Verity must never take the server down
                com.mojang.logging.LogUtils.getLogger().warn("[huggynpc] Verity tick failed", ex);
                a.jumpAge = -1;
                a.target = null;
                a.onHead = false;
            }
        }
        if (tick % 2 == 0) {
            try {
                watchHeads();
            } catch (RuntimeException ex) {
                com.mojang.logging.LogUtils.getLogger().warn("[huggynpc] Verity head check failed", ex);
            }
        }
    }

    /** Finds the Veritys and the NPCs wearing one (also those loaded with the world). */
    private static void scan(ServerLevel level) {
        for (Entity en : level.getAllEntities()) {
            if (!(en instanceof LivingEntity le) || !NpcCombat.isNpc(le) || !le.isAlive()) continue;
            if (le.getTags().contains(MERGED_TAG)) MERGED.putIfAbsent(le.getUUID(), le);
            if (ACTORS.containsKey(le.getUUID())) continue;
            boolean v = isVerity(le);
            // a Custom NPC turned into Verity with the NPC Wand: remembered by a tag from now on
            if (!v && tick % 40 == 0 && showsVerity(le)) {
                le.addTag(VERITY_TAG);
                v = true;
            }
            if (v) ACTORS.put(le.getUUID(), new Actor(le));
        }
    }

    private static void act(Actor a) {
        LivingEntity e = a.e;
        ServerLevel level = (ServerLevel) e.level();
        // ---- falling apart
        if (down(e)) {
            a.jumpAge = -1;
            a.onHead = false;
            a.target = null;
            if (!a.crumbled) {
                Vec3 body = RagdollBridge.bodyPosition(e);
                if (a.crumbleAt < 0) {
                    a.crumbleAt = tick;
                    a.lastBody = body;
                } else if (tick - a.crumbleAt >= 2) {
                    Vec3 v = body.subtract(a.lastBody).scale(10.0D);
                    if (v.length() > 20.0D) v = v.normalize().scale(20.0D);
                    crumble(level, e, body, v, a.small ? 1.0F : BIG);
                    a.crumbled = true;
                } else {
                    a.lastBody = a.lastBody.lerp(body, 0.5D);
                }
            }
            return;
        }
        if (a.crumbled) {
            a.crumbled = false;
            a.crumbleAt = -1;
            send(e, VerityPayload.RESTORE, e.position(), Vec3.ZERO, 1.0F, e.getYRot());
        }
        if (e.isPassenger()) return;

        // ---- choose a head to go for
        if (a.target != null && !validTarget(a, a.target, KEEP)) dropTarget(a);
        if (a.target == null && --a.seekIn <= 0) {
            a.seekIn = 10;
            a.target = findTarget(a);
        }
        if (a.target == null) return;   // nobody around: idle
        LivingEntity t = a.target;
        if (e instanceof Mob m) {
            m.getNavigation().stop();
            m.setTarget(null);
        }
        e.fallDistance = 0.0F;
        face(e, t.position());

        if (a.jumpAge >= 0) {
            flyHop(a, level);
            return;
        }
        if (a.onHead) {
            Vec3 h = headTop(t);
            e.setPos(h.x, h.y, h.z);
            e.setDeltaMovement(Vec3.ZERO);
        }
        if (a.cooldown > 0) {
            a.cooldown--;
            return;
        }
        if (!a.onHead && !e.onGround() && !e.isInWater()) return;
        planHop(a, level);
    }

    /** Big on the ground (3x); back to head size for the jump onto a head and while it sits there. */
    public static final float BIG = 3.0F;

    private static void updateSize(Actor a) {
        boolean small = !a.crumbled && ((a.jumpAge >= 0 && a.finalHop) || a.onHead);
        if (small == a.small) return;
        a.small = small;
        send(a.e, VerityPayload.SIZE, a.e.position(), Vec3.ZERO, small ? 1.0F : BIG, a.e.getYRot());
    }

    private static boolean validTarget(Actor a, LivingEntity t, double range) {
        return t.isAlive() && !t.isRemoved() && t.level() == a.e.level() && !down(t) && !isVerity(t) && !t.getTags().contains(MERGED_TAG)
                && t.distanceTo(a.e) <= range;
    }

    private static LivingEntity findTarget(Actor a) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity o : a.e.level().getEntitiesOfClass(LivingEntity.class, a.e.getBoundingBox().inflate(SEEK),
                x -> x != a.e && NpcCombat.isNpc(x))) {
            if (!validTarget(a, o, SEEK)) continue;
            double d = o.distanceToSqr(a.e);
            if (d < bestD) {
                bestD = d;
                best = o;
            }
        }
        return best;
    }

    private static void dropTarget(Actor a) {
        if (a.onHead) {
            // slides off the head
            Vec3 off = new Vec3(a.e.getRandom().nextGaussian(), 0.0D, a.e.getRandom().nextGaussian());
            a.e.setDeltaMovement(off.normalize().scale(0.15D).add(0.0D, 0.2D, 0.0D));
            a.e.hurtMarked = true;
        }
        a.target = null;
        a.onHead = false;
        a.jumpAge = -1;
        a.cooldown = 10;
    }

    private static Vec3 headTop(LivingEntity t) {
        return t.position().add(0.0D, t.getBbHeight(), 0.0D);
    }

    private static void face(LivingEntity e, Vec3 at) {
        Vec3 d = at.subtract(e.position());
        if (d.x * d.x + d.z * d.z < 1.0E-4D) return;
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        e.setYRot(yaw);
        e.yBodyRot = yaw;
        e.setYHeadRot(yaw);
    }

    /** The next hop: straight onto the head when it is close, else a hop towards it (or a bounce on the head). */
    private static void planHop(Actor a, ServerLevel level) {
        LivingEntity e = a.e, t = a.target;
        Vec3 from = e.position(), head = headTop(t);
        a.from = from;
        if (a.onHead) {
            a.to = head;
            a.finalHop = true;
            a.jumpT = 10;
            a.height = 0.7D;
        } else {
            Vec3 d = head.subtract(from);
            double h = Math.sqrt(d.x * d.x + d.z * d.z);
            if (h <= 3.2D && d.y < 4.0D) {
                a.to = head;
                a.finalHop = true;
                a.jumpT = (int) Math.round(9 + h * 2.0D + Math.max(0.0D, d.y));
                a.height = 0.9D + Math.max(0.0D, d.y) * 0.6D;
            } else {
                double step = Math.min(h - 1.6D, 3.0D);
                Vec3 dir = new Vec3(d.x, 0.0D, d.z).normalize();
                Vec3 to = from.add(dir.scale(step));
                double g = ground(level, to.x, to.y + 1.5D, to.z, from.y);
                if (Math.abs(g - from.y) > 2.5D) g = from.y;
                a.to = new Vec3(to.x, g, to.z);
                a.finalHop = false;
                a.jumpT = (int) Math.round(8 + step * 2.0D);
                a.height = 0.8D + Math.max(0.0D, g - from.y) * 0.6D;
            }
        }
        if (!clear(level, a)) {
            // something in the way: a little hop on the spot (it will find another way next time)
            a.to = from;
            a.finalHop = false;
            a.jumpT = 8;
            a.height = 0.5D;
        }
        a.jumpAge = 0;
        e.setOnGround(false);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.SLIME_JUMP_SMALL, SoundSource.NEUTRAL, 0.7F, 1.6F + level.random.nextFloat() * 0.3F);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.NEUTRAL, 0.35F, a.finalHop ? 1.4F : 1.0F);
    }

    private static Vec3 arc(Actor a, double u) {
        Vec3 base = a.from.lerp(a.to, u);
        return base.add(0.0D, 4.0D * a.height * u * (1.0D - u), 0.0D);
    }

    private static boolean clear(ServerLevel level, Actor a) {
        for (double u = 0.25D; u < 0.95D; u += 0.25D) {
            Vec3 p = arc(a, u).add(0.0D, 0.25D, 0.0D);
            BlockPos bp = BlockPos.containing(p);
            if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) return false;
        }
        return true;
    }

    private static void flyHop(Actor a, ServerLevel level) {
        LivingEntity e = a.e;
        a.jumpAge++;
        if (a.finalHop && a.target != null) a.to = headTop(a.target);   // the head may move: follow it
        double u = Math.min(1.0D, a.jumpAge / (double) a.jumpT);
        Vec3 p = arc(a, u), prev = arc(a, Math.max(0.0D, (a.jumpAge - 1) / (double) a.jumpT));
        e.setPos(p.x, p.y, p.z);
        e.setDeltaMovement(p.subtract(prev));
        e.fallDistance = 0.0F;
        if (u < 1.0D) return;
        a.jumpAge = -1;
        e.setDeltaMovement(Vec3.ZERO);
        if (!a.finalHop) {
            a.cooldown = 3 + level.random.nextInt(6);
            return;
        }
        // landed on the head
        a.onHead = true;
        a.cooldown = 4 + level.random.nextInt(6);
        Vec3 h = headTop(a.target);
        level.playSound(null, h.x, h.y, h.z, SoundEvents.SLIME_SQUISH_SMALL, SoundSource.NEUTRAL, 0.9F, 1.5F);
        level.playSound(null, h.x, h.y, h.z, SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.NEUTRAL, 0.35F, 0.7F);
        if (level.random.nextDouble() < MERGE_CHANCE) merge(a, level);
    }

    /** Verity takes the head's place: one NPC with a pixel-ball head. */
    private static void merge(Actor a, ServerLevel level) {
        LivingEntity t = a.target;
        Vec3 h = headTop(t).subtract(0.0D, 0.25D, 0.0D);
        t.addTag(MERGED_TAG);
        MERGED.put(t.getUUID(), t);
        send(t, VerityPayload.MERGED, h, Vec3.ZERO, 1.0F, t.getYHeadRot());
        level.playSound(null, h.x, h.y, h.z, SoundEvents.PLAYER_BURP, SoundSource.NEUTRAL, 1.0F, 1.4F);
        level.playSound(null, h.x, h.y, h.z, SoundEvents.SLIME_SQUISH, SoundSource.NEUTRAL, 1.0F, 1.2F);
        level.playSound(null, h.x, h.y, h.z, SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.NEUTRAL, 0.6F, 2.0F);
        level.sendParticles(new DustParticleOptions(new Vector3f(1.0F, 0.78F, 0.12F), 1.2F), h.x, h.y, h.z, 24, 0.3D, 0.3D, 0.3D, 0.0D);
        level.sendParticles(ParticleTypes.POOF, h.x, h.y, h.z, 6, 0.2D, 0.2D, 0.2D, 0.02D);
        a.e.discard();   // dropped from ACTORS on the next tick
    }

    // ------------------------------------------------------------------ falling apart

    private static void crumble(ServerLevel level, LivingEntity e, Vec3 at, Vec3 vel, float scale) {
        send(e, VerityPayload.CRUMBLE, at, vel, scale, e.getYRot());
        level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.NEUTRAL, 0.8F, 1.6F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.NEUTRAL, 0.6F, 0.5F);
    }

    /** NPCs wearing Verity: when one goes down (or dies) its Verity head crumbles and its own head is back. */
    private static void watchHeads() {
        Iterator<LivingEntity> it = MERGED.values().iterator();
        while (it.hasNext()) {
            LivingEntity m = it.next();
            if (m.isRemoved()) {
                it.remove();
                continue;
            }
            if (!m.getTags().contains(MERGED_TAG)) {
                it.remove();
                continue;
            }
            if (m.isAlive() && !down(m)) continue;
            headBurst(m);
            it.remove();
        }
    }

    private static void headBurst(LivingEntity m) {
        m.removeTag(MERGED_TAG);
        if (!(m.level() instanceof ServerLevel level)) return;
        Vec3 h = m.position().add(0.0D, m.getBbHeight() - headSize(m) * 0.5D, 0.0D);
        Vec3 v = m.getDeltaMovement().scale(20.0D);
        send(m, VerityPayload.HEAD_BURST, h, v, headSize(m) / 0.5F, m.getYHeadRot());
        level.playSound(null, h.x, h.y, h.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.NEUTRAL, 0.8F, 1.6F);
    }

    /** About how big a head this NPC has (blocks). */
    static float headSize(LivingEntity m) {
        return Mth.clamp(m.getBbHeight() / 1.8F * 0.5F, 0.4F, 1.0F);
    }

    private static void onDeath(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!(e.level() instanceof ServerLevel level)) return;
        Actor a = ACTORS.get(e.getUUID());
        if (a != null && !a.crumbled) {
            float s = a.small ? 1.0F : BIG;
            crumble(level, e, e.position().add(0.0D, 0.25D * s, 0.0D), e.getDeltaMovement().scale(20.0D), s);
            a.crumbled = true;
        }
        // watchHeads drops it from MERGED (the tag is gone)
        if (e.getTags().contains(MERGED_TAG)) headBurst(e);
    }

    /** A player starts seeing an NPC: tell its client whether it wears Verity, or lies crumbled. */
    private static void onTrack(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !(event.getTarget() instanceof LivingEntity t)) return;
        if (t.getTags().contains(MERGED_TAG)) {
            PacketDistributor.sendToPlayer(p, new VerityPayload(VerityPayload.MERGED, t.getId(), t.getX(), t.getY(), t.getZ(), 0, 0, 0, 1.0F, 0.0F));
        }
        Actor a = ACTORS.get(t.getUUID());
        if (a != null && a.crumbled) {
            PacketDistributor.sendToPlayer(p, new VerityPayload(VerityPayload.HIDE, t.getId(), t.getX(), t.getY(), t.getZ(), 0, 0, 0, 1.0F, 0.0F));
        }
        if (a != null && a.small) {
            PacketDistributor.sendToPlayer(p, new VerityPayload(VerityPayload.SIZE, t.getId(), t.getX(), t.getY(), t.getZ(), 0, 0, 0, 1.0F, 0.0F));
        }
    }

    private static void send(LivingEntity e, int action, Vec3 at, Vec3 vel, float scale, float yaw) {
        PacketDistributor.sendToPlayersTrackingEntity(e, new VerityPayload(action, e.getId(), at.x, at.y, at.z,
                (float) vel.x, (float) vel.y, (float) vel.z, scale, yaw));
    }

    private static double ground(Level level, double x, double fromY, double z, double fallback) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int by = Mth.floor(fromY); by >= Mth.floor(fromY) - 6; by--) {
            p.set(Mth.floor(x), by, Mth.floor(z));
            VoxelShape s = level.getBlockState(p).getCollisionShape(level, p);
            if (!s.isEmpty()) return by + s.max(Direction.Axis.Y);
        }
        return fallback;
    }
}

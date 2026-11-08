package com.example.huggynpc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Fighting NPCs (every Custom NPCs NPC and the plain Huggy-family mobs):
 * - Custom NPCs ignores player hits on NPCs of a faction that is friendly to the player (the default one). Player
 *   hits on NPCs are therefore applied by this mod: same damage as a normal hit (held item, swing strength,
 *   critical jump), with knockback and tool wear, using a damage source without the faction check.
 * - An NPC that would drop below 1 health does not die: it stays at 1 health and falls as a Sable ragdoll that does
 *   not get up by itself and takes no damage while lying.
 * - /npcrevive [radius] stands them up again with full health.
 */
public final class NpcCombat {
    public static final String DOWN_TAG = "huggynpc_down";
    private static final List<Object[]> TO_DROP = new ArrayList<>();   // {entity, velocity}
    /** NPCs dropped this session -> tick of the drop (to notice when something else stood them up) */
    private static final java.util.Map<LivingEntity, Integer> DOWNED = new java.util.WeakHashMap<>();

    private NpcCombat() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(NpcCombat::onAttack);
        NeoForge.EVENT_BUS.addListener(NpcCombat::onDamage);
        NeoForge.EVENT_BUS.addListener(NpcCombat::onIncoming);
        NeoForge.EVENT_BUS.addListener(NpcCombat::onTick);
        NeoForge.EVENT_BUS.addListener(NpcCombat::onCommands);
        NeoForge.EVENT_BUS.addListener(NpcCombat::onJoin);
    }

    // ------------------------------------------------------------------ 1 health
    /**
     * Every NPC (spawned now, or loaded with the world) has 1 health: any hit "kills" it, i.e. drops it as a ragdoll.
     * Custom NPCs keeps its max health in its stats (and writes it into the attribute), so it is set there.
     */
    private static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !isNpc(event.getEntity())) return;
        setOneHealth((LivingEntity) event.getEntity());
        hideName(event.getEntity());
    }

    /** No name over the head: Custom NPCs' "show name" set to never (0 = always, 1 = never, 2 = when attacking). */
    static void hideName(Entity e) {
        try {
            Object display = e.getClass().getField("display").get(e);
            display.getClass().getMethod("setShowName", int.class).invoke(display, 1);
        } catch (Throwable ignored) {
            // not a Custom NPC: plain mobs show no name anyway
        }
        e.setCustomNameVisible(false);
    }

    static void setOneHealth(LivingEntity e) {
        try {
            Object stats = e.getClass().getField("stats").get(e);
            stats.getClass().getMethod("setMaxHealth", int.class).invoke(stats, 1);
        } catch (Throwable ignored) {
            // not a Custom NPC (the plain Huggy): the attribute is enough
        }
        var max = e.getAttribute(Attributes.MAX_HEALTH);
        if (max != null && max.getBaseValue() != 1.0D) max.setBaseValue(1.0D);
        if (e.getHealth() > e.getMaxHealth()) e.setHealth(e.getMaxHealth());
    }

    public static boolean isNpc(Entity e) {
        if (!(e instanceof LivingEntity) || e instanceof Player) return false;
        if (e instanceof HuggyEntity) return true;
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getNamespace().equals("customnpcs");
    }

    // ------------------------------------------------------------------ player hits
    private static void onAttack(AttackEntityEvent event) {
        Player p = event.getEntity();
        Entity target = event.getTarget();
        if (p.level().isClientSide() || !isNpc(target) || p.isSpectator()) return;
        event.setCanceled(true);   // vanilla attack would be refused for friendly NPCs: do it here instead
        playerHit(p, (LivingEntity) target);
    }

    /** The client's own hit packet (see NpcHitPayload): checked by distance only, not by the server-side hitbox. */
    static void handleHit(NpcHitPayload msg, net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer p) || p.isSpectator() || !p.isAlive()) return;
        Entity target = p.level().getEntity(msg.entityId());
        if (!isNpc(target) || !target.isAlive()) {
            com.mojang.logging.LogUtils.getLogger().info("[huggynpc] hit ignored: entity {} is not a live NPC", msg.entityId());
            return;
        }
        double reach = p.entityInteractionRange() + 3.0D;
        if (target.distanceTo(p) > reach + target.getBbWidth()) {
            com.mojang.logging.LogUtils.getLogger().info("[huggynpc] hit ignored: {} is {} blocks away", target.getName().getString(), target.distanceTo(p));
            return;
        }
        p.resetLastActionTime();
        playerHit(p, (LivingEntity) target);
    }

    private static void playerHit(Player p, LivingEntity npc) {
        if (isDown(npc)) {
            com.mojang.logging.LogUtils.getLogger().info("[huggynpc] hit on {} ignored: it is lying as a ragdoll", npc.getName().getString());
            return;
        }

        float strength = p.getAttackStrengthScale(0.5F);
        float damage = (float) p.getAttributeValue(Attributes.ATTACK_DAMAGE) * (0.2F + strength * strength * 0.8F);
        boolean crit = strength > 0.9F && p.fallDistance > 0.0F && !p.onGround() && !p.onClimbable() && !p.isInWater() && !p.isPassenger();
        if (crit) damage *= 1.5F;
        p.resetAttackStrengthTicker();
        if (damage <= 0.0F) return;

        // generic source: Custom NPCs only refuses damage that comes from a player of a friendly faction
        boolean hurt = npc.hurt(npc.damageSources().generic(), damage);
        com.mojang.logging.LogUtils.getLogger().info("[huggynpc] hit {} for {} -> hurt={}, health={}/{}",
                npc.getName().getString(), damage, hurt, npc.getHealth(), npc.getMaxHealth());
        if (!hurt) {
            // refused by Custom NPCs itself (not just the short hit cooldown): every NPC has 1 health, so it falls anyway
            if (npc.invulnerableTime <= 10) knockDown(npc, p);
            return;
        }
        float kb = (float) p.getAttributeValue(Attributes.ATTACK_KNOCKBACK) + (p.isSprinting() && strength > 0.9F ? 1.0F : 0.0F);
        npc.knockback(0.4F + kb * 0.5F, Mth.sin(p.getYRot() * Mth.DEG_TO_RAD), -Mth.cos(p.getYRot() * Mth.DEG_TO_RAD));
        npc.hurtMarked = true;
        npc.setLastHurtByMob(p);
        p.level().playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                crit ? SoundEvents.PLAYER_ATTACK_CRIT : strength > 0.9F ? SoundEvents.PLAYER_ATTACK_STRONG : SoundEvents.PLAYER_ATTACK_WEAK,
                SoundSource.PLAYERS, 1.0F, 1.0F);
        if (crit) p.crit(npc);
        ItemStack held = p.getMainHandItem();
        if (!held.isEmpty() && held.isDamageableItem()) held.hurtAndBreak(1, p, EquipmentSlot.MAINHAND);
        p.causeFoodExhaustion(0.1F);
    }

    // ------------------------------------------------------------------ "death" -> ragdoll
    private static void onDamage(LivingDamageEvent.Pre event) {
        LivingEntity e = event.getEntity();
        if (e.level().isClientSide() || !isNpc(e)) return;
        if (isDown(e)) {
            event.setNewDamage(0.0F);
            return;
        }
        float after = e.getHealth() - event.getNewDamage();
        if (after >= 1.0F) return;
        // keep it alive at 1 health and drop it as a ragdoll (next tick, outside the damage handling)
        event.setNewDamage(Math.max(0.0F, e.getHealth() - 1.0F));
        knockDown(e, event.getSource().getEntity());
    }

    /** Drops the NPC as a lying ragdoll (next tick, outside any damage handling), pushed away from src. */
    static void knockDown(LivingEntity e, Entity src) {
        if (e.getTags().contains(DOWN_TAG) && DOWNED.containsKey(e)) return;
        e.addTag(DOWN_TAG);
        DOWNED.put(e, tickCounter);   // counts as lying from now on (the drop itself happens next tick)
        Vec3 push = Vec3.ZERO;
        if (src == null) {
            for (Player pl : e.level().players()) if (pl.distanceToSqr(e) < 64.0D) { src = pl; break; }
        }
        if (src != null) {
            Vec3 d = e.position().subtract(src.position()).multiply(1.0D, 0.0D, 1.0D);
            if (d.lengthSqr() > 1.0E-4D) push = d.normalize().scale(4.0D).add(0.0D, 2.5D, 0.0D);
        }
        synchronized (TO_DROP) {
            TO_DROP.add(new Object[]{e, push});
        }
    }

    /** A lying NPC takes no damage at all (Sable hurts ragdolls on hard landings; a dead NPC loses its body). */
    private static void onIncoming(LivingIncomingDamageEvent event) {
        LivingEntity e = event.getEntity();
        if (!e.level().isClientSide() && isDown(e)) event.setCanceled(true);
    }

    /**
     * Really lying as a ragdoll right now. The DOWN_TAG is saved with the NPC, so it also survives in a world that was
     * closed while the NPC lay down, and gets copied into clones / spawner NPCs made from it: such an NPC stands, but
     * would take no hits at all. A tag that does not belong to a lying body is removed (and the NPC healed).
     */
    static boolean isDown(LivingEntity e) {
        if (!e.getTags().contains(DOWN_TAG)) return false;
        Integer at = DOWNED.get(e);
        if (at != null && tickCounter - at < 60) return true;            // just dropped: Sable may still build the body
        if (!RagdollBridge.isAvailable()) return at != null;             // no Sable: only this session's knock-overs
        if (RagdollBridge.isRagdolled(e) || RagdollBridge.isPending(e)) return true;
        e.removeTag(DOWN_TAG);
        DOWNED.remove(e);
        if (e.getHealth() < e.getMaxHealth()) e.setHealth(e.getMaxHealth());
        return false;
    }

    private static int tickCounter;

    private static void onTick(ServerTickEvent.Post event) {
        tickCounter++;
        if (tickCounter % 20 == 0) RagdollBridge.sweepStale(tickCounter);
        // stood up by something else (kickmod /unragdoll, Sable): drop the lying protection and heal
        if (tickCounter % 40 == 0 && !DOWNED.isEmpty()) {
            DOWNED.entrySet().removeIf(en -> {
                LivingEntity e = en.getKey();
                if (e.isRemoved()) return true;
                if (!e.getTags().contains(DOWN_TAG)) return true;
                if (tickCounter - en.getValue() < 60 || RagdollBridge.isRagdolled(e) || RagdollBridge.isPending(e)) return false;
                if (RagdollBridge.isAvailable()) { e.removeTag(DOWN_TAG); e.setHealth(e.getMaxHealth()); return true; }
                return false;
            });
        }
        List<Object[]> todo;
        synchronized (TO_DROP) {
            todo = new ArrayList<>(TO_DROP);
            TO_DROP.clear();
        }
        for (Object[] o : todo) {
            LivingEntity e = (LivingEntity) o[0];
            if (e.isRemoved() || !(e.level() instanceof ServerLevel level)) continue;
            Vec3 v = (Vec3) o[1];
            e.setHealth(Math.max(1.0F, e.getHealth()));
            boolean ok = RagdollBridge.launchForever(level, e, v, new Vec3(level.random.nextGaussian(), level.random.nextGaussian(), level.random.nextGaussian()));
            DOWNED.put(e, tickCounter);
            if (!ok) {   // no Sable: at least knock it over
                e.setDeltaMovement(v.scale(1.0D / 20.0D));
                e.hurtMarked = true;
            }
        }
    }

    // ------------------------------------------------------------------ /npcrevive
    private static void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("npcrevive")
                .executes(c -> revive(c, 200))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 2000))
                        .executes(c -> revive(c, IntegerArgumentType.getInteger(c, "radius")))));
    }

    private static int revive(CommandContext<CommandSourceStack> c, int radius) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        Vec3 centre = src.getPosition();
        double r2 = (double) radius * radius;
        int n = 0;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(radius), x -> x.getTags().contains(DOWN_TAG))) {
            if (e.distanceToSqr(centre) > r2) continue;
            if (RagdollBridge.isRagdolled(e)) RagdollBridge.release(e);
            e.removeTag(DOWN_TAG);
            DOWNED.remove(e);
            e.setHealth(e.getMaxHealth());
            n++;
        }
        final int count = n;
        src.sendSuccess(() -> Component.literal("Поднято NPC: " + count), false);
        return n;
    }

}

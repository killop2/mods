package com.example.flatoutcar;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Трупы сбитых мобов. Труп — это моб в «вечном» рэгдолле Sable: тело лежит, сам моб скрыт под ним,
 * без ИИ и звуков. Пока моб жив, Sable держит тело; если моб умрёт — Sable уберёт и тело,
 * поэтому урон трупу проходит только от игрока (ударил труп — убрал его).
 * Срок жизни и лимит трупов — в конфиге (mobs.corpseLifetimeSeconds, mobs.maxCorpses).
 * Метка хранится в NBT моба, поэтому после перезахода в мир трупы снова учитываются.
 */
@EventBusSubscriber(modid = FlatoutCarMod.MOD_ID)
public final class CorpseManager {
    private static final String TAG = FlatoutCarMod.MOD_ID + ":corpse_until";
    private static final String TAG_NO_AI = FlatoutCarMod.MOD_ID + ":orig_no_ai";
    private static final String TAG_SILENT = FlatoutCarMod.MOD_ID + ":orig_silent";
    private static final long FOREVER = Long.MAX_VALUE;

    /** По измерениям: UUID трупа -> игровое время удаления (в порядке появления). */
    private static final Map<ResourceKey<Level>, LinkedHashMap<UUID, Long>> CORPSES = new HashMap<>();

    private CorpseManager() {
    }

    public static boolean isCorpse(Entity e) {
        return e.getPersistentData().contains(TAG);
    }

    /** Превращает моба в труп, пытаясь запустить «вечный» рэгдолл. @return true при успехе. */
    public static boolean launchCorpse(ServerLevel level, LivingEntity mob, Vec3 velocity) {
        if (!RagdollBridge.launchMobCorpse(level, mob, velocity)) {
            return false;
        }
        int life = CarConfig.MOBS_CORPSE_LIFETIME.get();
        long until = life <= 0 ? FOREVER : level.getGameTime() + life * 20L;
        CompoundTag data = mob.getPersistentData();
        data.putLong(TAG, until);
        // исходное состояние — чтобы /flatoutcar revive вернул моба как был
        data.putBoolean(TAG_NO_AI, mob instanceof Mob mm && mm.isNoAi());
        data.putBoolean(TAG_SILENT, mob.isSilent());
        if (mob instanceof Mob m) {
            m.setPersistenceRequired();   // не исчезает при удалении игрока
            m.setNoAi(true);              // скрытый моб под телом не ходит и не атакует
            m.setTarget(null);
        }
        mob.setSilent(true);
        track(level, mob.getUUID(), until);
        return true;
    }

    private static void track(ServerLevel level, UUID id, long until) {
        LinkedHashMap<UUID, Long> map = CORPSES.computeIfAbsent(level.dimension(), k -> new LinkedHashMap<>());
        map.put(id, until);
        int max = CarConfig.MOBS_MAX_CORPSES.get();
        Iterator<Map.Entry<UUID, Long>> it = map.entrySet().iterator();
        while (map.size() > max && it.hasNext()) {
            UUID old = it.next().getKey();
            it.remove();
            Entity e = level.getEntity(old);
            if (e != null) {
                e.discard();
            }
        }
    }

    /** Труп загрузился вместе с чанком/миром — снова учитываем. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && isCorpse(event.getEntity())) {
            CompoundTag data = event.getEntity().getPersistentData();
            track(level, event.getEntity().getUUID(), data.getLong(TAG));
        }
    }

    /** Урон трупу проходит только от игрока; падения, удары тела о землю и прочее его не убивают. */
    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        LivingEntity e = event.getEntity();
        if (!e.level().isClientSide && isCorpse(e) && !(event.getSource().getEntity() instanceof Player)) {
            event.setCanceled(true);
        }
    }

    // ---------- команды ----------

    /**
     * /flatoutcar revive [радиус]       — поднять лежащих мобов/NPC (все загруженные в измерении или в радиусе)
     * /flatoutcar clearcorpses [радиус] — убрать трупы
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(FlatoutCarMod.MOD_ID)
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("revive")
                        .executes(c -> run(c.getSource(), -1, true))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 10000))
                                .executes(c -> run(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), true))))
                .then(Commands.literal("clearcorpses")
                        .executes(c -> run(c.getSource(), -1, false))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 10000))
                                .executes(c -> run(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), false)))));
    }

    private static int run(CommandSourceStack src, int radius, boolean revive) {
        ServerLevel level = src.getLevel();
        LinkedHashMap<UUID, Long> map = CORPSES.get(level.dimension());
        int count = 0;
        if (map != null) {
            Vec3 at = src.getPosition();
            for (UUID id : new ArrayList<>(map.keySet())) {
                Entity e = level.getEntity(id);
                if (!(e instanceof LivingEntity mob) || !mob.isAlive()) {
                    map.remove(id);
                    continue;
                }
                if (radius > 0 && mob.position().distanceTo(at) > radius) {
                    continue;
                }
                map.remove(id);
                if (revive) {
                    revive(level, mob);
                } else {
                    mob.discard();
                }
                count++;
            }
        }
        final int n = count;
        src.sendSuccess(() -> Component.literal(revive ? "Поднято мобов: " + n : "Убрано трупов: " + n), true);
        return count;
    }

    private static void revive(ServerLevel level, LivingEntity mob) {
        CompoundTag data = mob.getPersistentData();
        boolean noAi = data.getBoolean(TAG_NO_AI);
        boolean silent = data.getBoolean(TAG_SILENT);
        data.remove(TAG);
        data.remove(TAG_NO_AI);
        data.remove(TAG_SILENT);
        RagdollBridge.releaseMob(level, mob);
        if (mob instanceof Mob m) {
            m.setNoAi(noAi);
        }
        mob.setSilent(silent);
    }

    /** Раз в 2 секунды: убираем истёкшие трупы и забываем выгруженные/убитые. */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 40L != 0L) {
            return;
        }
        LinkedHashMap<UUID, Long> map = CORPSES.get(level.dimension());
        if (map == null || map.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        Iterator<Map.Entry<UUID, Long>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> en = it.next();
            Entity e = level.getEntity(en.getKey());
            if (e == null || !e.isAlive()) {
                it.remove();             // выгружен с чанком (вернётся через onJoin) или убит игроком
            } else if (now >= en.getValue()) {
                it.remove();
                e.discard();             // Sable уберёт тело вместе с мобом
            }
        }
    }
}

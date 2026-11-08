package com.example.wheelofwonder;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Wheel of Wonder: a carnival weapon. Everything gameplay-related runs on the server inside WheelEntity;
 * the client only draws the wheel, its debris and the side camera. No network packets of its own.
 */
@Mod(WheelMod.MOD_ID)
public final class WheelMod {
    public static final String MOD_ID = "wheelofwonder";

    /** Entity tag of NPCs ragdolled by the wheel (damage protection, /wheelofwonder release). Saved with the entity. */
    public static final String RAGDOLL_TAG = "wheelofwonder_ragdoll";
    /** kickmod's tag: if kickmod is installed its joint mixin makes these ragdolls limp (floppy on the wheel). */
    public static final String KICKMOD_LIMP_TAG = "kickmod_ragdoll";

    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<WheelEntity>> WHEEL = ENTITIES.register("wheel",
            () -> EntityType.Builder.<WheelEntity>of(WheelEntity::new, MobCategory.MISC)
                    .sized(1.0F, 3.2F).noSave().fireImmune().clientTrackingRange(16).updateInterval(1)
                    .build(MOD_ID + ":wheel"));

    public static final DeferredHolder<EntityType<?>, EntityType<WheelPieceEntity>> PIECE = ENTITIES.register("wheel_piece",
            () -> EntityType.Builder.<WheelPieceEntity>of(WheelPieceEntity::new, MobCategory.MISC)
                    .sized(0.3F, 0.3F).noSave().fireImmune().clientTrackingRange(10).updateInterval(2)
                    .build(MOD_ID + ":wheel_piece"));

    public static final DeferredHolder<EntityType<?>, EntityType<BallistaEntity>> BALLISTA = ENTITIES.register("dark_ballista",
            () -> EntityType.Builder.<BallistaEntity>of(BallistaEntity::new, MobCategory.MISC)
                    .sized(2.2F, 1.9F).fireImmune().clientTrackingRange(10).updateInterval(3)
                    .build(MOD_ID + ":dark_ballista"));

    public static final DeferredItem<BallistaItem> BALLISTA_ITEM = ITEMS.register("dark_ballista",
            () -> new BallistaItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredHolder<EntityType<?>, EntityType<RocketRideEntity>> ROCKET_RIDE = ENTITIES.register("rocket_ride",
            () -> EntityType.Builder.<RocketRideEntity>of(RocketRideEntity::new, MobCategory.MISC)
                    .sized(1.8F, 4.8F).fireImmune().clientTrackingRange(10).updateInterval(3)
                    .build(MOD_ID + ":rocket_ride"));

    public static final DeferredHolder<EntityType<?>, EntityType<RocketEntity>> ROCKET = ENTITIES.register("rocket",
            () -> EntityType.Builder.<RocketEntity>of(RocketEntity::new, MobCategory.MISC)
                    .sized(0.8F, 0.8F).noSave().fireImmune().clientTrackingRange(16).updateInterval(1)
                    .build(MOD_ID + ":rocket"));

    public static final DeferredHolder<EntityType<?>, EntityType<RangerRideEntity>> RANGER = ENTITIES.register("ranger_ride",
            () -> EntityType.Builder.<RangerRideEntity>of(RangerRideEntity::new, MobCategory.MISC)
                    .sized(1.6F, 8.0F).fireImmune().clientTrackingRange(12).updateInterval(3)
                    .build(MOD_ID + ":ranger_ride"));

    public static final DeferredItem<RangerRideItem> RANGER_ITEM = ITEMS.register("ranger_ride",
            () -> new RangerRideItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredItem<RocketRideItem> ROCKET_ITEM = ITEMS.register("rocket_ride",
            () -> new RocketRideItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredItem<WheelItem> WHEEL_ITEM = ITEMS.register("wheel_of_wonder",
            () -> new WheelItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredItem<WalrusGlovesItem> GLOVES_ITEM = ITEMS.register("walrus_punch",
            () -> new WalrusGlovesItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredHolder<EntityType<?>, EntityType<MeteorEntity>> METEOR = ENTITIES.register("chaos_meteor",
            () -> EntityType.Builder.<MeteorEntity>of(MeteorEntity::new, MobCategory.MISC)
                    .sized(2.2F, 2.2F).noSave().fireImmune().clientTrackingRange(16).updateInterval(1)
                    .build(MOD_ID + ":chaos_meteor"));

    public static final DeferredItem<ChaosMeteorItem> METEOR_ITEM = ITEMS.register("chaos_meteor",
            () -> new ChaosMeteorItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredItem<ShockwaveItem> SHOCK_ITEM = ITEMS.register("shockwave",
            () -> new ShockwaveItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredHolder<EntityType<?>, EntityType<GhostShipEntity>> GHOST_SHIP = ENTITIES.register("ghost_ship",
            () -> EntityType.Builder.<GhostShipEntity>of(GhostShipEntity::new, MobCategory.MISC)
                    .sized(4.0F, 6.0F).noSave().fireImmune().clientTrackingRange(16).updateInterval(2)
                    .build(MOD_ID + ":ghost_ship"));

    public static final DeferredItem<TrizubItem> TRIZUB_ITEM = ITEMS.register("trizub",
            () -> new TrizubItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredHolder<EntityType<?>, EntityType<StrangeEntity>> STRANGE = ENTITIES.register("strange",
            () -> EntityType.Builder.<StrangeEntity>of(StrangeEntity::new, MobCategory.MISC)
                    .sized(1.0F, 2.0F).noSave().fireImmune().clientTrackingRange(16).updateInterval(2)
                    .build(MOD_ID + ":strange"));

    public static final DeferredItem<StrangeHammerItem> STRANGE_HAMMER_ITEM = ITEMS.register("strange_hammer",
            () -> new StrangeHammerItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredHolder<EntityType<?>, EntityType<PrismCoasterEntity>> PRISM = ENTITIES.register("prism_splitter",
            () -> EntityType.Builder.<PrismCoasterEntity>of(PrismCoasterEntity::new, MobCategory.MISC)
                    .sized(3.0F, 2.5F).fireImmune().clientTrackingRange(16).updateInterval(1)
                    .build(MOD_ID + ":prism_splitter"));

    public static final DeferredItem<PrismCoasterItem> PRISM_ITEM = ITEMS.register("prism_splitter",
            () -> new PrismCoasterItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredItem<TelekinesisOrbItem> ORB_ITEM = ITEMS.register("telekinesis_orb",
            () -> new TelekinesisOrbItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredItem<SinisterChainItem> CHAIN_ITEM = ITEMS.register("sinister_chain",
            () -> new SinisterChainItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredItem<ImpalementArtsItem> DAGGERS_ITEM = ITEMS.register("impalement_arts",
            () -> new ImpalementArtsItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    /**
     * Keeps the chunks under a flying rocket / ragdoll simulated (entities tick there) for a few seconds, even when
     * the camera has flown away from the player. Renewed every tick while they fly; expires by itself.
     */
    public static final net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> FLIGHT_TICKET =
            net.minecraft.server.level.TicketType.create(MOD_ID + "_flight", java.util.Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong), 60);

    public static void keepLoaded(net.minecraft.server.level.ServerLevel level, Vec3 at) {
        net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(net.minecraft.core.BlockPos.containing(at));
        level.getChunkSource().addRegionTicket(FLIGHT_TICKET, cp, 3, cp);
    }

    /**
     * NPCs a wheel, ballista or rocket is holding right now. The PULL_TAG alone is not trusted: entity tags are saved
     * with the NPC, so a tag left over from a quit game, or copied into a clone / spawner NPC, would make that NPC
     * unhittable forever. Only an NPC in this set (this session, this ride) is protected; stale tags are removed.
     */
    private static final java.util.Set<java.util.UUID> HELD = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void hold(LivingEntity mob) {
        mob.addTag(WheelEntity.PULL_TAG);
        HELD.add(mob.getUUID());
    }

    public static void unhold(LivingEntity mob) {
        mob.removeTag(WheelEntity.PULL_TAG);
        HELD.remove(mob.getUUID());
    }

    /** Really held by one of our rides right now (a leftover tag is cleaned up on the way). */
    public static boolean held(net.minecraft.world.entity.Entity e) {
        if (!e.getTags().contains(WheelEntity.PULL_TAG)) return false;
        if (HELD.contains(e.getUUID())) return true;
        if (!e.level().isClientSide()) e.removeTag(WheelEntity.PULL_TAG);
        return false;
    }

    /** NPCs loaded from the save or spawned from a copy: drop tags that belonged to a ride of another session. */
    private static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof LivingEntity le) held(le);
    }

    public WheelMod(IEventBus modBus, ModContainer container) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, WheelConfig.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, WheelConfig.CLIENT_SPEC);
        modBus.addListener(WheelMod::creativeTabs);
        modBus.addListener(WheelMod::registerPayloads);
        NeoForge.EVENT_BUS.addListener(FollowTracker::onServerTick);
        NeoForge.EVENT_BUS.addListener(PunchServer::onServerTick);
        NeoForge.EVENT_BUS.addListener(BallistaServer::onServerTick);
        NeoForge.EVENT_BUS.addListener(DaggerServer::onServerTick);
        NeoForge.EVENT_BUS.addListener(DaggerServer::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ChainServer::onServerTick);
        NeoForge.EVENT_BUS.addListener(ChainServer::onJoin);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> ChainServer.onServerStopping());
        NeoForge.EVENT_BUS.addListener(ShockwaveServer::onServerTick);
        NeoForge.EVENT_BUS.addListener(DeferredBlockChanges::onServerTick);
        NeoForge.EVENT_BUS.addListener(WhirlServer::onServerTick);
        NeoForge.EVENT_BUS.addListener(TelekinesisServer::onServerTick);
        NeoForge.EVENT_BUS.addListener(TrizubServer::onServerTick);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> TrizubServer.onServerStopping());
        NeoForge.EVENT_BUS.addListener(StrangeServer::onServerTick);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> StrangeServer.onServerStopping());
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> TelekinesisServer.onServerStopping());
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> WhirlServer.onServerStopping());
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> DeferredBlockChanges.clear());
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> ShockwaveServer.onServerStopping());
        NeoForge.EVENT_BUS.addListener(WheelMod::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(WheelMod::onJoin);
        NeoForge.EVENT_BUS.addListener(WheelMod::onRegisterCommands);
    }

    private static void registerPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        var r = event.registrar("2");
        r.playToClient(FollowPayload.TYPE, FollowPayload.STREAM_CODEC, (p, ctx) -> ClientHooks.onFollow(p));
        r.playToServer(PunchRequest.TYPE, PunchRequest.STREAM_CODEC, PunchServer::handleRequest);
        r.playToClient(PunchState.TYPE, PunchState.STREAM_CODEC, (p, ctx) -> ClientHooks.onPunchState(p));
        r.playToServer(BallistaRequest.TYPE, BallistaRequest.STREAM_CODEC, BallistaServer::handleRequest);
        r.playToClient(BallistaState.TYPE, BallistaState.STREAM_CODEC, (p, ctx) -> ClientHooks.onBallistaState(p));
        r.playToServer(DaggerRequest.TYPE, DaggerRequest.STREAM_CODEC, DaggerServer::handleRequest);
        r.playToClient(DaggerState.TYPE, DaggerState.STREAM_CODEC, (p, ctx) -> ClientHooks.onDaggerState(p));
        r.playToServer(ChainRequest.TYPE, ChainRequest.STREAM_CODEC, ChainServer::handleRequest);
        r.playToClient(ChainSync.TYPE, ChainSync.STREAM_CODEC, (p, ctx) -> ClientHooks.onChainSync(p));
        r.playToServer(TrizubRequest.TYPE, TrizubRequest.STREAM_CODEC, TrizubServer::handleRequest);
        r.playToClient(TrizubState.TYPE, TrizubState.STREAM_CODEC, (p, ctx) -> ClientHooks.onTrizubState(p));
        r.playToServer(StrangeRequest.TYPE, StrangeRequest.STREAM_CODEC, StrangeServer::handleRequest);
        r.playToClient(StrangeState.TYPE, StrangeState.STREAM_CODEC, (p, ctx) -> ClientHooks.onStrangeState(p));
        r.playToClient(PrismSync.TYPE, PrismSync.STREAM_CODEC, (p, ctx) -> ClientHooks.onPrismSync(p));
        r.playToServer(TeleRequest.TYPE, TeleRequest.STREAM_CODEC, TelekinesisServer::handleRequest);
        r.playToClient(TeleState.TYPE, TeleState.STREAM_CODEC, (p, ctx) -> ClientHooks.onTeleState(p));
        r.playToClient(WhirlState.TYPE, WhirlState.STREAM_CODEC, (p, ctx) -> ClientHooks.onWhirlState(p));
        r.playToServer(ShockRequest.TYPE, ShockRequest.STREAM_CODEC, ShockwaveServer::handleRequest);
        r.playToClient(ShockState.TYPE, ShockState.STREAM_CODEC, (p, ctx) -> ClientHooks.onShockState(p));
    }

    private static void creativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT || event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(WHEEL_ITEM.get());
            event.accept(GLOVES_ITEM.get());
            event.accept(DAGGERS_ITEM.get());
            event.accept(CHAIN_ITEM.get());
            event.accept(METEOR_ITEM.get());
            event.accept(SHOCK_ITEM.get());
            event.accept(ORB_ITEM.get());
            event.accept(TRIZUB_ITEM.get());
            event.accept(STRANGE_HAMMER_ITEM.get());
            event.accept(BALLISTA_ITEM.get());
            event.accept(ROCKET_ITEM.get());
            event.accept(RANGER_ITEM.get());
            event.accept(PRISM_ITEM.get());
        }
    }

    /**
     * Ragdolls hurt their mob on hard landings; an NPC that dies loses its ragdoll and CustomNPCs respawns it at home.
     * NPCs thrown by the wheel take no damage while they lie as a ragdoll (same rule as kickmod).
     * Also no fall damage while the wheel is pulling them through the air.
     */
    private static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity e = event.getEntity();
        if (e.level().isClientSide()) return;
        // the Walrus PUNCH! leap: no fall damage for the puncher until they land again
        if (e instanceof net.minecraft.server.level.ServerPlayer sp && PunchServer.protectsFall(sp)
                && event.getSource().is(net.minecraft.tags.DamageTypeTags.IS_FALL)) {
            event.setCanceled(true);
            return;
        }
        if (!WheelConfig.PROTECT.get()) return;
        if (held(e)) {
            event.setCanceled(true);
            return;
        }
        if (e.getTags().contains(RAGDOLL_TAG) && WheelRagdolls.isRagdolled(e)) {
            event.setCanceled(true);
        }
    }

    /** /wheelofwonder release [radius]: stands every ragdolled NPC around back up (kickmod's /unragdoll works too). */
    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(MOD_ID)
                .then(Commands.literal("release")
                        .executes(c -> release(c.getSource(), 150))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 1000))
                                .executes(c -> release(c.getSource(), IntegerArgumentType.getInteger(c, "radius"))))));
    }

    private static int release(CommandSourceStack src, int radius) {
        Vec3 center = src.getPosition();
        double r2 = (double) radius * radius;
        int n = 0;
        for (LivingEntity mob : src.getLevel().getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(radius),
                m -> !(m instanceof Player))) {
            if (mob.distanceToSqr(center) <= r2 && WheelRagdolls.isRagdolled(mob) && WheelRagdolls.release(mob)) {
                mob.removeTag(RAGDOLL_TAG);
                n++;
            }
        }
        final int count = n;
        src.sendSuccess(() -> Component.translatable("wheelofwonder.msg.released", count), false);
        return count;
    }
}

package com.example.waterslide;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

@Mod(WaterSlideMod.MODID)
public final class WaterSlideMod {
    public static final String MODID = "waterslide";

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    private static BlockBehaviour.Properties partProps() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(0.6F, 1200.0F)
                .sound(SoundType.METAL).noOcclusion().dynamicShape().noLootTable()
                .pushReaction(PushReaction.BLOCK)
                .isValidSpawn((s, l, p, t) -> false).isRedstoneConductor((s, l, p) -> false)
                .isSuffocating((s, l, p) -> false).isViewBlocking((s, l, p) -> false);
    }

    public static final DeferredBlock<SlidePartBlock> PART = BLOCKS.register("part", () -> new SlidePartBlock(partProps()));
    public static final DeferredBlock<SlideAnchorBlock> ANCHOR = BLOCKS.register("anchor", () -> new SlideAnchorBlock(partProps()));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SlideAnchorBlockEntity>> ANCHOR_BE =
            BLOCK_ENTITIES.register("anchor", () -> BlockEntityType.Builder.of(SlideAnchorBlockEntity::new, ANCHOR.get()).build(null));

    /** One spawner per slide type (index = type). */
    public static final java.util.List<DeferredItem<SlideItem>> SPAWNERS = java.util.List.of(
            spawner("water_slide", 0), spawner("water_slide_drop", 1), spawner("water_slide_spiral", 2), spawner("water_slide_serpentine", 3),
            spawner("dark_carnival_slide", 9));
    public static final DeferredItem<SlideItem> SPAWNER = SPAWNERS.get(0);

    private static DeferredItem<SlideItem> spawner(String id, int type) {
        return ITEMS.register(id, () -> new SlideItem(new Item.Properties().stacksTo(1), type));
    }

    private static final boolean SABLE = ModList.get().isLoaded("sable");

    public WaterSlideMod(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(WaterSlideMod::addToTab);
        NeoForge.EVENT_BUS.addListener(WaterSlideMod::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(SlidePhysics::onEntityTick);
        NeoForge.EVENT_BUS.addListener(WaterSlideMod::onLevelLoad);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> SlideRegistry.clear(false));
        if (SABLE) {
            NeoForge.EVENT_BUS.addListener(WaterSlideMod::onLevelTick);
        }
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Supplier<Runnable> client = () -> () -> com.example.waterslide.client.ClientSetup.init(modBus);
            client.get().run();
        }
    }

    private static void addToTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            for (DeferredItem<SlideItem> item : SPAWNERS) event.accept(item.get());
        }
    }

    /** The spawner never breaks blocks: left click places a slide. */
    private static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getItemStack().getItem() instanceof SlideItem) {
            event.setCanceled(true);
        }
    }

    private static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SlideManager.loadSaved(level);
        }
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SableSlidePush.tick(level);
        }
    }
}

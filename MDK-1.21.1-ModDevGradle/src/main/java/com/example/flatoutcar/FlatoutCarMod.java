package com.example.flatoutcar;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(FlatoutCarMod.MOD_ID)
public class FlatoutCarMod {
    public static final String MOD_ID = "flatoutcar";

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MOD_ID);

    /**
     * Точка посадки водителя относительно центра машины (в блоках, без поворота: +X — левый борт, +Z — вперёд).
     * Игрок ставится на 0.6 ниже этой точки, его таз оказывается примерно на 0.1 выше неё.
     * Подобрано под водительское кресло модели BMW M3 GTR (масштаб 1.25).
     */
    public static final Vec3 SEAT = new Vec3(0.55D, 0.40D, -0.25D);

    public static final DeferredHolder<EntityType<?>, EntityType<CarEntity>> CAR = ENTITIES.register("car",
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    .sized(2.6F, 1.7F)
                    .passengerAttachments(SEAT)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build("car"));

    public static final DeferredHolder<Item, CarItem> CAR_ITEM = ITEMS.register("car_spawner",
            () -> new CarItem(new Item.Properties().stacksTo(1)));

    // Звуки машины. Сами файлы задаются в assets/flatoutcar/sounds.json (сейчас — ванильные звуки, без своих ogg).
    public static final DeferredHolder<SoundEvent, SoundEvent> ENGINE_SOUND = sound("car.engine");
    public static final DeferredHolder<SoundEvent, SoundEvent> CRASH_SOUND = sound("car.crash");
    public static final DeferredHolder<SoundEvent, SoundEvent> BUMP_SOUND = sound("car.bump");
    public static final DeferredHolder<SoundEvent, SoundEvent> EJECT_SOUND = sound("car.eject");

    public FlatoutCarMod(IEventBus modBus, ModContainer container) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, CarConfig.SPEC);
        modBus.addListener(this::registerPayloads);
        modBus.addListener(this::addToCreativeTab);
    }

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, name)));
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("2");
        registrar.playToServer(EjectPayload.TYPE, EjectPayload.STREAM_CODEC, ServerEject::handle);
    }

    private void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(CAR_ITEM.get());
        }
    }
}

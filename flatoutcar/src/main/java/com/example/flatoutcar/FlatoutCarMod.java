package com.example.flatoutcar;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
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

    /** Высота посадки игрока: attach-точка минус 0.6 (ridingOffset игрока). Меняйте при желании. */
    private static final float SEAT_ATTACH_Y = 0.95F;

    public static final DeferredHolder<EntityType<?>, EntityType<CarEntity>> CAR = ENTITIES.register("car",
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    .sized(1.9F, 1.0F)
                    .passengerAttachments(SEAT_ATTACH_Y)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build("car"));

    public static final DeferredHolder<Item, CarItem> CAR_ITEM = ITEMS.register("car_spawner",
            () -> new CarItem(new Item.Properties().stacksTo(1)));

    public FlatoutCarMod(IEventBus modBus) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(this::registerPayloads);
        modBus.addListener(this::addToCreativeTab);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(EjectPayload.TYPE, EjectPayload.STREAM_CODEC, ServerEject::handle);
    }

    private void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(CAR_ITEM.get());
        }
    }
}

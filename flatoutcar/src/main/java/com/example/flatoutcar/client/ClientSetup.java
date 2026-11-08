package com.example.flatoutcar.client;

import com.example.flatoutcar.FlatoutCarMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = FlatoutCarMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    /** Клавиша по умолчанию: G (меняется в настройках управления). */
    public static final KeyMapping EJECT_KEY = new KeyMapping(
            "key.flatoutcar.eject", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.flatoutcar");

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(EJECT_KEY);
    }

    @SubscribeEvent
    public static void registerLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(CarRenderer.LAYER, CarRenderer::createLayer);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(FlatoutCarMod.CAR.get(), CarRenderer::new);
    }
}

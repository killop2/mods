package com.example.waterslide.client;

import com.example.waterslide.SlidePartBlock;
import com.example.waterslide.SlideRegistry;
import com.example.waterslide.WaterSlideMod;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class ClientSetup {
    private ClientSetup() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers e) ->
                e.registerBlockEntityRenderer(WaterSlideMod.ANCHOR_BE.get(), ctx -> new SlideRenderer()));
        // no black voxel outline when looking at the slide
        NeoForge.EVENT_BUS.addListener((RenderHighlightEvent.Block e) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.getBlockState(e.getTarget().getBlockPos()).getBlock() instanceof SlidePartBlock) {
                e.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> SlideRegistry.clear(true));
    }
}

package com.example.huggynpc.client;

import com.example.huggynpc.HuggyNpcMod;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class ClientSetup {
    private ClientSetup() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void init(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers e) -> {
            for (HuggyNpcMod.NpcModel m : HuggyNpcMod.MODELS) {
                if (m == HuggyNpcMod.VERITY) e.registerEntityRenderer(m.type().get(), VerityRenderer::new);
                else e.registerEntityRenderer(m.type().get(), ctx -> new HuggyRenderer(ctx, m));
            }
        });
        VerityClient.init();
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderFrameEvent.Pre e) -> RagdollFrame.nextFrame());
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, NpcAim::onAttack);
        NeoForge.EVENT_BUS.addListener(RemoteDeleteAll::onInit);
        // debugging aid: -Dhuggynpc.fpslog=true writes the frame rate to the log every 2 seconds
        if (Boolean.getBoolean("huggynpc.fpslog")) {
            NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) -> {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.level != null && mc.level.getGameTime() % 40 == 0) {
                    com.mojang.logging.LogUtils.getLogger().info("[huggynpc] fps={} entities={}", mc.getFps(), mc.level.getEntityCount());
                }
            });
        }
        // receive the events even if another mod cancels them, so the tracker never goes stale
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, RenderLivingEvent.Pre.class,
                e -> RenderTracker.pre((RenderLivingEvent.Pre) e));
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, RenderLivingEvent.Post.class,
                e -> RenderTracker.post((RenderLivingEvent.Post) e));
    }
}

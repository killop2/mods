package com.example.kickmod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(KickMod.MOD_ID)
public final class KickMod {
    public static final String MOD_ID = "kickmod";

    public KickMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, KickConfig.SPEC);
        modBus.addListener(KickMod::registerPayloads);
        NeoForge.EVENT_BUS.addListener(KickServer::onServerTick);
        NeoForge.EVENT_BUS.addListener(KickServer::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(KickServer::onIncomingDamage);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("2");
        registrar.playToServer(KickPayload.TYPE, KickPayload.STREAM_CODEC, KickServer::handleKick);
        registrar.playToClient(TrackPayload.TYPE, TrackPayload.STREAM_CODEC, (p, ctx) -> ClientHooks.onTrack(p));
    }
}

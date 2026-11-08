package com.example.kickmod;

import net.neoforged.fml.loading.FMLEnvironment;

/** Keeps client classes out of the dedicated server: client code is only touched on the client dist. */
public final class ClientHooks {
    private ClientHooks() {
    }

    public static void onTrack(TrackPayload p) {
        if (FMLEnvironment.dist.isClient()) {
            com.example.kickmod.client.KickController.onTrack(p);
        }
    }
}

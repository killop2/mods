package com.example.wheelofwonder;

import net.neoforged.fml.loading.FMLEnvironment;

/** Keeps client classes out of the dedicated server: client code is only touched on the client dist. */
public final class ClientHooks {
    private ClientHooks() {
    }

    static void onFollow(FollowPayload p) {
        if (FMLEnvironment.dist.isClient()) {
            com.example.wheelofwonder.client.WheelCamera.onFollow(p.entityId(), p.x(), p.y(), p.z());
        }
    }

    static void onPunchState(PunchState p) {
        if (FMLEnvironment.dist.isClient()) {
            com.example.wheelofwonder.client.PunchController.onState(p.phase(), p.entityId(), p.x(), p.y(), p.z());
        }
    }

    static void onBallistaState(BallistaState p) {
        if (FMLEnvironment.dist.isClient()) {
            com.example.wheelofwonder.client.BallistaCamera.onState(p.phase(), p.entityId(), p.x(), p.y(), p.z());
        }
    }

    static void onDaggerState(DaggerState p) {
        if (FMLEnvironment.dist.isClient()) {
            com.example.wheelofwonder.client.DaggerController.onState(p.phase(), p.entityId(), p.x(), p.y(), p.z(), p.ticks());
        }
    }

    static void onShockState(ShockState p) {
        if (FMLEnvironment.dist.isClient()) {
            com.example.wheelofwonder.client.ShockwaveFx.onState(p);
            com.example.wheelofwonder.client.ShockwaveController.onState(p);
        }
    }

    static void onTrizubState(TrizubState p) {
        if (FMLEnvironment.dist.isClient()) {
            com.example.wheelofwonder.client.TrizubController.onState(p);
        }
    }

    static void onStrangeState(StrangeState p) {
        if (FMLEnvironment.dist.isClient()) {
            com.example.wheelofwonder.client.StrangeController.onState(p);
        }
    }

    static void onPrismSync(PrismSync p) {
        if (FMLEnvironment.dist.isClient()) {
            com.example.wheelofwonder.client.PrismCoasterRenderer.onSync(p);
        }
    }

    static void onTeleState(TeleState p) {
        if (FMLEnvironment.dist.isClient()) {
            com.example.wheelofwonder.client.TelekinesisClient.onState(p);
        }
    }

    static void onWhirlState(WhirlState p) {
        if (FMLEnvironment.dist.isClient()) {
            com.example.wheelofwonder.client.WhirlClient.onState(p);
        }
    }

    static void onChainSync(ChainSync p) {
        if (FMLEnvironment.dist.isClient()) {
            com.example.wheelofwonder.client.ChainClient.onSync(p);
        }
    }

    static void wheelTick(WheelEntity wheel) {
        if (FMLEnvironment.dist.isClient()) {
            com.example.wheelofwonder.client.WheelCamera.onWheelTick(wheel);
        }
    }
}

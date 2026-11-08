package com.example.flatoutcar.client;

import com.example.flatoutcar.CarEntity;
import com.example.flatoutcar.EjectPayload;
import com.example.flatoutcar.FlatoutCarMod;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = FlatoutCarMod.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        while (ClientSetup.EJECT_KEY.consumeClick()) {
            if (mc.player.getVehicle() instanceof CarEntity car && car.getControllingPassenger() == mc.player) {
                PacketDistributor.sendToServer(new EjectPayload(car.getSpeed(), false));
            }
        }
    }
}

package com.example.flatoutcar;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ServerEject {
    private ServerEject() {
    }

    public static void handle(EjectPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.getVehicle() instanceof CarEntity car) || car.getControllingPassenger() != player) {
                return;
            }
            if (!Double.isFinite(payload.x()) || !Double.isFinite(payload.y()) || !Double.isFinite(payload.z())) {
                return;
            }

            // блоков/тик -> м/с, с ограничением от читерских значений
            Vec3 v = new Vec3(payload.x(), payload.y(), payload.z()).scale(20.0D);
            double max = CarEntity.MAX_SPEED * 20.0D * 1.2D;
            if (v.length() > max) {
                v = v.scale(max / v.length());
            }

            // машина продолжает катиться по инерции
            car.setSpeed((float) (new Vec3(payload.x(), 0, payload.z()).dot(car.forward())));

            // позиция «у лобового стекла»: впереди и выше центра машины
            Vec3 f = car.forward();
            Vec3 start = car.position().add(f.x * 0.9D, 1.1D, f.z * 0.9D);

            player.stopRiding();
            player.connection.teleport(start.x, start.y, start.z, player.getYRot(), player.getXRot());

            if (!RagdollBridge.launch(player, v)) {
                player.sendSystemMessage(Component.literal(
                        "[Flatout Car] Sable: Ragdolls API не найден - вылет без рэгдолла."));
                player.setDeltaMovement(v.scale(0.05D));
                player.hurtMarked = true;
            }
        });
    }
}

package com.example.flatoutcar;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Серверная часть вылета (кнопка G или столкновение).
 * Скорость вылета (м/с) = база + коэффициент * скорость машины, параметры в CarConfig.
 */
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
            if (!Float.isFinite(payload.speed())) {
                return;
            }
            eject(player, car, Mth.clamp(payload.speed(), -CarEntity.MAX_SPEED, CarEntity.MAX_SPEED), payload.crash());
        });
    }

    public static void eject(ServerPlayer player, CarEntity car, float carSpeed, boolean crash) {
        // уже в рэгдолле от другого мода — не мешаем его физике
        if (RagdollBridge.isRagdolled(player)) {
            return;
        }
        double carMps = Math.abs(carSpeed) * 20.0D;
        Vec3 f = car.forward();
        double dir = carSpeed < -0.05F ? -1.0D : 1.0D;   // на заднем ходу вылет назад
        double forward = CarConfig.EJECT_FORWARD_BASE.get() + CarConfig.EJECT_FORWARD_PER_SPEED.get() * carMps;
        double up = CarConfig.EJECT_UP_BASE.get() + CarConfig.EJECT_UP_PER_SPEED.get() * carMps;
        Vec3 v = new Vec3(f.x * forward * dir, up, f.z * forward * dir);
        double max = CarConfig.EJECT_MAX_SPEED.get();
        if (v.length() > max) {
            v = v.scale(max / v.length());
        }

        // при G машина катится дальше по инерции; при ударе скорость уже погашена клиентом
        if (!crash) {
            car.setSpeed(carSpeed);
        }

        // старт — над капотом у лобового стекла; если там блок, то прямо с сиденья вверх
        Vec3 seat = player.position();
        Vec3 start = seat.add(f.x * 0.9D * dir, 0.9D, f.z * 0.9D * dir);
        AABB box = player.getDimensions(player.getPose()).makeBoundingBox(start);
        if (player.level().getBlockCollisions(player, box).iterator().hasNext()) {
            start = seat.add(0.0D, 0.6D, 0.0D);
        }

        player.stopRiding();
        player.connection.teleport(start.x, start.y, start.z, player.getYRot(), player.getXRot());

        player.level().playSound(null, start.x, start.y, start.z, FlatoutCarMod.EJECT_SOUND.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
        if (crash) {
            player.level().playSound(null, car.getX(), car.getY(), car.getZ(), FlatoutCarMod.CRASH_SOUND.get(), SoundSource.NEUTRAL, 1.6F, 0.8F);
        }

        if (!RagdollBridge.launch(player, v)) {
            if (!RagdollBridge.isAvailable()) {
                player.sendSystemMessage(Component.literal(
                        "[Flatout Car] Sable: Ragdolls API не найден - вылет без рэгдолла."));
            }
            player.setDeltaMovement(v.scale(1.0D / 20.0D));   // м/с -> блоки/тик
            player.hurtMarked = true;
        }
    }
}

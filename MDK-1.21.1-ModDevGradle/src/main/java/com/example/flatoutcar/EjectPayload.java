package com.example.flatoutcar;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Клиент водителя -> сервер: «катапультируй меня».
 * speed — скорость машины в блоках/тик (у столкновения — скорость до удара), crash — вылет от столкновения.
 * Направление и сила вылета считаются на сервере по конфигу.
 */
public record EjectPayload(float speed, boolean crash) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<EjectPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(FlatoutCarMod.MOD_ID, "eject"));

    public static final StreamCodec<ByteBuf, EjectPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, EjectPayload::speed,
            ByteBufCodecs.BOOL, EjectPayload::crash,
            EjectPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

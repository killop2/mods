package com.example.flatoutcar;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Клиент -> сервер: «катапультируй меня». Скорость машины в блоках/тик. */
public record EjectPayload(double x, double y, double z) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<EjectPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(FlatoutCarMod.MOD_ID, "eject"));

    public static final StreamCodec<ByteBuf, EjectPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, EjectPayload::x,
            ByteBufCodecs.DOUBLE, EjectPayload::y,
            ByteBufCodecs.DOUBLE, EjectPayload::z,
            EjectPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

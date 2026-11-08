package com.example.kickmod;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server. phase 0 = hit started (wind-up), phase 1 = the hit lands now.
 * launchForward / launchUp: the chosen animation's launch multipliers (clamped on the server).
 */
public record KickPayload(int entityId, int phase, float launchForward, float launchUp) implements CustomPacketPayload {
    public static final int START = 0;
    public static final int IMPACT = 1;
    /** the camera is done with the body (Ctrl): the server stops sending its position */
    public static final int DONE = 2;

    public KickPayload(int entityId, int phase) {
        this(entityId, phase, 1.0F, 1.0F);
    }

    public static final Type<KickPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(KickMod.MOD_ID, "kick"));
    public static final StreamCodec<ByteBuf, KickPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, KickPayload::entityId,
            ByteBufCodecs.VAR_INT, KickPayload::phase,
            ByteBufCodecs.FLOAT, KickPayload::launchForward,
            ByteBufCodecs.FLOAT, KickPayload::launchUp,
            KickPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

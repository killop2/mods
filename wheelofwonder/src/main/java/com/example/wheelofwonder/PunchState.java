package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> puncher: HIT (uppercut landed, pos = body centre), APEX (the body hangs at its highest point, pos),
 * TRACK (body position every tick), SMASH (the top punch landed), LANDED (the body hit the ground), END (combo aborted).
 */
public record PunchState(int phase, int entityId, double x, double y, double z) implements CustomPacketPayload {
    public static final int HIT = 0, APEX = 1, TRACK = 2, SMASH = 3, LANDED = 4, END = 5;
    public static final Type<PunchState> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "punch_state"));
    public static final StreamCodec<ByteBuf, PunchState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PunchState::phase,
            ByteBufCodecs.VAR_INT, PunchState::entityId,
            ByteBufCodecs.DOUBLE, PunchState::x,
            ByteBufCodecs.DOUBLE, PunchState::y,
            ByteBufCodecs.DOUBLE, PunchState::z,
            PunchState::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

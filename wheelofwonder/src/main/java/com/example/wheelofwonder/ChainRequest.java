package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Player -> server (Sinister Chain): ATTACH this NPC (part 0 = hand, 1 = leg), RELEASE the chain this NPC is in,
 * CLOSE the current chain (the next NPC starts a new one), RELEASE_ALL of the player's chains.
 */
public record ChainRequest(int action, int entityId, int part) implements CustomPacketPayload {
    /** WHIRL = Alt + right click: Whirlwind Whip; WHIRL_DONE = its camera is back on the player (Ctrl). */
    public static final int ATTACH = 0, RELEASE = 1, CLOSE = 2, RELEASE_ALL = 3, WHIRL = 4, WHIRL_DONE = 5;
    public static final Type<ChainRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "chain_request"));
    public static final StreamCodec<ByteBuf, ChainRequest> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ChainRequest::action,
            ByteBufCodecs.VAR_INT, ChainRequest::entityId,
            ByteBufCodecs.VAR_INT, ChainRequest::part,
            ChainRequest::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

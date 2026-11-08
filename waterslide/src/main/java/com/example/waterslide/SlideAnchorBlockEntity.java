package com.example.waterslide;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** No saved data: it only registers the slide on load and gives the renderer a place to hang off. */
public class SlideAnchorBlockEntity extends BlockEntity {
    /** Client render cache: light per probe, refreshed now and then. */
    public int[] lights;
    public long lightTick = Long.MIN_VALUE;
    /** Client cache of the Dark Carnival decorations: light per decoration probe, and the last particle tick. */
    public int[] decorLights;
    public long decorLightTick = Long.MIN_VALUE, decorFxTick = Long.MIN_VALUE;

    public SlideAnchorBlockEntity(BlockPos pos, BlockState state) {
        super(WaterSlideMod.ANCHOR_BE.get(), pos, state);
    }

    public SlideInstance instance() {
        Level level = getLevel();
        if (level == null) return null;
        BlockState st = getBlockState();
        return SlideRegistry.register(level.isClientSide(), level.dimension(), worldPosition, st.getValue(SlideAnchorBlock.FACING), st.getValue(SlideAnchorBlock.TYPE));
    }

    @Override
    public void onLoad() {
        super.onLoad();
        SlideInstance s = instance();
        if (s != null && getLevel() instanceof net.minecraft.server.level.ServerLevel server) {
            SlideData.get(server).add(worldPosition, s.facing, s.type);
        }
    }

    private boolean chunkUnloading;

    /**
     * Client: the anchor's chunk can unload while you are still riding far down a long slide (the anchor sits at its
     * start). Then the slide stays known; a slide that was really broken is caught by the physics' trough check.
     */
    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        chunkUnloading = true;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // client: forget it when the block is broken; server keeps it (saved data) until the slide is broken
        if (getLevel() != null && getLevel().isClientSide() && !chunkUnloading) {
            SlideRegistry.unregister(true, getLevel().dimension(), worldPosition);
        }
    }
}

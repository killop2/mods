package com.example.waterslide;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Placing and removing whole slides (server side). */
public final class SlideManager {
    private static boolean removing;

    private SlideManager() {
    }

    public static boolean isRemoving() {
        return removing;
    }

    /** @return translation key of the message to show */
    public static String tryPlace(ServerLevel level, ServerPlayer player, int type) {
        Direction facing = player.getDirection();
        SlideGeometry g = SlideTypes.get(type);
        // put the slide in front of the player: start platform straight ahead, 3 blocks away
        int px = (int) Math.round((g.deckX0 + g.deckX1) * 0.5), pz = (int) Math.floor(g.deckZ0) - 3;
        BlockPos anchor = player.blockPosition().offset(rotate(facing, -px, -pz));

        // a chain type (the long slide) places all its sections, end to end
        int chainAt = SlideTypes.chainIndex(type);
        java.util.List<SlideInstance> parts = new java.util.ArrayList<>();
        if (chainAt == 0) {
            int[] chain = SlideTypes.chainOf(type);
            int[][] off = SlideTypes.offsetsOf(type);
            for (int k = 0; k < chain.length; k++) {
                BlockPos a = anchor.offset(rotate(facing, off[k][0], off[k][1]));
                parts.add(new SlideInstance(level.dimension(), a, facing, chain[k]));
            }
        } else {
            parts.add(new SlideInstance(level.dimension(), anchor, facing, type));
        }

        AABB playerBox = player.getBoundingBox();
        java.util.Set<Long> cellsAll = new java.util.HashSet<>();
        for (SlideInstance slide : parts) {
            for (long c : slide.cells.keySet()) {
                BlockPos p = BlockPos.of(c);
                if (level.isOutsideBuildHeight(p)) return "message.waterslide.blocked";
                // far sections may be outside the loaded area: load those chunks now
                if (!level.isLoaded(p)) level.getChunk(p.getX() >> 4, p.getZ() >> 4);
                BlockState st = level.getBlockState(p);
                // sections overlap a little where they meet: their own cells are fine
                if (!st.canBeReplaced() && !cellsAll.contains(c)) return "message.waterslide.blocked";   // ground, walls, other builds
                if (playerBox.intersects(new AABB(p))) return "message.waterslide.blocked";
            }
            cellsAll.addAll(slide.cells.keySet());
        }

        for (SlideInstance slide : parts) place(level, slide);
        level.playSound(null, anchor, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, 0.5F, 1.4F);
        return "message.waterslide.placed";
    }

    private static void place(ServerLevel level, SlideInstance slide) {
        BlockPos anchor = slide.anchor;
        // register first, so the collision exists before any block shows up
        SlideRegistry.unregister(false, slide.dim, slide.anchor);
        SlideRegistry.add(false, slide);
        SlideData.get(level).add(anchor, slide.facing, slide.type);
        SlidePartBlock part = WaterSlideMod.PART.get();
        for (it.unimi.dsi.fastutil.longs.Long2IntMap.Entry e : slide.cells.long2IntEntrySet()) {
            BlockPos p = BlockPos.of(e.getLongKey());
            if (p.equals(anchor)) continue;
            // where two sections overlap, keep the other section's anchor block
            if (level.getBlockState(p).getBlock() instanceof SlideAnchorBlock) continue;
            level.setBlock(p, part.withShape(e.getIntValue()), Block.UPDATE_CLIENTS);
        }
        level.setBlock(anchor, WaterSlideMod.ANCHOR.get().defaultBlockState().setValue(SlideAnchorBlock.FACING, slide.facing)
                .setValue(SlideAnchorBlock.TYPE, slide.type), Block.UPDATE_CLIENTS);
    }

    /** The other sections of the long slide that pos belongs to (empty if it is not part of a chain). */
    private static java.util.List<SlideInstance> chainMates(ServerLevel level, SlideInstance slide) {
        java.util.List<SlideInstance> out = new java.util.ArrayList<>();
        int k = SlideTypes.chainIndex(slide.type);
        if (k < 0) return out;
        int[] chain = SlideTypes.chainOf(slide.type);
        int[][] off = SlideTypes.offsetsOf(slide.type);
        BlockPos first = slide.anchor.offset(rotate(slide.facing, -off[k][0], -off[k][1]));
        for (int j = 0; j < chain.length; j++) {
            if (j == k) continue;
            BlockPos a = first.offset(rotate(slide.facing, off[j][0], off[j][1]));
            for (SlideInstance s : SlideRegistry.list(false)) {
                if (SlideInstance.sameSlide(s, level.dimension(), a) && s.type == chain[j]) out.add(s);
            }
        }
        return out;
    }

    /** Removes the slide that owns pos. @return its type, or -1 if there was none */
    public static int removeAt(ServerLevel level, BlockPos pos) {
        SlideInstance slide = SlideRegistry.find(false, level.dimension(), pos);
        if (slide == null) return -1;
        // the long slide goes as a whole: all its sections
        java.util.List<SlideInstance> all = new java.util.ArrayList<>();
        all.add(slide);
        all.addAll(chainMates(level, slide));
        removing = true;
        try {
            for (SlideInstance s : all) {
                for (long c : s.cells.keySet()) {
                    BlockPos p = BlockPos.of(c);
                    if (!level.isLoaded(p)) level.getChunk(p.getX() >> 4, p.getZ() >> 4);
                    if (level.getBlockState(p).getBlock() instanceof SlidePartBlock) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        } finally {
            removing = false;
        }
        for (SlideInstance s : all) {
            SlideRegistry.unregister(false, s.dim, s.anchor);
            SlideData.get(level).remove(s.anchor);
        }
        level.playSound(null, pos, SoundEvents.ITEM_BREAK, SoundSource.BLOCKS, 1.0F, 0.8F);
        // the long slide drops (once) the item that places it
        return SlideTypes.chainIndex(slide.type) >= 0 ? SlideTypes.chainOf(slide.type)[0] : slide.type;
    }

    /** Server start / dimension load: know every saved slide's collision right away. */
    public static void loadSaved(ServerLevel level) {
        for (SlideData.Entry e : SlideData.get(level).entries()) {
            SlideRegistry.register(false, level.dimension(), e.anchor(), e.facing(), e.type());
        }
    }

    /** Local (x right-ish, z forward) offset turned to the player's facing (same rotation as SlideInstance). */
    private static BlockPos rotate(Direction facing, int x, int z) {
        return switch (facing) {
            case WEST -> new BlockPos(-z, 0, x);
            case NORTH -> new BlockPos(-x, 0, -z);
            case EAST -> new BlockPos(z, 0, -x);
            default -> new BlockPos(x, 0, z);
        };
    }
}

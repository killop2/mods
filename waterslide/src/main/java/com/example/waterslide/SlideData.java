package com.example.waterslide;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * Slides saved with each dimension, so the server knows their collision as soon as the world loads
 * (before the anchor's chunk ticks, so physics never sees a slide without its shape).
 */
public final class SlideData extends SavedData {
    private static final String NAME = "waterslide_slides";

    public record Entry(BlockPos anchor, Direction facing, int type) {
    }

    private final List<Entry> entries = new ArrayList<>();

    public static SlideData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(SlideData::new, SlideData::load), NAME);
    }

    private static SlideData load(CompoundTag tag, HolderLookup.Provider provider) {
        SlideData d = new SlideData();
        for (Tag t : tag.getList("slides", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            d.entries.add(new Entry(BlockPos.of(c.getLong("pos")), Direction.from2DDataValue(c.getInt("facing")), c.getInt("type")));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (Entry e : entries) {
            CompoundTag c = new CompoundTag();
            c.putLong("pos", e.anchor().asLong());
            c.putInt("facing", e.facing().get2DDataValue());
            c.putInt("type", e.type());
            list.add(c);
        }
        tag.put("slides", list);
        return tag;
    }

    public List<Entry> entries() {
        return entries;
    }

    public void add(BlockPos anchor, Direction facing, int type) {
        entries.removeIf(e -> e.anchor().equals(anchor));
        entries.add(new Entry(anchor.immutable(), facing, type));
        setDirty();
    }

    public void remove(BlockPos anchor) {
        if (entries.removeIf(e -> e.anchor().equals(anchor))) setDirty();
    }
}

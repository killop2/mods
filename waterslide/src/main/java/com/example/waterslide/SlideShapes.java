package com.example.waterslide;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Collision of the slide, stored per block STATE instead of per position.
 *
 * Physics engines (Sable / Sable ragdolls) cache a block's collision by its BlockState and query it at BlockPos.ZERO,
 * so a shape that depends on the position is invisible to them. The slide geometry is fixed, and so is the block grid
 * relative to the anchor, so every cell of a slide (for each of the 4 facings) has a known 4x4x4 voxel pattern.
 * All distinct patterns form a palette; each cell's block state carries its palette index.
 */
public final class SlideShapes {
    private static final int RES = SlideGeometry.RES;

    /** Palette: trough bits and support bits (64 bits = 4x4x4 voxels each). */
    private static final long[] TROUGH;
    private static final long[] SOLID;
    public static final int COUNT;
    /** Index digits: state carries three properties 0..BASE-1 (keeps the per-state neighbour tables small). */
    public static final int BASE;
    /** Values of the fourth digit (s3): palette entries added after the original slide types. */
    public static final int HIGH;
    /** Per slide type and rotation (SOUTH, WEST, NORTH, EAST): cell offset from the anchor -> palette index. */
    private static final Long2IntOpenHashMap[][] CELLS = new Long2IntOpenHashMap[SlideTypes.count()][4];

    private static final VoxelShape[] FULL;
    private static final VoxelShape[] SUPPORT;

    static {
        Object2IntOpenHashMap<Key> palette = new Object2IntOpenHashMap<>();
        List<Key> order = new ArrayList<>();
        int legacyCount = 0;
        for (int type = 0; type < SlideTypes.count(); type++)
        for (int r = 0; r < 4; r++) {
            if (type == SlideTypes.LEGACY_TYPES && r == 0) legacyCount = order.size();
            SlideGeometry g = SlideTypes.get(type);
            it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap trough = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
            it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap solid = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
            fill(g.troughVox, r, trough);
            fill(g.solidVox, r, solid);
            trough.put(0L, trough.get(0L));   // anchor cell always exists
            Long2IntOpenHashMap cells = new Long2IntOpenHashMap();
            LongArrayList keys = new LongArrayList(trough.keySet());
            for (long k : solid.keySet()) if (!trough.containsKey(k)) keys.add(k);
            keys.sort(null);
            for (long cell : keys) {
                Key key = new Key(trough.get(cell), solid.get(cell));
                int idx;
                if (palette.containsKey(key)) {
                    idx = palette.getInt(key);
                } else {
                    idx = order.size();
                    palette.put(key, idx);
                    order.add(key);
                }
                cells.put(cell, idx);
            }
            CELLS[type][r] = cells;
        }
        COUNT = order.size();
        if (legacyCount == 0) legacyCount = COUNT;
        // The digit base comes from the ORIGINAL slide types only, so every state already saved in a world keeps
        // its meaning; new types add their shapes at the end of the palette and use a fourth digit (s3) for them.
        int base = 2;
        while ((long) base * base * base < legacyCount) base++;
        BASE = base;
        int b3 = base * base * base;
        HIGH = Math.max(2, (COUNT + b3 - 1) / b3);
        TROUGH = new long[COUNT];
        SOLID = new long[COUNT];
        for (int i = 0; i < COUNT; i++) {
            TROUGH[i] = order.get(i).trough;
            SOLID[i] = order.get(i).solid;
        }
        FULL = new VoxelShape[COUNT];
        SUPPORT = new VoxelShape[COUNT];
    }

    private record Key(long trough, long solid) {
    }

    private SlideShapes() {
    }

    public static int rotationIndex(Direction facing) {
        return switch (facing) {
            case WEST -> 1;
            case NORTH -> 2;
            case EAST -> 3;
            default -> 0;
        };
    }

    /** Cells of a slide relative to its anchor, with their palette index. */
    public static Long2IntOpenHashMap cells(int type, Direction facing) {
        return CELLS[type >= 0 && type < CELLS.length ? type : 0][rotationIndex(facing)];
    }

    /** Trough + supports (ragdolls, physics, entities that are not riding, mouse picking). */
    public static VoxelShape full(int idx) {
        if (idx < 0 || idx >= COUNT) return Shapes.empty();
        VoxelShape s = FULL[idx];
        if (s == null) {
            s = build(TROUGH[idx] | SOLID[idx]);
            FULL[idx] = s;
        }
        return s;
    }

    /** Supports only (platform, poles): for entities the slide physics is carrying. */
    public static VoxelShape support(int idx) {
        if (idx < 0 || idx >= COUNT) return Shapes.empty();
        VoxelShape s = SUPPORT[idx];
        if (s == null) {
            s = build(SOLID[idx]);
            SUPPORT[idx] = s;
        }
        return s;
    }

    private static void fill(LongArrayList src, int rot, it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap dst) {
        for (int i = 0; i < src.size(); i++) {
            long k = src.getLong(i);
            // voxel centre in local space -> rotated about the anchor centre -> world-relative voxel
            double lx = (SlideGeometry.voxX(k) + 0.5) / RES - 0.5;
            double ly = (SlideGeometry.voxY(k) + 0.5) / RES;
            double lz = (SlideGeometry.voxZ(k) + 0.5) / RES - 0.5;
            double wx, wz;
            switch (rot) {
                case 1 -> { wx = -lz; wz = lx; }
                case 2 -> { wx = -lx; wz = -lz; }
                case 3 -> { wx = lz; wz = -lx; }
                default -> { wx = lx; wz = lz; }
            }
            int vx = (int) Math.floor((wx + 0.5) * RES), vy = (int) Math.floor(ly * RES), vz = (int) Math.floor((wz + 0.5) * RES);
            long cell = BlockPos.asLong(Math.floorDiv(vx, RES), Math.floorDiv(vy, RES), Math.floorDiv(vz, RES));
            int bit = ((Math.floorMod(vy, RES)) * RES + Math.floorMod(vz, RES)) * RES + Math.floorMod(vx, RES);
            dst.put(cell, dst.get(cell) | (1L << bit));
        }
    }

    private static VoxelShape build(long bits) {
        if (bits == 0L) return Shapes.empty();
        VoxelShape out = Shapes.empty();
        double v = 1.0 / RES;
        for (int y = 0; y < RES; y++) {
            for (int z = 0; z < RES; z++) {
                int x = 0;
                while (x < RES) {
                    if (!get(bits, x, y, z)) {
                        x++;
                        continue;
                    }
                    int x0 = x;
                    while (x < RES && get(bits, x, y, z)) x++;
                    out = Shapes.joinUnoptimized(out, Shapes.box(x0 * v, y * v, z * v, x * v, (y + 1) * v, (z + 1) * v), BooleanOp.OR);
                }
            }
        }
        return out.optimize();
    }

    private static boolean get(long bits, int x, int y, int z) {
        return (bits & (1L << ((y * RES + z) * RES + x))) != 0;
    }
}

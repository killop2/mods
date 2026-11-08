package com.example.waterslide;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** One placed slide: where it is, which way it faces, and which world cells it occupies. */
public final class SlideInstance {
    public final ResourceKey<Level> dim;
    public final BlockPos anchor;
    public final Direction facing;
    public final int type;
    public final SlideGeometry geo;
    public final AABB box;
    private final double ox, oy, oz;
    /** World cell -> palette index of its collision shape. */
    public final Long2IntOpenHashMap cells = new Long2IntOpenHashMap();
    private final LongOpenHashSet cellSet = new LongOpenHashSet();

    public SlideInstance(ResourceKey<Level> dim, BlockPos anchor, Direction facing, int type) {
        this.dim = dim;
        this.anchor = anchor.immutable();
        this.facing = facing.getAxis().isHorizontal() ? facing : Direction.SOUTH;
        this.type = type >= 0 && type < SlideTypes.count() ? type : 0;
        this.geo = SlideTypes.get(this.type);
        ox = anchor.getX() + 0.5D;
        oy = anchor.getY();
        oz = anchor.getZ() + 0.5D;
        int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0, x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
        for (Long2IntMap.Entry e : SlideShapes.cells(this.type, this.facing).long2IntEntrySet()) {
            long rel = e.getLongKey();
            int x = anchor.getX() + BlockPos.getX(rel), y = anchor.getY() + BlockPos.getY(rel), z = anchor.getZ() + BlockPos.getZ(rel);
            long c = BlockPos.asLong(x, y, z);
            cells.put(c, e.getIntValue());
            cellSet.add(c);
            x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
            x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
        }
        box = new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
    }

    // ------------------------------------------------------------------ transforms (rotation about the anchor centre)

    public double[] toWorld(double lx, double ly, double lz) {
        double[] r = rot(lx, lz);
        return new double[]{ox + r[0], oy + ly, oz + r[1]};
    }

    public double[] toLocal(double wx, double wy, double wz) {
        double[] r = unrot(wx - ox, wz - oz);
        return new double[]{r[0], wy - oy, r[1]};
    }

    public double[] dirToWorld(double lx, double ly, double lz) {
        double[] r = rot(lx, lz);
        return new double[]{r[0], ly, r[1]};
    }

    public double[] dirToLocal(double wx, double wy, double wz) {
        double[] r = unrot(wx, wz);
        return new double[]{r[0], wy, r[1]};
    }

    private double[] rot(double x, double z) {
        return switch (facing) {
            case WEST -> new double[]{-z, x};
            case NORTH -> new double[]{-x, -z};
            case EAST -> new double[]{z, -x};
            default -> new double[]{x, z};
        };
    }

    private double[] unrot(double x, double z) {
        return switch (facing) {
            case WEST -> new double[]{z, -x};
            case NORTH -> new double[]{-x, -z};
            case EAST -> new double[]{-z, x};
            default -> new double[]{x, z};
        };
    }

    public boolean contains(BlockPos pos) {
        return cellSet.contains(pos.asLong());
    }

    /** World block containing a local point (light probes). */
    public BlockPos worldBlock(double[] local) {
        double[] w = toWorld(local[0], local[1], local[2]);
        return BlockPos.containing(w[0], w[1], w[2]);
    }

    public static boolean sameSlide(SlideInstance a, ResourceKey<Level> dim, BlockPos anchor) {
        return a.dim.equals(dim) && a.anchor.equals(anchor);
    }
}

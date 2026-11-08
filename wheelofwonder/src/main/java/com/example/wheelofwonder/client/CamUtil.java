package com.example.wheelofwonder.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Camera helpers that only look at real world blocks. Sable adds its physics bodies (the ragdolls) to level.clip(),
 * so vanilla ray casts would bump the camera into the very NPC it is filming.
 */
final class CamUtil {
    private CamUtil() {
    }

    static boolean solid(Minecraft mc, BlockPos bp) {
        // anything with collision counts, leaves and glass too: the camera must never look at the body through them
        BlockState st = mc.level.getBlockState(bp);
        return !st.getCollisionShape(mc.level, bp).isEmpty();
    }

    /** Free distance from {@code from} towards {@code to} before the first solid world block. */
    static double freeLength(Minecraft mc, Vec3 from, Vec3 to, double startAt) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 1.0E-3D) return len;
        Vec3 n = d.scale(1.0D / len);
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (double s = startAt; s <= len; s += 0.1D) {
            Vec3 q = from.add(n.scale(s));
            bp.set(Mth.floor(q.x), Mth.floor(q.y), Mth.floor(q.z));
            if (solid(mc, bp)) return s;
        }
        return len;
    }

    /** Spot between from and to, stopping 0.4 blocks before a wall (but never closer than minDist). */
    static Vec3 clearOfBlocks(Minecraft mc, Vec3 from, Vec3 to, double minDist) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 1.0E-3D) return to;
        double free = freeLength(mc, from, to, 0.0D);
        if (free >= len) return to;
        return from.add(d.scale(Math.max(Math.min(minDist, len), free - 0.4D) / len));
    }

    /** Lifts a point out of solid blocks (a body lying on the ground may sink in a bit). */
    static Vec3 freeStart(Minecraft mc, Vec3 from) {
        for (int i = 0; i < 8; i++) {
            BlockPos bp = BlockPos.containing(from);
            if (mc.level.getBlockState(bp).getCollisionShape(mc.level, bp).isEmpty()) return from;
            from = from.add(0.0D, 0.25D, 0.0D);
        }
        return from;
    }

    /** Pulls the camera towards {@code from} if any of 8 slightly offset rays hits a world block (like vanilla). */
    static Vec3 safeCamera(Minecraft mc, Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 1.0E-3D) return to;
        double best = len;
        for (int i = 0; i < 8; i++) {
            double ox = ((i & 1) * 2 - 1) * 0.1D, oy = ((i >> 1 & 1) * 2 - 1) * 0.1D, oz = ((i >> 2 & 1) * 2 - 1) * 0.1D;
            double free = freeLength(mc, from.add(ox, oy, oz), to.add(ox, oy, oz), 0.0D);
            if (free < best) best = free;
        }
        if (best >= len) return to;
        return from.add(d.scale(Math.max(0.3D, best - 0.2D) / len));
    }

    /** No solid block within 0.2 of the point in any direction (the near plane cannot cut into the ground). */
    static boolean roomy(Minecraft mc, Vec3 q) {
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 8; i++) {
            double ox = ((i & 1) * 2 - 1) * 0.2D, oy = ((i >> 1 & 1) * 2 - 1) * 0.2D, oz = ((i >> 2 & 1) * 2 - 1) * 0.2D;
            bp.set(Mth.floor(q.x + ox), Mth.floor(q.y + oy), Mth.floor(q.z + oz));
            if (solid(mc, bp)) return false;
        }
        return true;
    }

    /** The camera spot moved towards {@code pivot} until it has room around it. */
    static Vec3 roomyAlong(Minecraft mc, Vec3 pos, Vec3 pivot) {
        for (int i = 0; i <= 20; i++) {
            Vec3 q = lerp(pos, pivot, i / 20.0D);
            if (roomy(mc, q)) return q;
        }
        return pivot;
    }

    static WheelCamera.CameraPose pose(Vec3 pos, Vec3 look, boolean detached) {
        Vec3 d = look.subtract(pos);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) (-(Mth.atan2(d.y, h) * Mth.RAD_TO_DEG));
        return new WheelCamera.CameraPose(pos, yaw, pitch, detached);
    }

    static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return a.add(b.subtract(a).scale(t));
    }

    static float ease(float x) {
        x = Mth.clamp(x, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }
}

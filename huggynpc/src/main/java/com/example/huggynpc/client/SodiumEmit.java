package com.example.huggynpc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.api.util.NormI8;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.caffeinemc.mods.sodium.api.vertex.format.common.EntityVertex;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

/**
 * Fast path for the big NPC meshes: vertices are written straight into the buffer in batches through Sodium's
 * vertex writer API (the way Sodium draws vanilla mob models; Iris understands it too), instead of one
 * VertexConsumer call chain per vertex. Only used when Sodium is installed; otherwise SkinMesh falls back.
 * This class is only loaded after the Sodium check, so it never touches missing classes.
 */
final class SodiumEmit {
    private static final int BATCH = 512;   // vertices per push (multiple of 4), fits the thread's MemoryStack

    private SodiumEmit() {
    }

    /** @return false if this buffer cannot be written directly (then the caller uses the normal path). */
    static boolean emit(VertexConsumer vc, PoseStack.Pose pose, int[] quads, float[] op, float[] on, float[] uv,
                        int light, int overlay, int argb) {
        VertexBufferWriter writer = VertexBufferWriter.tryOf(vc);
        if (writer == null) return false;
        Matrix4f m = pose.pose();
        Matrix3f n = pose.normal();
        float m00 = m.m00(), m01 = m.m01(), m02 = m.m02(), m10 = m.m10(), m11 = m.m11(), m12 = m.m12();
        float m20 = m.m20(), m21 = m.m21(), m22 = m.m22(), m30 = m.m30(), m31 = m.m31(), m32 = m.m32();
        float n00 = n.m00(), n01 = n.m01(), n02 = n.m02(), n10 = n.m10(), n11 = n.m11(), n12 = n.m12();
        float n20 = n.m20(), n21 = n.m21(), n22 = n.m22();
        int color = ColorARGB.toABGR(argb);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long buffer = stack.nmalloc(BATCH * EntityVertex.STRIDE);
            long ptr = buffer;
            int count = 0;
            for (int i = 0; i < quads.length; i++) {
                int v = quads[i], v3 = v * 3;
                float x = op[v3], y = op[v3 + 1], z = op[v3 + 2];
                float nx = on[v3], ny = on[v3 + 1], nz = on[v3 + 2];
                float px = m00 * x + m10 * y + m20 * z + m30;
                float py = m01 * x + m11 * y + m21 * z + m31;
                float pz = m02 * x + m12 * y + m22 * z + m32;
                float tx = n00 * nx + n10 * ny + n20 * nz;
                float ty = n01 * nx + n11 * ny + n21 * nz;
                float tz = n02 * nx + n12 * ny + n22 * nz;
                float len = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (len > 1.0E-6F) { tx /= len; ty /= len; tz /= len; }
                EntityVertex.write(ptr, px, py, pz, color, uv[v * 2], uv[v * 2 + 1], overlay, light, NormI8.pack(tx, ty, tz));
                ptr += EntityVertex.STRIDE;
                if (++count == BATCH) {
                    writer.push(stack, buffer, count, EntityVertex.FORMAT);
                    ptr = buffer;
                    count = 0;
                }
            }
            if (count > 0) writer.push(stack, buffer, count, EntityVertex.FORMAT);
        }
        return true;
    }
}

package com.example.huggynpc.client;

import com.example.huggynpc.HuggyNpcMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Skinned mesh + skeleton + sampled animations from assets/huggynpc/models/entity/huggy.skin
 * (written by tools/fbxconv/SkinToMc.java). Model space: blocks, +Y up, feet at 0, front towards +Z.
 * Matrices are 3x4 affine, row-major. All scratch buffers are reused: rendering is single-threaded.
 */
public final class SkinMesh {
    private static final Logger LOGGER = LogUtils.getLogger();


    /** Per joint: qx qy qz qw tx ty tz sx sy sz. */
    public static final int L = 10;

    public static final class Anim {
        public final String name;
        public final boolean loop;
        public final float duration;
        final int frames;
        final float[][] local;   // [frame][joint * L]

        Anim(String name, boolean loop, float duration, int frames, float[][] local) {
            this.name = name;
            this.loop = loop;
            this.duration = duration;
            this.frames = frames;
            this.local = local;
        }
    }

    /** Per ragdoll quad: 4 x (x, y, z, u, v) in model space (blocks) + face normal. */
    public static final int RAG_STRIDE = 23;

    /** Simplified body part for Sable: Ragdolls (see RagdollParts). Pivot = joint position in model space. */
    public record RagPart(String name, int parent, float px, float py, float pz, float[] quads, int count) {
    }

    public final java.util.List<RagPart> ragdoll = new java.util.ArrayList<>();

    private static final Map<ResourceLocation, SkinMesh> CACHE = new HashMap<>();

    final int joints;
    final int[] parent;
    final String[] names;
    final float[] root = new float[12];
    final float[] ibm;           // joints * 12
    final float[] rest;          // joints * L
    final int vertices;
    final float[] pos, nrm, uv;
    final byte[] inflCount;
    final int[] inflJoint;       // vertices * 4
    final float[] inflWeight;    // vertices * 4
    final int[] quads;           // quads * 4 vertex indices
    final Map<String, Anim> anims = new HashMap<>();
    public final int headJoint;
    /** Reduced mesh (version 5) for far NPCs and shader shadows: a subset of the vertices and its own quads; null if absent. */
    final int[] lodVerts, lodQuads;

    // scratch
    private final float[] global, skin, tmp = new float[12];
    private final float[] outPos, outNrm;
    public final float[] poseA, poseB;

    /** Loaded once per model and cached (null if the file is missing or broken; logged once). */
    public static SkinMesh get(ResourceLocation location) {
        if (CACHE.containsKey(location)) return CACHE.get(location);
        SkinMesh mesh = null;
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(location)) {
            mesh = new SkinMesh(new DataInputStream(new BufferedInputStream(new GZIPInputStream(in), 1 << 16)));
            LOGGER.info("[huggynpc] loaded {}: {} joints, {} vertices, {} quads, animations {}",
                    location, mesh.joints, mesh.vertices, mesh.quads.length / 4, mesh.anims.keySet());
        } catch (Exception e) {
            LOGGER.error("[huggynpc] cannot load {}", location, e);
        }
        CACHE.put(location, mesh);
        return mesh;
    }

    private SkinMesh(DataInputStream in) throws Exception {
        if (in.readInt() != 0x534B494E) throw new IllegalStateException("bad .skin header");
        int version = in.readInt();
        if (version < 1 || version > 7) throw new IllegalStateException("unsupported .skin version " + version);
        // version 6: like 5, with 32-bit vertex indices (models over 65535 vertices)
        // version 7: like 6 (the far model may be empty), then the "look" quads: their texture has other looks in a
        // 4 x 4 grid of the texture (moods, glowing, angry), the glowing ones lit up in the dark
        final boolean wide = version >= 6;
        joints = in.readInt();
        float[] m16 = new float[16];
        for (int i = 0; i < 16; i++) m16[i] = in.readFloat();
        System.arraycopy(m16, 0, root, 0, 12);
        parent = new int[joints];
        names = new String[joints];
        ibm = new float[joints * 12];
        rest = new float[joints * L];
        int head = -1;
        for (int j = 0; j < joints; j++) {
            names[j] = in.readUTF();
            if (head < 0 && names[j].toLowerCase(java.util.Locale.ROOT).contains("head") && !names[j].toLowerCase(java.util.Locale.ROOT).contains("end")) head = j;
            parent[j] = in.readShort();
            float tx = in.readFloat(), ty = in.readFloat(), tz = in.readFloat();
            float qx = in.readFloat(), qy = in.readFloat(), qz = in.readFloat(), qw = in.readFloat();
            float sx = in.readFloat(), sy = in.readFloat(), sz = in.readFloat();
            float[] r = {qx, qy, qz, qw, tx, ty, tz, sx, sy, sz};
            System.arraycopy(r, 0, rest, j * L, L);
            for (int i = 0; i < 16; i++) m16[i] = in.readFloat();
            System.arraycopy(m16, 0, ibm, j * 12, 12);
        }

        vertices = in.readInt();
        pos = new float[vertices * 3];
        nrm = new float[vertices * 3];
        uv = new float[vertices * 2];
        inflCount = new byte[vertices];
        inflJoint = new int[vertices * 4];
        inflWeight = new float[vertices * 4];
        for (int v = 0; v < vertices; v++) {
            for (int c = 0; c < 3; c++) pos[v * 3 + c] = in.readFloat();
            for (int c = 0; c < 3; c++) nrm[v * 3 + c] = in.readFloat();
            for (int c = 0; c < 2; c++) uv[v * 2 + c] = in.readFloat();
            int n = in.readUnsignedByte();
            inflCount[v] = (byte) n;
            for (int k = 0; k < n; k++) {
                inflJoint[v * 4 + k] = in.readUnsignedByte();
                inflWeight[v * 4 + k] = in.readFloat();
            }
        }
        int q = in.readInt();
        quads = new int[q * 4];
        for (int i = 0; i < quads.length; i++) quads[i] = wide ? in.readInt() : in.readUnsignedShort();

        int count = in.readInt();
        for (int a = 0; a < count; a++) {
            String name = in.readUTF();
            boolean loop = in.readBoolean();
            float dur = in.readFloat();
            int frames = in.readInt();
            int[] mask = new int[joints];
            for (int j = 0; j < joints; j++) mask[j] = in.readUnsignedByte();
            float[][] local = new float[frames][];
            for (int f = 0; f < frames; f++) {
                float[] fr = rest.clone();
                for (int j = 0; j < joints; j++) {
                    int o = j * L;
                    if ((mask[j] & 1) != 0) {
                        float x = in.readShort() / 32767F, y = in.readShort() / 32767F, z = in.readShort() / 32767F, w = in.readShort() / 32767F;
                        float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
                        fr[o] = x / len; fr[o + 1] = y / len; fr[o + 2] = z / len; fr[o + 3] = w / len;
                    }
                    if ((mask[j] & 2) != 0) for (int c = 0; c < 3; c++) fr[o + 4 + c] = in.readFloat();
                    if ((mask[j] & 4) != 0) for (int c = 0; c < 3; c++) fr[o + 7 + c] = in.readFloat();
                }
                local[f] = fr;
            }
            anims.put(name, new Anim(name, loop, dur, frames, local));
        }

        int[] jointSegment = null;
        if (version >= 3) {
            jointSegment = new int[joints];
            for (int j = 0; j < joints; j++) jointSegment[j] = in.readUnsignedByte();
        }
        if (version >= 2) {
            int parts = in.readInt();
            for (int p = 0; p < parts; p++) {
                String name = in.readUTF();
                int parentPart = version >= 3 ? in.readByte() : -1;
                float px = in.readFloat(), py = in.readFloat(), pz = in.readFloat();
                int qn = in.readInt();
                float[] data = new float[qn * RAG_STRIDE];
                for (int i = 0; i < data.length; i++) data[i] = in.readFloat();
                ragdoll.add(new RagPart(name, parentPart, px, py, pz, data, qn));
            }
        }
        // version 4: the head bone (for head tracking) is stored; older files: found by name above
        if (version >= 4) {
            int h = in.readInt();
            if (h >= 0 && h < joints) head = h;
        }
        headJoint = head;
        if (version >= 5) {
            int[] lv = new int[in.readInt()];
            for (int i = 0; i < lv.length; i++) lv[i] = wide ? in.readInt() : in.readUnsignedShort();
            int[] lq = new int[in.readInt() * 4];
            for (int i = 0; i < lq.length; i++) lq[i] = wide ? in.readInt() : in.readUnsignedShort();
            lodVerts = lq.length > 0 ? lv : null;
            lodQuads = lq.length > 0 ? lq : null;
        } else {
            lodVerts = null;
            lodQuads = null;
        }
        if (version >= 7) {
            int n = in.readInt();
            java.util.Set<Integer> look = new java.util.HashSet<>();
            int[] lq = new int[n * 4];
            boolean[] glow = new boolean[n];
            for (int i = 0; i < n; i++) {
                int qi = in.readInt();
                glow[i] = in.readBoolean();
                look.add(qi);
                System.arraycopy(quads, qi * 4, lq, i * 4, 4);
            }
            java.util.List<Integer> rest = new java.util.ArrayList<>();
            for (int qi = 0; qi < quads.length / 4; qi++) if (!look.contains(qi)) for (int c = 0; c < 4; c++) rest.add(quads[qi * 4 + c]);
            lookQuads = lq;
            lookGlow = glow;
            bodyQuads = rest.stream().mapToInt(Integer::intValue).toArray();
        }

        // ragdoll part of every quad (by the strongest bone of its first vertex), same table as the converter
        quadSegment = new int[quads.length / 4];
        for (int qi = 0; qi < quadSegment.length; qi++) {
            int v = quads[qi * 4], best = 0;
            for (int k = 1; k < inflCount[v]; k++) if (inflWeight[v * 4 + k] > inflWeight[v * 4 + best]) best = k;
            int j = inflJoint[v * 4 + best];
            quadSegment[qi] = jointSegment != null ? jointSegment[j] : segmentOf(names[j]);
        }
        // ragdoll part weights of every vertex (its bone weights summed per part), for the seamless ragdoll
        vPart = new int[vertices * 4];
        vPartW = new float[vertices * 4];
        for (int v = 0; v < vertices; v++) {
            int n = 0;
            for (int k = 0; k < inflCount[v]; k++) {
                int j = inflJoint[v * 4 + k];
                int p = jointSegment != null ? jointSegment[j] : segmentOf(names[j]);
                int slot = -1;
                for (int s = 0; s < n; s++) if (vPart[v * 4 + s] == p) slot = s;
                if (slot < 0) { slot = n++; vPart[v * 4 + slot] = p; }
                vPartW[v * 4 + slot] += inflWeight[v * 4 + k];
            }
            for (int s = n; s < 4; s++) vPart[v * 4 + s] = -1;
        }

        buildUnique();
        global = new float[joints * 12];
        skin = new float[joints * 12];
        outPos = new float[vertices * 3];
        outNrm = new float[vertices * 3];
        poseA = new float[joints * L];
        poseB = new float[joints * L];
    }

    final int[] quadSegment;
    private final int[] vPart;
    private final float[] vPartW;
    private float[] restPos, restNrm;

    private void ensureRest() {
        if (restPos != null) return;
        sample(anim("idle"), 0, poseB);
        pose(poseB, 0F, 0F);
        restPos = outPos.clone();
        restNrm = outNrm.clone();
    }

    /**
     * The whole ragdoll as one seamless mesh: every vertex is placed by the transforms of the ragdoll parts it
     * belongs to (blended by its bone weights, like normal skinning), so the joints between parts never tear open.
     * Matrices map model space (blocks) to the render space of this frame; output vertices are already transformed.
     */
    public void emitMerged(org.joml.Matrix4f[] mats, org.joml.Matrix3f[] nmats, int[] lights, VertexConsumer vc, int overlay, int color) {
        ensureRest();
        final float[] op = restPos, on = restNrm, t = uv;
        final int[] q = quads;
        org.joml.Vector3f a = new org.joml.Vector3f(), b = new org.joml.Vector3f();
        for (int i = 0; i < q.length; i++) {
            int v = q[i], v3 = v * 3, v4 = v * 4;
            float x = 0, y = 0, z = 0, nx = 0, ny = 0, nz = 0, best = -1;
            int light = 0;
            for (int k = 0; k < 4; k++) {
                int p = vPart[v4 + k];
                if (p < 0) break;
                if (p >= mats.length || mats[p] == null) continue;
                float w = vPartW[v4 + k];
                mats[p].transformPosition(op[v3], op[v3 + 1], op[v3 + 2], a);
                nmats[p].transform(on[v3], on[v3 + 1], on[v3 + 2], b);
                x += w * a.x; y += w * a.y; z += w * a.z;
                nx += w * b.x; ny += w * b.y; nz += w * b.z;
                if (w > best) { best = w; light = lights[p]; }
            }
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1.0E-6F) { nx = 0; ny = 1; nz = 0; len = 1; }
            vc.addVertex(x, y, z)
                    .setColor(color)
                    .setUv(t[v * 2], t[v * 2 + 1])
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(nx / len, ny / len, nz / len);
        }
    }

    static int segmentOf(String n) {
        if (n.startsWith("head") || n.startsWith("mouth") || n.contains("eye")) return 1;
        if (n.startsWith("lf_shoulder") || n.startsWith("lf_arm")) return 2;
        if (n.startsWith("rt_shoulder") || n.startsWith("rt_arm")) return 3;
        if (n.startsWith("lf_leg") || n.startsWith("lf_foot")) return 4;
        if (n.startsWith("rt_leg") || n.startsWith("rt_foot")) return 5;
        return 0;
    }

    /**
     * Emits only the quads of the given ragdoll parts, in the idle pose the ragdoll was built from
     * (Sable draws a ragdoll part by part). The rest pose is skinned once and cached.
     */
    public void emitParts(PoseStack.Pose pose, VertexConsumer vc, int light, int overlay, int color, boolean[] keep) {
        ensureRest();
        final float[] op = restPos, on = restNrm, t = uv;
        final int[] q = quads;
        for (int qi = 0; qi < quadSegment.length; qi++) {
            int s = quadSegment[qi];
            if (s >= keep.length || !keep[s]) continue;
            for (int c = 0; c < 4; c++) {
                int v = q[qi * 4 + c], v3 = v * 3;
                vc.addVertex(pose, op[v3], op[v3 + 1], op[v3 + 2])
                        .setColor(color)
                        .setUv(t[v * 2], t[v * 2 + 1])
                        .setOverlay(overlay)
                        .setLight(light)
                        .setNormal(pose, on[v3], on[v3 + 1], on[v3 + 2]);
            }
        }
    }

    public Anim anim(String name) {
        Anim a = anims.get(name);
        return a != null ? a : anims.get("idle");
    }

    /** Local joint transforms of animation {@code a} at time {@code t} seconds (looped or clamped). */
    public void sample(Anim a, double t, float[] out) {
        double d = a.duration;
        double tt = a.loop ? ((t % d) + d) % d : Math.max(0, Math.min(d, t));
        double f = d <= 0 ? 0 : tt / d * (a.frames - 1);
        int i0 = Math.min(a.frames - 1, (int) Math.floor(f));
        int i1 = a.loop ? (i0 + 1) % a.frames : Math.min(a.frames - 1, i0 + 1);
        float w = (float) (f - i0);
        mix(a.local[i0], a.local[i1], w, out);
    }

    /** out = a*(1-w) + b*w per joint (normalised lerp for rotations). out may alias a or b. */
    public void mix(float[] a, float[] b, float w, float[] out) {
        float iw = 1F - w;
        for (int j = 0; j < joints; j++) {
            int o = j * L;
            float dot = a[o] * b[o] + a[o + 1] * b[o + 1] + a[o + 2] * b[o + 2] + a[o + 3] * b[o + 3];
            float bw = dot < 0 ? -w : w;
            float x = a[o] * iw + b[o] * bw, y = a[o + 1] * iw + b[o + 1] * bw;
            float z = a[o + 2] * iw + b[o + 2] * bw, q = a[o + 3] * iw + b[o + 3] * bw;
            float len = (float) Math.sqrt(x * x + y * y + z * z + q * q);
            if (len < 1.0E-6F) len = 1F;
            out[o] = x / len; out[o + 1] = y / len; out[o + 2] = z / len; out[o + 3] = q / len;
            for (int c = 4; c < L; c++) out[o + c] = a[o + c] * iw + b[o + c] * w;
        }
    }

    /**
     * Builds joint matrices from local transforms and skins every vertex.
     * headYaw/headPitch (radians) turn the head joint around its own origin in model space.
     */
    public void pose(float[] local, float headYaw, float headPitch) {
        pose(local, headYaw, headPitch, outPos, outNrm);
    }

    /** Sodium installed: the fast vertex path (SodiumEmit) may be used. Checked here so SodiumEmit is never loaded without it. */
    private static final boolean SODIUM = net.neoforged.fml.ModList.get().isLoaded("sodium") && !Boolean.getBoolean("huggynpc.nofast");

    /** Vertex count (for per-entity output buffers). */
    public int vertexCount() {
        return vertices;
    }

    public boolean hasLod() {
        return lodQuads != null;
    }

    /** Same as pose(), but the skinned vertices go into the given arrays (vertices * 3 each). */
    public void pose(float[] local, float headYaw, float headPitch, float[] outP, float[] outN) {
        pose(local, headYaw, headPitch, outP, outN, false);
    }

    /** lod: only the vertices of the reduced mesh are skinned (the others keep old values). */
    public void pose(float[] local, float headYaw, float headPitch, float[] outP, float[] outN, boolean lod) {
        float cy = (float) Math.cos(headYaw), sy = (float) Math.sin(headYaw);
        float cp = (float) Math.cos(headPitch), sp = (float) Math.sin(headPitch);
        // R = RotY(yaw) * RotX(pitch)
        float r00 = cy, r01 = sy * sp, r02 = sy * cp;
        float r10 = 0, r11 = cp, r12 = -sp;
        float r20 = -sy, r21 = cy * sp, r22 = cy * cp;
        boolean turnHead = headJoint >= 0 && (headYaw != 0F || headPitch != 0F);

        for (int j = 0; j < joints; j++) {
            trs(local, j * L, tmp);
            int p = parent[j];
            mul(p < 0 ? root : global, p < 0 ? 0 : p * 12, tmp, 0, global, j * 12);
            if (turnHead && j == headJoint) {
                int o = j * 12;
                for (int c = 0; c < 3; c++) {
                    float a = global[o + c], b = global[o + 4 + c], d = global[o + 8 + c];
                    global[o + c] = r00 * a + r01 * b + r02 * d;
                    global[o + 4 + c] = r10 * a + r11 * b + r12 * d;
                    global[o + 8 + c] = r20 * a + r21 * b + r22 * d;
                }
            }
            mul(global, j * 12, ibm, j * 12, skin, j * 12);
        }

        final float[] s = skin, op = outP, on = outN;
        final boolean useLod = lod && lodVerts != null;
        // every distinct (point, bones) and (normal, bones) is skinned once, then copied to the vertices using it
        final int[] ups = useLod ? lodUPos : null, uns = useLod ? lodUNrm : null;
        final int pc = ups != null ? ups.length : uPosCount, nc = uns != null ? uns.length : uNrmCount;
        final float[] skP = sPos, skN = sNrm;
        for (int i = 0; i < pc; i++) {
            int u = ups != null ? ups[i] : i, u3 = u * 3, u4 = u * 4;
            float px = uPos[u3], py = uPos[u3 + 1], pz = uPos[u3 + 2];
            float x = 0, y = 0, z = 0;
            for (int k = 0, n = uPC[u]; k < n; k++) {
                float w = uPW[u4 + k];
                int o = uPJ[u4 + k] * 12;
                x += w * (s[o] * px + s[o + 1] * py + s[o + 2] * pz + s[o + 3]);
                y += w * (s[o + 4] * px + s[o + 5] * py + s[o + 6] * pz + s[o + 7]);
                z += w * (s[o + 8] * px + s[o + 9] * py + s[o + 10] * pz + s[o + 11]);
            }
            skP[u3] = x; skP[u3 + 1] = y; skP[u3 + 2] = z;
        }
        for (int i = 0; i < nc; i++) {
            int u = uns != null ? uns[i] : i, u3 = u * 3, u4 = u * 4;
            float nx = uNrm[u3], ny = uNrm[u3 + 1], nz = uNrm[u3 + 2];
            float ax = 0, ay = 0, az = 0;
            for (int k = 0, n = uNC[u]; k < n; k++) {
                float w = uNW[u4 + k];
                int o = uNJ[u4 + k] * 12;
                ax += w * (s[o] * nx + s[o + 1] * ny + s[o + 2] * nz);
                ay += w * (s[o + 4] * nx + s[o + 5] * ny + s[o + 6] * nz);
                az += w * (s[o + 8] * nx + s[o + 9] * ny + s[o + 10] * nz);
            }
            float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            if (len < 1.0E-8F) { ax = 0; ay = 1; az = 0; len = 1; }
            skN[u3] = ax / len; skN[u3 + 1] = ay / len; skN[u3 + 2] = az / len;
        }
        final int[] subset = useLod ? lodVerts : null;
        final int count = subset != null ? subset.length : vertices;
        final int[] vp = vPos, vn = vNrm;
        for (int i = 0; i < count; i++) {
            int v = subset != null ? subset[i] : i, v3 = v * 3;
            int a = vp[v] * 3, b = vn[v] * 3;
            op[v3] = skP[a]; op[v3 + 1] = skP[a + 1]; op[v3 + 2] = skP[a + 2];
            on[v3] = skN[b]; on[v3 + 1] = skN[b + 1]; on[v3 + 2] = skN[b + 2];
        }
    }

    // ------------------------------------------------------------------ shared skinning work

    private int[] vPos, vNrm, lodUPos, lodUNrm;
    private float[] uPos, uNrm, uPW, uNW, sPos, sNrm;
    private byte[] uPC, uNC;
    private int[] uPJ, uNJ;
    private int uPosCount, uNrmCount;

    /**
     * Finds the distinct (position, bone weights) and (normal, bone weights) of the vertices. Models built from
     * cubes repeat them a lot (every cube corner is in several faces, all four corners of a face share the normal),
     * so skinning those once instead of per vertex saves most of the work.
     */
    private void buildUnique() {
        vPos = new int[vertices];
        vNrm = new int[vertices];
        java.util.Map<String, Integer> pk = new java.util.HashMap<>(), nk = new java.util.HashMap<>();
        float[] up = new float[vertices * 3], un = new float[vertices * 3], pw = new float[vertices * 4], nw = new float[vertices * 4];
        int[] pj = new int[vertices * 4], nj = new int[vertices * 4];
        byte[] pc = new byte[vertices], nc = new byte[vertices];
        StringBuilder sb = new StringBuilder();
        for (int v = 0; v < vertices; v++) {
            sb.setLength(0);
            int n = inflCount[v];
            for (int k = 0; k < n; k++) sb.append(inflJoint[v * 4 + k]).append(':').append(Float.floatToIntBits(inflWeight[v * 4 + k])).append(',');
            String infl = sb.toString();
            String kp = Float.floatToIntBits(pos[v * 3]) + "," + Float.floatToIntBits(pos[v * 3 + 1]) + "," + Float.floatToIntBits(pos[v * 3 + 2]) + "|" + infl;
            String kn = Float.floatToIntBits(nrm[v * 3]) + "," + Float.floatToIntBits(nrm[v * 3 + 1]) + "," + Float.floatToIntBits(nrm[v * 3 + 2]) + "|" + infl;
            Integer ip = pk.get(kp);
            if (ip == null) {
                ip = pk.size();
                pk.put(kp, ip);
                System.arraycopy(pos, v * 3, up, ip * 3, 3);
                pc[ip] = (byte) n;
                System.arraycopy(inflJoint, v * 4, pj, ip * 4, 4);
                System.arraycopy(inflWeight, v * 4, pw, ip * 4, 4);
            }
            Integer in = nk.get(kn);
            if (in == null) {
                in = nk.size();
                nk.put(kn, in);
                System.arraycopy(nrm, v * 3, un, in * 3, 3);
                nc[in] = (byte) n;
                System.arraycopy(inflJoint, v * 4, nj, in * 4, 4);
                System.arraycopy(inflWeight, v * 4, nw, in * 4, 4);
            }
            vPos[v] = ip;
            vNrm[v] = in;
        }
        uPosCount = pk.size();
        uNrmCount = nk.size();
        uPos = java.util.Arrays.copyOf(up, uPosCount * 3);
        uNrm = java.util.Arrays.copyOf(un, uNrmCount * 3);
        uPC = java.util.Arrays.copyOf(pc, uPosCount);
        uNC = java.util.Arrays.copyOf(nc, uNrmCount);
        uPJ = java.util.Arrays.copyOf(pj, uPosCount * 4);
        uNJ = java.util.Arrays.copyOf(nj, uNrmCount * 4);
        uPW = java.util.Arrays.copyOf(pw, uPosCount * 4);
        uNW = java.util.Arrays.copyOf(nw, uNrmCount * 4);
        sPos = new float[uPosCount * 3];
        sNrm = new float[uNrmCount * 3];
        if (lodVerts != null) {
            java.util.TreeSet<Integer> a = new java.util.TreeSet<>(), b = new java.util.TreeSet<>();
            for (int v : lodVerts) { a.add(vPos[v]); b.add(vNrm[v]); }
            lodUPos = a.stream().mapToInt(Integer::intValue).toArray();
            lodUNrm = b.stream().mapToInt(Integer::intValue).toArray();
        }
        LOGGER.info("[huggynpc] skinning shared: {} vertices -> {} points, {} normals", vertices, uPosCount, uNrmCount);
    }

    /** Emits the posed mesh; {@code pose} must already map model space (blocks, +Z front) to the entity. */
    public void emit(PoseStack.Pose pose, VertexConsumer vc, int light, int overlay, int color) {
        emit(pose, vc, light, overlay, color, outPos, outNrm);
    }

    /** Emits vertices skinned earlier into the given arrays (see pose(..., outP, outN)). */
    public void emit(PoseStack.Pose pose, VertexConsumer vc, int light, int overlay, int color, float[] op, float[] on) {
        emit(pose, vc, light, overlay, color, op, on, false);
    }

    /** lod: draws the reduced mesh (its vertices must have been skinned with pose(..., lod)). */
    public void emit(PoseStack.Pose pose, VertexConsumer vc, int light, int overlay, int color, float[] op, float[] on, boolean lod) {
        final int[] q = lod && lodQuads != null ? lodQuads : (bodyQuads != null ? bodyQuads : quads);
        if (SODIUM && SodiumEmit.emit(vc, pose, q, op, on, uv, light, overlay, color)) return;
        final float[] t = uv;
        for (int i = 0; i < q.length; i++) {
            int v = q[i], v3 = v * 3;
            vc.addVertex(pose, op[v3], op[v3 + 1], op[v3 + 2])
                    .setColor(color)
                    .setUv(t[v * 2], t[v * 2 + 1])
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(pose, on[v3], on[v3 + 1], on[v3 + 2]);
        }
    }

    // ------------------------------------------------------------------ without the head (Verity sits there)

    private int headSeg = -2;
    private int[] headlessQuads, headVerts;

    /** Ragdoll part that is the head (by name), or -1 if the model has none. */
    public int headSegment() {
        if (headSeg == -2) {
            headSeg = -1;
            for (int i = 0; i < ragdoll.size(); i++) {
                if (ragdoll.get(i).name().toLowerCase(java.util.Locale.ROOT).contains("head")) {
                    headSeg = i;
                    break;
                }
            }
            if (headSeg >= 0) {
                java.util.List<Integer> keep = new java.util.ArrayList<>();
                java.util.Set<Integer> hv = new java.util.TreeSet<>();
                for (int qi = 0; qi < quadSegment.length; qi++) {
                    boolean head = quadSegment[qi] == headSeg;
                    for (int c = 0; c < 4; c++) {
                        if (head) hv.add(quads[qi * 4 + c]);
                        else keep.add(quads[qi * 4 + c]);
                    }
                }
                headlessQuads = keep.stream().mapToInt(Integer::intValue).toArray();
                headVerts = hv.stream().mapToInt(Integer::intValue).toArray();
                if (headVerts.length == 0) headSeg = -1;
            }
        }
        return headSeg;
    }

    /**
     * Emits the posed mesh (full detail) without the head's quads. outBox gets the head's box in model space:
     * centre x, y, z and its largest size. @return false if the model has no head part (nothing drawn).
     */
    public boolean emitHeadless(PoseStack.Pose pose, VertexConsumer vc, int light, int overlay, int color, float[] op, float[] on, float[] outBox) {
        if (headSegment() < 0) return false;
        final int[] q = headlessQuads;
        if (!(SODIUM && SodiumEmit.emit(vc, pose, q, op, on, uv, light, overlay, color))) {
            final float[] t = uv;
            for (int i = 0; i < q.length; i++) {
                int v = q[i], v3 = v * 3;
                vc.addVertex(pose, op[v3], op[v3 + 1], op[v3 + 2]).setColor(color).setUv(t[v * 2], t[v * 2 + 1])
                        .setOverlay(overlay).setLight(light).setNormal(pose, on[v3], on[v3 + 1], on[v3 + 2]);
            }
        }
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        for (int v : headVerts) {
            float x = op[v * 3], y = op[v * 3 + 1], z = op[v * 3 + 2];
            x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
            x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
        }
        outBox[0] = (x0 + x1) * 0.5F;
        outBox[1] = (y0 + y1) * 0.5F;
        outBox[2] = (z0 + z1) * 0.5F;
        outBox[3] = Math.max(x1 - x0, Math.max(y1 - y0, z1 - z0));
        return true;
    }

    // ------------------------------------------------------------------ glowing eyes (the doll)

    private int[] eyeQuads, bodyQuads;
    private float eyeDv;

    /**
     * Splits off the quads textured from the eye rectangle (u0..u1, v0..v1 of the texture, 0..1): they are drawn by
     * emitEyes, which can move them to the red-eye picture {@code dv} lower and light them up.
     */
    public void enableEyeSwap(float u0, float v0, float u1, float v1, float dv) {
        if (this.eyeQuads != null) return;
        java.util.List<Integer> eyes = new java.util.ArrayList<>(), body = new java.util.ArrayList<>();
        for (int qi = 0; qi < quads.length / 4; qi++) {
            boolean inside = true;
            for (int c = 0; c < 4; c++) {
                int v = quads[qi * 4 + c];
                float u = uv[v * 2], w = uv[v * 2 + 1];
                if (u < u0 || u > u1 || w < v0 || w > v1) inside = false;
            }
            for (int c = 0; c < 4; c++) (inside ? eyes : body).add(quads[qi * 4 + c]);
        }
        if (eyes.isEmpty()) return;
        this.eyeQuads = eyes.stream().mapToInt(Integer::intValue).toArray();
        this.bodyQuads = body.stream().mapToInt(Integer::intValue).toArray();
        this.eyeDv = dv;
    }

    /** Draws the eye quads: glow 0 = normal eyes, above 0 = the red ones, lit up more and more (to full bright at 1). */
    public void emitEyes(PoseStack.Pose pose, VertexConsumer vc, int light, int overlay, int color, float[] op, float[] on, float glow) {
        if (eyeQuads == null) return;
        float dv = glow > 0F ? eyeDv : 0F;
        if (glow > 0F) {
            int block = net.minecraft.client.renderer.LightTexture.block(light), sky = net.minecraft.client.renderer.LightTexture.sky(light);
            light = net.minecraft.client.renderer.LightTexture.pack(Math.round(block + (15 - block) * glow), Math.round(sky + (15 - sky) * glow));
        }
        final int[] q = eyeQuads;
        for (int i = 0; i < q.length; i++) {
            int v = q[i], v3 = v * 3;
            vc.addVertex(pose, op[v3], op[v3 + 1], op[v3 + 2])
                    .setColor(color)
                    .setUv(uv[v * 2], uv[v * 2 + 1] + dv)
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(pose, on[v3], on[v3 + 1], on[v3 + 2]);
        }
    }

    private int[] lookQuads;
    private boolean[] lookGlow;

    public boolean hasLooks() {
        return lookQuads != null;
    }

    public boolean hasAnim(String name) {
        return anims.containsKey(name);
    }

    /**
     * Draws the look quads in look {@code slot} of the 4 x 4 texture grid; in the dark (lightUp > 0) the glowing
     * ones are lit up.
     */
    public void emitLooks(PoseStack.Pose pose, VertexConsumer vc, int light, int overlay, int color, float[] op, float[] on, int slot, float lightUp) {
        if (lookQuads == null) return;
        float du = (slot % 4) * 0.25F, dv = (slot / 4) * 0.25F;
        int lit = light;
        if (lightUp > 0F) {
            int block = net.minecraft.client.renderer.LightTexture.block(light), sky = net.minecraft.client.renderer.LightTexture.sky(light);
            lit = net.minecraft.client.renderer.LightTexture.pack(Math.round(block + (15 - block) * lightUp), Math.round(sky + (15 - sky) * lightUp));
        }
        final int[] q = lookQuads;
        for (int i = 0; i < q.length; i++) {
            int v = q[i], v3 = v * 3;
            vc.addVertex(pose, op[v3], op[v3 + 1], op[v3 + 2])
                    .setColor(color)
                    .setUv(uv[v * 2] + du, uv[v * 2 + 1] + dv)
                    .setOverlay(overlay)
                    .setLight(lookGlow[i / 4] ? lit : light)
                    .setNormal(pose, on[v3], on[v3 + 1], on[v3 + 2]);
        }
    }

    /** Draws the swapped quads moved dv down the texture (another look), lit up by lightUp (0..1, 1 = full bright). */
    public void emitSwap(PoseStack.Pose pose, VertexConsumer vc, int light, int overlay, int color, float[] op, float[] on, float dv, float lightUp) {
        if (eyeQuads == null) return;
        if (lightUp > 0F) {
            int block = net.minecraft.client.renderer.LightTexture.block(light), sky = net.minecraft.client.renderer.LightTexture.sky(light);
            light = net.minecraft.client.renderer.LightTexture.pack(Math.round(block + (15 - block) * lightUp), Math.round(sky + (15 - sky) * lightUp));
        }
        final int[] q = eyeQuads;
        for (int i = 0; i < q.length; i++) {
            int v = q[i], v3 = v * 3;
            vc.addVertex(pose, op[v3], op[v3 + 1], op[v3 + 2])
                    .setColor(color)
                    .setUv(uv[v * 2], uv[v * 2 + 1] + dv)
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(pose, on[v3], on[v3 + 1], on[v3 + 2]);
        }
    }

    // ------------------------------------------------------------------ math (3x4 affine, row-major)
    private static void trs(float[] l, int o, float[] m) {
        float x = l[o], y = l[o + 1], z = l[o + 2], w = l[o + 3];
        float sx = l[o + 7], sy = l[o + 8], sz = l[o + 9];
        m[0] = (1 - 2 * (y * y + z * z)) * sx; m[1] = 2 * (x * y - z * w) * sy;       m[2] = 2 * (x * z + y * w) * sz;       m[3] = l[o + 4];
        m[4] = 2 * (x * y + z * w) * sx;       m[5] = (1 - 2 * (x * x + z * z)) * sy; m[6] = 2 * (y * z - x * w) * sz;       m[7] = l[o + 5];
        m[8] = 2 * (x * z - y * w) * sx;       m[9] = 2 * (y * z + x * w) * sy;       m[10] = (1 - 2 * (x * x + y * y)) * sz; m[11] = l[o + 6];
    }

    private static void mul(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
        for (int r = 0; r < 3; r++) {
            float a0 = a[ao + r * 4], a1 = a[ao + r * 4 + 1], a2 = a[ao + r * 4 + 2], a3 = a[ao + r * 4 + 3];
            out[oo + r * 4] = a0 * b[bo] + a1 * b[bo + 4] + a2 * b[bo + 8];
            out[oo + r * 4 + 1] = a0 * b[bo + 1] + a1 * b[bo + 5] + a2 * b[bo + 9];
            out[oo + r * 4 + 2] = a0 * b[bo + 2] + a1 * b[bo + 6] + a2 * b[bo + 10];
            out[oo + r * 4 + 3] = a0 * b[bo + 3] + a1 * b[bo + 7] + a2 * b[bo + 11] + a3;
        }
    }
}

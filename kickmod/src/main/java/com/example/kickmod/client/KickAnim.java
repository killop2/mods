package com.example.kickmod.client;

import com.example.kickmod.KickMod;
import org.joml.Quaternionf;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One pose track for the player model (.anim, made by tools/fbxconv: FbxAnimToMc from FBX, AnimGen by hand).
 * Each frame: waist position + torso rotation (entity-local render space) and ModelPart angles
 * for head (x,y,z), arms and legs (x,z, plus y in format v2).
 */
public final class KickAnim {
    public static final int ROOT_X = 0, ROOT_Y = 1, ROOT_Z = 2, QX = 3, QY = 4, QZ = 5, QW = 6;
    public static final int HEAD_X = 7, HEAD_Y = 8, HEAD_Z = 9;
    public static final int RARM_X = 10, RARM_Z = 11, LARM_X = 12, LARM_Z = 13, RLEG_X = 14, RLEG_Z = 15, LLEG_X = 16, LLEG_Z = 17;
    public static final int RARM_Y = 18, LARM_Y = 19, RLEG_Y = 20, LLEG_Y = 21;
    public static final int CHANNELS = 22;

    private static final Map<String, KickAnim> CACHE = new HashMap<>();

    public final float fps;
    public final float[][] frames;
    public final int impactFrame;

    private KickAnim(float fps, float[][] frames, int impactFrame) {
        this.fps = fps;
        this.frames = frames;
        this.impactFrame = impactFrame;
    }

    /** config/kickmod/anims/&lt;name&gt;.anim if it exists (your own animations), otherwise the one inside the mod. */
    public static synchronized KickAnim get(String name) {
        return CACHE.computeIfAbsent(name, KickAnim::load);
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    /** Animation length in game ticks (at normal speed). */
    public float lengthTicks() {
        return (frames.length - 1) / fps * 20.0F;
    }

    public float impactTicks() {
        return impactFrame / fps * 20.0F;
    }

    /** Pose at time t (ticks), linear interpolation, torso quaternion via nlerp. */
    public float[] sample(float ticks, float[] out) {
        float f = Math.max(0.0F, ticks / 20.0F * fps);
        int i = Math.min((int) f, frames.length - 1);
        int j = Math.min(i + 1, frames.length - 1);
        float a = Math.min(1.0F, f - i);
        float[] p = frames[i], q = frames[j];
        for (int c = 0; c < CHANNELS; c++) out[c] = p[c] + (q[c] - p[c]) * a;
        // angles near +-pi can wrap between frames: take the short way
        for (int c = HEAD_X; c < CHANNELS; c++) {
            float d = q[c] - p[c];
            if (d > Math.PI || d < -Math.PI) {
                d = (float) Math.IEEEremainder(d, Math.PI * 2.0D);
                out[c] = p[c] + d * a;
            }
        }
        float dot = p[QX] * q[QX] + p[QY] * q[QY] + p[QZ] * q[QZ] + p[QW] * q[QW];
        float s = dot < 0 ? -1.0F : 1.0F;
        Quaternionf r = new Quaternionf(p[QX] + (s * q[QX] - p[QX]) * a, p[QY] + (s * q[QY] - p[QY]) * a,
                p[QZ] + (s * q[QZ] - p[QZ]) * a, p[QW] + (s * q[QW] - p[QW]) * a).normalize();
        out[QX] = r.x;
        out[QY] = r.y;
        out[QZ] = r.z;
        out[QW] = r.w;
        return out;
    }

    private static KickAnim load(String name) {
        Path own = AnimConfig.dir().resolve("anims").resolve(name + ".anim");
        try {
            if (Files.isRegularFile(own)) {
                try (InputStream in = Files.newInputStream(own)) {
                    return parse(in);
                }
            }
            String path = "/assets/" + KickMod.MOD_ID + "/anim/" + name + ".anim";
            try (InputStream in = KickAnim.class.getResourceAsStream(path)) {
                if (in == null) return null;
                return parse(in);
            }
        } catch (Exception e) {
            com.mojang.logging.LogUtils.getLogger().error("[kickmod] cannot load animation {}", name, e);
            return null;
        }
    }

    private static KickAnim parse(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String magic = r.readLine();
        if (magic == null || !magic.startsWith("KICKANIM")) throw new IllegalStateException("bad header");
        String[] h = r.readLine().trim().split("\\s+");
        float fps = Float.parseFloat(h[0]);
        int impact = Integer.parseInt(h[2]);
        List<float[]> list = new ArrayList<>();
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] t = line.split("\\s+");
            float[] fr = new float[CHANNELS]; // v1 files have 18 channels: limb y rotations stay 0
            for (int i = 0; i < Math.min(CHANNELS, t.length); i++) fr[i] = Float.parseFloat(t[i]);
            list.add(fr);
        }
        if (list.isEmpty()) throw new IllegalStateException("no frames");
        return new KickAnim(fps, list.toArray(new float[0][]), Math.min(impact, list.size() - 1));
    }
}

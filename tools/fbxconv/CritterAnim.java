import com.google.gson.*;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Adds hand-made animations to a skinned glTF that has none (a humanoid rig with JNT_* bone names):
 * idle, walk, run, attack, jump, crouch, crouchwalk, sit, dance. Rotations are written in the character's own
 * frame (left / up / forward, found from the rest pose: up = +Y, forward = from the head to the eyes) and turned
 * into each bone's local rotation, so the bones' own axes do not matter:
 *   local' = Gparent^-1 * D * Gparent * local     (D = rotation about the joint, in model axes at rest)
 * The arms are first lowered from the rest pose (T/A pose) to hang beside the body.
 *
 * Usage: java -cp gson.jar CritterAnim.java in.gltf out.gltf
 */
public class CritterAnim {
    static JsonObject g;
    static ByteArrayOutputStream bin = new ByteArrayOutputStream();
    static JsonArray nodes;
    static int[] parent;
    static double[][] gRot;      // global rest rotation per node (quaternion x y z w)
    static double[][] gPos;      // global rest position per node
    static double[] left, up, fwd;

    public static void main(String[] a) throws Exception {
        Path src = Paths.get(a[0]), dst = Paths.get(a[1]);
        g = JsonParser.parseString(Files.readString(src)).getAsJsonObject();
        String uri = g.getAsJsonArray("buffers").get(0).getAsJsonObject().get("uri").getAsString();
        bin.write(Files.readAllBytes(src.resolveSibling(uri)));
        nodes = g.getAsJsonArray("nodes");
        // the characters' top nodes are named after the animal: "Bear" contains "ear" and the converter would take
        // the whole body for part of the head; neutral names instead
        for (JsonElement e : nodes) {
            JsonObject o = e.getAsJsonObject();
            String n = o.has("name") ? o.get("name").getAsString() : "";
            if (n.matches("Elephant|Bear|Dog|Cat|Chicken|Rabbit|Unicorn|Pig")) o.addProperty("name", "Character_" + n.length());
        }
        parent = new int[nodes.size()];
        Arrays.fill(parent, -1);
        for (int i = 0; i < nodes.size(); i++) {
            JsonObject n = nodes.get(i).getAsJsonObject();
            if (n.has("children")) for (JsonElement c : n.getAsJsonArray("children")) parent[c.getAsInt()] = i;
        }
        gRot = new double[nodes.size()][];
        gPos = new double[nodes.size()][];
        gScale = new double[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) global(i);
        // the skin's joints by short name
        JsonArray sj = g.getAsJsonArray("skins").get(0).getAsJsonObject().getAsJsonArray("joints");
        for (JsonElement e : sj) {
            int n = e.getAsInt();
            JOINT.put(nodes.get(n).getAsJsonObject().get("name").getAsString(), n);
        }
        // character frame
        up = new double[]{0, 1, 0};
        int head = need("JNT_Head");
        Integer eyeL = JOINT.get("JNT_L_GlowingEye") != null ? JOINT.get("JNT_L_GlowingEye") : JOINT.get("JNT_L_EyeDetached");
        Integer eyeR = JOINT.get("JNT_R_GlowingEye") != null ? JOINT.get("JNT_R_GlowingEye") : JOINT.get("JNT_R_EyeDetached");
        double[] eyes = eyeL != null && eyeR != null ? mid(gPos[eyeL], gPos[eyeR]) : null;
        double[] f = eyes != null ? sub(eyes, gPos[head]) : new double[]{0, 0, 1};
        f[1] = 0;
        fwd = norm(f);
        // left: the side where the L_ bones are
        double[] side = cross(up, fwd);
        double[] lv = sub(gPos[need("JNT_L_Shoulder")], gPos[need("JNT_R_Shoulder")]);
        left = dot(side, lv) >= 0 ? side : scale(side, -1);
        System.out.printf(Locale.ROOT, "forward %s, left %s%n", Arrays.toString(fwd), Arrays.toString(left));
        height = 0;
        for (double[] p : gPos) if (p != null) height = Math.max(height, p[1]);

        JsonArray anims = new JsonArray();
        anims.add(anim("idle", 3.0, (t, r, h) -> {
            double s = Math.sin(t / 3.0 * Math.PI * 2);
            r.put("JNT_Spine_04", new double[]{1.5 * s, 0, 0});
            r.put("JNT_Head", new double[]{-1.5 * s, 3 * Math.sin(t / 3.0 * Math.PI), 0});
            arms(r, 2, 6 + s, -6, -6);
            tailsEars(r, t, 0.5, 6);
            h[1] = 0.004 * s;
        }));
        anims.add(anim("walk", 1.0, (t, r, h) -> {
            double ph = t * Math.PI * 2, s = Math.sin(ph);
            legs(r, -28 * s, 28 * s, 6 + 34 * Math.max(0, -Math.sin(ph + 0.8)), 6 + 34 * Math.max(0, Math.sin(ph + 0.8)));
            arms(r, 20 * s, 6, -12 - 8 * Math.max(0, -s), -12 - 8 * Math.max(0, s));
            r.get("JNT_R_Shoulder")[0] = -20 * s;
            r.put("JNT_Spine_04", new double[]{3, 4 * s, 0});
            r.put("JNT_Cog", new double[]{0, -4 * s, 0});
            tailsEars(r, t, 2, 12);
            h[1] = 0.015 * Math.cos(2 * ph) - 0.005;
        }));
        anims.add(anim("run", 0.62, (t, r, h) -> {
            double ph = t / 0.62 * Math.PI * 2, s = Math.sin(ph);
            legs(r, -50 * s, 50 * s, 15 + 75 * Math.max(0, -Math.sin(ph + 0.9)), 15 + 75 * Math.max(0, Math.sin(ph + 0.9)));
            arms(r, 45 * s, 10, -80, -80);
            r.get("JNT_R_Shoulder")[0] = -45 * s;
            r.put("JNT_Spine_04", new double[]{12, 7 * s, 0});
            r.put("JNT_Head", new double[]{-10, 0, 0});
            tailsEars(r, t, 3.2, 18);
            h[1] = 0.03 * Math.cos(2 * ph) - 0.02;
        }));
        anims.add(anim("attack", 0.6, (t, r, h) -> {
            double[] ts = {0, 0.18, 0.3, 0.42, 0.6};
            arms(r, k(t, ts, 2, -20, 20, 15, 2), 6, k(t, ts, -6, -60, -40, -30, -6), -6);
            r.get("JNT_R_Shoulder")[0] = k(t, ts, 0, 25, -88, -80, 0);
            r.get("JNT_R_Shoulder")[2] = k(t, ts, -6, -12, -6, -6, -6);
            r.get("JNT_R_Elbow")[0] = k(t, ts, -6, -110, -8, -15, -6);
            r.put("JNT_Spine_04", new double[]{k(t, ts, 0, 2, 8, 6, 0), k(t, ts, 0, 18, -22, -18, 0), 0});
            legs(r, k(t, ts, 0, -8, -18, -14, 0), k(t, ts, 0, 6, 14, 10, 0), k(t, ts, 0, 10, 20, 14, 0), 0);
            h[1] = k(t, ts, 0, -0.02, -0.04, -0.03, 0);
        }));
        anims.add(anim("jump", 0.8, (t, r, h) -> {
            legs(r, -45, -35, 70, 55);
            arms(r, -35, 25, -30, -30);
            r.get("JNT_R_Shoulder")[0] = -35;
            r.put("JNT_Spine_04", new double[]{8, 0, 0});
            tailsEars(r, t, 1, 20);
        }));
        anims.add(anim("crouch", 2.0, (t, r, h) -> {
            legs(r, -55, -55, 95, 95);
            arms(r, -20, 8, -40, -40);
            r.get("JNT_R_Shoulder")[0] = -20;
            r.put("JNT_Spine_04", new double[]{22 + Math.sin(t * Math.PI), 0, 0});
            r.put("JNT_Head", new double[]{-18, 0, 0});
            tailsEars(r, t, 0.5, 5);
            h[1] = -0.2; h[2] = -0.04;
        }));
        anims.add(anim("crouchwalk", 1.2, (t, r, h) -> {
            double ph = t / 1.2 * Math.PI * 2, s = Math.sin(ph);
            legs(r, -55 - 18 * s, -55 + 18 * s, 95 + 10 * s, 95 - 10 * s);
            arms(r, -20 + 12 * s, 8, -40, -40);
            r.get("JNT_R_Shoulder")[0] = -20 - 12 * s;
            r.put("JNT_Spine_04", new double[]{22, 0, 0});
            r.put("JNT_Head", new double[]{-18, 0, 0});
            tailsEars(r, t, 1.6, 8);
            h[1] = -0.2 + 0.01 * Math.cos(2 * ph); h[2] = -0.04;
        }));
        anims.add(anim("sit", 3.0, (t, r, h) -> {
            legs(r, -88, -88, 88, 88);
            arms(r, -25, 6, -45, -45);
            r.get("JNT_R_Shoulder")[0] = -25;
            r.put("JNT_Spine_04", new double[]{1.5 * Math.sin(t / 3.0 * Math.PI * 2), 0, 0});
            tailsEars(r, t, 0.4, 5);
        }));
        anims.add(anim("dance", 1.2, (t, r, h) -> {
            double ph = t / 1.2 * Math.PI * 2, s = Math.sin(ph), s2 = Math.sin(2 * ph);
            arms(r, 0, 120 + 25 * s, -20 * s2, -20 * s2);
            r.get("JNT_R_Shoulder")[2] = -120 + 25 * s;
            r.put("JNT_Cog", new double[]{0, 15 * s, 0});
            r.put("JNT_Spine_04", new double[]{0, -10 * s, 0});
            r.put("JNT_Head", new double[]{0, 0, 8 * s2});
            legs(r, -15 * Math.max(0, s2), -15 * Math.max(0, -s2), 25 * Math.max(0, s2), 25 * Math.max(0, -s2));
            tailsEars(r, t, 1.7, 22);
            h[1] = 0.03 * Math.abs(s2) - 0.02; h[0] = 0.03 * s;
        }));
        g.add("animations", anims);
        String binName = dst.getFileName().toString().replace(".gltf", ".bin");
        g.getAsJsonArray("buffers").get(0).getAsJsonObject().addProperty("uri", binName);
        g.getAsJsonArray("buffers").get(0).getAsJsonObject().addProperty("byteLength", bin.size());
        Files.write(dst.resolveSibling(binName), bin.toByteArray());
        Files.writeString(dst, new GsonBuilder().create().toJson(g));
        System.out.println("written " + dst + " with " + anims.size() + " animations");
    }

    static final Map<String, Integer> JOINT = new LinkedHashMap<>();
    static double height;
    static double[] gScale;

    static int need(String n) {
        Integer j = JOINT.get(n);
        if (j == null) throw new IllegalStateException("bone missing: " + n);
        return j;
    }

    /** Arms: swing (+ = back), out (+ = away from the body), elbow bend for left / right (- = forward). */
    static void arms(Map<String, double[]> r, double swing, double out, double elbowL, double elbowR) {
        r.put("JNT_L_Shoulder", new double[]{swing, 0, out});
        r.put("JNT_R_Shoulder", new double[]{swing, 0, -out});
        r.put("JNT_L_Elbow", new double[]{elbowL, 0, 0});
        r.put("JNT_R_Elbow", new double[]{elbowR, 0, 0});
    }

    /** Legs: thigh swing (- = forward), knee bend (+ = foot back); the ankle keeps the foot level-ish. */
    static void legs(Map<String, double[]> r, double thL, double thR, double knL, double knR) {
        r.put("JNT_L_Leg", new double[]{thL, 0, 0});
        r.put("JNT_R_Leg", new double[]{thR, 0, 0});
        r.put("JNT_L_Knee", new double[]{knL, 0, 0});
        r.put("JNT_R_Knee", new double[]{knR, 0, 0});
        r.put("JNT_L_Ankle", new double[]{-knL * 0.3 - thL * 0.4, 0, 0});
        r.put("JNT_R_Ankle", new double[]{-knR * 0.3 - thR * 0.4, 0, 0});
    }

    /** Every tail bone wags (yaw), long ears flop a little. */
    static void tailsEars(Map<String, double[]> r, double t, double speed, double amp) {
        for (String n : JOINT.keySet()) {
            String l = n.toLowerCase(Locale.ROOT);
            if (l.contains("tail")) {
                int idx = n.matches(".*_(\\d+)$") ? Integer.parseInt(n.replaceAll(".*_(\\d+)$", "$1")) : 1;
                double phase = idx * 0.6;
                // a long tail (the cat's) points straight back in the rest pose: let it hang in an arc, tip curled up
                double droop = !l.contains("tail_long") ? 0 : idx == 1 ? -45 : idx <= 7 ? 3 : 9;
                r.put(n, new double[]{droop, amp * 0.6 * Math.sin(t * speed * Math.PI * 2 - phase), 0});
            } else if (l.contains("ear") && (l.contains("rabbit") || l.contains("dog") || l.contains("elephant"))) {
                double sgn = l.contains("_l_") ? 1 : -1;
                r.put(n, new double[]{amp * 0.15 * Math.sin(t * speed * Math.PI * 2 + 1), 0, sgn * amp * 0.12 * Math.sin(t * speed * Math.PI * 2)});
            }
        }
    }

    static double k(double t, double[] ts, double... vs) {
        if (t <= ts[0]) return vs[0];
        for (int i = 0; i < ts.length - 1; i++) if (t <= ts[i + 1]) {
            double x = (t - ts[i]) / (ts[i + 1] - ts[i]);
            x = x * x * (3 - 2 * x);
            return vs[i] + (vs[i + 1] - vs[i]) * x;
        }
        return vs[vs.length - 1];
    }

    interface Pose { void pose(double t, Map<String, double[]> rot, double[] hips); }

    /** Samples the pose at 20 fps into rotation channels of every animated bone (and the Cog's translation). */
    static JsonObject anim(String name, double dur, Pose p) throws IOException {
        int frames = (int) Math.round(dur * 20) + 1;
        float[] times = new float[frames];
        for (int f = 0; f < frames; f++) times[f] = (float) (dur * f / (frames - 1));
        Map<String, float[]> rotOut = new LinkedHashMap<>();
        float[] cogT = new float[frames * 3];
        int cog = need("JNT_Cog");
        double unit = height / 1.9;   // the hip offsets are written for a 1.9 block figure
        for (int f = 0; f < frames; f++) {
            Map<String, double[]> r = new HashMap<>();
            double[] h = new double[3];
            p.pose(times[f], r, h);
            // the arms always hang down first (rest pose T/A)
            for (String n : JOINT.keySet()) {
                double[] deg = r.get(n);
                double[] q = deg != null ? charQuat(deg) : new double[]{0, 0, 0, 1};
                if (n.equals("JNT_L_Shoulder") || n.equals("JNT_R_Shoulder")) q = qmul(q, armDown(n));
                if (deg == null && !n.equals("JNT_L_Shoulder") && !n.equals("JNT_R_Shoulder")) continue;
                int node = JOINT.get(n);
                double[] local = localRest(node);
                double[] pg = parent[node] >= 0 ? gRot[parent[node]] : new double[]{0, 0, 0, 1};
                double[] res = qmul(qmul(qmul(qinv(pg), q), pg), local);
                float[] arr = rotOut.computeIfAbsent(n, x -> new float[frames * 4]);
                for (int c = 0; c < 4; c++) arr[f * 4 + c] = (float) res[c];
            }
            // hip bob: model-space offset turned into the Cog's parent space
            double[] world = add(add(scale(left, h[0] * unit), scale(up, h[1] * unit)), scale(fwd, h[2] * unit));
            double[] pg = parent[cog] >= 0 ? gRot[parent[cog]] : new double[]{0, 0, 0, 1};
            double ps = parent[cog] >= 0 ? gScale[parent[cog]] : 1;
            double[] lt = rotate(qinv(pg), world);
            double[] rt = trs(cog)[0];
            for (int c = 0; c < 3; c++) cogT[f * 3 + c] = (float) (rt[c] + lt[c] / ps);
        }
        JsonObject a = new JsonObject();
        a.addProperty("name", name);
        JsonArray samplers = new JsonArray(), channels = new JsonArray();
        int tAcc = addFloats(times, "SCALAR");
        for (Map.Entry<String, float[]> e : rotOut.entrySet()) {
            JsonObject s = new JsonObject();
            s.addProperty("input", tAcc); s.addProperty("output", addFloats(e.getValue(), "VEC4")); s.addProperty("interpolation", "LINEAR");
            samplers.add(s);
            JsonObject c = new JsonObject(), tg = new JsonObject();
            tg.addProperty("node", JOINT.get(e.getKey())); tg.addProperty("path", "rotation");
            c.addProperty("sampler", samplers.size() - 1); c.add("target", tg);
            channels.add(c);
        }
        JsonObject s = new JsonObject();
        s.addProperty("input", tAcc); s.addProperty("output", addFloats(cogT, "VEC3")); s.addProperty("interpolation", "LINEAR");
        samplers.add(s);
        JsonObject c = new JsonObject(), tg = new JsonObject();
        tg.addProperty("node", cog); tg.addProperty("path", "translation");
        c.addProperty("sampler", samplers.size() - 1); c.add("target", tg);
        channels.add(c);
        a.add("samplers", samplers);
        a.add("channels", channels);
        return a;
    }

    /** Rotation that turns the rest arm (shoulder -> wrist) to hang down, a little away from the body. */
    static double[] armDown(String shoulder) {
        boolean l = shoulder.contains("_L_");
        int sh = need(shoulder), wr = need(l ? "JNT_L_Wrist" : "JNT_R_Wrist");
        double[] d = norm(sub(gPos[wr], gPos[sh]));
        double[] target = norm(add(add(scale(up, -1), scale(left, l ? 0.18 : -0.18)), scale(fwd, 0.04)));
        return arc(d, target);
    }

    /** Character-frame degrees (x = pitch about left, + = back; y = yaw about up; z = roll about forward) to a model quaternion. */
    static double[] charQuat(double[] deg) {
        // + pitch must swing a hanging limb backwards: rotating "down" about +left by + gives "back" when left x up = back
        double[] axisX = left;
        double[] qx = axis(axisX, sgnPitch() * deg[0]), qy = axis(up, deg[1]), qz = axis(fwd, sgnRoll() * deg[2]);
        return qmul(qy, qmul(qx, qz));
    }

    /** Sign so that + pitch moves a downward limb backwards (-forward). */
    static double sgnPitch() {
        double[] d = rotate(axis(left, 30), scale(up, -1));
        return dot(d, fwd) < 0 ? 1 : -1;
    }

    /** Sign so that + roll moves a downward limb towards +left (out for the left arm). */
    static double sgnRoll() {
        double[] d = rotate(axis(fwd, 30), scale(up, -1));
        return dot(d, left) > 0 ? 1 : -1;
    }

    // ------------------------------------------------------------------ rest pose

    static double[][] trs(int n) {
        JsonObject o = nodes.get(n).getAsJsonObject();
        double[] t = arr(o, "translation", 0, 0, 0), r = arr(o, "rotation", 0, 0, 0, 1), s = arr(o, "scale", 1, 1, 1);
        return new double[][]{t, r, s};
    }

    static double[] localRest(int n) { return trs(n)[1]; }

    static void global(int n) {
        if (gRot[n] != null) return;
        double[][] l = trs(n);
        int p = parent[n];
        if (p < 0) {
            gRot[n] = l[1]; gPos[n] = l[0]; gScale[n] = l[2][0];
            return;
        }
        global(p);
        gRot[n] = qmul(gRot[p], l[1]);
        gPos[n] = add(gPos[p], rotate(gRot[p], scale(l[0], gScale[p])));
        gScale[n] = gScale[p] * l[2][0];
    }

    static double[] arr(JsonObject o, String k, double... def) {
        if (!o.has(k)) return def.clone();
        JsonArray a = o.getAsJsonArray(k);
        double[] r = new double[a.size()];
        for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsDouble();
        return r;
    }

    // ------------------------------------------------------------------ buffers

    static int addFloats(float[] d, String type) throws IOException {
        int off = bin.size();
        ByteBuffer b = ByteBuffer.allocate(d.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : d) b.putFloat(f);
        bin.write(b.array());
        JsonArray views = g.getAsJsonArray("bufferViews"), acc = g.getAsJsonArray("accessors");
        JsonObject v = new JsonObject(); v.addProperty("buffer", 0); v.addProperty("byteOffset", off); v.addProperty("byteLength", d.length * 4);
        views.add(v);
        JsonObject a = new JsonObject();
        int c = type.equals("VEC4") ? 4 : type.equals("VEC3") ? 3 : 1;
        a.addProperty("bufferView", views.size() - 1); a.addProperty("componentType", 5126);
        a.addProperty("count", d.length / c); a.addProperty("type", type);
        if (type.equals("SCALAR")) {
            JsonArray mn = new JsonArray(), mx = new JsonArray();
            mn.add(d[0]); mx.add(d[d.length - 1]);
            a.add("min", mn); a.add("max", mx);
        }
        acc.add(a);
        return acc.size() - 1;
    }

    // ------------------------------------------------------------------ math

    static double[] axis(double[] ax, double deg) {
        double h = Math.toRadians(deg) / 2, s = Math.sin(h);
        return new double[]{ax[0] * s, ax[1] * s, ax[2] * s, Math.cos(h)};
    }

    static double[] qmul(double[] a, double[] b) {
        return new double[]{
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
    }

    static double[] qinv(double[] q) { return new double[]{-q[0], -q[1], -q[2], q[3]}; }

    static double[] rotate(double[] q, double[] v) {
        double[] p = qmul(qmul(q, new double[]{v[0], v[1], v[2], 0}), qinv(q));
        return new double[]{p[0], p[1], p[2]};
    }

    /** Shortest rotation taking unit vector a to unit vector b. */
    static double[] arc(double[] a, double[] b) {
        double[] c = cross(a, b);
        double d = dot(a, b);
        double[] q = {c[0], c[1], c[2], 1 + d};
        double l = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (l < 1e-9) return new double[]{0, 0, 0, 1};
        return new double[]{q[0] / l, q[1] / l, q[2] / l, q[3] / l};
    }

    static double[] mid(double[] a, double[] b) { return new double[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2}; }
    static double[] add(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static double[] scale(double[] a, double s) { return new double[]{a[0] * s, a[1] * s, a[2] * s}; }
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    static double[] norm(double[] a) { double l = Math.sqrt(dot(a, a)); return l < 1e-12 ? a : scale(a, 1 / l); }
}

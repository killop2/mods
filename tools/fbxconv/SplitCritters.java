import com.google.gson.*;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Splits a glTF holding several skinned characters side by side (one material each, plus a shared material for
 * eyes / hard parts) into one glTF per character: the character's triangles (its material, plus shared-material
 * triangles inside its x range), only the joints its vertices use (plus their ancestors, parent order kept),
 * remapped JOINTS_0. Nodes are kept as they are.
 *
 * Usage:
 *   java -cp gson.jar SplitCritters.java names in.gltf                     (prints materials, joint name groups)
 *   java -cp gson.jar SplitCritters.java split in.gltf outDir sharedMat     (one outDir/<material>.gltf per character)
 */
public class SplitCritters {
    static JsonObject g;
    static ByteBuffer bin;

    public static void main(String[] a) throws Exception {
        Path src = Paths.get(a[1]);
        g = JsonParser.parseString(Files.readString(src)).getAsJsonObject();
        String uri = g.getAsJsonArray("buffers").get(0).getAsJsonObject().get("uri").getAsString();
        bin = ByteBuffer.wrap(Files.readAllBytes(src.resolveSibling(uri))).order(ByteOrder.LITTLE_ENDIAN);
        if (a[0].equals("names")) names();
        else split(Paths.get(a[2]), a[3]);
    }

    static JsonArray nodes() { return g.getAsJsonArray("nodes"); }

    static void names() {
        JsonArray mats = g.getAsJsonArray("materials");
        for (int i = 0; i < mats.size(); i++) System.out.println("material " + i + ": " + mats.get(i).getAsJsonObject().get("name"));
        JsonArray joints = g.getAsJsonArray("skins").get(0).getAsJsonObject().getAsJsonArray("joints");
        int[] parent = parents();
        for (int i = 0; i < joints.size(); i++) {
            int n = joints.get(i).getAsInt();
            int depth = 0;
            for (int p = parent[n]; p >= 0; p = parent[p]) depth++;
            System.out.println(i + "\t" + "  ".repeat(Math.min(depth, 20)) + nodes().get(n).getAsJsonObject().get("name").getAsString());
        }
    }

    static int[] parents() {
        int[] p = new int[nodes().size()];
        Arrays.fill(p, -1);
        for (int i = 0; i < nodes().size(); i++) {
            JsonObject n = nodes().get(i).getAsJsonObject();
            if (n.has("children")) for (JsonElement c : n.getAsJsonArray("children")) p[c.getAsInt()] = i;
        }
        return p;
    }

    // ------------------------------------------------------------------ accessors

    static ByteBuffer view(int acc) {
        JsonObject ac = g.getAsJsonArray("accessors").get(acc).getAsJsonObject();
        JsonObject bv = g.getAsJsonArray("bufferViews").get(ac.get("bufferView").getAsInt()).getAsJsonObject();
        int off = (bv.has("byteOffset") ? bv.get("byteOffset").getAsInt() : 0) + (ac.has("byteOffset") ? ac.get("byteOffset").getAsInt() : 0);
        ByteBuffer b = bin.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        b.position(off);
        return b.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    static int count(int acc) { return g.getAsJsonArray("accessors").get(acc).getAsJsonObject().get("count").getAsInt(); }

    static int comps(int acc) {
        return switch (g.getAsJsonArray("accessors").get(acc).getAsJsonObject().get("type").getAsString()) {
            case "VEC2" -> 2; case "VEC3" -> 3; case "VEC4" -> 4; case "MAT4" -> 16; default -> 1;
        };
    }

    static float[] floats(int acc) {
        int n = count(acc) * comps(acc);
        ByteBuffer b = view(acc);
        float[] r = new float[n];
        for (int i = 0; i < n; i++) r[i] = b.getFloat();
        return r;
    }

    static int[] ints(int acc) {
        JsonObject ac = g.getAsJsonArray("accessors").get(acc).getAsJsonObject();
        int ct = ac.get("componentType").getAsInt(), n = count(acc) * comps(acc);
        ByteBuffer b = view(acc);
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = ct == 5121 ? b.get() & 255 : ct == 5123 ? b.getShort() & 65535 : b.getInt();
        return r;
    }

    // ------------------------------------------------------------------ split

    static void split(Path outDir, String shared) throws Exception {
        Files.createDirectories(outDir);
        JsonArray mats = g.getAsJsonArray("materials");
        JsonObject skin = g.getAsJsonArray("skins").get(0).getAsJsonObject();
        JsonArray jointArr = skin.getAsJsonArray("joints");
        int J = jointArr.size();
        float[] ibm = floats(skin.get("inverseBindMatrices").getAsInt());
        int[] parent = parents();
        Map<Integer, Integer> nodeToJoint = new HashMap<>();
        for (int i = 0; i < J; i++) nodeToJoint.put(jointArr.get(i).getAsInt(), i);

        JsonArray prims = g.getAsJsonArray("meshes").get(0).getAsJsonObject().getAsJsonArray("primitives");
        JsonObject at0 = prims.get(0).getAsJsonObject().getAsJsonObject("attributes");
        float[] pos = floats(at0.get("POSITION").getAsInt()), nrm = floats(at0.get("NORMAL").getAsInt()), uv = floats(at0.get("TEXCOORD_0").getAsInt());
        int[] jnt = ints(at0.get("JOINTS_0").getAsInt());
        float[] wts = floats(at0.get("WEIGHTS_0").getAsInt());
        int sharedMat = -1;
        for (int m = 0; m < mats.size(); m++) if (mats.get(m).getAsJsonObject().get("name").getAsString().contains(shared)) sharedMat = m;
        Map<Integer, int[]> trisByMat = new HashMap<>();
        for (JsonElement pe : prims) {
            JsonObject pr = pe.getAsJsonObject();
            if (pr.getAsJsonObject("attributes").get("POSITION").getAsInt() != at0.get("POSITION").getAsInt())
                throw new IllegalStateException("primitives do not share one vertex array");
            trisByMat.put(pr.get("material").getAsInt(), ints(pr.get("indices").getAsInt()));
        }
        int[] sharedTris = trisByMat.getOrDefault(sharedMat, new int[0]);

        for (int m = 0; m < mats.size(); m++) {
            if (m == sharedMat || !trisByMat.containsKey(m)) continue;
            String name = mats.get(m).getAsJsonObject().get("name").getAsString();
            int[] own = trisByMat.get(m);
            // the character's box (x and z) from its own triangles
            float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, z0 = Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
            for (int v : own) { x0 = Math.min(x0, pos[v * 3]); x1 = Math.max(x1, pos[v * 3]); z0 = Math.min(z0, pos[v * 3 + 2]); z1 = Math.max(z1, pos[v * 3 + 2]); }
            float mx = (x1 - x0) * 0.05f, mz = (z1 - z0) * 0.05f;
            List<Integer> sh = new ArrayList<>();
            for (int t = 0; t < sharedTris.length; t += 3) {
                float cx = 0, cz = 0;
                for (int k = 0; k < 3; k++) { cx += pos[sharedTris[t + k] * 3] / 3; cz += pos[sharedTris[t + k] * 3 + 2] / 3; }
                if (cx >= x0 - mx && cx <= x1 + mx && cz >= z0 - mz && cz <= z1 + mz) for (int k = 0; k < 3; k++) sh.add(sharedTris[t + k]);
            }
            // vertices and joints used
            TreeMap<Integer, Integer> vmap = new TreeMap<>();
            for (int v : own) vmap.putIfAbsent(v, 0);
            for (int v : sh) vmap.putIfAbsent(v, 0);
            TreeSet<Integer> usedJ = new TreeSet<>();
            for (int v : vmap.keySet()) for (int k = 0; k < 4; k++) if (wts[v * 4 + k] > 0) usedJ.add(jnt[v * 4 + k]);
            // ancestors that are joints, so the chain to the root stays complete
            for (int j : new ArrayList<>(usedJ)) {
                for (int p = parent[jointArr.get(j).getAsInt()]; p >= 0; p = parent[p]) {
                    Integer pj = nodeToJoint.get(p);
                    if (pj != null) usedJ.add(pj);
                }
            }
            List<Integer> jl = new ArrayList<>(usedJ);   // original order keeps parents before children
            Map<Integer, Integer> jmap = new HashMap<>();
            for (int i = 0; i < jl.size(); i++) jmap.put(jl.get(i), i);
            int ni = 0;
            for (Map.Entry<Integer, Integer> e : vmap.entrySet()) e.setValue(ni++);
            int V = vmap.size();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonArray accessors = new JsonArray(), views = new JsonArray();
            float[] P = new float[V * 3], N = new float[V * 3], U = new float[V * 2], W = new float[V * 4];
            int[] JJ = new int[V * 4];
            for (Map.Entry<Integer, Integer> e : vmap.entrySet()) {
                int o = e.getKey(), n = e.getValue();
                System.arraycopy(pos, o * 3, P, n * 3, 3);
                System.arraycopy(nrm, o * 3, N, n * 3, 3);
                System.arraycopy(uv, o * 2, U, n * 2, 2);
                System.arraycopy(wts, o * 4, W, n * 4, 4);
                for (int k = 0; k < 4; k++) JJ[n * 4 + k] = wts[o * 4 + k] > 0 ? jmap.get(jnt[o * 4 + k]) : 0;
            }
            int aP = addFloats(out, views, accessors, P, "VEC3", true), aN = addFloats(out, views, accessors, N, "VEC3", false);
            int aU = addFloats(out, views, accessors, U, "VEC2", false), aW = addFloats(out, views, accessors, W, "VEC4", false);
            int aJ = addInts(out, views, accessors, JJ, "VEC4", 5123);
            int[] ownI = Arrays.stream(own).map(vmap::get).toArray(), shI = sh.stream().mapToInt(vmap::get).toArray();
            int aI1 = addInts(out, views, accessors, ownI, "SCALAR", 5125);
            int aI2 = shI.length > 0 ? addInts(out, views, accessors, shI, "SCALAR", 5125) : -1;
            float[] ib = new float[jl.size() * 16];
            for (int i = 0; i < jl.size(); i++) System.arraycopy(ibm, jl.get(i) * 16, ib, i * 16, 16);
            int aB = addFloats(out, views, accessors, ib, "MAT4", false);

            JsonObject ng = new JsonObject();
            ng.add("asset", g.get("asset"));
            ng.add("nodes", g.get("nodes"));
            if (g.has("scenes")) { ng.add("scenes", g.get("scenes")); ng.add("scene", g.get("scene")); }
            JsonArray nm = new JsonArray();
            nm.add(mats.get(m));
            if (aI2 >= 0) nm.add(mats.get(sharedMat));
            ng.add("materials", nm);
            JsonObject attrs = new JsonObject();
            attrs.addProperty("POSITION", aP); attrs.addProperty("NORMAL", aN); attrs.addProperty("TEXCOORD_0", aU);
            attrs.addProperty("JOINTS_0", aJ); attrs.addProperty("WEIGHTS_0", aW);
            JsonArray pa = new JsonArray();
            JsonObject p1 = new JsonObject(); p1.add("attributes", attrs); p1.addProperty("indices", aI1); p1.addProperty("material", 0); pa.add(p1);
            if (aI2 >= 0) { JsonObject p2 = new JsonObject(); p2.add("attributes", attrs); p2.addProperty("indices", aI2); p2.addProperty("material", 1); pa.add(p2); }
            JsonObject mesh = new JsonObject(); mesh.add("primitives", pa);
            JsonArray meshes = new JsonArray(); meshes.add(mesh);
            ng.add("meshes", meshes);
            JsonObject sk = new JsonObject();
            JsonArray sj = new JsonArray();
            for (int j : jl) sj.add(jointArr.get(j).getAsInt());
            sk.add("joints", sj); sk.addProperty("inverseBindMatrices", aB);
            JsonArray skins = new JsonArray(); skins.add(sk);
            ng.add("skins", skins);
            ng.add("accessors", accessors);
            ng.add("bufferViews", views);
            String binName = name + ".bin";
            JsonObject buf = new JsonObject(); buf.addProperty("uri", binName); buf.addProperty("byteLength", out.size());
            JsonArray bufs = new JsonArray(); bufs.add(buf);
            ng.add("buffers", bufs);
            Files.write(outDir.resolve(binName), out.toByteArray());
            Files.writeString(outDir.resolve(name + ".gltf"), new GsonBuilder().create().toJson(ng));
            System.out.printf(Locale.ROOT, "%-12s %6d vertices, %6d own + %5d shared triangles, %3d joints, x[%.0f %.0f]%n",
                    name, V, own.length / 3, shI.length / 3, jl.size(), x0, x1);
        }
    }

    static int addFloats(ByteArrayOutputStream out, JsonArray views, JsonArray accessors, float[] d, String type, boolean minmax) throws IOException {
        int off = out.size();
        ByteBuffer b = ByteBuffer.allocate(d.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : d) b.putFloat(f);
        out.write(b.array());
        JsonObject v = new JsonObject(); v.addProperty("buffer", 0); v.addProperty("byteOffset", off); v.addProperty("byteLength", d.length * 4);
        views.add(v);
        JsonObject a = new JsonObject();
        a.addProperty("bufferView", views.size() - 1); a.addProperty("componentType", 5126);
        int c = type.equals("VEC3") ? 3 : type.equals("VEC2") ? 2 : type.equals("VEC4") ? 4 : 16;
        a.addProperty("count", d.length / c); a.addProperty("type", type);
        if (minmax) {
            float[] mn = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, mx = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int i = 0; i < d.length; i++) { mn[i % 3] = Math.min(mn[i % 3], d[i]); mx[i % 3] = Math.max(mx[i % 3], d[i]); }
            JsonArray a1 = new JsonArray(), a2 = new JsonArray();
            for (int k = 0; k < 3; k++) { a1.add(mn[k]); a2.add(mx[k]); }
            a.add("min", a1); a.add("max", a2);
        }
        accessors.add(a);
        return accessors.size() - 1;
    }

    static int addInts(ByteArrayOutputStream out, JsonArray views, JsonArray accessors, int[] d, String type, int ct) throws IOException {
        int off = out.size();
        int sz = ct == 5123 ? 2 : 4;
        ByteBuffer b = ByteBuffer.allocate(d.length * sz).order(ByteOrder.LITTLE_ENDIAN);
        for (int x : d) { if (sz == 2) b.putShort((short) x); else b.putInt(x); }
        out.write(b.array());
        while (out.size() % 4 != 0) out.write(0);
        JsonObject v = new JsonObject(); v.addProperty("buffer", 0); v.addProperty("byteOffset", off); v.addProperty("byteLength", d.length * sz);
        views.add(v);
        JsonObject a = new JsonObject();
        a.addProperty("bufferView", views.size() - 1); a.addProperty("componentType", ct);
        a.addProperty("count", d.length / (type.equals("VEC4") ? 4 : 1)); a.addProperty("type", type);
        accessors.add(a);
        return accessors.size() - 1;
    }
}

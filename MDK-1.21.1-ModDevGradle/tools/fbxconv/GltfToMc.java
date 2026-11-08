import com.google.gson.*;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Converts a Blockbench glTF car into the Flatout Car mesh format (textured quads).
 * Usage: java -cp gson.jar GltfToMc.java model.gltf texture.png out.json previewPrefix scale
 *   scale: blocks per Blockbench pixel multiplier (1.0 = 1/16 block per pixel).
 * Model convention (Blockbench): Y up, front of the car towards -Z. Output: front towards +Z.
 * Groups: wheels_front / wheels_back (split into left/right), steering_wheel, everything else = body.
 */
public class GltfToMc {
    static JsonObject gltf;
    static ByteBuffer bin;

    record Vtx(double x, double y, double z, double u, double v) {
        boolean same(Vtx o) {
            return Math.abs(x - o.x) < 1e-4 && Math.abs(y - o.y) < 1e-4 && Math.abs(z - o.z) < 1e-4
                    && Math.abs(u - o.u) < 1e-5 && Math.abs(v - o.v) < 1e-5;
        }
    }

    static final class Part {
        String role; double[] pivot; List<Vtx[]> quads = new ArrayList<>();
        Part(String role) { this.role = role; }
    }

    public static void main(String[] args) throws Exception {
        gltf = JsonParser.parseString(Files.readString(Paths.get(args[0]))).getAsJsonObject();
        BufferedImage tex = ImageIO.read(new File(args[1]));
        double scale = Double.parseDouble(args[4]) / 16.0;
        String uri = gltf.getAsJsonArray("buffers").get(0).getAsJsonObject().get("uri").getAsString();
        byte[] data = uri.startsWith("data:") ? Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1))
                : Files.readAllBytes(Paths.get(args[0]).resolveSibling(uri));
        bin = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        JsonArray nodes = gltf.getAsJsonArray("nodes");
        Map<String, Part> parts = new LinkedHashMap<>();
        Map<String, double[]> groupPivot = new HashMap<>();
        int[] stats = new int[2];
        for (JsonElement r : gltf.getAsJsonArray("scenes").get(0).getAsJsonObject().getAsJsonArray("nodes")) {
            walk(r.getAsInt(), ident(), "body", nodes, parts, groupPivot, stats);
        }
        System.out.println("triangles " + stats[0] + ", merged quads " + stats[1]);

        // ---- flip to front=+Z, scale to blocks, ground at y=0, centered on x/z ----
        double[] mn = {1e9, 1e9, 1e9}, mx = {-1e9, -1e9, -1e9};
        for (Part p : parts.values()) for (Vtx[] q : p.quads) for (Vtx v : q) {
            double[] c = {-v.x, v.y, -v.z};
            for (int k = 0; k < 3; k++) { mn[k] = Math.min(mn[k], c[k]); mx[k] = Math.max(mx[k], c[k]); }
        }
        double cx = (mn[0] + mx[0]) / 2, cz = (mn[2] + mx[2]) / 2, by = mn[1];
        Map<String, Part> out = new LinkedHashMap<>();
        for (Map.Entry<String, Part> e : parts.entrySet()) {
            Part src = e.getValue();
            for (Vtx[] q : src.quads) {
                Vtx[] t = new Vtx[4];
                for (int k = 0; k < 4; k++) {
                    Vtx v = q[k];
                    t[k] = new Vtx((-v.x - cx) * scale, (v.y - by) * scale, (-v.z - cz) * scale, v.u, v.v);
                }
                String role = src.role;
                if (role.startsWith("wheel_")) {
                    double ax = (t[0].x + t[1].x + t[2].x + t[3].x) / 4;
                    role = role + (ax >= 0 ? "_left" : "_right");   // +X = left side when facing +Z
                }
                out.computeIfAbsent(role, Part::new).quads.add(t);
            }
        }
        for (Part p : out.values()) {
            double[] a = {1e9, 1e9, 1e9}, b = {-1e9, -1e9, -1e9};
            for (Vtx[] q : p.quads) for (Vtx v : q) {
                double[] c = {v.x, v.y, v.z};
                for (int k = 0; k < 3; k++) { a[k] = Math.min(a[k], c[k]); b[k] = Math.max(b[k], c[k]); }
            }
            String g = p.role.startsWith("wheel_front") ? "wheels_front" : p.role.startsWith("wheel_rear") ? "wheels_back" : p.role.equals("steering") ? "steering_wheel" : null;
            if (g != null && groupPivot.containsKey(g)) {
                double[] gp = groupPivot.get(g);
                double gy = (gp[1] - by) * scale, gz = (-gp[2] - cz) * scale, gx = (-gp[0] - cx) * scale;
                // Blockbench group pivots are not always on the axle: use the part's own centre.
                p.pivot = new double[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2};
            } else {
                p.pivot = new double[]{0, 0, 0};
            }
            System.out.printf(Locale.ROOT, "part %-18s quads=%4d min=[%.3f %.3f %.3f] max=[%.3f %.3f %.3f] pivot=[%.3f %.3f %.3f]%n",
                    p.role, p.quads.size(), a[0], a[1], a[2], b[0], b[1], b[2], p.pivot[0], p.pivot[1], p.pivot[2]);
        }
        System.out.printf(Locale.ROOT, "car size (blocks): length %.2f width %.2f height %.2f%n",
                (mx[2] - mn[2]) * scale, (mx[0] - mn[0]) * scale, (mx[1] - mn[1]) * scale);
        // Roof height / floor height over the driver seat (x of steering wheel, z slightly behind it).
        Part st = out.get("steering");
        if (st != null) {
            double sx = st.pivot[0], sz = st.pivot[2] - 0.45 * scale * 16;
            for (double dz = -0.4; dz <= 0.41; dz += 0.2) {
                double roof = -1, floor = 9;
                for (Vtx[] q : out.get("body").quads) {
                    double qx = 0, qz = 0, qy = 0;
                    for (Vtx v : q) { qx += v.x / 4; qy += v.y / 4; qz += v.z / 4; }
                    if (Math.abs(qx - sx) < 0.2 && Math.abs(qz - (sz + dz)) < 0.15) { roof = Math.max(roof, qy); if (qy > 0.05) floor = Math.min(floor, qy); }
                }
                System.out.printf(Locale.ROOT, "seat column x=%.2f z=%.2f: lowest surface %.2f, highest %.2f%n", sx, sz + dz, floor, roof);
            }
        }

        // ---- write JSON ----
        StringBuilder js = new StringBuilder("{\n  \"source\": \"BMW M3 GTR E46 (NFS:MW) Blockbench model\",\n  \"parts\": [\n");
        int pi = 0;
        for (Part p : out.values()) {
            js.append(pi++ == 0 ? "" : ",\n").append("    {\"role\": \"").append(p.role).append("\", ");
            js.append(String.format(Locale.ROOT, "\"pivot\": [%.4f, %.4f, %.4f], \"quads\": [", p.pivot[0], p.pivot[1], p.pivot[2]));
            int n = 0;
            for (Vtx[] q : p.quads) for (Vtx v : q) {
                js.append(n++ == 0 ? "" : ",");
                js.append(String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.5f,%.5f", v.x, v.y, v.z, v.u, v.v));
            }
            js.append("]}");
        }
        js.append("\n  ]\n}\n");
        Files.writeString(Paths.get(args[2]), js.toString());

        List<Vtx[]> all = new ArrayList<>();
        for (Part p : out.values()) all.addAll(p.quads);
        for (int view = 0; view < 4; view++) preview(all, tex, args[3] + "_" + new String[]{"side", "top", "front", "34"}[view] + ".png", view);
    }

    static void walk(int idx, double[] parent, String role, JsonArray nodes, Map<String, Part> parts, Map<String, double[]> pivots, int[] stats) {
        JsonObject n = nodes.get(idx).getAsJsonObject();
        String name = n.has("name") ? n.get("name").getAsString() : "";
        double[] local = local(n);
        double[] world = mul(parent, local);
        String r = role;
        if (name.equals("wheels_front")) r = "wheel_front";
        else if (name.equals("wheels_back")) r = "wheel_rear";
        else if (name.equals("steering_wheel")) r = "steering";
        if (!r.equals(role) || name.startsWith("wheels") || name.equals("steering_wheel")) pivots.put(name, new double[]{world[3], world[7], world[11]});
        if (n.has("mesh")) {
            JsonObject mesh = gltf.getAsJsonArray("meshes").get(n.get("mesh").getAsInt()).getAsJsonObject();
            for (JsonElement pe : mesh.getAsJsonArray("primitives")) {
                JsonObject prim = pe.getAsJsonObject();
                JsonObject at = prim.getAsJsonObject("attributes");
                double[] pos = floats(at.get("POSITION").getAsInt());
                double[] uv = floats(at.get("TEXCOORD_0").getAsInt());
                int count = pos.length / 3;
                int[] ind = prim.has("indices") ? ints(prim.get("indices").getAsInt()) : null;
                int tc = (ind == null ? count : ind.length) / 3;
                Vtx[][] tris = new Vtx[tc][3];
                for (int t = 0; t < tc; t++) for (int k = 0; k < 3; k++) {
                    int i = ind == null ? t * 3 + k : ind[t * 3 + k];
                    double[] w = apply(world, pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]);
                    tris[t][k] = new Vtx(w[0], w[1], w[2], uv[i * 2], uv[i * 2 + 1]);
                }
                stats[0] += tc;
                Part part = parts.computeIfAbsent(r, Part::new);
                for (int t = 0; t < tc; t++) {
                    Vtx[] q = t + 1 < tc ? merge(tris[t], tris[t + 1]) : null;
                    if (q != null) { part.quads.add(q); stats[1]++; t++; }
                    else part.quads.add(new Vtx[]{tris[t][0], tris[t][1], tris[t][2], tris[t][2]});
                }
            }
        }
        if (n.has("children")) for (JsonElement c : n.getAsJsonArray("children")) walk(c.getAsInt(), world, r, nodes, parts, pivots, stats);
    }

    /** Two triangles sharing an edge -> one quad (keeps winding of the first triangle). */
    static Vtx[] merge(Vtx[] a, Vtx[] b) {
        for (int i = 0; i < 3; i++) {
            Vtx p = a[i], q = a[(i + 1) % 3];
            int ip = idx(b, p), iq = idx(b, q);
            if (ip < 0 || iq < 0) continue;
            Vtx d = b[3 - ip - iq];
            Vtx[] r = new Vtx[4];
            // a: p q o  ->  quad p d q o  (d lies across edge p-q)
            Vtx o = a[(i + 2) % 3];
            r[0] = p; r[1] = d; r[2] = q; r[3] = o;
            return r;
        }
        return null;
    }
    static int idx(Vtx[] t, Vtx v) { for (int i = 0; i < 3; i++) if (t[i].same(v)) return i; return -1; }

    // ---------------- accessors ----------------
    static double[] floats(int acc) {
        JsonObject a = gltf.getAsJsonArray("accessors").get(acc).getAsJsonObject();
        int comps = switch (a.get("type").getAsString()) { case "VEC2" -> 2; case "VEC3" -> 3; case "VEC4" -> 4; default -> 1; };
        int count = a.get("count").getAsInt();
        JsonObject bv = gltf.getAsJsonArray("bufferViews").get(a.get("bufferView").getAsInt()).getAsJsonObject();
        int off = (bv.has("byteOffset") ? bv.get("byteOffset").getAsInt() : 0) + (a.has("byteOffset") ? a.get("byteOffset").getAsInt() : 0);
        int stride = bv.has("byteStride") ? bv.get("byteStride").getAsInt() : comps * 4;
        double[] r = new double[count * comps];
        for (int i = 0; i < count; i++) for (int c = 0; c < comps; c++) r[i * comps + c] = bin.getFloat(off + i * stride + c * 4);
        return r;
    }
    static int[] ints(int acc) {
        JsonObject a = gltf.getAsJsonArray("accessors").get(acc).getAsJsonObject();
        int count = a.get("count").getAsInt(), ct = a.get("componentType").getAsInt();
        JsonObject bv = gltf.getAsJsonArray("bufferViews").get(a.get("bufferView").getAsInt()).getAsJsonObject();
        int off = (bv.has("byteOffset") ? bv.get("byteOffset").getAsInt() : 0) + (a.has("byteOffset") ? a.get("byteOffset").getAsInt() : 0);
        int[] r = new int[count];
        for (int i = 0; i < count; i++) r[i] = ct == 5125 ? bin.getInt(off + i * 4) : ct == 5123 ? (bin.getShort(off + i * 2) & 0xFFFF) : (bin.get(off + i) & 0xFF);
        return r;
    }

    // ---------------- math ----------------
    static double[] ident() { double[] m = new double[16]; m[0] = m[5] = m[10] = m[15] = 1; return m; }
    static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { double s = 0; for (int k = 0; k < 4; k++) s += a[i * 4 + k] * b[k * 4 + j]; r[i * 4 + j] = s; }
        return r;
    }
    static double[] apply(double[] m, double x, double y, double z) {
        return new double[]{m[0] * x + m[1] * y + m[2] * z + m[3], m[4] * x + m[5] * y + m[6] * z + m[7], m[8] * x + m[9] * y + m[10] * z + m[11]};
    }
    static double[] arr(JsonObject n, String k, double... def) {
        if (!n.has(k)) return def;
        JsonArray a = n.getAsJsonArray(k); double[] r = new double[a.size()];
        for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsDouble();
        return r;
    }
    static double[] local(JsonObject n) {
        if (n.has("matrix")) { double[] c = arr(n, "matrix"); double[] m = new double[16]; for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) m[i * 4 + j] = c[j * 4 + i]; return m; }
        double[] t = arr(n, "translation", 0, 0, 0), q = arr(n, "rotation", 0, 0, 0, 1), s = arr(n, "scale", 1, 1, 1);
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double[] r = {
                1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)};
        double[] m = ident();
        for (int i = 0; i < 3; i++) { for (int j = 0; j < 3; j++) m[i * 4 + j] = r[i * 3 + j] * s[j]; m[i * 4 + 3] = t[i]; }
        return m;
    }

    // ---------------- preview ----------------
    static void preview(List<Vtx[]> quads, BufferedImage tex, String file, int view) throws IOException {
        int W = 640, H = 420; BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(170, 200, 230)); g.fillRect(0, 0, W, H);
        double yaw = Math.toRadians(35), pitch = Math.toRadians(25), sc = 110;
        List<double[]> polys = new ArrayList<>();
        for (Vtx[] q : quads) {
            double[] p = new double[8]; double depth = 0, cu = 0, cv = 0;
            for (int k = 0; k < 4; k++) {
                Vtx v = q[k]; double sx, sy, d;
                if (view == 0) { sx = v.z; sy = v.y; d = -v.x; }
                else if (view == 1) { sx = v.x; sy = -v.z; d = -v.y; }
                else if (view == 2) { sx = -v.x; sy = v.y; d = -v.z; }
                else {
                    double x1 = v.x * Math.cos(yaw) + v.z * Math.sin(yaw), z1 = -v.x * Math.sin(yaw) + v.z * Math.cos(yaw);
                    sx = x1; sy = v.y * Math.cos(pitch) - z1 * Math.sin(pitch); d = v.y * Math.sin(pitch) + z1 * Math.cos(pitch);
                }
                p[k * 2] = W / 2.0 + sx * sc; p[k * 2 + 1] = (view == 1 ? H / 2.0 : H * 0.7) - sy * sc; depth += d; cu += v.u; cv += v.v;
            }
            int tx = Math.min(tex.getWidth() - 1, Math.max(0, (int) (cu / 4 * tex.getWidth())));
            int ty = Math.min(tex.getHeight() - 1, Math.max(0, (int) (cv / 4 * tex.getHeight())));
            int argb = tex.getRGB(tx, ty);
            if ((argb >>> 24) < 20) argb = 0xFFFF00FF;   // transparent texel at face centre: show magenta
            polys.add(new double[]{p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], depth, argb & 0xFFFFFF});
        }
        polys.sort((a, b) -> Double.compare(b[8], a[8]));
        for (double[] p : polys) {
            g.setColor(new Color((int) p[9]));
            Path2D path = new Path2D.Double(); path.moveTo(p[0], p[1]); path.lineTo(p[2], p[3]); path.lineTo(p[4], p[5]); path.lineTo(p[6], p[7]); path.closePath();
            g.fill(path);
        }
        // reference: 1.8-block player standing at origin (side/front views)
        g.setColor(Color.BLACK);
        g.drawString(new String[]{"side: front -> right", "top: front -> up, left side -> right", "front view", "3/4"}[view], 8, 16);
        g.dispose(); ImageIO.write(img, "png", new File(file));
    }
}

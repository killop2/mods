import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.Inflater;
import javax.imageio.ImageIO;

/**
 * Converts a binary FBX (7.x) low-poly model into a simple JSON mesh for the Flatout Car mod.
 * Usage: java FbxToMc.java input.fbx output.json previewPrefix [lengthBlocks] [flipForward]
 */
public class FbxToMc {
    // ---------------- FBX binary parsing ----------------
    static final class Node {
        String name;
        List<Object> props = new ArrayList<>();
        List<Node> children = new ArrayList<>();
        Node child(String n) { for (Node c : children) if (c.name.equals(n)) return c; return null; }
        List<Node> all(String n) { List<Node> r = new ArrayList<>(); for (Node c : children) if (c.name.equals(n)) r.add(c); return r; }
    }

    static ByteBuffer buf;
    static int version;

    static Node readNode() throws Exception {
        long end, numProps;
        if (version >= 7500) { end = buf.getLong(); numProps = buf.getLong(); buf.getLong(); }
        else { end = buf.getInt() & 0xFFFFFFFFL; numProps = buf.getInt() & 0xFFFFFFFFL; buf.getInt(); }
        int nameLen = buf.get() & 0xFF;
        if (end == 0) return null;
        byte[] nb = new byte[nameLen]; buf.get(nb);
        Node n = new Node(); n.name = new String(nb, "ISO-8859-1");
        for (long i = 0; i < numProps; i++) n.props.add(readProp());
        while (buf.position() < end) {
            Node c = readNode();
            if (c == null) break;
            n.children.add(c);
        }
        buf.position((int) end);
        return n;
    }

    static Object readProp() throws Exception {
        char t = (char) buf.get();
        switch (t) {
            case 'Y': return (int) buf.getShort();
            case 'C': return buf.get() != 0;
            case 'I': return buf.getInt();
            case 'F': return (double) buf.getFloat();
            case 'D': return buf.getDouble();
            case 'L': return buf.getLong();
            case 'S': case 'R': {
                int len = buf.getInt(); byte[] b = new byte[len]; buf.get(b);
                return t == 'S' ? new String(b, "ISO-8859-1") : b;
            }
            case 'f': case 'd': case 'l': case 'i': case 'b': {
                int count = buf.getInt(), enc = buf.getInt(), clen = buf.getInt();
                byte[] raw = new byte[clen]; buf.get(raw);
                int elem = (t == 'd' || t == 'l') ? 8 : (t == 'b' ? 1 : 4);
                byte[] data = raw;
                if (enc == 1) {
                    Inflater inf = new Inflater(); inf.setInput(raw);
                    data = new byte[count * elem]; int off = 0;
                    while (off < data.length) { int r = inf.inflate(data, off, data.length - off); if (r == 0 && (inf.finished() || inf.needsInput())) break; off += r; }
                    inf.end();
                }
                ByteBuffer d = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                if (t == 'd') { double[] a = new double[count]; for (int i = 0; i < count; i++) a[i] = d.getDouble(); return a; }
                if (t == 'f') { double[] a = new double[count]; for (int i = 0; i < count; i++) a[i] = d.getFloat(); return a; }
                if (t == 'i') { int[] a = new int[count]; for (int i = 0; i < count; i++) a[i] = d.getInt(); return a; }
                if (t == 'l') { long[] a = new long[count]; for (int i = 0; i < count; i++) a[i] = d.getLong(); return a; }
                return data;
            }
            default: throw new IllegalStateException("Unknown prop type " + t + " at " + buf.position());
        }
    }

    // ---------------- math ----------------
    static double[] ident() { double[] m = new double[16]; m[0] = m[5] = m[10] = m[15] = 1; return m; }
    static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
            double s = 0; for (int k = 0; k < 4; k++) s += a[i * 4 + k] * b[k * 4 + j]; r[i * 4 + j] = s;
        }
        return r;
    }
    static double[] trans(double[] t) { double[] m = ident(); m[3] = t[0]; m[7] = t[1]; m[11] = t[2]; return m; }
    static double[] scale(double[] s) { double[] m = ident(); m[0] = s[0]; m[5] = s[1]; m[10] = s[2]; return m; }
    static double[] rot(int axis, double deg) {
        double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r); double[] m = ident();
        if (axis == 0) { m[5] = c; m[6] = -s; m[9] = s; m[10] = c; }
        if (axis == 1) { m[0] = c; m[2] = s; m[8] = -s; m[10] = c; }
        if (axis == 2) { m[0] = c; m[1] = -s; m[4] = s; m[5] = c; }
        return m;
    }
    static double[] euler(double[] e) { return mul(rot(2, e[2]), mul(rot(1, e[1]), rot(0, e[0]))); } // XYZ order
    static double[] apply(double[] m, double x, double y, double z) {
        return new double[]{m[0] * x + m[1] * y + m[2] * z + m[3], m[4] * x + m[5] * y + m[6] * z + m[7], m[8] * x + m[9] * y + m[10] * z + m[11]};
    }

    // ---------------- scene ----------------
    static final class Model { long id; String name; double[] local = ident(); Long parent; Long geom; List<Long> mats = new ArrayList<>(); double[] world; }
    static final class Geom { double[] verts; int[] poly; int[] matIdx; String matMapping; }

    static double[] p70(Node n, String key, double[] def) {
        Node p = n.child("Properties70");
        if (p == null) return def;
        for (Node c : p.all("P")) if (key.equals(c.props.get(0))) {
            double[] r = new double[c.props.size() - 4];
            for (int i = 0; i < r.length; i++) r[i] = ((Number) c.props.get(4 + i)).doubleValue();
            return r;
        }
        return def;
    }
    static String clean(Object o) { String s = (String) o; int i = s.indexOf('\0'); return i >= 0 ? s.substring(0, i) : s; }

    public static void main(String[] args) throws Exception {
        byte[] all = Files.readAllBytes(Paths.get(args[0]));
        buf = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(23); version = buf.getInt();
        List<Node> top = new ArrayList<>();
        while (buf.remaining() > 160) { Node n = readNode(); if (n == null) break; top.add(n); }
        Node objects = null, conns = null, global = null;
        for (Node n : top) { if (n.name.equals("Objects")) objects = n; if (n.name.equals("Connections")) conns = n; if (n.name.equals("GlobalSettings")) global = n; }
        System.out.println("FBX version " + version);
        if (global != null) {
            for (String k : new String[]{"UpAxis", "UpAxisSign", "FrontAxis", "FrontAxisSign", "CoordAxis", "CoordAxisSign", "UnitScaleFactor"})
                System.out.println("  " + k + " = " + Arrays.toString(p70(global, k, null)));
        }

        Map<Long, Model> models = new LinkedHashMap<>();
        Map<Long, Geom> geoms = new HashMap<>();
        Map<Long, double[]> matColor = new HashMap<>();
        Map<Long, String> matName = new HashMap<>();
        for (Node n : objects.children) {
            long id = (Long) n.props.get(0);
            if (n.name.equals("Model")) {
                Model m = new Model(); m.id = id; m.name = clean(n.props.get(1));
                double[] t = p70(n, "Lcl Translation", new double[]{0, 0, 0});
                double[] r = p70(n, "Lcl Rotation", new double[]{0, 0, 0});
                double[] s = p70(n, "Lcl Scaling", new double[]{1, 1, 1});
                double[] pre = p70(n, "PreRotation", new double[]{0, 0, 0});
                m.local = mul(trans(t), mul(euler(pre), mul(euler(r), scale(s))));
                double[] gt = p70(n, "GeometricTranslation", null), gr = p70(n, "GeometricRotation", null), gs = p70(n, "GeometricScaling", null);
                if (gt != null || gr != null || gs != null) System.out.println("  WARN geometric transform on " + m.name);
                models.put(id, m);
            } else if (n.name.equals("Geometry")) {
                Geom g = new Geom();
                g.verts = (double[]) n.child("Vertices").props.get(0);
                g.poly = (int[]) n.child("PolygonVertexIndex").props.get(0);
                Node lm = n.child("LayerElementMaterial");
                if (lm != null) { g.matIdx = (int[]) lm.child("Materials").props.get(0); g.matMapping = (String) lm.child("MappingInformationType").props.get(0); }
                geoms.put(id, g);
            } else if (n.name.equals("Material")) {
                matName.put(id, clean(n.props.get(1)));
                double[] c = p70(n, "DiffuseColor", null);
                if (c == null) c = p70(n, "Diffuse", new double[]{0.8, 0.8, 0.8});
                matColor.put(id, c);
            }
        }
        for (Node c : conns.all("C")) {
            if (!"OO".equals(c.props.get(0))) continue;
            long child = (Long) c.props.get(1), parent = (Long) c.props.get(2);
            Model pm = models.get(parent);
            if (models.containsKey(child) && pm != null) models.get(child).parent = parent;
            else if (geoms.containsKey(child) && pm != null) pm.geom = child;
            else if (matColor.containsKey(child) && pm != null) pm.mats.add(child);
        }
        for (Model m : models.values()) world(m, models);

        // ---- collect triangles in FBX space ----
        List<double[]> tris = new ArrayList<>();   // 9 coords
        List<Integer> triColor = new ArrayList<>();
        List<String> triPart = new ArrayList<>();
        List<double[]> palette = new ArrayList<>();
        Map<String, Integer> palIdx = new HashMap<>();
        for (Model m : models.values()) {
            System.out.println("  Model '" + m.name + "' parent=" + (m.parent == null ? "-" : models.get(m.parent).name)
                    + (m.geom != null ? " mesh polys~" + countPolys(geoms.get(m.geom).poly) : "")
                    + " mats=" + m.mats.stream().map(matName::get).toList());
            if (m.geom == null) continue;
            Geom g = geoms.get(m.geom);
            int polyNo = 0; List<Integer> cur = new ArrayList<>();
            for (int raw : g.poly) {
                boolean last = raw < 0; int vi = last ? ~raw : raw; cur.add(vi);
                if (!last) continue;
                int mi = 0;
                if (g.matIdx != null) mi = "AllSame".equals(g.matMapping) ? g.matIdx[0] : g.matIdx[Math.min(polyNo, g.matIdx.length - 1)];
                double[] col = m.mats.isEmpty() ? new double[]{0.8, 0.8, 0.8} : matColor.get(m.mats.get(Math.min(mi, m.mats.size() - 1)));
                String key = String.format(Locale.ROOT, "%.4f,%.4f,%.4f", col[0], col[1], col[2]);
                Integer ci = palIdx.get(key);
                if (ci == null) { ci = palette.size(); palette.add(col); palIdx.put(key, ci); }
                double[][] p = new double[cur.size()][];
                for (int k = 0; k < p.length; k++) { int v = cur.get(k); p[k] = apply(m.world, g.verts[v * 3], g.verts[v * 3 + 1], g.verts[v * 3 + 2]); }
                for (int k = 1; k + 1 < p.length; k++) {
                    tris.add(new double[]{p[0][0], p[0][1], p[0][2], p[k][0], p[k][1], p[k][2], p[k + 1][0], p[k + 1][1], p[k + 1][2]});
                    triColor.add(ci); triPart.add(m.name);
                }
                cur.clear(); polyNo++;
            }
        }
        for (int i = 0; i < palette.size(); i++) System.out.println("  color " + i + " = " + Arrays.toString(palette.get(i)));

        // ---- bounding box in FBX space ----
        double[] mn = {1e9, 1e9, 1e9}, mx = {-1e9, -1e9, -1e9};
        for (double[] t : tris) for (int k = 0; k < 9; k++) { mn[k % 3] = Math.min(mn[k % 3], t[k]); mx[k % 3] = Math.max(mx[k % 3], t[k]); }
        System.out.println("  bbox min " + Arrays.toString(mn) + " max " + Arrays.toString(mx) + " tris " + tris.size());

        // ---- orient: Y up (FBX default). Car length along the longer horizontal axis -> Minecraft +Z (front). ----
        boolean lenAlongX = (mx[0] - mn[0]) > (mx[2] - mn[2]);
        boolean flip = args.length > 4 && Boolean.parseBoolean(args[4]);
        double length = lenAlongX ? mx[0] - mn[0] : mx[2] - mn[2];
        double target = args.length > 3 ? Double.parseDouble(args[3]) : 3.0;
        double s = target / length;
        double cx = (mn[0] + mx[0]) / 2, cz = (mn[2] + mx[2]) / 2, by = mn[1];
        List<double[]> out = new ArrayList<>();
        for (double[] t : tris) {
            double[] o = new double[9];
            for (int k = 0; k < 3; k++) {
                double x = t[k * 3] - cx, y = t[k * 3 + 1] - by, z = t[k * 3 + 2] - cz;
                double nx, nz;
                if (lenAlongX) { nz = x; nx = -z; } else { nz = z; nx = x; }
                if (flip) { nz = -nz; nx = -nx; }
                o[k * 3] = nx * s; o[k * 3 + 1] = y * s; o[k * 3 + 2] = nz * s;
            }
            out.add(o);
        }

        // ---- parts: group by FBX model; wheel parts get their pivot (bbox centre) ----
        Map<String, List<Integer>> parts = new LinkedHashMap<>();
        for (int i = 0; i < out.size(); i++) parts.computeIfAbsent(triPart.get(i), k -> new ArrayList<>()).add(i);
        StringBuilder js = new StringBuilder();
        js.append("{\n  \"source\": \"Free Low Poly Vehicles Pack by Rgsdev (CC0) - ").append(Paths.get(args[0]).getFileName()).append("\",\n");
        js.append("  \"palette\": [");
        for (int i = 0; i < palette.size(); i++) {
            double[] c = palette.get(i);
            int rgb = (clamp(c[0]) << 16) | (clamp(c[1]) << 8) | clamp(c[2]);
            js.append(i == 0 ? "" : ", ").append(String.format("\"%06X\"", rgb));
        }
        js.append("],\n  \"parts\": [\n");
        int pi = 0;
        double[] omn = {1e9, 1e9, 1e9}, omx = {-1e9, -1e9, -1e9};
        for (double[] t : out) for (int k = 0; k < 9; k++) { omn[k % 3] = Math.min(omn[k % 3], t[k]); omx[k % 3] = Math.max(omx[k % 3], t[k]); }
        for (Map.Entry<String, List<Integer>> e : parts.entrySet()) {
            double[] a = {1e9, 1e9, 1e9}, b = {-1e9, -1e9, -1e9};
            for (int i : e.getValue()) for (int k = 0; k < 9; k++) { a[k % 3] = Math.min(a[k % 3], out.get(i)[k]); b[k % 3] = Math.max(b[k % 3], out.get(i)[k]); }
            String role = "body";
            if (e.getKey().toLowerCase().contains("wheel")) {
                double pz = (a[2] + b[2]) / 2;
                role = pz > 0 ? "wheel_front" : "wheel_rear";
            }
            js.append(pi++ == 0 ? "" : ",\n").append("    {\"name\": \"").append(e.getKey()).append("\", \"role\": \"").append(role).append("\", ");
            js.append(String.format(Locale.ROOT, "\"pivot\": [%.4f, %.4f, %.4f], ", (a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2));
            js.append("\"tris\": [");
            int ti = 0;
            for (int i : e.getValue()) {
                double[] t = out.get(i);
                js.append(ti++ == 0 ? "" : ",").append(triColor.get(i));
                for (double v : t) js.append(',').append(String.format(Locale.ROOT, "%.4f", v));
            }
            js.append("]}");
            System.out.println(String.format(Locale.ROOT, "  part %-20s role=%-11s tris=%4d min=[%.2f %.2f %.2f] max=[%.2f %.2f %.2f]", e.getKey(), role, e.getValue().size(), a[0], a[1], a[2], b[0], b[1], b[2]));
        }
        js.append("\n  ]\n}\n");
        Files.writeString(Paths.get(args[1]), js.toString());
        System.out.println(String.format(Locale.ROOT, "  output bbox (blocks): x %.2f..%.2f  y %.2f..%.2f  z %.2f..%.2f", omn[0], omx[0], omn[1], omx[1], omn[2], omx[2]));

        // ---- previews: side (from +X looking -X), top, front, 3/4 ----
        String pre = args[2];
        preview(out, triColor, palette, pre + "_side.png", 0);
        preview(out, triColor, palette, pre + "_top.png", 1);
        preview(out, triColor, palette, pre + "_front.png", 2);
        preview(out, triColor, palette, pre + "_34.png", 3);
    }

    /** Linear material colour -> sRGB byte. */
    static int clamp(double c) { return (int) Math.max(0, Math.min(255, Math.round(Math.pow(Math.max(0, c), 1 / 2.2) * 255))); }
    static int countPolys(int[] p) { int c = 0; for (int v : p) if (v < 0) c++; return c; }
    static void world(Model m, Map<Long, Model> all) {
        if (m.world != null) return;
        if (m.parent == null || !all.containsKey(m.parent)) { m.world = m.local; return; }
        Model p = all.get(m.parent); world(p, all); m.world = mul(p.world, m.local);
    }

    /** Simple painter's-algorithm render. view 0=side (front of car to the right), 1=top (front up), 2=front, 3=3/4. */
    static void preview(List<double[]> tris, List<Integer> col, List<double[]> pal, String file, int view) throws IOException {
        int W = 520, H = 360; BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(170, 200, 230)); g.fillRect(0, 0, W, H);
        double yaw = view == 3 ? Math.toRadians(35) : 0, pitch = view == 3 ? Math.toRadians(25) : 0;
        List<double[]> proj = new ArrayList<>();
        for (int i = 0; i < tris.size(); i++) {
            double[] t = tris.get(i); double[] p = new double[7]; double depth = 0;
            double[][] v = new double[3][3];
            for (int k = 0; k < 3; k++) {
                double x = t[k * 3], y = t[k * 3 + 1], z = t[k * 3 + 2], sx, sy, d;
                if (view == 0) { sx = z; sy = y; d = -x; }
                else if (view == 1) { sx = x; sy = z; d = -y; }
                else if (view == 2) { sx = -x; sy = y; d = -z; }
                else {
                    double x1 = x * Math.cos(yaw) + z * Math.sin(yaw), z1 = -x * Math.sin(yaw) + z * Math.cos(yaw);
                    double y1 = y * Math.cos(pitch) - z1 * Math.sin(pitch), z2 = y * Math.sin(pitch) + z1 * Math.cos(pitch);
                    sx = x1; sy = y1; d = z2;
                }
                v[k] = new double[]{x, y, z};
                p[k * 2] = W / 2.0 + sx * 120; p[k * 2 + 1] = H * 0.62 - sy * 120 + (view == 1 ? -H * 0.12 + 0 : 0); depth += d;
            }
            if (view == 1) { for (int k = 0; k < 3; k++) p[k * 2 + 1] = H / 2.0 - (tris.get(i)[k * 3 + 2]) * 100; for (int k = 0; k < 3; k++) p[k * 2] = W / 2.0 + tris.get(i)[k * 3] * 100; }
            double ux = v[1][0] - v[0][0], uy = v[1][1] - v[0][1], uz = v[1][2] - v[0][2];
            double wx = v[2][0] - v[0][0], wy = v[2][1] - v[0][1], wz = v[2][2] - v[0][2];
            double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx, nl = Math.sqrt(nx * nx + ny * ny + nz * nz) + 1e-9;
            double light = 0.55 + 0.45 * Math.abs((nx * 0.3 + ny * 0.8 + nz * 0.5) / nl);
            p[6] = depth; proj.add(new double[]{p[0], p[1], p[2], p[3], p[4], p[5], depth, col.get(i), light});
        }
        proj.sort((a, b) -> Double.compare(b[6], a[6]));
        for (double[] p : proj) {
            double[] c = pal.get((int) p[7]);
            g.setColor(new Color((int) (clamp(c[0]) * p[8]), (int) (clamp(c[1]) * p[8]), (int) (clamp(c[2]) * p[8])));
            Path2D path = new Path2D.Double(); path.moveTo(p[0], p[1]); path.lineTo(p[2], p[3]); path.lineTo(p[4], p[5]); path.closePath();
            g.fill(path); g.draw(path);
        }
        g.setColor(Color.BLACK); g.drawString(new String[]{"side: front of car -> right", "top: front of car -> down", "front view", "3/4 view"}[view], 8, 16);
        g.dispose(); ImageIO.write(img, "png", new File(file));
    }
}

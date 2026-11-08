import com.google.gson.*;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.Inflater;

/**
 * Binary FBX (skinned, animated, Unreal/Maya export) -> glTF 2.0 (.gltf + .bin) for SkinToMc.
 * Skeleton = all non-mesh models; skin from Deformer clusters; vertices moved to the bind-pose world space
 * (cluster Transform), so inverse bind = inverse(cluster TransformLink). One primitive per material.
 * Animation stacks whose name contains one of the given filters are sampled at 30 fps (LINEAR).
 * Usage: java -cp gson.jar FbxToGltf.java in.fbx out.gltf [animFilter,animFilter,...]
 */
public class FbxToGltf {
    static final class Node {
        String name; List<Object> props = new ArrayList<>(); List<Node> children = new ArrayList<>();
        Node child(String n) { for (Node c : children) if (c.name.equals(n)) return c; return null; }
        List<Node> all(String n) { List<Node> r = new ArrayList<>(); for (Node c : children) if (c.name.equals(n)) r.add(c); return r; }
    }
    static ByteBuffer buf; static int version;

    static Node readNode() throws Exception {
        long end, numProps;
        if (version >= 7500) { end = buf.getLong(); numProps = buf.getLong(); buf.getLong(); }
        else { end = buf.getInt() & 0xFFFFFFFFL; numProps = buf.getInt() & 0xFFFFFFFFL; buf.getInt(); }
        int nameLen = buf.get() & 0xFF;
        if (end == 0) return null;
        byte[] nb = new byte[nameLen]; buf.get(nb);
        Node n = new Node(); n.name = new String(nb, "ISO-8859-1");
        for (long i = 0; i < numProps; i++) n.props.add(readProp());
        while (buf.position() < end) { Node c = readNode(); if (c == null) break; n.children.add(c); }
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
            case 'S': case 'R': { int len = buf.getInt(); byte[] b = new byte[len]; buf.get(b); return t == 'S' ? new String(b, "ISO-8859-1") : b; }
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

    // ---- 4x4 row-major ----
    static double[] ident() { double[] m = new double[16]; m[0] = m[5] = m[10] = m[15] = 1; return m; }
    static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { double s = 0; for (int k = 0; k < 4; k++) s += a[i * 4 + k] * b[k * 4 + j]; r[i * 4 + j] = s; }
        return r;
    }
    static double[] rot(int axis, double deg) {
        double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r); double[] m = ident();
        if (axis == 0) { m[5] = c; m[6] = -s; m[9] = s; m[10] = c; }
        if (axis == 1) { m[0] = c; m[2] = s; m[8] = -s; m[10] = c; }
        if (axis == 2) { m[0] = c; m[1] = -s; m[4] = s; m[5] = c; }
        return m;
    }
    static double[] euler(double[] e) { return mul(rot(2, e[2]), mul(rot(1, e[1]), rot(0, e[0]))); }   // FBX eEulerXYZ
    static double[] transpose3(double[] m) { double[] r = ident(); for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i * 4 + j] = m[j * 4 + i]; return r; }
    static double[] colMajor(double[] a) { double[] m = new double[16]; for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) m[r * 4 + c] = a[c * 4 + r]; return m; }
    static double[] invert(double[] m) {   // general 4x4 inverse (row-major)
        double[] inv = new double[16], a = m;
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];
        double det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
        for (int i = 0; i < 16; i++) inv[i] /= det;
        return inv;
    }
    static double[] quat(double[] m) {   // rotation part (may contain uniform scale) -> quaternion x,y,z,w
        double sx = Math.sqrt(m[0] * m[0] + m[4] * m[4] + m[8] * m[8]);
        double[] r = new double[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i * 3 + j] = m[i * 4 + j] / (j == 0 ? sx : Math.sqrt(m[j] * m[j] + m[4 + j] * m[4 + j] + m[8 + j] * m[8 + j]));
        double tr = r[0] + r[4] + r[8], x, y, z, w;
        if (tr > 0) { double s = Math.sqrt(tr + 1) * 2; w = s / 4; x = (r[7] - r[5]) / s; y = (r[2] - r[6]) / s; z = (r[3] - r[1]) / s; }
        else if (r[0] > r[4] && r[0] > r[8]) { double s = Math.sqrt(1 + r[0] - r[4] - r[8]) * 2; w = (r[7] - r[5]) / s; x = s / 4; y = (r[1] + r[3]) / s; z = (r[2] + r[6]) / s; }
        else if (r[4] > r[8]) { double s = Math.sqrt(1 + r[4] - r[0] - r[8]) * 2; w = (r[2] - r[6]) / s; x = (r[1] + r[3]) / s; y = s / 4; z = (r[5] + r[7]) / s; }
        else { double s = Math.sqrt(1 + r[8] - r[0] - r[4]) * 2; w = (r[3] - r[1]) / s; x = (r[2] + r[6]) / s; y = (r[5] + r[7]) / s; z = s / 4; }
        double l = Math.sqrt(x * x + y * y + z * z + w * w);
        return new double[]{x / l, y / l, z / l, w / l};
    }

    static final class Model {
        long id; String name, type; Long parent; double[] t, r, s, pre, post, gt, gr, gs;
        Map<String, double[][]> curves = new HashMap<>();   // key: stackId + ":" + T0..S2
        List<Long> children = new ArrayList<>();
    }

    static double[] p70(Node n, String key, double[] def) {
        Node p = n.child("Properties70"); if (p == null) return def;
        for (Node c : p.all("P")) if (key.equals(c.props.get(0))) {
            if (c.props.size() <= 4) return def;
            double[] r = new double[c.props.size() - 4];
            for (int i = 0; i < r.length; i++) { Object o = c.props.get(4 + i); if (!(o instanceof Number)) return def; r[i] = ((Number) o).doubleValue(); }
            return r;
        }
        return def;
    }
    static String clean(Object o) { String s = (String) o; int i = s.indexOf('\0'); return i >= 0 ? s.substring(0, i) : s; }
    static final long TICKS_PER_SEC = 46186158000L;

    static Map<Long, Model> models = new LinkedHashMap<>();
    static Map<Long, double[]> bindPose = new HashMap<>();

    static double eval(double[][] c, double t) {
        double[] ts = c[0], v = c[1];
        if (t <= ts[0]) return v[0];
        if (t >= ts[ts.length - 1]) return v[v.length - 1];
        int lo = 0, hi = ts.length - 1;
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (ts[mid] <= t) lo = mid; else hi = mid; }
        return v[lo] + (v[hi] - v[lo]) * (t - ts[lo]) / (ts[hi] - ts[lo]);
    }
    static double[] channel(Model m, String stack, String ch, double[] def, double t) {
        double[] r = def.clone();
        if (stack != null) for (int i = 0; i < 3; i++) { double[][] c = m.curves.get(stack + ":" + ch + i); if (c != null) r[i] = eval(c, t); }
        return r;
    }
    /** local = T * Rpre * R * Rpost^-1 * S (pivots/offsets assumed zero) */
    static double[][] localTRS(Model m, String stack, double t) {
        double[] tr = channel(m, stack, "T", m.t, t), r = channel(m, stack, "R", m.r, t), s = channel(m, stack, "S", m.s, t);
        double[] rot = mul(euler(m.pre), mul(euler(r), transpose3(euler(m.post))));
        return new double[][]{tr, quat(rot), s};
    }
    static double[] trs(double[] t, double[] q, double[] s) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double[] r = {1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), 2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), 2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)};
        double[] m = ident();
        for (int i = 0; i < 3; i++) { for (int j = 0; j < 3; j++) m[i * 4 + j] = r[i * 3 + j] * s[j]; m[i * 4 + 3] = t[i]; }
        return m;
    }
    static double[] restGlobal(Model m) {
        double[][] l = localTRS(m, null, 0);
        double[] lm = trs(l[0], l[1], l[2]);
        return m.parent == null ? lm : mul(restGlobal(models.get(m.parent)), lm);
    }

    public static void main(String[] args) throws Exception {
        buf = ByteBuffer.wrap(Files.readAllBytes(Paths.get(args[0]))).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(23); version = buf.getInt();
        String[] filters = args.length > 2 ? args[2].split(",") : new String[0];
        List<Node> top = new ArrayList<>();
        while (buf.remaining() > 160) { Node n = readNode(); if (n == null) break; top.add(n); }
        Node objects = null, conns = null;
        for (Node n : top) { if (n.name.equals("Objects")) objects = n; if (n.name.equals("Connections")) conns = n; }

        Map<Long, Node> geoms = new HashMap<>(), clusters = new HashMap<>(), skins = new HashMap<>();
        Map<Long, String> materials = new LinkedHashMap<>(), stacks = new LinkedHashMap<>(), curveNodes = new HashMap<>();
        Set<Long> layers = new HashSet<>();
        Map<Long, double[][]> curves = new HashMap<>();
        for (Node n : objects.children) {
            long id = (Long) n.props.get(0);
            String nm = n.props.size() > 1 ? clean(n.props.get(1)) : "";
            switch (n.name) {
                case "Model" -> {
                    Model m = new Model(); m.id = id; m.name = nm; m.type = clean(n.props.get(2));
                    m.t = p70(n, "Lcl Translation", new double[]{0, 0, 0}); m.r = p70(n, "Lcl Rotation", new double[]{0, 0, 0});
                    m.s = p70(n, "Lcl Scaling", new double[]{1, 1, 1}); m.pre = p70(n, "PreRotation", new double[]{0, 0, 0});
                    m.post = p70(n, "PostRotation", new double[]{0, 0, 0});
                    m.gt = p70(n, "GeometricTranslation", new double[]{0, 0, 0}); m.gr = p70(n, "GeometricRotation", new double[]{0, 0, 0});
                    m.gs = p70(n, "GeometricScaling", new double[]{1, 1, 1});
                    double[] ro = p70(n, "RotationOrder", null);
                    if (ro != null && ro[0] != 0) System.out.println("WARN rotation order " + ro[0] + " on " + nm);
                    models.put(id, m);
                }
                case "Geometry" -> geoms.put(id, n);
                case "Pose" -> {
                    for (Node pn : n.all("PoseNode")) {
                        Node nd = pn.child("Node"), mx = pn.child("Matrix");
                        if (nd != null && mx != null) bindPose.put((Long) nd.props.get(0), colMajor((double[]) mx.props.get(0)));
                    }
                }
                case "Deformer" -> { String k = clean(n.props.get(2)); if (k.equals("Cluster")) clusters.put(id, n); else if (k.equals("Skin")) skins.put(id, n); }
                case "Material" -> materials.put(id, nm);
                case "AnimationStack" -> stacks.put(id, nm);
                case "AnimationLayer" -> layers.add(id);
                case "AnimationCurveNode" -> curveNodes.put(id, nm);
                case "AnimationCurve" -> {
                    long[] kt = (long[]) n.child("KeyTime").props.get(0);
                    double[] kv = (double[]) n.child("KeyValueFloat").props.get(0);
                    double[] ts = new double[kt.length]; for (int i = 0; i < kt.length; i++) ts[i] = kt[i] / (double) TICKS_PER_SEC;
                    curves.put(id, new double[][]{ts, kv});
                }
                default -> { }
            }
        }
        Map<Long, Long> geomOfModel = new HashMap<>(), skinOfGeom = new HashMap<>(), clusterBone = new HashMap<>(), layerStack = new HashMap<>(), cnLayer = new HashMap<>();
        Map<Long, List<Long>> skinClusters = new HashMap<>(), modelMats = new HashMap<>();
        Map<Long, Object[]> cnTarget = new HashMap<>();
        List<Object[]> curveLinks = new ArrayList<>();
        for (Node c : conns.all("C")) {
            String type = clean(c.props.get(0)); long ch = (Long) c.props.get(1), pa = (Long) c.props.get(2);
            if (type.equals("OO")) {
                if (models.containsKey(ch) && models.containsKey(pa)) { models.get(ch).parent = pa; models.get(pa).children.add(ch); }
                else if (geoms.containsKey(ch) && models.containsKey(pa)) geomOfModel.put(pa, ch);
                else if (materials.containsKey(ch) && models.containsKey(pa)) modelMats.computeIfAbsent(pa, k -> new ArrayList<>()).add(ch);
                else if (skins.containsKey(ch) && geoms.containsKey(pa)) skinOfGeom.put(pa, ch);
                else if (clusters.containsKey(ch) && skins.containsKey(pa)) skinClusters.computeIfAbsent(pa, k -> new ArrayList<>()).add(ch);
                else if (models.containsKey(ch) && clusters.containsKey(pa)) clusterBone.put(pa, ch);
                else if (layers.contains(ch) && stacks.containsKey(pa)) layerStack.put(ch, pa);
                else if (curveNodes.containsKey(ch) && layers.contains(pa)) cnLayer.put(ch, pa);
            } else if (type.equals("OP")) {
                String prop = clean(c.props.get(3));
                if (curveNodes.containsKey(ch) && models.containsKey(pa)) cnTarget.put(ch, new Object[]{pa, prop});
                else if (curves.containsKey(ch) && curveNodes.containsKey(pa)) curveLinks.add(new Object[]{ch, pa, prop});
            }
        }
        // curves -> model channels, per stack
        Map<Long, double[]> stackRange = new HashMap<>();
        for (Object[] l : curveLinks) {
            long cn = (Long) l[1]; Object[] tg = cnTarget.get(cn); Long layer = cnLayer.get(cn);
            if (tg == null || layer == null) continue;
            Long stack = layerStack.get(layer); if (stack == null) continue;
            String prop = (String) tg[1];
            String ch = prop.equals("Lcl Translation") ? "T" : prop.equals("Lcl Rotation") ? "R" : prop.equals("Lcl Scaling") ? "S" : null;
            if (ch == null) continue;
            String ax = (String) l[2]; int ai = ax.endsWith("X") ? 0 : ax.endsWith("Y") ? 1 : 2;
            double[][] cv = curves.get((Long) l[0]);
            models.get((Long) tg[0]).curves.put(stack + ":" + ch + ai, cv);
            double[] rg = stackRange.computeIfAbsent(stack, k -> new double[]{1e18, -1e18});
            rg[0] = Math.min(rg[0], cv[0][0]); rg[1] = Math.max(rg[1], cv[0][cv[0].length - 1]);
        }

        // ---- skeleton: every non-mesh model, parents first ----
        List<Model> joints = new ArrayList<>();
        for (Model m : models.values()) if (m.parent == null) collect(m, joints);
        Map<Long, Integer> jointIndex = new HashMap<>();
        for (int i = 0; i < joints.size(); i++) jointIndex.put(joints.get(i).id, i);
        double[][] ibm = new double[joints.size()][];
        for (int i = 0; i < joints.size(); i++) ibm[i] = invert(restGlobal(joints.get(i)));

        // ---- meshes ----
        List<double[]> P = new ArrayList<>(), N = new ArrayList<>(), UV = new ArrayList<>();
        List<int[]> J = new ArrayList<>(); List<double[]> W = new ArrayList<>();
        List<String> matNames = new ArrayList<>();
        Map<Integer, List<Integer>> trisByMat = new TreeMap<>();
        for (Model mm : models.values()) {
            Long gid = geomOfModel.get(mm.id); if (gid == null) continue;
            Node g = geoms.get(gid);
            double[] cp = (double[]) g.child("Vertices").props.get(0);
            int[] pvi = (int[]) g.child("PolygonVertexIndex").props.get(0);
            int ncp = cp.length / 3;
            // skin weights per control point (top 4)
            List<List<double[]>> infl = new ArrayList<>(); for (int i = 0; i < ncp; i++) infl.add(new ArrayList<>());
            double[] meshBind = null;
            Long sk = skinOfGeom.get(gid);
            if (sk != null) for (long cid : skinClusters.getOrDefault(sk, List.of())) {
                Node cl = clusters.get(cid); Long bone = clusterBone.get(cid);
                if (bone == null || !jointIndex.containsKey(bone) || cl.child("Indexes") == null) continue;
                int ji = jointIndex.get(bone);
                if (System.getProperty("dbg") != null && meshBind == null) {
                    double[] tl = colMajor((double[]) cl.child("TransformLink").props.get(0)), tf = colMajor((double[]) cl.child("Transform").props.get(0));
                    double[] rg = restGlobal(models.get(bone));
                    double[] mb = restGlobal(mm);
                    System.out.println("bone " + models.get(bone).name + "\n TransformLink " + Arrays.toString(tl) + "\n restGlobal    " + Arrays.toString(rg)
                            + "\n Transform     " + Arrays.toString(tf) + "\n meshGlobal    " + Arrays.toString(mb)
                            + "\n bindPose bone " + Arrays.toString(bindPose.get(bone)) + "\n bindPose mesh " + Arrays.toString(bindPose.get(mm.id)));
                    double[] mn = {1e18, 1e18, 1e18}, mx = {-1e18, -1e18, -1e18};
                    for (int i = 0; i < cp.length; i++) { mn[i % 3] = Math.min(mn[i % 3], cp[i]); mx[i % 3] = Math.max(mx[i % 3], cp[i]); }
                    System.out.println(" cp bounds " + Arrays.toString(mn) + " " + Arrays.toString(mx));
                }
                ibm[ji] = invert(colMajor((double[]) cl.child("TransformLink").props.get(0)));
                // mesh bind matrix: the BindPose entry (same space as TransformLink); the cluster Transform is in a
                // different space in some exports (Blender/Unreal: missing the 100x unit scale)
                if (meshBind == null) meshBind = bindPose.containsKey(mm.id) ? bindPose.get(mm.id) : colMajor((double[]) cl.child("Transform").props.get(0));
                int[] ix = (int[]) cl.child("Indexes").props.get(0); double[] w = (double[]) cl.child("Weights").props.get(0);
                for (int k = 0; k < ix.length; k++) infl.get(ix[k]).add(new double[]{ji, w[k]});
            }
            if (meshBind == null) meshBind = mul(restGlobal(mm), mul(trs(mm.gt, quat(euler(mm.gr)), mm.gs), ident()));
            int rigid = mm.parent != null && jointIndex.containsKey(mm.parent) ? jointIndex.get(mm.parent) : 0;
            // layers
            Node ln = g.child("LayerElementNormal"), lu = g.child("LayerElementUV"), lm = g.child("LayerElementMaterial");
            double[] nrm = ln == null ? null : (double[]) ln.child("Normals").props.get(0);
            int[] nIdx = ln != null && ln.child("NormalsIndex") != null ? (int[]) ln.child("NormalsIndex").props.get(0) : null;
            boolean nByCp = ln != null && clean(ln.child("MappingInformationType").props.get(0)).startsWith("ByVert") || ln != null && clean(ln.child("MappingInformationType").props.get(0)).equals("ByControlPoint");
            double[] uvs = lu == null ? null : (double[]) lu.child("UV").props.get(0);
            int[] uvIdx = lu != null && lu.child("UVIndex") != null ? (int[]) lu.child("UVIndex").props.get(0) : null;
            boolean uvByCp = lu != null && !clean(lu.child("MappingInformationType").props.get(0)).equals("ByPolygonVertex");
            int[] mats = lm == null ? null : (int[]) lm.child("Materials").props.get(0);
            boolean allSame = lm == null || clean(lm.child("MappingInformationType").props.get(0)).equals("AllSame");
            List<Long> mlist = modelMats.getOrDefault(mm.id, List.of());
            Map<String, Integer> weld = new HashMap<>();
            int poly = 0, start = 0;
            for (int i = 0; i < pvi.length; i++) {
                if (pvi[i] >= 0) continue;
                int n = i - start + 1;
                int matLocal = mats == null ? 0 : allSame ? mats[0] : mats[Math.min(poly, mats.length - 1)];
                String matName = matLocal < mlist.size() ? materials.get(mlist.get(matLocal)) : "default";
                int mi = matNames.indexOf(matName); if (mi < 0) { mi = matNames.size(); matNames.add(matName); }
                int[] vids = new int[n];
                for (int k = 0; k < n; k++) {
                    int pvIndex = start + k, c = pvi[pvIndex] < 0 ? ~pvi[pvIndex] : pvi[pvIndex];
                    int ni = nByCp ? c : pvIndex; if (nIdx != null) ni = nIdx[ni];
                    int ui = uvByCp ? c : pvIndex; if (uvIdx != null) ui = uvIdx[ui];
                    double[] p = apply(meshBind, cp[c * 3], cp[c * 3 + 1], cp[c * 3 + 2]);
                    double[] nn = nrm == null ? new double[]{0, 1, 0} : applyDir(meshBind, nrm[ni * 3], nrm[ni * 3 + 1], nrm[ni * 3 + 2]);
                    double u = uvs == null ? 0 : uvs[ui * 2], v = uvs == null ? 0 : 1 - uvs[ui * 2 + 1];
                    String key = c + "|" + Math.round(nn[0] * 1000) + "," + Math.round(nn[1] * 1000) + "," + Math.round(nn[2] * 1000) + "|" + Math.round(u * 1e5) + "," + Math.round(v * 1e5);
                    Integer vi = weld.get(key);
                    if (vi == null) {
                        vi = P.size(); weld.put(key, vi);
                        P.add(p); N.add(nn); UV.add(new double[]{u, v});
                        List<double[]> inf = new ArrayList<>(infl.get(c));
                        inf.sort((a, b) -> Double.compare(b[1], a[1]));
                        int[] jj = new int[4]; double[] ww = new double[4]; double sum = 0;
                        for (int q = 0; q < Math.min(4, inf.size()); q++) { jj[q] = (int) inf.get(q)[0]; ww[q] = inf.get(q)[1]; sum += ww[q]; }
                        if (sum <= 0) { jj[0] = rigid; ww[0] = 1; sum = 1; }
                        for (int q = 0; q < 4; q++) ww[q] /= sum;
                        J.add(jj); W.add(ww);
                    }
                    vids[k] = vi;
                }
                List<Integer> tl = trisByMat.computeIfAbsent(mi, k -> new ArrayList<>());
                for (int k = 1; k + 1 < n; k++) { tl.add(vids[0]); tl.add(vids[k]); tl.add(vids[k + 1]); }
                poly++; start = i + 1;
            }
        }
        System.out.println("joints " + joints.size() + ", vertices " + P.size() + ", materials " + matNames);

        // ---- write glTF ----
        ByteArrayOutputStream bin = new ByteArrayOutputStream();
        JsonArray accessors = new JsonArray(), views = new JsonArray();
        JsonObject root = new JsonObject();
        JsonObject asset = new JsonObject(); asset.addProperty("version", "2.0"); root.add("asset", asset);

        JsonArray nodes = new JsonArray();
        for (Model m : joints) {
            JsonObject n = new JsonObject(); n.addProperty("name", m.name);
            double[][] l = localTRS(m, null, 0);
            n.add("translation", arr(l[0])); n.add("rotation", arr(l[1])); n.add("scale", arr(l[2]));
            JsonArray ch = new JsonArray();
            for (long c : m.children) if (jointIndex.containsKey(c)) ch.add(jointIndex.get(c));
            if (ch.size() > 0) n.add("children", ch);
            nodes.add(n);
        }
        int meshNode = nodes.size();
        JsonObject mn = new JsonObject(); mn.addProperty("name", "mesh"); mn.addProperty("mesh", 0); mn.addProperty("skin", 0); nodes.add(mn);
        root.add("nodes", nodes);
        JsonArray sceneNodes = new JsonArray();
        for (int i = 0; i < joints.size(); i++) if (joints.get(i).parent == null || !jointIndex.containsKey(joints.get(i).parent)) sceneNodes.add(i);
        sceneNodes.add(meshNode);
        JsonObject scene = new JsonObject(); scene.add("nodes", sceneNodes); JsonArray scenes = new JsonArray(); scenes.add(scene); root.add("scenes", scenes);

        int aPos = floatAcc(bin, views, accessors, P, "VEC3", true), aNrm = floatAcc(bin, views, accessors, N, "VEC3", false), aUv = floatAcc(bin, views, accessors, UV, "VEC2", false);
        int aW = floatAcc(bin, views, accessors, W, "VEC4", false);
        int aJ;
        { int off = align(bin); for (int[] j : J) for (int v : j) writeShort(bin, v); aJ = acc(views, accessors, off, bin.size() - off, 5123, J.size(), "VEC4"); }
        JsonArray prims = new JsonArray(), mats = new JsonArray();
        for (Map.Entry<Integer, List<Integer>> e : trisByMat.entrySet()) {
            int off = align(bin); for (int v : e.getValue()) writeInt(bin, v);
            int aI = acc(views, accessors, off, bin.size() - off, 5125, e.getValue().size(), "SCALAR");
            JsonObject p = new JsonObject(), at = new JsonObject();
            at.addProperty("POSITION", aPos); at.addProperty("NORMAL", aNrm); at.addProperty("TEXCOORD_0", aUv); at.addProperty("JOINTS_0", aJ); at.addProperty("WEIGHTS_0", aW);
            p.add("attributes", at); p.addProperty("indices", aI); p.addProperty("material", e.getKey()); p.addProperty("mode", 4);
            prims.add(p);
        }
        for (String m : matNames) { JsonObject o = new JsonObject(); o.addProperty("name", m); mats.add(o); }
        root.add("materials", mats);
        JsonObject mesh = new JsonObject(); mesh.add("primitives", prims); JsonArray meshes = new JsonArray(); meshes.add(mesh); root.add("meshes", meshes);
        { int off = align(bin); for (double[] m : ibm) for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++) writeFloat(bin, m[r * 4 + c]);
            int aIbm = acc(views, accessors, off, bin.size() - off, 5126, ibm.length, "MAT4");
            JsonObject skin = new JsonObject(); JsonArray jj = new JsonArray(); for (int i = 0; i < joints.size(); i++) jj.add(i);
            skin.add("joints", jj); skin.addProperty("inverseBindMatrices", aIbm); JsonArray skinsA = new JsonArray(); skinsA.add(skin); root.add("skins", skinsA); }

        // ---- animations ----
        JsonArray anims = new JsonArray();
        for (Map.Entry<Long, String> st : stacks.entrySet()) {
            String sn = st.getValue();
            boolean want = filters.length == 0;
            for (String f : filters) if (!f.isEmpty() && sn.contains(f)) want = true;
            double[] rg = stackRange.get(st.getKey());
            if (!want || rg == null) continue;
            String sk = String.valueOf(st.getKey());
            int frames = Math.max(2, (int) Math.round((rg[1] - rg[0]) * 30) + 1);
            int offT = align(bin);
            for (int f = 0; f < frames; f++) writeFloat(bin, (float) ((rg[1] - rg[0]) * f / (frames - 1)));
            int aT = acc(views, accessors, offT, bin.size() - offT, 5126, frames, "SCALAR");
            accessors.get(aT).getAsJsonObject().add("min", arr(new double[]{0})); accessors.get(aT).getAsJsonObject().add("max", arr(new double[]{rg[1] - rg[0]}));
            JsonArray samplers = new JsonArray(), channels = new JsonArray();
            for (int ji = 0; ji < joints.size(); ji++) {
                Model m = joints.get(ji);
                boolean has = false; for (String k : m.curves.keySet()) if (k.startsWith(sk + ":")) { has = true; break; }
                if (!has) continue;
                double[][][] smp = new double[frames][][];
                for (int f = 0; f < frames; f++) smp[f] = localTRS(m, sk, rg[0] + (rg[1] - rg[0]) * f / (frames - 1));
                for (int c = 0; c < 3; c++) {
                    int off = align(bin);
                    double[] prev = null;
                    for (int f = 0; f < frames; f++) {
                        double[] v = smp[f][c];
                        if (c == 1 && prev != null && v[0] * prev[0] + v[1] * prev[1] + v[2] * prev[2] + v[3] * prev[3] < 0) v = new double[]{-v[0], -v[1], -v[2], -v[3]};
                        for (double d : v) writeFloat(bin, (float) d);
                        prev = v;
                    }
                    int aO = acc(views, accessors, off, bin.size() - off, 5126, frames, c == 1 ? "VEC4" : "VEC3");
                    JsonObject s = new JsonObject(); s.addProperty("input", aT); s.addProperty("output", aO); s.addProperty("interpolation", "LINEAR");
                    samplers.add(s);
                    JsonObject chn = new JsonObject(), tgt = new JsonObject();
                    tgt.addProperty("node", ji); tgt.addProperty("path", c == 0 ? "translation" : c == 1 ? "rotation" : "scale");
                    chn.addProperty("sampler", samplers.size() - 1); chn.add("target", tgt); channels.add(chn);
                }
            }
            JsonObject a = new JsonObject(); a.addProperty("name", sn); a.add("samplers", samplers); a.add("channels", channels); anims.add(a);
            System.out.printf(Locale.ROOT, "anim %-60s %.2fs %d frames%n", sn, rg[1] - rg[0], frames);
        }
        root.add("animations", anims);
        root.add("accessors", accessors); root.add("bufferViews", views);
        Path out = Paths.get(args[1]);
        String binName = out.getFileName().toString().replace(".gltf", ".bin");
        JsonObject b = new JsonObject(); b.addProperty("byteLength", bin.size()); b.addProperty("uri", binName);
        JsonArray bufs = new JsonArray(); bufs.add(b); root.add("buffers", bufs);
        Files.write(out.resolveSibling(binName), bin.toByteArray());
        Files.writeString(out, new Gson().toJson(root));
        System.out.println("written " + out + " (" + bin.size() + " bytes binary)");
    }

    static void collect(Model m, List<Model> out) {
        if (m.type.equals("Mesh")) return;
        out.add(m);
        for (long c : m.children) collect(models.get(c), out);
    }
    static double[] apply(double[] m, double x, double y, double z) { return new double[]{m[0] * x + m[1] * y + m[2] * z + m[3], m[4] * x + m[5] * y + m[6] * z + m[7], m[8] * x + m[9] * y + m[10] * z + m[11]}; }
    static double[] applyDir(double[] m, double x, double y, double z) {
        double[] r = {m[0] * x + m[1] * y + m[2] * z, m[4] * x + m[5] * y + m[6] * z, m[8] * x + m[9] * y + m[10] * z};
        double l = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]); if (l < 1e-12) return new double[]{0, 1, 0};
        return new double[]{r[0] / l, r[1] / l, r[2] / l};
    }
    static JsonArray arr(double[] v) { JsonArray a = new JsonArray(); for (double d : v) a.add(d); return a; }
    static int align(ByteArrayOutputStream b) { while (b.size() % 4 != 0) b.write(0); return b.size(); }
    static void writeFloat(ByteArrayOutputStream b, double d) { float f = (float) d; writeInt(b, Float.floatToIntBits(f)); }
    static void writeInt(ByteArrayOutputStream b, int v) { b.write(v); b.write(v >> 8); b.write(v >> 16); b.write(v >> 24); }
    static void writeShort(ByteArrayOutputStream b, int v) { b.write(v); b.write(v >> 8); }
    static int acc(JsonArray views, JsonArray accessors, int off, int len, int ct, int count, String type) {
        JsonObject v = new JsonObject(); v.addProperty("buffer", 0); v.addProperty("byteOffset", off); v.addProperty("byteLength", len); views.add(v);
        JsonObject a = new JsonObject(); a.addProperty("bufferView", views.size() - 1); a.addProperty("componentType", ct); a.addProperty("count", count); a.addProperty("type", type);
        accessors.add(a); return accessors.size() - 1;
    }
    static int floatAcc(ByteArrayOutputStream bin, JsonArray views, JsonArray accessors, List<double[]> data, String type, boolean minmax) {
        int off = align(bin); int n = data.get(0).length;
        double[] mn = new double[n], mx = new double[n]; Arrays.fill(mn, 1e18); Arrays.fill(mx, -1e18);
        for (double[] d : data) for (int i = 0; i < n; i++) { writeFloat(bin, (float) d[i]); mn[i] = Math.min(mn[i], d[i]); mx[i] = Math.max(mx[i], d[i]); }
        int a = acc(views, accessors, off, bin.size() - off, 5126, data.size(), type);
        if (minmax) { accessors.get(a).getAsJsonObject().add("min", arr(mn)); accessors.get(a).getAsJsonObject().add("max", arr(mx)); }
        return a;
    }
}

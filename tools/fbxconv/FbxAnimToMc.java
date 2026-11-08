import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.Inflater;

/**
 * Converts a skeletal (Mixamo-style) binary FBX animation into a compact pose track for the
 * vanilla Minecraft player model (head, body, 2 arms, 2 legs).
 * Usage: java FbxAnimToMc.java input.fbx output.anim [fps]
 *
 * Output format (text):
 *   KICKANIM 1
 *   fps frames impactFrame kickLeg(R|L)
 *   per frame: rootX rootY rootZ qx qy qz qw headX headY headZ rArmX rArmZ lArmX lArmZ rLegX rLegZ lLegX lLegZ
 * root* = waist position in entity-local render space (blocks, y up, -z forward),
 * q = torso rotation in the same space (applied around the waist),
 * limb angles = ModelPart xRot/zRot (radians), head = xRot/yRot/zRot relative to torso.
 */
public class FbxAnimToMc {
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

    // ---- 4x4 row-major matrices, column vectors ----
    static double[] ident() { double[] m = new double[16]; m[0] = m[5] = m[10] = m[15] = 1; return m; }
    static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { double s = 0; for (int k = 0; k < 4; k++) s += a[i * 4 + k] * b[k * 4 + j]; r[i * 4 + j] = s; }
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
    static double[] euler(double[] e) { return mul(rot(2, e[2]), mul(rot(1, e[1]), rot(0, e[0]))); } // FBX eEulerXYZ
    static double[] pos(double[] m) { return new double[]{m[3], m[7], m[11]}; }

    // ---- 3-vectors / 3x3 ----
    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    static double[] sc(double[] a, double s) { return new double[]{a[0] * s, a[1] * s, a[2] * s}; }
    static double[] norm(double[] a) { double l = Math.sqrt(dot(a, a)); return l < 1e-9 ? new double[]{0, 1, 0} : sc(a, 1 / l); }
    /** 3x3 from columns. m[r][c] */
    static double[][] cols(double[] x, double[] y, double[] z) { return new double[][]{{x[0], y[0], z[0]}, {x[1], y[1], z[1]}, {x[2], y[2], z[2]}}; }
    static double[][] tr(double[][] m) { double[][] r = new double[3][3]; for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i][j] = m[j][i]; return r; }
    static double[][] m3(double[][] a, double[][] b) { double[][] r = new double[3][3]; for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) r[i][j] += a[i][k] * b[k][j]; return r; }
    static double[] mv(double[][] m, double[] v) { return new double[]{dot(m[0], v), dot(m[1], v), dot(m[2], v)}; }
    static double[][] rot3(double[] m4) { // orthonormalised rotation part of a 4x4
        double[] x = norm(new double[]{m4[0], m4[4], m4[8]}), y = new double[]{m4[1], m4[5], m4[9]};
        double[] z = norm(cross(x, y)); y = cross(z, x);
        return cols(x, y, z);
    }

    static final class Model { long id; String name; Long parent; double[] t, r, s, pre, post; Map<String, double[][]> curves = new HashMap<>(); }

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
    static String shortName(String n) { int i = n.lastIndexOf(':'); return i >= 0 ? n.substring(i + 1) : n; }

    static final long TICKS_PER_SEC = 46186158000L;

    static double evalCurve(double[][] c, double tSec) { // c[0]=times(sec), c[1]=values
        double[] t = c[0], v = c[1];
        if (t.length == 0) return 0;
        if (tSec <= t[0]) return v[0];
        if (tSec >= t[t.length - 1]) return v[v.length - 1];
        int lo = 0, hi = t.length - 1;
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (t[mid] <= tSec) lo = mid; else hi = mid; }
        double a = (tSec - t[lo]) / (t[hi] - t[lo]);
        return v[lo] + (v[hi] - v[lo]) * a;
    }

    static Map<Long, Model> models = new LinkedHashMap<>();
    static Map<String, Model> byName = new HashMap<>();

    static double[] channel(Model m, String ch, double[] def, double tSec) {
        double[] r = def.clone();
        for (int i = 0; i < 3; i++) { double[][] c = m.curves.get(ch + i); if (c != null) r[i] = evalCurve(c, tSec); }
        return r;
    }
    static double[] world(Model m, double tSec, Map<Long, double[]> cache) {
        double[] w = cache.get(m.id); if (w != null) return w;
        double[] t = channel(m, "T", m.t, tSec), r = channel(m, "R", m.r, tSec), s = channel(m, "S", m.s, tSec);
        double[] local = mul(trans(t), mul(euler(m.pre), mul(euler(r), mul(invRot(euler(m.post)), scale(s)))));
        Model p = m.parent == null ? null : models.get(m.parent);
        w = p == null ? local : mul(world(p, tSec, cache), local);
        cache.put(m.id, w);
        return w;
    }
    static double[] invRot(double[] m) { double[] r = ident(); for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i * 4 + j] = m[j * 4 + i]; return r; }

    static Model bone(String... names) {
        for (String n : names) { Model m = byName.get(n); if (m != null) return m; }
        throw new IllegalStateException("Bone not found: " + Arrays.toString(names) + " have " + byName.keySet());
    }

    public static void main(String[] args) throws Exception {
        byte[] all = Files.readAllBytes(Paths.get(args[0]));
        double fps = args.length > 2 ? Double.parseDouble(args[2]) : 30;
        buf = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(23); version = buf.getInt();
        List<Node> top = new ArrayList<>();
        while (buf.remaining() > 160) { Node n = readNode(); if (n == null) break; top.add(n); }
        Node objects = null, conns = null, global = null;
        for (Node n : top) { if (n.name.equals("Objects")) objects = n; if (n.name.equals("Connections")) conns = n; if (n.name.equals("GlobalSettings")) global = n; }
        System.out.println("FBX version " + version);
        if (global != null) for (String k : new String[]{"UpAxis", "UpAxisSign", "FrontAxis", "FrontAxisSign", "CoordAxis", "CoordAxisSign", "UnitScaleFactor", "TimeMode", "CustomFrameRate"})
            System.out.println("  " + k + " = " + Arrays.toString(p70(global, k, null)));

        Map<Long, String> curveNodeKind = new HashMap<>();
        Map<Long, double[][]> curves = new HashMap<>();
        for (Node n : objects.children) {
            long id = (Long) n.props.get(0);
            if (n.name.equals("Model")) {
                Model m = new Model(); m.id = id; m.name = shortName(clean(n.props.get(1)));
                m.t = p70(n, "Lcl Translation", new double[]{0, 0, 0});
                m.r = p70(n, "Lcl Rotation", new double[]{0, 0, 0});
                m.s = p70(n, "Lcl Scaling", new double[]{1, 1, 1});
                m.pre = p70(n, "PreRotation", new double[]{0, 0, 0});
                m.post = p70(n, "PostRotation", new double[]{0, 0, 0});
                double[] ro = p70(n, "RotationOrder", null);
                if (ro != null && ro[0] != 0) System.out.println("  WARN rotation order " + ro[0] + " on " + m.name);
                for (String k : new String[]{"RotationPivot", "RotationOffset", "ScalingPivot", "ScalingOffset"}) {
                    double[] v = p70(n, k, null);
                    if (v != null && (Math.abs(v[0]) + Math.abs(v[1]) + Math.abs(v[2])) > 1e-6) System.out.println("  WARN " + k + " on " + m.name + " " + Arrays.toString(v));
                }
                models.put(id, m); byName.put(m.name, m);
            } else if (n.name.equals("AnimationCurveNode")) {
                curveNodeKind.put(id, clean(n.props.get(1)));
            } else if (n.name.equals("AnimationCurve")) {
                long[] kt = (long[]) n.child("KeyTime").props.get(0);
                double[] kv = (double[]) n.child("KeyValueFloat").props.get(0);
                double[] ts = new double[kt.length]; for (int i = 0; i < kt.length; i++) ts[i] = kt[i] / (double) TICKS_PER_SEC;
                curves.put(id, new double[][]{ts, kv});
            }
        }
        Map<Long, Long> curveNodeModel = new HashMap<>();
        Map<Long, String> curveNodeProp = new HashMap<>();
        List<Object[]> curveLinks = new ArrayList<>();
        for (Node c : conns.all("C")) {
            String type = clean(c.props.get(0)); long child = (Long) c.props.get(1), parent = (Long) c.props.get(2);
            if (type.equals("OO") && models.containsKey(child) && models.containsKey(parent)) models.get(child).parent = parent;
            if (type.equals("OP") && curveNodeKind.containsKey(child) && models.containsKey(parent)) { curveNodeModel.put(child, parent); curveNodeProp.put(child, clean(c.props.get(3))); }
            if (type.equals("OP") && curves.containsKey(child) && curveNodeKind.containsKey(parent)) curveLinks.add(new Object[]{child, parent, clean(c.props.get(3))});
        }
        double tMin = Double.MAX_VALUE, tMax = -Double.MAX_VALUE;
        for (Object[] l : curveLinks) {
            Long cn = (Long) l[1]; Long mid = curveNodeModel.get(cn); if (mid == null) continue;
            String prop = curveNodeProp.get(cn);
            String ch = prop.equals("Lcl Translation") ? "T" : prop.equals("Lcl Rotation") ? "R" : prop.equals("Lcl Scaling") ? "S" : null;
            if (ch == null) continue;
            String axis = (String) l[2]; int ai = axis.endsWith("X") ? 0 : axis.endsWith("Y") ? 1 : 2;
            double[][] cv = curves.get((Long) l[0]);
            models.get(mid).curves.put(ch + ai, cv);
            if (cv[0].length > 1) { tMin = Math.min(tMin, cv[0][0]); tMax = Math.max(tMax, cv[0][cv[0].length - 1]); }
        }
        System.out.println("Models: " + models.size() + ", animated time " + tMin + " .. " + tMax + " s");
        int frames = (int) Math.floor((tMax - tMin) * fps + 1e-6) + 1;

        Model hips = bone("Hips"), neck = bone("Neck", "Spine2"), head = bone("Head");
        Model lArm = bone("LeftArm"), lHand = bone("LeftHand"), rArm = bone("RightArm"), rHand = bone("RightHand");
        Model lUp = bone("LeftUpLeg"), lFoot = bone("LeftFoot"), rUp = bone("RightUpLeg"), rFoot = bone("RightFoot");

        // Reference character frame from the first frame: x = character left, y = up, z = forward.
        Map<Long, double[]> c0 = new HashMap<>();
        double[] h0 = pos(world(hips, tMin, c0)), n0 = pos(world(neck, tMin, c0));
        double[] up = norm(sub(n0, h0));
        double[] left = norm(sub(pos(world(lUp, tMin, c0)), pos(world(rUp, tMin, c0))));
        left = norm(sub(left, sc(up, dot(left, up))));
        double[] fwd = cross(left, up);
        // keep the reference frame upright (world up), just take the facing direction
        double[] worldUp = up; // FBX scenes here are Y-up; derive from global settings if needed
        double[][] F = tr(cols(left, up, fwd)); // world -> character coords
        double footY0 = Math.min(mv(F, pos(world(lFoot, tMin, c0)))[1], mv(F, pos(world(rFoot, tMin, c0)))[1]);
        double[] H0 = mv(F, h0);
        double hipHeight = H0[1] - footY0;
        // ankle joint is ~ 1/12 of hip height above the ground; MC waist pivot is at 0.75 blocks
        double unit = 0.75 / (hipHeight * 1.06);
        System.out.printf("hip height %.3f (scene units), unit scale %.5f%n", hipHeight, unit);

        double[][] out = new double[frames][];
        double maxR = -1e9, maxL = -1e9; int impR = 0, impL = 0;
        double[][] headRest = null;
        double minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
        for (int f = 0; f < frames; f++) {
            double t = tMin + f / fps;
            Map<Long, double[]> c = new HashMap<>();
            double[] H = mv(F, pos(world(hips, t, c)));
            double[] N = mv(F, pos(world(neck, t, c)));
            double[] LA = mv(F, pos(world(lArm, t, c))), RA = mv(F, pos(world(rArm, t, c)));
            double[] LH = mv(F, pos(world(lHand, t, c))), RH = mv(F, pos(world(rHand, t, c)));
            double[] LU = mv(F, pos(world(lUp, t, c))), RU = mv(F, pos(world(rUp, t, c)));
            double[] LF = mv(F, pos(world(lFoot, t, c))), RF = mv(F, pos(world(rFoot, t, c)));
            // torso frame (character coords)
            double[] tu = norm(sub(N, H));
            double[] tl = norm(sub(LA, RA)); tl = norm(sub(tl, sc(tu, dot(tl, tu))));
            double[] tf = cross(tl, tu);
            double[][] T = cols(tl, tu, tf);   // torso -> character
            double[][] Tt = tr(T);
            // head rotation relative to torso
            double[][] Hw = m3(F, rot3(world(head, t, c)));
            double[][] headRel = m3(Tt, Hw);
            if (headRest == null) headRest = headRel;
            double[][] hr = m3(headRel, tr(headRest)); // character-torso coords
            // limb directions in torso coords
            double[] dRA = mv(Tt, norm(sub(RH, RA))), dLA = mv(Tt, norm(sub(LH, LA)));
            double[] dRL = mv(Tt, norm(sub(RF, RU))), dLL = mv(Tt, norm(sub(LF, LU)));

            // root: waist position in entity-local render space (x = -left, y = up, z = -forward)
            double rx = -(H[0] - H0[0]) * unit, ry = 0.75 + (H[1] - H0[1]) * unit, rz = -(H[2] - H0[2]) * unit;
            minX = Math.min(minX, rx); maxX = Math.max(maxX, rx); minZ = Math.min(minZ, rz); maxZ = Math.max(maxZ, rz);
            // torso rotation in local space: M T M^-1, M = diag(-1,1,-1)
            double[][] M = {{-1, 0, 0}, {0, 1, 0}, {0, 0, -1}};
            double[] q = quat(m3(M, m3(T, M)));
            // head: model space P = diag(1,-1,-1)
            double[][] P = {{1, 0, 0}, {0, -1, 0}, {0, 0, -1}};
            double[] he = zyx(m3(P, m3(hr, P)));
            double[] ra = limb(dRA), la = limb(dLA), rl = limb(dRL), ll = limb(dLL);
            out[f] = new double[]{rx, ry, rz, q[0], q[1], q[2], q[3], he[0], he[1], he[2], ra[0], ra[1], la[0], la[1], rl[0], rl[1], ll[0], ll[1]};
            // kick reach: foot forward of hips, in character coords
            double reachR = RF[2] - H[2], reachL = LF[2] - H[2];
            if (reachR > maxR) { maxR = reachR; impR = f; }
            if (reachL > maxL) { maxL = reachL; impL = f; }
        }
        boolean right = maxR >= maxL;
        int impact = right ? impR : impL;
        System.out.printf("frames %d @ %.0f fps (%.2f s); kick leg %s, impact frame %d (%.2f s), reach %.2f blocks%n",
                frames, fps, (frames - 1) / fps, right ? "RIGHT" : "LEFT", impact, impact / fps, (right ? maxR : maxL) * unit);
        System.out.printf("root drift x[%.2f..%.2f] z[%.2f..%.2f] blocks%n", minX, maxX, minZ, maxZ);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Paths.get(args[1])))) {
            w.println("KICKANIM 1");
            w.println((int) fps + " " + frames + " " + impact + " " + (right ? "R" : "L"));
            for (double[] r : out) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < r.length; i++) { if (i > 0) sb.append(' '); sb.append(String.format(Locale.ROOT, "%.4f", r[i])); }
                w.println(sb);
            }
        }
        for (int f = 0; f < frames; f += Math.max(1, frames / 8)) System.out.println("  f" + f + " " + fmt(out[f]));
    }

    static String fmt(double[] r) { StringBuilder sb = new StringBuilder(); for (double v : r) sb.append(String.format(Locale.ROOT, "%6.2f", v)); return sb.toString(); }

    /** direction in torso coords (left, up, forward) -> ModelPart xRot, zRot for a limb hanging along +Y. */
    static double[] limb(double[] d) {
        double x = d[0], y = -d[1], z = -d[2]; // model space
        double a = Math.asin(Math.max(-1, Math.min(1, z)));
        double cz = Math.atan2(-x, y);
        return new double[]{a, cz};
    }
    /** R = Rz(c) Ry(b) Rx(a) -> (a, b, c) */
    static double[] zyx(double[][] R) {
        double b = Math.asin(Math.max(-1, Math.min(1, -R[2][0])));
        double a = Math.atan2(R[2][1], R[2][2]);
        double c = Math.atan2(R[1][0], R[0][0]);
        return new double[]{a, b, c};
    }
    static double[] quat(double[][] m) {
        double tr = m[0][0] + m[1][1] + m[2][2], x, y, z, w;
        if (tr > 0) { double s = Math.sqrt(tr + 1) * 2; w = 0.25 * s; x = (m[2][1] - m[1][2]) / s; y = (m[0][2] - m[2][0]) / s; z = (m[1][0] - m[0][1]) / s; }
        else if (m[0][0] > m[1][1] && m[0][0] > m[2][2]) { double s = Math.sqrt(1 + m[0][0] - m[1][1] - m[2][2]) * 2; w = (m[2][1] - m[1][2]) / s; x = 0.25 * s; y = (m[0][1] + m[1][0]) / s; z = (m[0][2] + m[2][0]) / s; }
        else if (m[1][1] > m[2][2]) { double s = Math.sqrt(1 + m[1][1] - m[0][0] - m[2][2]) * 2; w = (m[0][2] - m[2][0]) / s; x = (m[0][1] + m[1][0]) / s; y = 0.25 * s; z = (m[1][2] + m[2][1]) / s; }
        else { double s = Math.sqrt(1 + m[2][2] - m[0][0] - m[1][1]) * 2; w = (m[1][0] - m[0][1]) / s; x = (m[0][2] + m[2][0]) / s; y = (m[1][2] + m[2][1]) / s; z = 0.25 * s; }
        return new double[]{x, y, z, w};
    }
}

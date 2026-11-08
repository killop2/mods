import java.io.*; import java.nio.*; import java.nio.file.*; import java.util.*; import java.util.zip.Inflater;
public class FbxInfo {
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


    static String clean(Object o) { String s = (String) o; int i = s.indexOf('\0'); return i >= 0 ? s.substring(0, i) : s; }
    public static void main(String[] a) throws Exception {
        buf = ByteBuffer.wrap(Files.readAllBytes(Paths.get(a[0]))).order(ByteOrder.LITTLE_ENDIAN); buf.position(23); version = buf.getInt();
        List<Node> top = new ArrayList<>(); while (buf.remaining() > 160) { Node n = readNode(); if (n == null) break; top.add(n); }
        System.out.println("version " + version);
        for (Node t : top) {
            if (t.name.equals("GlobalSettings")) for (Node p : t.child("Properties70").all("P")) { String k = (String)p.props.get(0); if (k.matches("UpAxis|FrontAxis|CoordAxis|UnitScaleFactor|UpAxisSign|FrontAxisSign|CoordAxisSign")) System.out.println("  " + k + "=" + p.props.get(p.props.size()-1)); }
            if (!t.name.equals("Objects")) continue;
            Map<String,Integer> cnt = new TreeMap<>(); List<String> stacks = new ArrayList<>(), meshes = new ArrayList<>(), mats = new ArrayList<>(), tex = new ArrayList<>(); List<String> bones = new ArrayList<>();
            for (Node n : t.children) {
                String k = n.name + (n.props.size() > 2 ? ":" + clean(n.props.get(2)) : ""); cnt.merge(k, 1, Integer::sum);
                String nm = n.props.size() > 1 ? clean(n.props.get(1)) : "";
                if (n.name.equals("AnimationStack")) stacks.add(nm);
                if (n.name.equals("Geometry")) { Node v = n.child("Vertices"); Node pi = n.child("PolygonVertexIndex"); int pv = pi == null ? 0 : ((int[]) pi.props.get(0)).length; int polys = 0; if (pi != null) for (int x : (int[]) pi.props.get(0)) if (x < 0) polys++; meshes.add(nm + " cp=" + (v == null ? 0 : ((double[]) v.props.get(0)).length / 3) + " polys=" + polys + " pv=" + pv); }
                if (n.name.equals("Material")) mats.add(nm);
                if (n.name.equals("Texture")) { Node f = n.child("RelativeFilename"); tex.add(nm + " -> " + (f == null ? "" : clean(f.props.get(0)))); }
                if (n.name.equals("Model") && clean(n.props.get(2)).equals("LimbNode")) bones.add(nm.replace("Model::", "").replace("\u0000\u0001Model",""));
            }
            System.out.println("  objects " + cnt);
            System.out.println("  stacks(" + stacks.size() + ") " + stacks);
            System.out.println("  meshes " + meshes); System.out.println("  materials " + mats); System.out.println("  textures " + tex);
            System.out.println("  bones(" + bones.size() + ") " + String.join(",", bones.subList(0, Math.min(bones.size(), 400))));
        }
    }
}

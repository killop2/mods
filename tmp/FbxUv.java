import java.io.*; import java.nio.*; import java.nio.file.*; import java.util.*; import java.util.zip.Inflater;
public class FbxUv {
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
        for (Node t : top) if (t.name.equals("Objects")) for (Node g : t.all("Geometry")) {
            for (Node lu : g.all("LayerElementUV")) {
                double[] uv = (double[]) lu.child("UV").props.get(0);
                double mnu=1e9,mxu=-1e9,mnv=1e9,mxv=-1e9; int out=0;
                for (int i=0;i<uv.length;i+=2){ mnu=Math.min(mnu,uv[i]);mxu=Math.max(mxu,uv[i]);mnv=Math.min(mnv,uv[i+1]);mxv=Math.max(mxv,uv[i+1]); if(uv[i]<0||uv[i]>1||uv[i+1]<0||uv[i+1]>1) out++; }
                System.out.println("UV layer idx=" + lu.props.get(0) + " name=" + clean(lu.child("Name").props.get(0)) + " map=" + clean(lu.child("MappingInformationType").props.get(0)) + " ref=" + clean(lu.child("ReferenceInformationType").props.get(0)) + " n=" + uv.length/2 + String.format(" u[%.3f %.3f] v[%.3f %.3f] outside=%d", mnu,mxu,mnv,mxv,out));
            }
            for (Node l : g.all("Layer")) { StringBuilder sb = new StringBuilder("Layer " + l.props.get(0) + ":"); for (Node le : l.all("LayerElement")) sb.append(" ").append(clean(le.child("Type").props.get(0))).append("#").append(le.child("TypedIndex").props.get(0)); System.out.println(sb); }
        }
    }
}
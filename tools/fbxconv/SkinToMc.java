import com.google.gson.*;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import javax.imageio.ImageIO;

/**
 * Converts a skinned, animated glTF (Sketchfab export) into the huggynpc ".skin" format:
 * skeleton + skinned mesh (quads over shared vertices) + selected animations sampled at a fixed rate.
 *
 * Usage:
 *   java -cp gson.jar SkinToMc.java analyze scene.gltf
 *   java -cp gson.jar SkinToMc.java convert scene.gltf texture.png out.skin previewPrefix height(blocks) fps name=gltfAnim[,loop|once] ...
 *
 * Output space: blocks, +Y up, feet on y=0, front of the character towards +Z, centred on x/z.
 */
public class SkinToMc {
    static JsonObject gltf;
    static ByteBuffer bin;
    static JsonArray nodes;
    static int[] parentOf;

    public static void main(String[] args) throws Exception {
        Path src = Paths.get(args[1]);
        gltf = JsonParser.parseString(Files.readString(src)).getAsJsonObject();
        String uri = gltf.getAsJsonArray("buffers").get(0).getAsJsonObject().get("uri").getAsString();
        bin = ByteBuffer.wrap(Files.readAllBytes(src.resolveSibling(uri))).order(ByteOrder.LITTLE_ENDIAN);
        nodes = gltf.getAsJsonArray("nodes");
        parentOf = new int[nodes.size()];
        Arrays.fill(parentOf, -1);
        for (int i = 0; i < nodes.size(); i++) {
            JsonObject n = nodes.get(i).getAsJsonObject();
            if (n.has("children")) for (JsonElement c : n.getAsJsonArray("children")) parentOf[c.getAsInt()] = i;
        }
        Model m = new Model();
        if (args[0].equals("analyze")) m.analyze();
        else m.convert(args);
    }

    // =====================================================================================
    static final class Model {
        int[] joints;              // node index per joint
        int[] jointParent;         // joint index of parent joint, -1 = root (uses fixed rootMatrix)
        double[][] restT, restR, restS;
        double[][] ibm;            // inverse bind matrices (row-major)
        double[] rootMatrix;       // global matrix of the non-joint parent of the root joint(s)
        double[] pos, nrm, uv;     // mesh vertex data (mesh space)
        int[] jIdx; double[] jW;   // 4 influences per vertex
        int[] idx;                 // triangle indices
        int vcount;

        Model() {
            JsonObject skin = gltf.getAsJsonArray("skins").get(0).getAsJsonObject();
            JsonArray ja = skin.getAsJsonArray("joints");
            joints = new int[ja.size()];
            Map<Integer, Integer> nodeToJoint = new HashMap<>();
            for (int i = 0; i < joints.length; i++) { joints[i] = ja.get(i).getAsInt(); nodeToJoint.put(joints[i], i); }
            // joints must be ordered parent-before-child for the runtime; verify / reorder
            jointParent = new int[joints.length];
            for (int i = 0; i < joints.length; i++) {
                Integer p = nodeToJoint.get(parentOf[joints[i]]);
                jointParent[i] = p == null ? -1 : p;
                if (p != null && p > i) throw new IllegalStateException("joint order: parent after child at " + i);
            }
            double[] ibmF = floats(skin.get("inverseBindMatrices").getAsInt());
            ibm = new double[joints.length][];
            for (int i = 0; i < joints.length; i++) {
                double[] m = new double[16];
                for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) m[r * 4 + c] = ibmF[i * 16 + c * 4 + r];
                ibm[i] = m;
            }
            restT = new double[joints.length][]; restR = new double[joints.length][]; restS = new double[joints.length][];
            for (int i = 0; i < joints.length; i++) {
                JsonObject n = nodes.get(joints[i]).getAsJsonObject();
                restT[i] = arr(n, "translation", 0, 0, 0);
                restR[i] = arr(n, "rotation", 0, 0, 0, 1);
                restS[i] = arr(n, "scale", 1, 1, 1);
            }
            int rootNode = -1;
            for (int i = 0; i < joints.length; i++) if (jointParent[i] < 0) {
                if (rootNode >= 0 && parentOf[joints[i]] != rootNode) throw new IllegalStateException("several joint roots with different parents");
                rootNode = parentOf[joints[i]];
            }
            rootMatrix = globalOf(rootNode);

            // all primitives (one per material) share the vertex arrays (FbxToGltf) or have their own (other exporters)
            JsonArray prims = gltf.getAsJsonArray("meshes").get(0).getAsJsonObject().getAsJsonArray("primitives");
            List<double[]> P = new ArrayList<>(), N = new ArrayList<>(), U = new ArrayList<>(), W = new ArrayList<>();
            List<int[]> Jl = new ArrayList<>(), I = new ArrayList<>(), M = new ArrayList<>();
            Map<Integer, Integer> posAccBase = new HashMap<>();
            int base = 0;
            for (JsonElement pe : prims) {
                JsonObject prim = pe.getAsJsonObject(), at = prim.getAsJsonObject("attributes");
                int pa = at.get("POSITION").getAsInt();
                Integer b = posAccBase.get(pa);
                if (b == null) {
                    b = base; posAccBase.put(pa, b);
                    double[] p = floats(pa); P.add(p); N.add(floats(at.get("NORMAL").getAsInt())); U.add(floats(at.get("TEXCOORD_0").getAsInt()));
                    Jl.add(ints(at.get("JOINTS_0").getAsInt())); W.add(floatsNorm(at.get("WEIGHTS_0").getAsInt()));
                    base += p.length / 3;
                }
                int[] ix = ints(prim.get("indices").getAsInt());
                for (int i = 0; i < ix.length; i++) ix[i] += b;
                I.add(ix);
                int mat = prim.has("material") ? prim.get("material").getAsInt() : 0;
                int[] mt = new int[ix.length / 3]; Arrays.fill(mt, mat); M.add(mt);
            }
            pos = concat(P); nrm = concat(N); uv = concat(U); jW = concat(W);
            jIdx = concatI(Jl); idx = concatI(I); triMat = concatI(M);
            vcount = pos.length / 3;
            matNames = new ArrayList<>();
            if (gltf.has("materials")) for (JsonElement me : gltf.getAsJsonArray("materials"))
                matNames.add(me.getAsJsonObject().has("name") ? me.getAsJsonObject().get("name").getAsString() : "");
        }

        int[] triMat;
        /** colour bits per channel kept by the voxel remesh (option bits:N) */
        int colourBits = 4, colourMask = 0xF0F0F0;
        /** pixel-art texture size for the atlas (option pixel:N), 0 = full resolution */
        int pixel = 0;
        List<String> matNames;

        String jname(int j) { return nodes.get(joints[j]).getAsJsonObject().get("name").getAsString(); }

        double[] globalOf(int node) {
            if (node < 0) return ident();
            return mul(globalOf(parentOf[node]), local(nodes.get(node).getAsJsonObject()));
        }

        /** Global joint matrices for given local TRS arrays. */
        double[][] globals(double[] root, double[][] T, double[][] R, double[][] S) {
            double[][] g = new double[joints.length][];
            for (int i = 0; i < joints.length; i++) {
                double[] l = trs(T[i], R[i], S[i]);
                g[i] = mul(jointParent[i] < 0 ? root : g[jointParent[i]], l);
            }
            return g;
        }

        double[] skinned(double[][] g, double[] conv) {
            double[][] sm = new double[joints.length][];
            for (int i = 0; i < joints.length; i++) sm[i] = mul(conv, mul(g[i], ibm[i]));
            double[] out = new double[vcount * 3];
            for (int v = 0; v < vcount; v++) {
                double x = 0, y = 0, z = 0;
                for (int k = 0; k < 4; k++) {
                    double w = jW[v * 4 + k]; if (w <= 0) continue;
                    double[] p = apply(sm[jIdx[v * 4 + k]], pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2]);
                    x += w * p[0]; y += w * p[1]; z += w * p[2];
                }
                out[v * 3] = x; out[v * 3 + 1] = y; out[v * 3 + 2] = z;
            }
            return out;
        }

        // ---------------------------------------------------------------- animations
        final class Anim {
            String name; double dur;
            int driftJoint = -1; double[] drift;
            Map<Integer, double[][]> rot = new HashMap<>(), tr = new HashMap<>(), sc = new HashMap<>();   // joint -> {times, values}
            Map<Integer, String> interp = new HashMap<>();
        }

        Anim loadAnim(String name) {
            // exact name (or "...|name") first, then "...|name_suffix" (Sketchfab adds the skeleton name)
            JsonObject found = null;
            for (int pass = 0; pass < 2 && found == null; pass++) for (JsonElement e : gltf.getAsJsonArray("animations")) {
                String n = e.getAsJsonObject().get("name").getAsString();
                if (pass == 0 ? (n.equals(name) || n.endsWith("|" + name)) : n.contains("|" + name + "_")) { found = e.getAsJsonObject(); break; }
            }
            for (JsonElement e : found == null ? new JsonArray() : List.of((JsonElement) found)) {
                JsonObject a = e.getAsJsonObject();
                String n = a.get("name").getAsString();
                Anim r = new Anim(); r.name = n;
                Map<Integer, Integer> nodeToJoint = new HashMap<>();
                for (int i = 0; i < joints.length; i++) nodeToJoint.put(joints[i], i);
                JsonArray samplers = a.getAsJsonArray("samplers");
                for (JsonElement ce : a.getAsJsonArray("channels")) {
                    JsonObject c = ce.getAsJsonObject();
                    JsonObject t = c.getAsJsonObject("target");
                    if (!t.has("node")) continue;
                    Integer j = nodeToJoint.get(t.get("node").getAsInt());
                    if (j == null) { System.out.println("  non-joint channel on node " + t.get("node") + " " + t.get("path")); continue; }
                    JsonObject s = samplers.get(c.get("sampler").getAsInt()).getAsJsonObject();
                    double[] times = floats(s.get("input").getAsInt());
                    double[] vals = floats(s.get("output").getAsInt());
                    String ip = s.has("interpolation") ? s.get("interpolation").getAsString() : "LINEAR";
                    r.dur = Math.max(r.dur, times[times.length - 1]);
                    String path = t.get("path").getAsString();
                    (path.equals("rotation") ? r.rot : path.equals("translation") ? r.tr : r.sc).put(j, new double[][]{times, vals});
                    r.interp.put(j, ip);
                }
                return r;
            }
            throw new IllegalArgumentException("animation not found: " + name);
        }

        /** Local TRS for every joint at time t. */
        double[][][] sample(Anim a, double t) {
            double[][] T = new double[joints.length][], R = new double[joints.length][], S = new double[joints.length][];
            for (int j = 0; j < joints.length; j++) {
                T[j] = a.tr.containsKey(j) ? interp(a.tr.get(j), t, 3, false) : restT[j];
                R[j] = a.rot.containsKey(j) ? interp(a.rot.get(j), t, 4, true) : restR[j];
                S[j] = a.sc.containsKey(j) ? interp(a.sc.get(j), t, 3, false) : restS[j];
            }
            if (a.driftJoint >= 0) {   // root motion removed: the animation plays in place
                double[] d = T[a.driftJoint].clone();
                for (int c = 0; c < 3; c++) d[c] -= a.drift[c] * t;
                T[a.driftJoint] = d;
            }
            return new double[][][]{T, R, S};
        }

        /**
         * Some animations move the whole character (walk cycles with root motion). The NPC moves by itself in the game,
         * so remove the straight horizontal travel of the top-most translated bone (start -> end), keep the rest.
         */
        void removeRootMotion(Anim a) {
            if (a.dur <= 0) return;
            double[][][] s0 = sample(a, 0), s1 = sample(a, a.dur);
            double[][] g0 = globals(rootMatrix, s0[0], s0[1], s0[2]), g1 = globals(rootMatrix, s1[0], s1[1], s1[2]);
            double[] b = bounds(pos);
            double size = Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2]));
            // the top-most translated bone that really travels (a root bone may stay put while the hips move)
            int mj = -1;
            for (int j = 0; j < joints.length && mj < 0; j++) {
                if (!a.tr.containsKey(j)) continue;
                double tx = g1[j][3] - g0[j][3], tz = g1[j][11] - g0[j][11];
                if (Math.sqrt(tx * tx + tz * tz) >= size * 0.02) mj = j;
            }
            if (mj < 0) return;
            double dx = g1[mj][3] - g0[mj][3], dz = g1[mj][11] - g0[mj][11];
            double[] pg = jointParent[mj] < 0 ? rootMatrix : g0[jointParent[mj]];
            // world delta -> parent-local delta (inverse of the parent's 3x3, which may contain scale)
            double[] inv = invert3(pg);
            double lx = inv[0] * dx + inv[2] * dz, ly = inv[3] * dx + inv[5] * dz, lz = inv[6] * dx + inv[8] * dz;
            if (Math.sqrt(dx * dx + dz * dz) < size * 0.02) return;
            a.driftJoint = mj;
            a.drift = new double[]{lx / a.dur, ly / a.dur, lz / a.dur};
            System.out.printf(Locale.ROOT, "  root motion removed from %s: %.1f units%n", a.name, Math.sqrt(dx * dx + dz * dz));
        }

        static double[] invert3(double[] m) {
            double a = m[0], b = m[1], c = m[2], d = m[4], e = m[5], f = m[6], g = m[8], h = m[9], i = m[10];
            double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
            return new double[]{(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
                    (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
                    (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det};
        }

        static double[] interp(double[][] ch, double t, int n, boolean quat) {
            double[] times = ch[0], v = ch[1];
            boolean cubic = v.length == times.length * n * 3;
            int stride = cubic ? n * 3 : n, off = cubic ? n : 0;
            if (t <= times[0]) return Arrays.copyOfRange(v, off, off + n);
            if (t >= times[times.length - 1]) { int b = (times.length - 1) * stride + off; return Arrays.copyOfRange(v, b, b + n); }
            int i = 0; while (i + 1 < times.length && times[i + 1] < t) i++;
            double f = (t - times[i]) / (times[i + 1] - times[i]);
            double[] a = Arrays.copyOfRange(v, i * stride + off, i * stride + off + n);
            double[] b = Arrays.copyOfRange(v, (i + 1) * stride + off, (i + 1) * stride + off + n);
            double[] r = new double[n];
            if (quat) {
                double d = 0; for (int k = 0; k < 4; k++) d += a[k] * b[k];
                double sgn = d < 0 ? -1 : 1, len = 0;
                for (int k = 0; k < 4; k++) { r[k] = a[k] * (1 - f) + b[k] * f * sgn; len += r[k] * r[k]; }
                len = Math.sqrt(len); for (int k = 0; k < 4; k++) r[k] /= len;
            } else for (int k = 0; k < n; k++) r[k] = a[k] * (1 - f) + b[k] * f;
            return r;
        }

        // ---------------------------------------------------------------- analysis
        void analyze() {
            int[] hist = new int[5];
            for (int v = 0; v < vcount; v++) { int c = 0; for (int k = 0; k < 4; k++) if (jW[v * 4 + k] > 0.001) c++; hist[c]++; }
            System.out.println("verts " + vcount + " tris " + idx.length / 3 + " influences histogram " + Arrays.toString(hist));
            Set<Integer> used = new TreeSet<>();
            for (int v = 0; v < vcount; v++) for (int k = 0; k < 4; k++) if (jW[v * 4 + k] > 0.001) used.add(jIdx[v * 4 + k]);
            System.out.println("joints used by skin: " + used.size() + " / " + joints.length);
            // quads: pairs of consecutive triangles sharing two indices
            int q = 0; for (int t = 0; t + 1 < idx.length / 3; t++) if (shared(t, t + 1) == 2) { q++; t++; }
            System.out.println("quad pairs " + q + " -> emitted quads " + (idx.length / 3 - q));
            // unique positions (welded)
            Set<String> uniq = new HashSet<>();
            for (int v = 0; v < vcount; v++) uniq.add(String.format(Locale.ROOT, "%.5f,%.5f,%.5f", pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2]));
            System.out.println("unique positions " + uniq.size());

            double[][] g = globals(rootMatrix, restT, restR, restS);
            report("rest", skinned(g, ident()));
            for (int j = 0; j < joints.length; j++) {
                String n = nodes.get(joints[j]).getAsJsonObject().get("name").getAsString();
                if (n.startsWith("head_JNT") || n.startsWith("lf_eye_JNT_0") || n.startsWith("rt_eye_JNT_0") || n.startsWith("rig_01") || n.startsWith("body_JNT1") || n.startsWith("lf_foot") || n.startsWith("lf_leg_JNT13_0"))
                    System.out.printf(Locale.ROOT, "  joint %-22s at %8.3f %8.3f %8.3f%n", n, g[j][3], g[j][7], g[j][11]);
            }
            for (JsonElement e : gltf.getAsJsonArray("animations")) {
                String n = e.getAsJsonObject().get("name").getAsString();
                String shortName = n.substring(n.indexOf('|') + 1).replace("_SK_Huggy_Voxel", "");
                Anim a = loadAnim(n);
                Set<String> ips = new TreeSet<>(a.interp.values());
                // root drift: rig_01 / body translation start->end
                double[] d0 = rootPos(a, 0), d1 = rootPos(a, a.dur);
                double[] bb = bounds(skinned(globals(rootMatrix, sample(a, a.dur * 0.25)[0], sample(a, a.dur * 0.25)[1], sample(a, a.dur * 0.25)[2]), ident()));
                System.out.printf(Locale.ROOT, "%-40s dur %5.2f rot %3d tr %3d sc %3d %s drift %6.2f %6.2f %6.2f  h=%.2f w=%.2f d=%.2f%n",
                        shortName, a.dur, a.rot.size(), a.tr.size(), a.sc.size(), ips, d1[0] - d0[0], d1[1] - d0[1], d1[2] - d0[2],
                        bb[4] - bb[1], bb[3] - bb[0], bb[5] - bb[2]);
            }
        }

        double[] rootPos(Anim a, double t) {
            double[][][] s = sample(a, t);
            double[][] g = globals(rootMatrix, s[0], s[1], s[2]);
            int j = 3; // body_JNT1 area
            for (int i = 0; i < joints.length; i++) if (nodes.get(joints[i]).getAsJsonObject().get("name").getAsString().startsWith("body_JNT1")) j = i;
            return new double[]{g[j][3], g[j][7], g[j][11]};
        }

        void report(String what, double[] p) {
            double[] b = bounds(p);
            System.out.printf(Locale.ROOT, "%s bounds x[%.3f %.3f] y[%.3f %.3f] z[%.3f %.3f]%n", what, b[0], b[3], b[1], b[4], b[2], b[5]);
        }

        int shared(int a, int b) {
            int c = 0;
            for (int i = 0; i < 3; i++) for (int k = 0; k < 3; k++) if (idx[a * 3 + i] == idx[b * 3 + k]) c++;
            return c;
        }

        // ---------------------------------------------------------------- conversion
        /**
         * convert scene.gltf out.skin previewPrefix height fps [options] name=animation[,once] ...
         * options: tex:MATERIAL=file.png (MATERIAL = substring of the material name, * = any), voxel:N (rebuild the
         * mesh from cubes, N cubes across the model), outtex:file.png (palette texture written with voxel:),
         * yaw:degrees (forward direction, default: from the eye bones)
         */
        void convert(String[] args) throws Exception {
            Path out = Paths.get(args[2]);
            String preview = args[3];
            double height = Double.parseDouble(args[4]);
            double fps = Double.parseDouble(args[5]);
            List<String[]> animSpecs = new ArrayList<>();
            Map<String, BufferedImage> texByMat = new LinkedHashMap<>();
            int voxel = 0, atlas = 0, snap = 0; boolean flat = false; String outTex = null; Double yawOpt = null;
            for (int i = 6; i < args.length; i++) {
                String a = args[i];
                if (a.startsWith("tex:")) { String[] kv = a.substring(4).split("=", 2); texByMat.put(kv[0], ImageIO.read(new File(kv[1]))); continue; }
                if (a.startsWith("voxel:")) { voxel = Integer.parseInt(a.substring(6)); continue; }
                if (a.startsWith("atlas:")) { atlas = Integer.parseInt(a.substring(6)); continue; }
                if (a.startsWith("pixel:")) { pixel = Integer.parseInt(a.substring(6)); continue; }
                if (a.startsWith("snap:")) { snap = Integer.parseInt(a.substring(5)); continue; }
                if (a.equals("flat")) { flat = true; continue; }
                if (a.startsWith("lod:")) { lodCells = Integer.parseInt(a.substring(4)); continue; }
                if (a.startsWith("bits:")) { colourBits = Integer.parseInt(a.substring(5)); int m = (0xFF << (8 - colourBits)) & 0xFF; colourMask = m << 16 | m << 8 | m; continue; }
                if (a.startsWith("outtex:")) { outTex = a.substring(7); continue; }
                if (a.startsWith("yaw:")) { yawOpt = Double.parseDouble(a.substring(4)); continue; }
                String[] kv = a.split("=", 2);
                String[] v = kv[1].split(",");
                animSpecs.add(new String[]{kv[0], v[0], v.length > 1 ? v[1] : "loop"});
            }
            BufferedImage[] matTex = new BufferedImage[Math.max(1, matNames.size())];
            for (int m = 0; m < matTex.length; m++) {
                String mn = m < matNames.size() ? matNames.get(m) : "";
                for (Map.Entry<String, BufferedImage> e : texByMat.entrySet()) if (e.getKey().equals("*") || mn.contains(e.getKey())) { matTex[m] = e.getValue(); if (!e.getKey().equals("*")) break; }
                System.out.println("material " + m + " " + mn + " -> " + (matTex[m] != null ? matTex[m].getWidth() + "px texture" : "NO TEXTURE"));
            }
            BufferedImage tex = matTex[0];
            if (voxel > 0) tex = voxelRemesh(voxel, matTex, outTex);
            else {
                if (snap > 0) snapMesh(snap);
                if (flat) flatNormals();
                if (atlas > 0) tex = atlasRemap(matTex, atlas, outTex);
            }

            // --- orientation: forward = from the head bone towards the eye bones (or yaw: option)
            double[][] g = globals(rootMatrix, restT, restR, restS);
            double yaw = 0;
            if (yawOpt != null) yaw = Math.toRadians(yawOpt);
            else {
                int head = findJoint("head"); List<Integer> eyes = new ArrayList<>();
                for (int j = 0; j < joints.length; j++) if (jname(j).toLowerCase(Locale.ROOT).contains("eye") && !jname(j).toLowerCase(Locale.ROOT).contains("lid")) eyes.add(j);
                if (head >= 0 && !eyes.isEmpty()) {
                    double fx = 0, fz = 0; for (int e : eyes) { fx += g[e][3] / eyes.size(); fz += g[e][11] / eyes.size(); }
                    yaw = Math.atan2(fx - g[head][3], fz - g[head][11]);
                }
            }
            double[] rotY = rotY(-yaw);
            System.out.printf(Locale.ROOT, "forward yaw %.1f deg%n", Math.toDegrees(yaw));

            // --- scale / centre from the idle pose (first spec): feet on y=0, bounding box centred on x/z
            Anim idle = loadAnim(animSpecs.get(0)[1]);
            removeRootMotion(idle);
            double[][][] s0 = sample(idle, 0);
            double[] p0 = skinned(globals(rootMatrix, s0[0], s0[1], s0[2]), rotY);
            double[] b = bounds(p0);
            double[][] gi = globals(rootMatrix, s0[0], s0[1], s0[2]);
            double[] hp = {(b[0] + b[3]) / 2, 0, (b[2] + b[5]) / 2};
            double k = height / (b[4] - b[1]);
            double[] conv = mul(scaleTranslate(k, -hp[0] * k, -b[1] * k, -hp[2] * k), rotY);
            System.out.printf(Locale.ROOT, "idle bbox h=%.4f -> scale %.3f blocks/unit%n", b[4] - b[1], k);
            double[] pv = skinned(gi, conv);
            double[] bb = bounds(pv);
            System.out.printf(Locale.ROOT, "idle pose in blocks: x[%.2f %.2f] y[%.2f %.2f] z[%.2f %.2f]%n", bb[0], bb[3], bb[1], bb[4], bb[2], bb[5]);

            // --- quads over shared vertex indices
            // --- weld vertices that are fully identical (position, uv, normal, weights) to cut skinning work
            Map<String, Integer> weld = new HashMap<>();
            int[] remap = new int[vcount];
            List<Integer> keep = new ArrayList<>();
            for (int v = 0; v < vcount; v++) {
                StringBuilder key = new StringBuilder();
                for (int c = 0; c < 3; c++) key.append(Math.round(pos[v * 3 + c] * 1e6)).append(',');
                for (int c = 0; c < 2; c++) key.append(Math.round(uv[v * 2 + c] * 1e6)).append(',');
                for (int c = 0; c < 3; c++) key.append(Math.round(nrm[v * 3 + c] * 1e3)).append(',');
                for (int c = 0; c < 4; c++) key.append(jIdx[v * 4 + c]).append(':').append(Math.round(jW[v * 4 + c] * 1e4)).append(',');
                Integer e = weld.get(key.toString());
                if (e == null) { e = keep.size(); weld.put(key.toString(), e); keep.add(v); }
                remap[v] = e;
            }
            System.out.println("welded vertices " + keep.size() + " / " + vcount);
            if (keep.size() > 65535) throw new IllegalStateException("too many vertices for 16-bit indices: " + keep.size() + " (use a smaller voxel:)");
            // pair triangles on welded indices (adjacent triangles of one face now share their vertices);
            // drop triangles that became degenerate
            int[] welded = new int[idx.length]; int wn = 0;
            for (int t = 0; t < idx.length / 3; t++) {
                int a = idx[t * 3], b2 = idx[t * 3 + 1], c = idx[t * 3 + 2];
                int ra = keep.get(remap[a]), rb = keep.get(remap[b2]), rc = keep.get(remap[c]);
                if (ra == rb || rb == rc || ra == rc) continue;
                welded[wn++] = ra; welded[wn++] = rb; welded[wn++] = rc;
            }
            idx = Arrays.copyOf(welded, wn);
            List<int[]> quads = pairTriangles();
            System.out.println("quads " + quads.size() + " from " + idx.length / 3 + " triangles");
            List<int[]> lodQuads = lodCells > 0 ? buildLod(lodCells) : null;
            List<Integer> lodVerts = new ArrayList<>();
            if (lodQuads != null) {
                TreeSet<Integer> used = new TreeSet<>();
                for (int[] q : lodQuads) for (int c = 0; c < 4; c++) used.add(remap[q[c]]);
                lodVerts.addAll(used);
                System.out.println("LOD: " + lodQuads.size() + " quads, " + lodVerts.size() + " vertices");
            }

            // --- write
            try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(out))))) {
                o.writeInt(0x534B494E); o.writeInt(lodQuads != null ? 5 : 4);
                // skeleton
                o.writeInt(joints.length);
                double[] root = mul(conv, rootMatrix);
                for (int i = 0; i < 16; i++) o.writeFloat((float) root[i]);
                for (int j = 0; j < joints.length; j++) {
                    o.writeUTF(jname(j));
                    o.writeShort(jointParent[j]);
                    for (double d : restT[j]) o.writeFloat((float) d);
                    for (double d : restR[j]) o.writeFloat((float) d);
                    for (double d : restS[j]) o.writeFloat((float) d);
                    for (int i = 0; i < 16; i++) o.writeFloat((float) ibm[j][i]);
                }
                // vertices
                o.writeInt(keep.size());
                for (int v : keep) {
                    for (int c = 0; c < 3; c++) o.writeFloat((float) pos[v * 3 + c]);
                    for (int c = 0; c < 3; c++) o.writeFloat((float) nrm[v * 3 + c]);
                    for (int c = 0; c < 2; c++) o.writeFloat((float) uv[v * 2 + c]);
                    int n = 0; for (int c = 0; c < 4; c++) if (jW[v * 4 + c] > 0.0005) n++;
                    o.writeByte(n);
                    double sum = 0; for (int c = 0; c < 4; c++) if (jW[v * 4 + c] > 0.0005) sum += jW[v * 4 + c];
                    for (int c = 0; c < 4; c++) if (jW[v * 4 + c] > 0.0005) { o.writeByte(jIdx[v * 4 + c]); o.writeFloat((float) (jW[v * 4 + c] / sum)); }
                }
                o.writeInt(quads.size());
                for (int[] q : quads) for (int c = 0; c < 4; c++) o.writeShort(remap[q[c]]);
                // animations
                o.writeInt(animSpecs.size());
                for (String[] spec : animSpecs) {
                    Anim a = loadAnim(spec[1]);
                    removeRootMotion(a);
                    int frames = Math.max(2, (int) Math.round(a.dur * fps) + 1);
                    o.writeUTF(spec[0]); o.writeBoolean(spec[2].equals("loop")); o.writeFloat((float) a.dur); o.writeInt(frames);
                    // which joints carry which channels (only those that actually change)
                    boolean[] hasR = new boolean[joints.length], hasT = new boolean[joints.length], hasS = new boolean[joints.length];
                    double[][][][] smp = new double[frames][][][];
                    for (int f = 0; f < frames; f++) smp[f] = sample(a, a.dur * f / (frames - 1));
                    for (int j = 0; j < joints.length; j++) for (int f = 0; f < frames; f++) {
                        if (diff(smp[f][1][j], restR[j]) > 1e-4) hasR[j] = true;
                        if (diff(smp[f][0][j], restT[j]) > 1e-6) hasT[j] = true;
                        if (diff(smp[f][2][j], restS[j]) > 1e-4) hasS[j] = true;
                    }
                    for (int j = 0; j < joints.length; j++) o.writeByte((hasR[j] ? 1 : 0) | (hasT[j] ? 2 : 0) | (hasS[j] ? 4 : 0));
                    for (int f = 0; f < frames; f++) for (int j = 0; j < joints.length; j++) {
                        if (hasR[j]) for (double d : smp[f][1][j]) o.writeShort((short) Math.round(Math.max(-1, Math.min(1, d)) * 32767));
                        if (hasT[j]) for (double d : smp[f][0][j]) o.writeFloat((float) d);
                        if (hasS[j]) for (double d : smp[f][2][j]) o.writeFloat((float) d);
                    }
                    int nr = 0, nt = 0, ns = 0; for (int j = 0; j < joints.length; j++) { if (hasR[j]) nr++; if (hasT[j]) nt++; if (hasS[j]) ns++; }
                    double[] bx = bounds(skinned(globals(rootMatrix, smp[frames / 3][0], smp[frames / 3][1], smp[frames / 3][2]), conv));
                    System.out.printf(Locale.ROOT, "anim %-8s <- %-30s %5.2fs %4d frames  rot %d tr %d sc %d  (1/3: h %.2f, x[%.2f %.2f] z[%.2f %.2f])%n",
                            spec[0], a.name.substring(a.name.indexOf('|') + 1), a.dur, frames, nr, nt, ns, bx[4] - bx[1], bx[0], bx[3], bx[2], bx[5]);
                    // preview of every animation at 1/3 of its length
                    renderPreview(skinned(globals(rootMatrix, smp[frames / 3][0], smp[frames / 3][1], smp[frames / 3][2]), conv), quads, tex,
                            preview + "_" + spec[0] + ".png", spec[0] + " @1/3");
                }
                // ragdoll parts (one box per part) and the head bone (version 4)
                writeRagdoll(o, pv, gi, conv, tex, preview);
                int headJoint = findJoint("head");
                o.writeInt(headJoint);
                System.out.println("head bone: " + (headJoint >= 0 ? jname(headJoint) : "none"));
                // version 5: reduced mesh for far NPCs and shadows, over a subset of the same vertices
                if (lodQuads != null) {
                    o.writeInt(lodVerts.size());
                    for (int v : lodVerts) o.writeShort(v);
                    o.writeInt(lodQuads.size());
                    for (int[] q : lodQuads) for (int c = 0; c < 4; c++) o.writeShort(remap[q[c]]);
                }
            }
            renderPreview(pv, quads, tex, preview + "_idle0.png", "idle frame 0");
            if (lodQuads != null) renderPreview(pv, lodQuads, tex, preview + "_lod.png", "LOD");
            System.out.println("written " + Files.size(out) + " bytes");
        }

        // ---------------------------------------------------------------- ragdoll parts
        /*
         * Ragdoll parts are found from the bone names: body, head (head/neck/eyes/mouth/ears/hair...) and limbs
         * (arm or leg, left/right, front/back for animals) plus tails. Each limb becomes a chain of up to 3 links.
         * Sable: Ragdolls joins a nested part to its parent with a joint, but merges a child into its parent when both
         * get the same role from their name (arm/hand, leg/foot, tail...), so the links alternate: arm -> tail -> arm,
         * leg -> tail -> leg, tail -> leg -> tail. Names must not contain each other (Sable matches by substring).
         */
        List<String> segNames = new ArrayList<>();
        List<Integer> segParent = new ArrayList<>(), segPivot = new ArrayList<>();
        int[] jointSeg;

        static final java.util.regex.Pattern HEAD = java.util.regex.Pattern.compile("head|neck|eye|mouth|jaw|ear|hair|teeth|teath|lip|brow|hat|tongue|nose|skull|face|cheek");
        static final java.util.regex.Pattern ARM = java.util.regex.Pattern.compile("arm|shoulder|hand|clavicle|finger|thumb|index|middle|ring|pinky|pinkie|elbow|wrist|claw");
        static final java.util.regex.Pattern LEG = java.util.regex.Pattern.compile("leg|thigh|knee|ankle|foot|toe|ball|calf|shin|hip|heel");

        /** "head", "tail", "arm|l|", "leg|r|f" ... or null for the body */
        String limbKey(String name) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.contains("ik") && (n.contains("_ik") || n.contains("ik_")) || n.contains("pole") || n.contains("handle") || n.startsWith("group") || n.startsWith("cluster")) return null;
            if (HEAD.matcher(n).find()) return "head";
            if (n.contains("tail")) return "tail";
            String side = null, fb = "";
            for (String t : n.split("[^a-z0-9]+")) {
                if (t.matches("(l|lf|left)\\d*")) side = "l";
                else if (t.matches("(r|rt|right)\\d*")) side = "r";
                else if (t.equals("front") || t.equals("fore")) fb = "f";
                else if (t.equals("back") || t.equals("hind") || t.equals("rear")) fb = "b";
            }
            if (side == null) return null;
            if (ARM.matcher(n).find() && !n.contains("leg")) return "arm|" + side + "|" + fb;
            if (LEG.matcher(n).find()) return "leg|" + side + "|" + fb;
            return null;
        }

        void buildSegments(double[] pv) {
            int J = joints.length;
            String[] key = new String[J];
            for (int j = 0; j < J; j++) {
                key[j] = limbKey(jname(j));
                if (key[j] == null && jointParent[j] >= 0) key[j] = key[jointParent[j]];   // fingers, helpers... follow the parent
            }
            // which joints actually move vertices
            int[] vcnt = new int[J];
            for (int v = 0; v < vcount; v++) { int best = 0; for (int q = 1; q < 4; q++) if (jW[v * 4 + q] > jW[v * 4 + best]) best = q; vcnt[jIdx[v * 4 + best]]++; }
            double[][] g = globals(rootMatrix, restT, restR, restS);
            jointSeg = new int[J];
            segNames.add("body"); segParent.add(-1); segPivot.add(-1);
            Map<String, List<Integer>> groups = new LinkedHashMap<>();
            for (int j = 0; j < J; j++) if (key[j] != null) groups.computeIfAbsent(key[j], x -> new ArrayList<>()).add(j);
            int bodyPivot = -1;
            for (int j = 0; j < J; j++) if (key[j] == null && vcnt[j] > 0) { bodyPivot = j; break; }
            segPivot.set(0, bodyPivot < 0 ? 0 : bodyPivot);
            for (Map.Entry<String, List<Integer>> e : groups.entrySet()) {
                List<Integer> js = e.getValue();
                int used = 0; for (int j : js) used += vcnt[j];
                if (used == 0) continue;
                // distance of every joint from the top of the group along the bones (rest pose)
                Set<Integer> in = new HashSet<>(js);
                double[] dist = new double[J];
                double maxD = 0; int top = -1;
                for (int j : js) {
                    int p = jointParent[j];
                    if (p < 0 || !in.contains(p)) { dist[j] = 0; if (top < 0) top = j; }
                    else dist[j] = dist[p] + Math.sqrt(sq(g[j][3] - g[p][3]) + sq(g[j][7] - g[p][7]) + sq(g[j][11] - g[p][11]));
                    if (vcnt[j] > 0) maxD = Math.max(maxD, dist[j]);
                }
                String k = e.getKey();
                if (k.equals("head")) {
                    int s = segNames.size(); segNames.add("head"); segParent.add(0); segPivot.add(top);
                    for (int j : js) jointSeg[j] = s;
                    continue;
                }
                String[] parts = k.split("\\|", -1);
                String type = parts[0], prefix = parts.length > 1 ? parts[1] + parts[2] : "";
                String[] roles = type.equals("arm") ? new String[]{"arm", "tail", "arm"} : type.equals("leg") ? new String[]{"leg", "tail", "leg"} : new String[]{"tail", "leg", "tail"};
                char tag = type.equals("arm") ? 'a' : type.equals("leg") ? 'g' : 't';
                int links = maxD > 0 ? 3 : 1;
                int[] linkSeg = new int[links];
                int[] linkPivot = new int[links];
                double[] linkPivotD = new double[links];
                Arrays.fill(linkPivot, -1); Arrays.fill(linkPivotD, 1e18);
                int[] linkVerts = new int[links];
                for (int j : js) {
                    int l = links == 1 ? 0 : Math.min(links - 1, (int) (dist[j] / maxD * links * 0.999));
                    linkVerts[l] += vcnt[j];
                    if (dist[j] < linkPivotD[l]) { linkPivotD[l] = dist[j]; linkPivot[l] = j; }
                }
                int parent = 0, last = -1;
                for (int l = 0; l < links; l++) {
                    if (linkVerts[l] == 0) { linkSeg[l] = last; continue; }
                    String name = (prefix.isEmpty() ? "" : prefix + "_") + roles[l % 3] + "_" + tag + l;
                    if (type.equals("tail") && prefix.isEmpty()) name = roles[l % 3].equals("tail") ? "tail_" + tag + l : "tail_leg_" + tag + l;
                    int s = segNames.size(); segNames.add(name); segParent.add(parent); segPivot.add(linkPivot[l]);
                    linkSeg[l] = s; parent = s; last = s;
                }
                for (int j : js) {
                    int l = links == 1 ? 0 : Math.min(links - 1, (int) (dist[j] / maxD * links * 0.999));
                    int s = linkSeg[l];
                    for (int x = l; s < 0 && x >= 0; x--) s = linkSeg[x];
                    for (int x = l; s < 0 && x < links; x++) s = linkSeg[x];
                    jointSeg[j] = s < 0 ? 0 : s;
                }
            }
            System.out.println("ragdoll parts: " + segNames);
        }

        boolean jointed(int a, int b) { return segParent.get(a) == b || segParent.get(b) == a; }

        /**
         * One box per ragdoll part = the bounds of that part of the mesh in the idle pose (6 quads; Sable reads at most
         * 128 per part). Sable uses it for the physics body; the visible ragdoll is the real mesh, cut by part.
         */
        void writeRagdoll(DataOutputStream o, double[] pv, double[][] g, double[] conv, BufferedImage tex, String preview) throws IOException {
            if (segNames.isEmpty()) buildSegments(pv);
            int S = segNames.size();
            int[] vseg = new int[vcount];
            for (int v = 0; v < vcount; v++) {
                int best = 0; for (int q = 1; q < 4; q++) if (jW[v * 4 + q] > jW[v * 4 + best]) best = q;
                vseg[v] = jointSeg[jIdx[v * 4 + best]];
            }
            double[][] mins = new double[S][], maxs = new double[S][];
            double[] us = new double[S], vs = new double[S];
            for (int s = 0; s < S; s++) {
                double[] mn = {1e9, 1e9, 1e9}, mx = {-1e9, -1e9, -1e9};
                for (int v = 0; v < vcount; v++) if (vseg[v] == s) {
                    for (int c = 0; c < 3; c++) { mn[c] = Math.min(mn[c], pv[v * 3 + c]); mx[c] = Math.max(mx[c], pv[v * 3 + c]); }
                    us[s] = uv[v * 2]; vs[s] = uv[v * 2 + 1];
                }
                if (mn[0] > mx[0]) { mn = new double[]{-0.05, 0, -0.05}; mx = new double[]{0.05, 0.1, 0.05}; }
                mins[s] = mn; maxs[s] = mx;
            }
            // Parts that are not joined collide with each other: overlapping boxes push each other apart every tick and
            // make the body shake. Shrink such boxes around their own centre (Sable places the visible mesh by it).
            int shrunk = 0;
            for (int pass = 0; pass < 20; pass++) {
                boolean any = false;
                for (int a = 0; a < S; a++) for (int b = a + 1; b < S; b++) {
                    if (jointed(a, b)) continue;
                    double best = 1e9; int axis = -1;
                    for (int c = 0; c < 3; c++) {
                        double ov = Math.min(maxs[a][c], maxs[b][c]) - Math.max(mins[a][c], mins[b][c]);
                        if (ov <= 0) { axis = -1; break; }
                        if (ov < best) { best = ov; axis = c; }
                    }
                    if (axis < 0) continue;
                    any = true; shrunk++;
                    double cut = best / 2 + 0.01;
                    for (int s : new int[]{a, b}) {
                        double centre = (mins[s][axis] + maxs[s][axis]) / 2, half = (maxs[s][axis] - mins[s][axis]) / 2;
                        half = Math.max(0.04, half - cut);
                        mins[s][axis] = centre - half; maxs[s][axis] = centre + half;
                    }
                }
                if (!any) break;
            }
            System.out.println("ragdoll boxes shrunk " + shrunk + " times to remove overlaps");
            for (int j = 0; j < joints.length; j++) o.writeByte(jointSeg[j]);
            o.writeInt(S);
            for (int s = 0; s < S; s++) {
                double[] mn = mins[s], mx = maxs[s];
                int pj = segPivot.get(s);
                double[] gp = mul(conv, g[pj < 0 ? 0 : pj]);
                o.writeUTF(segNames.get(s));
                o.writeByte(segParent.get(s));
                o.writeFloat((float) gp[3]); o.writeFloat((float) gp[7]); o.writeFloat((float) gp[11]);
                o.writeInt(6);
                for (int axis = 0; axis < 3; axis++) for (int sign = -1; sign <= 1; sign += 2) {
                    int ua = (axis + 1) % 3, va = (axis + 2) % 3;
                    double plane = sign > 0 ? mx[axis] : mn[axis];
                    double[][] corners = sign > 0
                            ? new double[][]{{mn[ua], mn[va]}, {mx[ua], mn[va]}, {mx[ua], mx[va]}, {mn[ua], mx[va]}}
                            : new double[][]{{mn[ua], mn[va]}, {mn[ua], mx[va]}, {mx[ua], mx[va]}, {mx[ua], mn[va]}};
                    for (int i = 0; i < 4; i++) {
                        double[] p = new double[3]; p[axis] = plane; p[ua] = corners[i][0]; p[va] = corners[i][1];
                        o.writeFloat((float) p[0]); o.writeFloat((float) p[1]); o.writeFloat((float) p[2]); o.writeFloat((float) us[s]); o.writeFloat((float) vs[s]);
                    }
                    for (int c = 0; c < 3; c++) o.writeFloat(c == axis ? sign : 0);
                }
                System.out.printf(Locale.ROOT, "ragdoll %-12s parent %-10s x[%.2f %.2f] y[%.2f %.2f] z[%.2f %.2f]%n", segNames.get(s),
                        segParent.get(s) < 0 ? "-" : segNames.get(segParent.get(s)), mn[0], mx[0], mn[1], mx[1], mn[2], mx[2]);
            }
        }

        static double sq(double v) { return v * v; }

        // ---------------------------------------------------------------- voxel remesh
        /**
         * Rebuilds the mesh from cubes in the bind pose: N cubes across the largest side, one solid colour per cube
         * face (point-sampled from the material textures, slightly quantised), same-colour faces of the same bone
         * merged into bigger quads. Every face is skinned like the original vertex it was sampled from, so the
         * animations still work. All colours go into a small palette texture (written to outTex).
         */
        BufferedImage voxelRemesh(int cells, BufferedImage[] matTex, String outTex) throws IOException {
            double[] b = bounds(pos);
            double r = Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2])) / cells;
            int nx = (int) Math.ceil((b[3] - b[0]) / r) + 3, ny = (int) Math.ceil((b[4] - b[1]) / r) + 3, nz = (int) Math.ceil((b[5] - b[2]) / r) + 3;
            double ox = b[0] - r, oy = b[1] - r, oz = b[2] - r;
            int n = nx * ny * nz;
            int[] src = new int[n]; Arrays.fill(src, -1);   // source vertex (skin weights) per cell
            int[] col = new int[n];
            int[] dom = new int[vcount];
            for (int v = 0; v < vcount; v++) { int best = 0; for (int q = 1; q < 4; q++) if (jW[v * 4 + q] > jW[v * 4 + best]) best = q; dom[v] = jIdx[v * 4 + best]; }
            for (int t = 0; t < idx.length / 3; t++) {
                int[] tv = {idx[t * 3], idx[t * 3 + 1], idx[t * 3 + 2]};
                BufferedImage tx = matTex[Math.min(triMat[t], matTex.length - 1)];
                double maxEdge = 0;
                for (int e = 0; e < 3; e++) { int a = tv[e], c = tv[(e + 1) % 3]; maxEdge = Math.max(maxEdge, Math.sqrt(sq(pos[a * 3] - pos[c * 3]) + sq(pos[a * 3 + 1] - pos[c * 3 + 1]) + sq(pos[a * 3 + 2] - pos[c * 3 + 2]))); }
                int steps = Math.max(1, (int) Math.ceil(maxEdge / (r * 0.4)));
                for (int i = 0; i <= steps; i++) for (int j = 0; j <= steps - i; j++) {
                    double[] w = {(double) i / steps, (double) j / steps, 1 - (double) (i + j) / steps};
                    double x = 0, y = 0, z = 0, u = 0, vv = 0; int bi = 0;
                    for (int q = 0; q < 3; q++) {
                        x += w[q] * pos[tv[q] * 3]; y += w[q] * pos[tv[q] * 3 + 1]; z += w[q] * pos[tv[q] * 3 + 2];
                        u += w[q] * uv[tv[q] * 2]; vv += w[q] * uv[tv[q] * 2 + 1];
                        if (w[q] > w[bi]) bi = q;
                    }
                    int id = ((int) ((x - ox) / r) * ny + (int) ((y - oy) / r)) * nz + (int) ((z - oz) / r);
                    if (src[id] >= 0) continue;
                    src[id] = tv[bi];
                    int c = tx == null ? 0x808080 : tx.getRGB(Math.floorMod((int) Math.floor(u * tx.getWidth()), tx.getWidth()), Math.floorMod((int) Math.floor(vv * tx.getHeight()), tx.getHeight())) & 0xFFFFFF;
                    col[id] = c & colourMask;   // fewer colour bits: fewer colours, bigger merged faces
                }
            }
            // outside air by flood fill; closed insides become solid
            boolean[] outside = new boolean[n];
            ArrayDeque<Integer> q = new ArrayDeque<>();
            outside[0] = true; q.add(0);
            int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            while (!q.isEmpty()) {
                int id = q.poll(); int cx = id / (ny * nz), cy = (id / nz) % ny, cz = id % nz;
                for (int[] d : dirs) {
                    int x = cx + d[0], y = cy + d[1], z = cz + d[2];
                    if (x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz) continue;
                    int k = (x * ny + y) * nz + z;
                    if (!outside[k] && src[k] < 0) { outside[k] = true; q.add(k); }
                }
            }
            // palette + greedy faces (key: bone + colour)
            Map<Integer, Integer> palette = new LinkedHashMap<>();
            List<double[]> P = new ArrayList<>(), N = new ArrayList<>(), U = new ArrayList<>(), W = new ArrayList<>();
            List<int[]> Jl = new ArrayList<>();
            List<Integer> I = new ArrayList<>();
            int[] dim = {nx, ny, nz};
            double[] o3 = {ox, oy, oz};
            for (int axis = 0; axis < 3; axis++) for (int sign = -1; sign <= 1; sign += 2) {
                int ua = (axis + 1) % 3, va = (axis + 2) % 3;
                for (int sl = 0; sl < dim[axis]; sl++) {
                    long[][] key = new long[dim[ua]][dim[va]];
                    int[][] cell = new int[dim[ua]][dim[va]];
                    for (int a = 0; a < dim[ua]; a++) for (int c = 0; c < dim[va]; c++) {
                        key[a][c] = -1;
                        int[] p = new int[3]; p[axis] = sl; p[ua] = a; p[va] = c;
                        int id = (p[0] * ny + p[1]) * nz + p[2];
                        if (src[id] < 0) continue;
                        p[axis] += sign;
                        boolean face = p[axis] < 0 || p[axis] >= dim[axis] || outside[(p[0] * ny + p[1]) * nz + p[2]];
                        if (!face) continue;
                        key[a][c] = ((long) dom[src[id]] << 24) | col[id];
                        cell[a][c] = id;
                    }
                    for (int a = 0; a < dim[ua]; a++) for (int c = 0; c < dim[va]; c++) {
                        long k = key[a][c]; if (k < 0) continue;
                        int w = 1; while (c + w < dim[va] && key[a][c + w] == k) w++;
                        int h = 1;
                        grow:
                        while (a + h < dim[ua]) { for (int i = 0; i < w; i++) if (key[a + h][c + i] != k) break grow; h++; }
                        for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) key[a + i][c + j] = -1;
                        int id = cell[a][c], sv = src[id];
                        int pi = palette.computeIfAbsent(col[id], x -> palette.size());
                        double plane = o3[axis] + (sl + (sign > 0 ? 1 : 0)) * r;
                        double u0 = o3[ua] + a * r, u1 = o3[ua] + (a + h) * r, v0 = o3[va] + c * r, v1 = o3[va] + (c + w) * r;
                        double[][] corners = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
                        int base = P.size();
                        double[] nn = new double[3]; nn[axis] = sign;
                        int[][] ci = {{a, c}, {a + h, c}, {a + h, c + w}, {a, c + w}};
                        for (int i = 0; i < 4; i++) {
                            double[] p = new double[3]; p[axis] = plane; p[ua] = corners[i][0]; p[va] = corners[i][1];
                            P.add(p); N.add(nn); U.add(new double[]{pi, 0});
                            // every quad touching this grid corner takes the same skin weights: the mesh bends
                            // as one piece instead of falling apart into separate cubes
                            int[] g3 = new int[3]; g3[axis] = sl + (sign > 0 ? 1 : 0); g3[ua] = ci[i][0]; g3[va] = ci[i][1];
                            int cs = cornerSource(src, dom, dom[sv], nx, ny, nz, g3[0], g3[1], g3[2]);
                            if (cs < 0) cs = sv;
                            Jl.add(new int[]{jIdx[cs * 4], jIdx[cs * 4 + 1], jIdx[cs * 4 + 2], jIdx[cs * 4 + 3]});
                            W.add(new double[]{jW[cs * 4], jW[cs * 4 + 1], jW[cs * 4 + 2], jW[cs * 4 + 3]});
                        }
                        // outward winding: (p1-p0) x (p2-p0) along the face direction
                        double[] p0 = P.get(base), p1 = P.get(base + 1), p2 = P.get(base + 2);
                        double[] e1 = {p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]}, e2 = {p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]};
                        double cr = new double[]{e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]}[axis];
                        int[] ord = cr * sign >= 0 ? new int[]{0, 1, 2, 0, 2, 3} : new int[]{0, 2, 1, 0, 3, 2};
                        for (int oi : ord) I.add(base + oi);
                    }
                }
            }
            // palette texture: one texel per colour
            int S = 16; while (S * S < palette.size()) S *= 2;
            BufferedImage pal = new BufferedImage(S, S, BufferedImage.TYPE_INT_RGB);
            for (Map.Entry<Integer, Integer> e : palette.entrySet()) {
                int c = e.getKey() | (e.getKey() >> colourBits & ~colourMask & 0xFFFFFF);   // stretch the cut channels back to 0..255
                pal.setRGB(e.getValue() % S, e.getValue() / S, c);
            }
            for (double[] u : U) { int pi = (int) u[0]; u[0] = (pi % S + 0.5) / S; u[1] = (pi / S + 0.5) / S; }
            pos = concat(P); nrm = concat(N); uv = concat(U); jW = concat(W); jIdx = concatI(Jl);
            idx = I.stream().mapToInt(Integer::intValue).toArray();
            triMat = new int[idx.length / 3];
            vcount = pos.length / 3;
            if (outTex != null) ImageIO.write(pal, "png", new File(outTex));
            System.out.printf(Locale.ROOT, "voxel remesh: %d cubes across, cube %.4f units, %d quads, %d colours, palette %dx%d%n",
                    cells, r, idx.length / 6, palette.size(), S, S);
            return pal;
        }

        /**
         * Source vertex shared by a grid corner: of the up to 8 filled cells around it that belong to the same bone or a bone next to it in the chain,
         * the one with the lowest index (cells of other body parts that merely touch in the bind pose are ignored).
         */
        int cornerSource(int[] src, int[] dom, int bone, int nx, int ny, int nz, int x, int y, int z) {
            int best = -1, bestId = Integer.MAX_VALUE;
            for (int dx = -1; dx <= 0; dx++) for (int dy = -1; dy <= 0; dy++) for (int dz = -1; dz <= 0; dz++) {
                int cx = x + dx, cy = y + dy, cz = z + dz;
                if (cx < 0 || cy < 0 || cz < 0 || cx >= nx || cy >= ny || cz >= nz) continue;
                int id = (cx * ny + cy) * nz + cz;
                if (src[id] >= 0 && id < bestId) { int d = dom[src[id]]; if (d == bone || jointParent[d] == bone || jointParent[bone] == d) { bestId = id; best = src[id]; } }
            }
            return best;
        }

        /**
         * Keeps the original mesh and puts every material's texture into one square atlas (size x size): each
         * material gets a tile, its UVs are moved into that tile (UDIM tiles like 1001/1002 are shifted back to 0..1).
         * Vertices shared by two materials are duplicated.
         */
        BufferedImage atlasRemap(BufferedImage[] matTex, int size, String outTex) throws IOException {
            int M = matTex.length, g = (int) Math.ceil(Math.sqrt(M)), T = size / g;
            BufferedImage at = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D gr = at.createGraphics();
            gr.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            for (int m = 0; m < M; m++) if (matTex[m] != null) gr.drawImage(matTex[m], (m % g) * T, (m / g) * T, T, T, null);
            gr.dispose();
            // UV tile of every material (UDIM 1002 -> u in 1..2): from the average UV, not the minimum, so one
            // stray coordinate just outside 0..1 cannot shift the whole material
            double[] fu = new double[M], fv = new double[M], cnt = new double[M];
            for (int t = 0; t < idx.length / 3; t++) for (int c = 0; c < 3; c++) {
                int v = idx[t * 3 + c], m = triMat[t];
                fu[m] += uv[v * 2]; fv[m] += uv[v * 2 + 1]; cnt[m]++;
            }
            for (int m = 0; m < M; m++) { fu[m] = cnt[m] > 0 ? Math.floor(fu[m] / cnt[m]) : 0; fv[m] = cnt[m] > 0 ? Math.floor(fv[m] / cnt[m]) : 0; }
            Map<Long, Integer> map = new HashMap<>();
            List<double[]> P = new ArrayList<>(), N = new ArrayList<>(), U = new ArrayList<>(), W = new ArrayList<>();
            List<int[]> Jl = new ArrayList<>();
            int[] nidx = new int[idx.length];
            // half a (final) texel inside the tile, so sampling never bleeds into the neighbouring tile
            double inset = 0.5 * (pixel > 0 ? (double) size / pixel : 1.0);
            for (int t = 0; t < idx.length / 3; t++) for (int c = 0; c < 3; c++) {
                int v = idx[t * 3 + c], m = triMat[t];
                long key = (long) v * M + m;
                Integer ni = map.get(key);
                if (ni == null) {
                    ni = P.size(); map.put(key, ni);
                    P.add(new double[]{pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2]});
                    N.add(new double[]{nrm[v * 3], nrm[v * 3 + 1], nrm[v * 3 + 2]});
                    double u = Math.max(0, Math.min(1, uv[v * 2] - fu[m])), w = Math.max(0, Math.min(1, uv[v * 2 + 1] - fv[m]));
                    U.add(new double[]{((m % g) * T + inset + u * (T - 2 * inset)) / size, ((m / g) * T + inset + w * (T - 2 * inset)) / size});
                    Jl.add(new int[]{jIdx[v * 4], jIdx[v * 4 + 1], jIdx[v * 4 + 2], jIdx[v * 4 + 3]});
                    W.add(new double[]{jW[v * 4], jW[v * 4 + 1], jW[v * 4 + 2], jW[v * 4 + 3]});
                }
                nidx[t * 3 + c] = ni;
            }
            pos = concat(P); nrm = concat(N); uv = concat(U); jW = concat(W); jIdx = concatI(Jl);
            idx = nidx; vcount = pos.length / 3;
            if (pixel > 0) at = pixelate(at, pixel);
            if (outTex != null) ImageIO.write(at, "png", new File(outTex));
            System.out.printf(Locale.ROOT, "atlas %dx%d, %d materials in %dx%d tiles, %d vertices%n", at.getWidth(), at.getHeight(), M, g, g, vcount);
            return at;
        }

        /** Minecraft-style pixel art: area-averaged down to size x size, colours reduced to 5 bits per channel. */
        BufferedImage pixelate(BufferedImage src, int size) {
            Image scaled = src.getScaledInstance(size, size, Image.SCALE_AREA_AVERAGING);
            BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics(); g.drawImage(scaled, 0, 0, null); g.dispose();
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                int c = out.getRGB(x, y) & 0xF8F8F8;
                out.setRGB(x, y, c | (c >> 5 & 0x070707));
            }
            return out;
        }

        /**
         * Low-poly look: every vertex moves to the average position of the grid cell (cells = N across the model)
         * it falls in, so small details melt into bigger flat facets; triangles that collapse are dropped.
         * UVs and skin weights stay, so textures and animations still fit.
         */
        void snapMesh(int cells) {
            double[] b = bounds(pos);
            double r = Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2])) / cells;
            Map<Long, double[]> sum = new HashMap<>();
            long[] key = new long[vcount];
            for (int v = 0; v < vcount; v++) {
                long kx = (long) Math.floor((pos[v * 3] - b[0]) / r), ky = (long) Math.floor((pos[v * 3 + 1] - b[1]) / r), kz = (long) Math.floor((pos[v * 3 + 2] - b[2]) / r);
                key[v] = (kx * 4096 + ky) * 4096 + kz;
                double[] s = sum.computeIfAbsent(key[v], k -> new double[4]);
                s[0] += pos[v * 3]; s[1] += pos[v * 3 + 1]; s[2] += pos[v * 3 + 2]; s[3]++;
            }
            for (int v = 0; v < vcount; v++) { double[] s = sum.get(key[v]); for (int c = 0; c < 3; c++) pos[v * 3 + c] = s[c] / s[3]; }
            int[] ni = new int[idx.length], nm = new int[triMat.length]; int n = 0;
            for (int t = 0; t < idx.length / 3; t++) {
                long a = key[idx[t * 3]], bb = key[idx[t * 3 + 1]], c = key[idx[t * 3 + 2]];
                if (a == bb || bb == c || a == c) continue;
                System.arraycopy(idx, t * 3, ni, n * 3, 3); nm[n++] = triMat[t];
            }
            System.out.printf(Locale.ROOT, "snap: %d cells across, %d -> %d triangles%n", cells, idx.length / 3, n);
            idx = Arrays.copyOf(ni, n * 3); triMat = Arrays.copyOf(nm, n);
        }

        /** Flat shading: every triangle gets its own face normal (vertices are split per face). */
        void flatNormals() {
            List<double[]> P = new ArrayList<>(), N = new ArrayList<>(), U = new ArrayList<>(), W = new ArrayList<>();
            List<int[]> Jl = new ArrayList<>();
            Map<String, Integer> weldF = new HashMap<>();
            int[] ni = new int[idx.length];
            for (int t = 0; t < idx.length / 3; t++) {
                int a = idx[t * 3], b2 = idx[t * 3 + 1], c = idx[t * 3 + 2];
                double[] e1 = {pos[b2 * 3] - pos[a * 3], pos[b2 * 3 + 1] - pos[a * 3 + 1], pos[b2 * 3 + 2] - pos[a * 3 + 2]};
                double[] e2 = {pos[c * 3] - pos[a * 3], pos[c * 3 + 1] - pos[a * 3 + 1], pos[c * 3 + 2] - pos[a * 3 + 2]};
                double[] fnm = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
                double l = Math.sqrt(fnm[0] * fnm[0] + fnm[1] * fnm[1] + fnm[2] * fnm[2]);
                if (l < 1e-12) fnm = new double[]{0, 1, 0}; else for (int k = 0; k < 3; k++) fnm[k] /= l;
                // keep the original winding side: flip if it disagrees with the smooth normals
                double dot = 0; for (int v : new int[]{a, b2, c}) dot += fnm[0] * nrm[v * 3] + fnm[1] * nrm[v * 3 + 1] + fnm[2] * nrm[v * 3 + 2];
                if (dot < 0) for (int k = 0; k < 3; k++) fnm[k] = -fnm[k];
                for (int k = 0; k < 3; k++) {
                    int v = idx[t * 3 + k];
                    String key = v + "|" + Math.round(fnm[0] * 100) + "," + Math.round(fnm[1] * 100) + "," + Math.round(fnm[2] * 100);
                    Integer id = weldF.get(key);
                    if (id == null) {
                        id = P.size(); weldF.put(key, id);
                        P.add(new double[]{pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2]}); N.add(fnm);
                        U.add(new double[]{uv[v * 2], uv[v * 2 + 1]});
                        Jl.add(new int[]{jIdx[v * 4], jIdx[v * 4 + 1], jIdx[v * 4 + 2], jIdx[v * 4 + 3]});
                        W.add(new double[]{jW[v * 4], jW[v * 4 + 1], jW[v * 4 + 2], jW[v * 4 + 3]});
                    }
                    ni[t * 3 + k] = id;
                }
            }
            pos = concat(P); nrm = concat(N); uv = concat(U); jW = concat(W); jIdx = concatI(Jl); idx = ni; vcount = pos.length / 3;
            System.out.println("flat shading: " + vcount + " vertices");
        }

        int findJoint(String part) {
            int best = -1;
            for (int j = 0; j < joints.length; j++) {
                String n = jname(j).toLowerCase(Locale.ROOT);
                if (n.contains(part) && !n.contains("end") && !n.contains("ik") && !n.contains("upper") && !n.contains("socket")) { best = j; break; }
            }
            return best;
        }

        int jointByPrefix(String p) {
            for (int i = 0; i < joints.length; i++) if (nodes.get(joints[i]).getAsJsonObject().get("name").getAsString().startsWith(p)) return i;
            throw new IllegalArgumentException(p);
        }

        /**
         * Pairs triangles that share an edge (same vertex indices) into quads, so each pair costs 4 emitted
         * vertices instead of 6. Only pairs whose 4 corners are coplanar and form a convex quad are merged.
         * Neighbours with the fewest options are matched first (greedy matching).
         */
        int lodCells = 0;

        /*
         * Reduced mesh by vertex clustering: vertices in one grid cell that also share their main bone, facing and
         * rough UV area collapse onto one of them (an existing vertex, so its uv and weights stay valid). Triangles
         * that become degenerate or duplicate are dropped and the rest are paired into quads again.
         */
        List<int[]> buildLod(int cells) {
            double[] lo = {1e30, 1e30, 1e30}, hi = {-1e30, -1e30, -1e30};
            for (int t = 0; t < idx.length; t++) for (int c = 0; c < 3; c++) {
                double p = pos[idx[t] * 3 + c]; lo[c] = Math.min(lo[c], p); hi[c] = Math.max(hi[c], p);
            }
            double cell = Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2])) / cells;
            Map<String, Integer> rep = new HashMap<>();
            Map<String, double[]> centre = new HashMap<>();
            Map<Integer, String> keyOf = new HashMap<>();
            for (int t = 0; t < idx.length; t++) {
                int v = idx[t];
                if (keyOf.containsKey(v)) continue;
                int bone = jIdx[v * 4]; double bw = -1;
                for (int c = 0; c < 4; c++) if (jW[v * 4 + c] > bw) { bw = jW[v * 4 + c]; bone = jIdx[v * 4 + c]; }
                int face = 0; double fm = -1;
                for (int c = 0; c < 3; c++) if (Math.abs(nrm[v * 3 + c]) > fm) { fm = Math.abs(nrm[v * 3 + c]); face = c * 2 + (nrm[v * 3 + c] < 0 ? 1 : 0); }
                String key = (int) Math.floor((pos[v * 3] - lo[0]) / cell) + "," + (int) Math.floor((pos[v * 3 + 1] - lo[1]) / cell) + ","
                        + (int) Math.floor((pos[v * 3 + 2] - lo[2]) / cell) + "," + bone + "," + face + ","
                        + (int) Math.floor(uv[v * 2] * 6) + "," + (int) Math.floor(uv[v * 2 + 1] * 6);
                keyOf.put(v, key);
                double[] s = centre.computeIfAbsent(key, k -> new double[4]);
                s[0] += pos[v * 3]; s[1] += pos[v * 3 + 1]; s[2] += pos[v * 3 + 2]; s[3]++;
            }
            // representative: the vertex nearest to the cluster centre
            Map<String, Double> bestD = new HashMap<>();
            for (Map.Entry<Integer, String> e : keyOf.entrySet()) {
                int v = e.getKey(); double[] s = centre.get(e.getValue());
                double dx = pos[v * 3] - s[0] / s[3], dy = pos[v * 3 + 1] - s[1] / s[3], dz = pos[v * 3 + 2] - s[2] / s[3];
                double d = dx * dx + dy * dy + dz * dz;
                Double bd = bestD.get(e.getValue());
                if (bd == null || d < bd) { bestD.put(e.getValue(), d); rep.put(e.getValue(), v); }
            }
            int[] saved = idx;
            int[] out = new int[idx.length]; int n = 0;
            Set<String> seen = new HashSet<>();
            for (int t = 0; t < saved.length / 3; t++) {
                int a = rep.get(keyOf.get(saved[t * 3])), b = rep.get(keyOf.get(saved[t * 3 + 1])), c = rep.get(keyOf.get(saved[t * 3 + 2]));
                if (a == b || b == c || a == c) continue;
                int m = Math.min(a, Math.min(b, c));
                String k = m == a ? a + "," + b + "," + c : m == b ? b + "," + c + "," + a : c + "," + a + "," + b;
                if (!seen.add(k)) continue;
                out[n++] = a; out[n++] = b; out[n++] = c;
            }
            idx = Arrays.copyOf(out, n);
            List<int[]> q = pairTriangles();
            idx = saved;
            return q;
        }

        List<int[]> pairTriangles() {
            int tc = idx.length / 3;
            Map<Long, List<Integer>> edges = new HashMap<>();
            for (int t = 0; t < tc; t++) for (int e = 0; e < 3; e++) {
                int a = idx[t * 3 + e], b = idx[t * 3 + (e + 1) % 3];
                long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
                edges.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
            }
            List<List<Integer>> cand = new ArrayList<>();
            for (int t = 0; t < tc; t++) cand.add(new ArrayList<>());
            for (List<Integer> l : edges.values()) {
                if (l.size() != 2) continue;
                int a = l.get(0), b = l.get(1);
                int[] q = mergeIdx(tri(a), tri(b));
                if (q != null && planarConvex(q)) { cand.get(a).add(b); cand.get(b).add(a); }
            }
            boolean[] used = new boolean[tc];
            Integer[] order = new Integer[tc];
            for (int t = 0; t < tc; t++) order[t] = t;
            Arrays.sort(order, Comparator.comparingInt(t -> cand.get(t).size()));
            List<int[]> out = new ArrayList<>();
            for (int t : order) {
                if (used[t]) continue;
                int best = -1;
                for (int o : cand.get(t)) if (!used[o] && (best < 0 || cand.get(o).size() < cand.get(best).size())) best = o;
                used[t] = true;
                if (best >= 0) { used[best] = true; out.add(mergeIdx(tri(t), tri(best))); }
                else { int[] a = tri(t); out.add(new int[]{a[0], a[1], a[2], a[2]}); }
            }
            return out;
        }

        int[] tri(int t) { return new int[]{idx[t * 3], idx[t * 3 + 1], idx[t * 3 + 2]}; }

        boolean planarConvex(int[] q) {
            double[][] p = new double[4][];
            for (int i = 0; i < 4; i++) p[i] = new double[]{pos[q[i] * 3], pos[q[i] * 3 + 1], pos[q[i] * 3 + 2]};
            double[] n0 = null;
            for (int i = 0; i < 4; i++) {
                double[] a = p[i], b = p[(i + 1) % 4], c = p[(i + 2) % 4];
                double[] u = {b[0] - a[0], b[1] - a[1], b[2] - a[2]}, v = {c[0] - b[0], c[1] - b[1], c[2] - b[2]};
                double[] n = {u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]};
                double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
                if (len < 1e-12) return false;
                for (int k = 0; k < 3; k++) n[k] /= len;
                if (n0 == null) n0 = n;
                else if (n[0] * n0[0] + n[1] * n0[1] + n[2] * n0[2] < 0.999) return false;
            }
            return true;
        }

        int[] mergeIdx(int[] a, int[] b) {
            for (int i = 0; i < 3; i++) {
                int p = a[i], q = a[(i + 1) % 3];
                int ip = -1, iq = -1;
                for (int k = 0; k < 3; k++) { if (b[k] == p) ip = k; if (b[k] == q) iq = k; }
                if (ip < 0 || iq < 0) continue;
                int d = b[3 - ip - iq], o = a[(i + 2) % 3];
                return new int[]{p, d, q, o};
            }
            return null;
        }

        void renderPreview(double[] p, List<int[]> quads, BufferedImage tex, String file, String title) throws IOException {
            int W = 900, H = 520; BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D gr = img.createGraphics();
            gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            gr.setColor(new Color(170, 200, 230)); gr.fillRect(0, 0, W, H);
            double sc = 140;
            String[] names = {"front (+Z towards viewer)", "side (front -> right)", "3/4"};
            for (int view = 0; view < 3; view++) {
                double cx = 150 + view * 300, base = H - 40;
                List<double[]> polys = new ArrayList<>();
                for (int[] q : quads) {
                    double[] pp = new double[8]; double depth = 0, cu = 0, cv = 0;
                    for (int c = 0; c < 4; c++) {
                        int v = q[c]; double x = p[v * 3], y = p[v * 3 + 1], z = p[v * 3 + 2], sx, d;
                        if (view == 0) { sx = -x; d = -z; }
                        else if (view == 1) { sx = z; d = x; }
                        else { double a = Math.toRadians(-35); sx = -(x * Math.cos(a) + z * Math.sin(a)); d = -(-x * Math.sin(a) + z * Math.cos(a)); }
                        pp[c * 2] = cx + sx * sc; pp[c * 2 + 1] = base - y * sc; depth += d; cu += uv[v * 2]; cv += uv[v * 2 + 1];
                    }
                    int tx = Math.floorMod((int) (cu / 4 * tex.getWidth()), tex.getWidth()), ty = Math.floorMod((int) (cv / 4 * tex.getHeight()), tex.getHeight());
                    polys.add(new double[]{pp[0], pp[1], pp[2], pp[3], pp[4], pp[5], pp[6], pp[7], depth, tex.getRGB(tx, ty) & 0xFFFFFF});
                }
                polys.sort((a, b2) -> Double.compare(b2[8], a[8]));
                for (double[] pl : polys) {
                    gr.setColor(new Color((int) pl[9]));
                    Path2D path = new Path2D.Double(); path.moveTo(pl[0], pl[1]); path.lineTo(pl[2], pl[3]); path.lineTo(pl[4], pl[5]); path.lineTo(pl[6], pl[7]); path.closePath();
                    gr.fill(path);
                }
                // reference: standard NPC 1.9 blocks and block grid
                gr.setColor(new Color(0, 0, 0, 120));
                gr.drawLine((int) cx - 120, (int) base, (int) cx + 120, (int) base);
                for (int b = 1; b <= 3; b++) { int yy = (int) (base - b * sc); gr.drawLine((int) cx - 120, yy, (int) cx - 100, yy); gr.drawString(b + "", (int) cx - 135, yy + 4); }
                gr.setColor(new Color(200, 0, 0)); int ny = (int) (base - 1.9 * sc); gr.drawLine((int) cx + 95, ny, (int) cx + 120, ny); gr.drawString("NPC 1.9", (int) cx + 70, ny - 3);
                gr.setColor(Color.BLACK); gr.drawString(names[view], (int) cx - 110, 18);
            }
            gr.drawString(title, 8, H - 8);
            gr.dispose(); ImageIO.write(img, "png", new File(file));
        }
    }

    // ---------------- helpers ----------------
    static double diff(double[] a, double[] b) { double m = 0; for (int i = 0; i < a.length; i++) m = Math.max(m, Math.abs(a[i] - b[i])); if (a.length == 4) { double m2 = 0; for (int i = 0; i < 4; i++) m2 = Math.max(m2, Math.abs(a[i] + b[i])); m = Math.min(m, m2); } return m; }
    static double[] concat(List<double[]> l) { int n = 0; for (double[] a : l) n += a.length; double[] r = new double[n]; int o = 0; for (double[] a : l) { System.arraycopy(a, 0, r, o, a.length); o += a.length; } return r; }
    static int[] concatI(List<int[]> l) { int n = 0; for (int[] a : l) n += a.length; int[] r = new int[n]; int o = 0; for (int[] a : l) { System.arraycopy(a, 0, r, o, a.length); o += a.length; } return r; }
    static double[] bounds(double[] p) {
        double[] b = {1e9, 1e9, 1e9, -1e9, -1e9, -1e9};
        for (int i = 0; i < p.length; i += 3) for (int c = 0; c < 3; c++) { b[c] = Math.min(b[c], p[i + c]); b[c + 3] = Math.max(b[c + 3], p[i + c]); }
        return b;
    }
    static double[] floats(int acc) {
        JsonObject a = gltf.getAsJsonArray("accessors").get(acc).getAsJsonObject();
        int comps = comps(a.get("type").getAsString());
        int count = a.get("count").getAsInt();
        JsonObject bv = gltf.getAsJsonArray("bufferViews").get(a.get("bufferView").getAsInt()).getAsJsonObject();
        int off = (bv.has("byteOffset") ? bv.get("byteOffset").getAsInt() : 0) + (a.has("byteOffset") ? a.get("byteOffset").getAsInt() : 0);
        int stride = bv.has("byteStride") ? bv.get("byteStride").getAsInt() : comps * 4;
        double[] r = new double[count * comps];
        for (int i = 0; i < count; i++) for (int c = 0; c < comps; c++) r[i * comps + c] = bin.getFloat(off + i * stride + c * 4);
        return r;
    }
    /** float or normalized unsigned byte/short accessor. */
    static double[] floatsNorm(int acc) {
        JsonObject a = gltf.getAsJsonArray("accessors").get(acc).getAsJsonObject();
        int ct = a.get("componentType").getAsInt();
        if (ct == 5126) return floats(acc);
        int[] iv = ints(acc); double div = ct == 5121 ? 255.0 : 65535.0;
        double[] r = new double[iv.length]; for (int i = 0; i < iv.length; i++) r[i] = iv[i] / div; return r;
    }
    static int comps(String t) { return switch (t) { case "VEC2" -> 2; case "VEC3" -> 3; case "VEC4" -> 4; case "MAT4" -> 16; default -> 1; }; }
    static int[] ints(int acc) {
        JsonObject a = gltf.getAsJsonArray("accessors").get(acc).getAsJsonObject();
        int count = a.get("count").getAsInt() * comps(a.get("type").getAsString()), ct = a.get("componentType").getAsInt();
        JsonObject bv = gltf.getAsJsonArray("bufferViews").get(a.get("bufferView").getAsInt()).getAsJsonObject();
        int off = (bv.has("byteOffset") ? bv.get("byteOffset").getAsInt() : 0) + (a.has("byteOffset") ? a.get("byteOffset").getAsInt() : 0);
        int size = ct == 5125 ? 4 : ct == 5123 ? 2 : 1;
        int comps = comps(a.get("type").getAsString());
        int stride = bv.has("byteStride") ? bv.get("byteStride").getAsInt() : comps * size;
        int[] r = new int[count];
        for (int i = 0; i < count; i++) {
            int el = i / comps, c = i % comps, o = off + el * stride + c * size;
            r[i] = ct == 5125 ? bin.getInt(o) : ct == 5123 ? (bin.getShort(o) & 0xFFFF) : (bin.get(o) & 0xFF);
        }
        return r;
    }
    static double[] ident() { double[] m = new double[16]; m[0] = m[5] = m[10] = m[15] = 1; return m; }
    static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { double s = 0; for (int k = 0; k < 4; k++) s += a[i * 4 + k] * b[k * 4 + j]; r[i * 4 + j] = s; }
        return r;
    }
    static double[] apply(double[] m, double x, double y, double z) {
        return new double[]{m[0] * x + m[1] * y + m[2] * z + m[3], m[4] * x + m[5] * y + m[6] * z + m[7], m[8] * x + m[9] * y + m[10] * z + m[11]};
    }
    static double[] rotY(double a) { double[] m = ident(); m[0] = Math.cos(a); m[2] = Math.sin(a); m[8] = -Math.sin(a); m[10] = Math.cos(a); return m; }
    static double[] scaleTranslate(double k, double tx, double ty, double tz) { double[] m = ident(); m[0] = m[5] = m[10] = k; m[3] = tx; m[7] = ty; m[11] = tz; return m; }
    static double[] arr(JsonObject n, String k, double... def) {
        if (!n.has(k)) return def;
        JsonArray a = n.getAsJsonArray(k); double[] r = new double[a.size()];
        for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsDouble();
        return r;
    }
    static double[] trs(double[] t, double[] q, double[] s) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double[] r = {
                1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)};
        double[] m = ident();
        for (int i = 0; i < 3; i++) { for (int j = 0; j < 3; j++) m[i * 4 + j] = r[i * 3 + j] * s[j]; m[i * 4 + 3] = t[i]; }
        return m;
    }
    static double[] local(JsonObject n) {
        if (n.has("matrix")) { double[] c = arr(n, "matrix"); double[] m = new double[16]; for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) m[i * 4 + j] = c[j * 4 + i]; return m; }
        return trs(arr(n, "translation", 0, 0, 0), arr(n, "rotation", 0, 0, 0, 1), arr(n, "scale", 1, 1, 1));
    }
}

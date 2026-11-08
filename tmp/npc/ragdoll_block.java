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
                    col[id] = c & 0xF0F0F0 | (c >> 4 & 0x0F0F0F) & 0;   // 4 bits per channel: fewer colours, bigger merged faces
                    col[id] = c & 0xF0F0F0;
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
                        for (int i = 0; i < 4; i++) {
                            double[] p = new double[3]; p[axis] = plane; p[ua] = corners[i][0]; p[va] = corners[i][1];
                            P.add(p); N.add(nn); U.add(new double[]{pi, 0});
                            Jl.add(new int[]{jIdx[sv * 4], jIdx[sv * 4 + 1], jIdx[sv * 4 + 2], jIdx[sv * 4 + 3]});
                            W.add(new double[]{jW[sv * 4], jW[sv * 4 + 1], jW[sv * 4 + 2], jW[sv * 4 + 3]});
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
                int c = e.getKey() | (e.getKey() >> 4 & 0x0F0F0F);   // stretch 4-bit channels back to 0..255
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

        int findJoint(String part) {
            int best = -1;
            for (int j = 0; j < joints.length; j++) {
                String n = jname(j).toLowerCase(Locale.ROOT);
                if (n.contains(part) && !n.contains("end") && !n.contains("ik") && !n.contains("upper") && !n.contains("socket")) { best = j; break; }
            }
            return best;
        }


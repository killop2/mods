package com.example.huggynpc.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds vanilla ModelParts out of the ragdoll boxes stored in huggy.skin: body, head and, for every limb, a chain
 * of three nested links (Sable: Ragdolls puts a joint between a part and each nested part).
 * Huggy itself is drawn as a skinned mesh and never uses these; they exist for Sable, which builds a mob ragdoll by
 * rendering the model's ModelPart fields. Each part has one cube whose polygons are replaced by the box faces
 * (Polygon/Vertex are package-private in Minecraft, hence reflection).
 */
final class RagdollParts {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_QUADS = 128;

    /** Parts by index (same order as in huggy.skin); children are also attached inside their parents. */
    final ModelPart[] parts;
    final String[] names;

    private RagdollParts(ModelPart[] parts, String[] names) {
        this.parts = parts;
        this.names = names;
    }

    ModelPart byName(String name) {
        for (int i = 0; i < names.length; i++) if (names[i].equals(name)) return parts[i];
        return null;
    }

    /** Parent part; parts without one (older files) hang on part 0, the body, so the body is the only root. */
    static int parentOf(List<SkinMesh.RagPart> rag, int i) {
        int p = rag.get(i).parent();
        return i == 0 ? -1 : p < 0 ? 0 : p;
    }

    /** @return null if anything is missing. */
    static RagdollParts build(SkinMesh mesh) {
        List<SkinMesh.RagPart> rag = mesh.ragdoll;
        if (rag.isEmpty()) return null;
        try {
            Class<?> vertexCls = Class.forName("net.minecraft.client.model.geom.ModelPart$Vertex");
            Class<?> polygonCls = Class.forName("net.minecraft.client.model.geom.ModelPart$Polygon");
            Constructor<?> vertexCtor = vertexCls.getDeclaredConstructor(float.class, float.class, float.class, float.class, float.class);
            Constructor<?> polygonCtor = polygonCls.getDeclaredConstructor(vertexCls.arrayType(), float.class, float.class, float.class,
                    float.class, float.class, float.class, boolean.class, Direction.class);
            Field normalField = polygonCls.getDeclaredField("normal");
            Field polygonsField = ModelPart.Cube.class.getDeclaredField("polygons");
            vertexCtor.setAccessible(true);
            polygonCtor.setAccessible(true);
            normalField.setAccessible(true);
            polygonsField.setAccessible(true);

            int n = rag.size();
            float[][] pivot = new float[n][];
            for (int i = 0; i < n; i++) {
                SkinMesh.RagPart rp = rag.get(i);
                // model space (blocks, +Y up, +Z front) -> vanilla model space (pixels, +Y down, -Z front, feet at y=24)
                pivot[i] = new float[]{rp.px() * 16F, (1.501F - rp.py()) * 16F, -rp.pz() * 16F};
            }
            ModelPart[] parts = new ModelPart[n];
            String[] names = new String[n];
            // children come after their parents in the file: build from the end so children exist first
            for (int i = n - 1; i >= 0; i--) {
                SkinMesh.RagPart rp = rag.get(i);
                names[i] = rp.name();
                int count = Math.min(rp.count(), MAX_QUADS);
                Object polygons = Array.newInstance(polygonCls, count);
                float[] d = rp.quads();
                float[] pv = pivot[i];
                for (int q = 0; q < count; q++) {
                    int o = q * SkinMesh.RAG_STRIDE;
                    Object verts = Array.newInstance(vertexCls, 4);
                    for (int k = 0; k < 4; k++) Array.set(verts, k, vertexCtor.newInstance(0F, 0F, 0F, 0F, 0F));
                    Object poly = polygonCtor.newInstance(verts, 0F, 0F, 1F, 1F, 1F, 1F, false, Direction.UP);
                    // the constructor remaps UVs into the same array: overwrite with the real corners afterwards
                    for (int k = 0; k < 4; k++) {
                        int v = o + k * 5;
                        float x = d[v] * 16F - pv[0], y = (1.501F - d[v + 1]) * 16F - pv[1], z = -d[v + 2] * 16F - pv[2];
                        Array.set(verts, k, vertexCtor.newInstance(x, y, z, d[v + 3], d[v + 4]));
                    }
                    ((Vector3f) normalField.get(poly)).set(d[o + 20], -d[o + 21], -d[o + 22]);
                    Array.set(polygons, q, poly);
                }
                ModelPart.Cube cube = new ModelPart.Cube(0, 0, 0F, 0F, 0F, 1F, 1F, 1F, 0F, 0F, 0F, false, 1F, 1F,
                        EnumSet.allOf(Direction.class));
                polygonsField.set(cube, polygons);

                Map<String, ModelPart> children = new LinkedHashMap<>();
                for (int c = i + 1; c < n; c++) if (parentOf(rag, c) == i) children.put(rag.get(c).name(), parts[c]);
                ModelPart part = new ModelPart(List.of(cube), children);
                int p = parentOf(rag, i);
                PartPose pose = p < 0
                        ? PartPose.offset(pv[0], pv[1], pv[2])
                        : PartPose.offset(pv[0] - pivot[p][0], pv[1] - pivot[p][1], pv[2] - pivot[p][2]);
                part.loadPose(pose);
                part.setInitialPose(pose);
                parts[i] = part;
            }
            LOGGER.info("[huggynpc] ragdoll parts ready: {}", String.join(", ", names));
            return new RagdollParts(parts, names);
        } catch (Throwable t) {
            LOGGER.warn("[huggynpc] cannot build ragdoll parts, Sable ragdolls will not work for Huggy", t);
            return null;
        }
    }

    /**
     * Placeholder until the parts are built. Lives here and not in HuggyModel: Sable turns every ModelPart field of
     * the model class (static ones too) into a ragdoll part, and an empty one shows up as a glass block.
     */
    static final ModelPart EMPTY = new ModelPart(List.of(), Map.of());
}

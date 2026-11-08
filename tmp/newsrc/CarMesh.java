package com.example.flatoutcar.client;

import com.example.flatoutcar.FlatoutCarMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Треугольный low-poly меш машины, сконвертированный из FBX (см. tools/fbxconv/FbxToMc.java).
 * Формат: assets/flatoutcar/meshes/car.json — палитра цветов и части (кузов, колёса),
 * у каждой части список треугольников: индекс цвета + 9 координат в блоках
 * (+Z — перед машины, +Y — вверх, начало координат — центр машины на уровне земли).
 */
public final class CarMesh {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceLocation LOCATION =
            ResourceLocation.fromNamespaceAndPath(FlatoutCarMod.MOD_ID, "meshes/car.json");

    public enum Role { BODY, WHEEL_FRONT, WHEEL_REAR }

    public static final class Part {
        public final String name;
        public final Role role;
        public final float px, py, pz;
        /** На треугольник: 9 координат (относительно pivot) + 3 компоненты нормали. */
        final float[] data;
        /** Цвет каждого треугольника, ARGB. */
        final int[] colors;

        Part(String name, Role role, float px, float py, float pz, float[] data, int[] colors) {
            this.name = name;
            this.role = role;
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.data = data;
            this.colors = colors;
        }

        /** Рисует часть. Позиция pose уже должна стоять в pivot части. */
        public void render(PoseStack.Pose pose, VertexConsumer vc, int light, int overlay) {
            for (int t = 0; t < this.colors.length; t++) {
                int o = t * 12;
                int c = this.colors[t];
                float nx = this.data[o + 9], ny = this.data[o + 10], nz = this.data[o + 11];
                // Entity-шейдеры рисуют четырёхугольниками: треугольник = квад с повтором последней вершины.
                for (int k = 0; k < 4; k++) {
                    int v = o + Math.min(k, 2) * 3;
                    vc.addVertex(pose, this.data[v], this.data[v + 1], this.data[v + 2])
                            .setColor(c)
                            .setUv(0.5F, 0.5F)
                            .setOverlay(overlay)
                            .setLight(light)
                            .setNormal(pose, nx, ny, nz);
                }
            }
        }
    }

    public final List<Part> parts;

    private CarMesh(List<Part> parts) {
        this.parts = parts;
    }

    public static CarMesh load(ResourceManager manager) {
        Optional<Resource> res = manager.getResource(LOCATION);
        if (res.isEmpty()) {
            LOGGER.error("Car mesh {} not found", LOCATION);
            return new CarMesh(List.of());
        }
        try (Reader reader = res.get().openAsReader()) {
            return parse(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (Exception e) {
            LOGGER.error("Failed to load car mesh {}", LOCATION, e);
            return new CarMesh(List.of());
        }
    }

    private static CarMesh parse(JsonObject root) {
        JsonArray pal = root.getAsJsonArray("palette");
        int[] palette = new int[pal.size()];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = 0xFF000000 | Integer.parseInt(pal.get(i).getAsString(), 16);
        }
        List<Part> parts = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("parts")) {
            JsonObject p = el.getAsJsonObject();
            Role role = switch (p.get("role").getAsString()) {
                case "wheel_front" -> Role.WHEEL_FRONT;
                case "wheel_rear" -> Role.WHEEL_REAR;
                default -> Role.BODY;
            };
            JsonArray pv = p.getAsJsonArray("pivot");
            float px = role == Role.BODY ? 0F : pv.get(0).getAsFloat();
            float py = role == Role.BODY ? 0F : pv.get(1).getAsFloat();
            float pz = role == Role.BODY ? 0F : pv.get(2).getAsFloat();
            JsonArray tris = p.getAsJsonArray("tris");
            int count = tris.size() / 10;
            float[] data = new float[count * 12];
            int[] colors = new int[count];
            for (int t = 0; t < count; t++) {
                int s = t * 10;
                colors[t] = palette[tris.get(s).getAsInt()];
                float[] v = new float[9];
                for (int k = 0; k < 9; k++) {
                    float pivot = k % 3 == 0 ? px : (k % 3 == 1 ? py : pz);
                    v[k] = tris.get(s + 1 + k).getAsFloat() - pivot;
                }
                System.arraycopy(v, 0, data, t * 12, 9);
                float ux = v[3] - v[0], uy = v[4] - v[1], uz = v[5] - v[2];
                float wx = v[6] - v[0], wy = v[7] - v[1], wz = v[8] - v[2];
                float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 1.0E-6F) {
                    nx /= len;
                    ny /= len;
                    nz /= len;
                } else {
                    ny = 1F;
                }
                data[t * 12 + 9] = nx;
                data[t * 12 + 10] = ny;
                data[t * 12 + 11] = nz;
            }
            parts.add(new Part(p.get("name").getAsString(), role, px, py, pz, data, colors));
        }
        return new CarMesh(parts);
    }
}

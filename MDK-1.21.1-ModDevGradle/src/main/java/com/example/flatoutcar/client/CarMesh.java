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
 * Текстурированный меш машины из четырёхугольников (assets/flatoutcar/meshes/car.json),
 * сконвертированный из glTF инструментом tools/GltfToMc.java.
 * Координаты в блоках: +Z — перед машины, +Y — вверх, 0 — центр машины на уровне земли.
 * Меш читается один раз при загрузке ресурсов и хранится плоским float-массивом.
 */
public final class CarMesh {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceLocation LOCATION =
            ResourceLocation.fromNamespaceAndPath(FlatoutCarMod.MOD_ID, "meshes/car.json");

    public enum Role { BODY, WHEEL_FRONT, WHEEL_REAR, STEERING }

    /** На четырёхугольник: 4 x (x, y, z, u, v) + нормаль (3). */
    private static final int STRIDE = 23;

    public static final class Part {
        public final Role role;
        public final float px, py, pz;
        private final float[] data;
        private final int quads;

        Part(Role role, float px, float py, float pz, float[] data, int quads) {
            this.role = role;
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.data = data;
            this.quads = quads;
        }

        /** Рисует часть; pose уже стоит в pivot части. */
        public void render(PoseStack.Pose pose, VertexConsumer vc, int light, int overlay) {
            float[] d = this.data;
            for (int q = 0; q < this.quads; q++) {
                int o = q * STRIDE;
                float nx = d[o + 20], ny = d[o + 21], nz = d[o + 22];
                for (int k = 0; k < 4; k++) {
                    int v = o + k * 5;
                    vc.addVertex(pose, d[v], d[v + 1], d[v + 2])
                            .setColor(-1)
                            .setUv(d[v + 3], d[v + 4])
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
        List<Part> parts = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("parts")) {
            JsonObject p = el.getAsJsonObject();
            String r = p.get("role").getAsString();
            Role role = r.startsWith("wheel_front") ? Role.WHEEL_FRONT
                    : r.startsWith("wheel_rear") ? Role.WHEEL_REAR
                    : r.equals("steering") ? Role.STEERING : Role.BODY;
            JsonArray pv = p.getAsJsonArray("pivot");
            float px = role == Role.BODY ? 0F : pv.get(0).getAsFloat();
            float py = role == Role.BODY ? 0F : pv.get(1).getAsFloat();
            float pz = role == Role.BODY ? 0F : pv.get(2).getAsFloat();
            JsonArray src = p.getAsJsonArray("quads");
            int quads = src.size() / 20;
            float[] data = new float[quads * STRIDE];
            float[] pivot = {px, py, pz};
            for (int q = 0; q < quads; q++) {
                int s = q * 20, o = q * STRIDE;
                for (int k = 0; k < 4; k++) {
                    for (int c = 0; c < 3; c++) {
                        data[o + k * 5 + c] = src.get(s + k * 5 + c).getAsFloat() - pivot[c];
                    }
                    data[o + k * 5 + 3] = src.get(s + k * 5 + 3).getAsFloat();
                    data[o + k * 5 + 4] = src.get(s + k * 5 + 4).getAsFloat();
                }
                // нормаль по диагоналям четырёхугольника (работает и для вырожденных треугольников)
                float ax = data[o + 10] - data[o], ay = data[o + 11] - data[o + 1], az = data[o + 12] - data[o + 2];
                float bx = data[o + 15] - data[o + 5], by = data[o + 16] - data[o + 6], bz = data[o + 17] - data[o + 7];
                float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 1.0E-6F) {
                    nx /= len;
                    ny /= len;
                    nz /= len;
                } else {
                    nx = 0F;
                    ny = 1F;
                    nz = 0F;
                }
                data[o + 20] = nx;
                data[o + 21] = ny;
                data[o + 22] = nz;
            }
            parts.add(new Part(role, px, py, pz, data, quads));
        }
        return new CarMesh(parts);
    }
}

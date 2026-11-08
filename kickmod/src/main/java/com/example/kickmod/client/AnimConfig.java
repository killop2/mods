package com.example.kickmod.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * config/kickmod/animations.json — the list of hits that Numpad 9 picks from at random.
 * Re-read on every key press, so edits work without restarting the game.
 *
 * Per animation:
 *   file      name of the .anim (config/kickmod/anims/NAME.anim first, then the built-in one)
 *   enabled   false = never picked
 *   slow      playback slowdown (1 = as authored, 2.3 = 2.3x slower)
 *   camera    side: "auto" | "left" | "right" (left/right of the attacker, facing the target)
 *             distance: blocks from the middle between you and the target
 *             height:   camera height above the middle point (blocks, the middle is 1 block above the feet)
 *             forward:  + moves the camera towards the target side, - behind you
 *             lookHeight: where it looks, blocks above the feet
 *   launch    forward / up: multipliers of the server launch speeds (0.3 .. 2.5)
 */
public final class AnimConfig {
    public static final class Camera {
        public String side = "auto";
        public double distance = 4.0D;
        public double height = 0.6D;
        public double forward = -0.6D;
        public double lookHeight = 0.85D;
    }

    public static final class Launch {
        public double forward = 1.0D;
        public double up = 1.0D;
    }

    public static final class Entry {
        public String file;
        public boolean enabled = true;
        public double slow = 1.0D;
        public Camera camera = new Camera();
        public Launch launch = new Launch();
    }

    public static final class Root {
        public List<Entry> animations = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AnimConfig() {
    }

    public static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve("kickmod");
    }

    private static Entry e(String file, double slow, String side, double dist, double height, double fwd, double look,
                           double lf, double lu) {
        Entry x = new Entry();
        x.file = file;
        x.slow = slow;
        x.camera.side = side;
        x.camera.distance = dist;
        x.camera.height = height;
        x.camera.forward = fwd;
        x.camera.lookHeight = look;
        x.launch.forward = lf;
        x.launch.up = lu;
        return x;
    }

    public static Root defaults() {
        Root r = new Root();
        r.animations.add(e("kick", 2.3D, "auto", 4.0D, 0.6D, -0.6D, 0.85D, 1.0D, 1.0D));
        r.animations.add(e("uppercut", 1.0D, "auto", 3.6D, 0.1D, -0.3D, 1.1D, 0.6D, 1.5D));
        r.animations.add(e("cross", 1.0D, "auto", 3.4D, 0.5D, -1.2D, 1.2D, 1.2D, 0.8D));
        r.animations.add(e("hook", 1.0D, "left", 3.8D, 0.7D, 0.4D, 1.1D, 1.0D, 0.9D));
        r.animations.add(e("spinkick", 1.0D, "auto", 4.4D, 0.9D, 0.0D, 1.0D, 1.35D, 1.0D));
        r.animations.add(e("pushkick", 1.0D, "right", 3.8D, 0.4D, -0.4D, 0.9D, 1.5D, 0.75D));
        return r;
    }

    /** Reads the file (creating it with the defaults the first time). Never throws. */
    public static Root load() {
        Path file = dir().resolve("animations.json");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.createDirectories(dir().resolve("anims"));
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(defaults(), w);
                }
            }
            try (Reader rd = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Root r = GSON.fromJson(rd, Root.class);
                if (r == null || r.animations == null || r.animations.isEmpty()) return defaults();
                for (Entry x : r.animations) {
                    if (x.camera == null) x.camera = new Camera();
                    if (x.launch == null) x.launch = new Launch();
                    if (x.slow <= 0.05D) x.slow = 1.0D;
                }
                return r;
            }
        } catch (Exception ex) {
            LogUtils.getLogger().warn("[kickmod] config/kickmod/animations.json is broken, using defaults: {}", ex.toString());
            return defaults();
        }
    }
}

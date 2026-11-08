package com.example.wheelofwonder.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;

/**
 * Shader packs (Iris) handle only the common render types well. The additive "eyes" glow and the "lightning"
 * ribbons come out as black-and-white noise under packs like Sildur's, so while a shader pack is active the
 * effects use plain translucent entity rendering instead. Without shaders nothing changes.
 * Iris is looked up through its public API by reflection: no compile dependency, works without Iris.
 */
public final class ShaderCompat {
    private static boolean resolved;
    private static Object api;
    private static Method inUse;

    private ShaderCompat() {
    }

    /** White with a soft alpha edge: the glow texture for translucent (shader pack) rendering. */
    static final net.minecraft.resources.ResourceLocation GLOW_SOFT =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.example.wheelofwonder.WheelMod.MOD_ID, "textures/entity/glow_soft.png");

    private static boolean cached;
    private static long checkedAt;

    /** Shader pack in use (asked at most twice a second: it is called for every glow quad). */
    public static boolean shadersOn() {
        long now = System.nanoTime();
        if (checkedAt == 0L || now - checkedAt > 500_000_000L) {
            checkedAt = now;
            cached = queryShaders();
        }
        return cached;
    }

    /**
     * The glow colours are made for additive light: brightness is stored as "darker = weaker". Blended normally
     * (shader packs) that paints dark discs, so there the colour goes to full brightness and the weakness goes into
     * the alpha instead.
     */
    static int glowColor(int argb) {
        if (!shadersOn()) return argb;
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        int m = Math.max(r, Math.max(g, b));
        if (m <= 0) return 0;
        float k = 255.0F / m;
        int a = Math.min(255, Math.round(m * 0.95F));
        return (a << 24) | (Math.min(255, Math.round(r * k)) << 16) | (Math.min(255, Math.round(g * k)) << 8) | Math.min(255, Math.round(b * k));
    }

    private static boolean queryShaders() {
        if (!resolved) {
            resolved = true;
            try {
                Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = c.getMethod("getInstance").invoke(null);
                inUse = c.getMethod("isShaderPackInUse");
            } catch (Throwable ignored) {
                api = null;
            }
        }
        if (api == null) return false;
        try {
            return Boolean.TRUE.equals(inUse.invoke(api));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Soft glow: additive "eyes" without shaders, translucent with shaders. */
    public static RenderType glow(ResourceLocation texture) {
        // every glow uses the round glow texture; with shaders its soft white twin (no dark rim)
        return shadersOn() ? RenderType.entityTranslucent(GLOW_SOFT) : RenderType.eyes(texture);
    }
}

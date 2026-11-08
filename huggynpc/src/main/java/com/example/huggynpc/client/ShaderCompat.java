package com.example.huggynpc.client;

import java.lang.reflect.Method;

/** Asks Iris (public API, by reflection: works without Iris) whether the shadow pass is being rendered. */
final class ShaderCompat {
    private static boolean resolved;
    private static Object api;
    private static Method shadowPass;

    private ShaderCompat() {
    }

    static boolean inShadowPass() {
        if (!resolved) {
            resolved = true;
            try {
                Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = c.getMethod("getInstance").invoke(null);
                shadowPass = c.getMethod("isRenderingShadowPass");
            } catch (Throwable ignored) {
                api = null;
            }
        }
        if (api == null) return false;
        try {
            return Boolean.TRUE.equals(shadowPass.invoke(api));
        } catch (Throwable t) {
            return false;
        }
    }
}

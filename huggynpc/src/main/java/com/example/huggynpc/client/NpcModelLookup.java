package com.example.huggynpc.client;

import com.example.huggynpc.HuggyEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Finds the Huggy model of a Custom NPC whose look is set to Huggy (Model -> Entities). Reflection only. */
public final class NpcModelLookup {
    private static boolean resolved;
    private static Field modelData;
    private static Method getEntity;

    private NpcModelLookup() {
    }

    /** @return the Huggy model if {@code npc} is a Custom NPC displayed as Huggy, else null. */
    public static EntityModel<?> huggyModelFor(LivingEntity npc) {
        LivingEntity shown = shownEntity(npc);
        if (!(shown instanceof HuggyEntity)) return null;
        EntityRenderer<?> r = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(shown);
        return r instanceof LivingEntityRenderer<?, ?> lr ? lr.getModel() : null;
    }

    /** @return the Huggy renderer if {@code npc} is a Custom NPC displayed as Huggy, else null. */
    public static EntityRenderer<?> huggyRendererFor(net.minecraft.world.entity.Entity npc) {
        if (!(npc instanceof LivingEntity le)) return null;
        LivingEntity shown = shownEntity(le);
        return shown instanceof HuggyEntity ? Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(shown) : null;
    }

    static LivingEntity shownEntity(LivingEntity npc) {
        if (npc == null || !npc.getClass().getName().startsWith("noppes.npcs.")) return null;
        try {
            if (!resolved || !modelData.getDeclaringClass().isInstance(npc)) {
                Field f;
                try {
                    f = npc.getClass().getField("modelData");
                } catch (NoSuchFieldException e) {
                    return null;   // a Custom NPCs entity without model data (e.g. crystal, slime)
                }
                for (Method m : f.getType().getMethods()) {
                    if (m.getName().equals("getEntity") && m.getParameterCount() == 1) getEntity = m;
                }
                modelData = f;
                resolved = getEntity != null;
            }
            if (!resolved) return null;
            Object data = modelData.get(npc);
            Object e = data == null ? null : getEntity.invoke(data, npc);
            return e instanceof LivingEntity le ? le : null;
        } catch (Throwable t) {
            return null;
        }
    }
}

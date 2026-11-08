package com.example.huggynpc.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Delete all" in Custom NPCs' Nearby NPCs window (NPC Wand): a button under "Freeze" that, after a confirmation,
 * deletes every NPC in the list. It sends Custom NPCs' own delete packet once per NPC, so their permission checks and
 * clean-up are the same as for the "Delete" button. Reflection only: no compile dependency on Custom NPCs.
 */
public final class RemoteDeleteAll {
    private static final String EDITOR = "noppes.npcs.client.gui.GuiNpcRemoteEditor";

    private RemoteDeleteAll() {
    }

    public static void onInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!screen.getClass().getName().equals(EDITOR)) return;
        try {
            int left = screen.getClass().getField("guiLeft").getInt(screen);
            int top = screen.getClass().getField("guiTop").getInt(screen);
            event.addListener(Button.builder(Component.translatable("huggynpc.remote.deleteall"), b -> confirm(screen))
                    .bounds(left + 170, top + 154, 82, 20).build());
        } catch (Throwable t) {
            LogUtils.getLogger().warn("[huggynpc] cannot add 'Delete all' to the Nearby NPCs window ({})", t.toString());
        }
    }

    private static void confirm(Screen editor) {
        Minecraft mc = Minecraft.getInstance();
        List<Integer> ids = ids(editor);
        mc.setScreen(new ConfirmScreen(yes -> {
            if (yes) deleteAll(ids);
            reopen(mc, editor, yes);
        }, Component.translatable("huggynpc.remote.deleteall.title", ids.size()), Component.translatable("huggynpc.remote.deleteall.message")));
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> ids(Screen editor) {
        try {
            Field f = editor.getClass().getDeclaredField("data");
            f.setAccessible(true);
            return new ArrayList<>(((Map<String, Integer>) f.get(editor)).values());
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static void deleteAll(List<Integer> ids) {
        try {
            Class<?> packet = Class.forName("noppes.npcs.packets.server.SPacketRemoteNpcDelete");
            var ctor = packet.getConstructor(int.class);
            var send = Class.forName("noppes.npcs.packets.Packets").getMethod("sendServer",
                    net.minecraft.network.protocol.common.custom.CustomPacketPayload.class);
            for (int id : ids) send.invoke(null, ctor.newInstance(id));
        } catch (Throwable t) {
            LogUtils.getLogger().warn("[huggynpc] 'Delete all' failed", t);
        }
    }

    /** Back to a fresh list (after deleting it asks the server again, the packets above are handled first). */
    private static void reopen(Minecraft mc, Screen old, boolean fresh) {
        if (!fresh) {
            mc.setScreen(old);
            return;
        }
        try {
            mc.setScreen((Screen) Class.forName(EDITOR).getConstructor().newInstance());
        } catch (Throwable t) {
            mc.setScreen(null);
        }
    }
}

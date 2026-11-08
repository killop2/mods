package com.example.wheelofwonder;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Impalement Arts: a fan of six Dark Carnival throwing daggers. Right click: they circle the thrower, then fly into
 * the NPC in the aimed direction (up to 70 blocks), stay stuck in it and carry it away as a ragdoll.
 * The right click is caught on the client (ClientSetup) before any entity interaction; DaggerServer runs the throw.
 */
public class ImpalementArtsItem extends Item {
    public ImpalementArtsItem(Properties props) {
        super(props);
    }

    public static boolean isDaggers(ItemStack stack) {
        return stack.getItem() instanceof ImpalementArtsItem;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.wheelofwonder.impalement_arts.desc").withStyle(ChatFormatting.RED));
        tooltip.add(Component.translatable("item.wheelofwonder.impalement_arts.desc2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.wheelofwonder.impalement_arts.desc3").withStyle(ChatFormatting.GRAY));
    }
}

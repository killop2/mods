package com.example.wheelofwonder;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Shockwave!: a crystal war spear. Right click: the hero swings it overhead and slams it into the ground; a glowing
 * rift runs along the ground in the aimed direction, pulls every NPC it touches into a vortex and blasts them apart.
 * The right click is caught on the client (ClientSetup -> ShockwaveController); ShockwaveServer runs it.
 */
public class ShockwaveItem extends Item {
    public ShockwaveItem(Properties props) {
        super(props);
    }

    public static boolean isShockwave(ItemStack stack) {
        return stack.getItem() instanceof ShockwaveItem;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.wheelofwonder.shockwave.desc").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.wheelofwonder.shockwave.desc2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.wheelofwonder.shockwave.desc3").withStyle(ChatFormatting.GRAY));
    }
}

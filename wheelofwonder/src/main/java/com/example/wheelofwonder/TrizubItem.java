package com.example.wheelofwonder;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Trident ("Тризуб!"): right click an NPC and the storm gathers, the ghost ship comes out of the fog, its dead crew
 * take the NPC aboard and throw it off the stern far up in the sky. The click is caught on the client
 * (TrizubController); TrizubServer and GhostShipEntity run it.
 */
public class TrizubItem extends Item {
    public TrizubItem(Properties props) {
        super(props);
    }

    public static boolean isTrizub(ItemStack stack) {
        return stack.getItem() instanceof TrizubItem;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.wheelofwonder.trizub.desc").withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.translatable("item.wheelofwonder.trizub.desc2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.wheelofwonder.trizub.desc3").withStyle(ChatFormatting.GRAY));
    }
}

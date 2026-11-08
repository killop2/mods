package com.example.wheelofwonder;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Sinister Chain: hellfire chains of the Dark Carnival. Right click on an NPC fastens it (by a hand or a leg) to
 * the NPC chained before it; the whole row follows the first one. The clicks are caught on the client
 * (ClientSetup) before any entity interaction; ChainServer keeps and pulls the chains.
 */
public class SinisterChainItem extends Item {
    public SinisterChainItem(Properties props) {
        super(props);
    }

    public static boolean isChain(ItemStack stack) {
        return stack.getItem() instanceof SinisterChainItem;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.wheelofwonder.sinister_chain.desc").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.wheelofwonder.sinister_chain.desc2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.wheelofwonder.sinister_chain.desc3").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.wheelofwonder.sinister_chain.desc4").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.wheelofwonder.sinister_chain.desc5").withStyle(ChatFormatting.RED));
    }
}

package com.example.wheelofwonder.mixin;

import com.example.wheelofwonder.WheelConfig;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.gui.components.toasts.TutorialToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops the "New recipes unlocked" and tutorial pop-ups (config: hideRecipeToasts). Advancements still show. */
@Mixin(ToastComponent.class)
public abstract class ToastMixin {
    @Inject(method = "addToast", at = @At("HEAD"), cancellable = true, require = 0)
    private void wheelofwonder$noRecipeToasts(Toast toast, CallbackInfo ci) {
        if ((toast instanceof RecipeToast || toast instanceof TutorialToast) && WheelConfig.HIDE_TOASTS.get()) ci.cancel();
    }
}

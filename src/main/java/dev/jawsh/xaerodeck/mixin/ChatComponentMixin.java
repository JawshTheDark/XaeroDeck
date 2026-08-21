package dev.jawsh.xaerodeck.mixin;

import dev.jawsh.xaerodeck.Notifications;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public class ChatComponentMixin {
    /**
     * Client-system messages are the path used by Meteor's notifier (and other
     * client mods) — server chat goes through different methods, so this
     * captures only mod notifications.
     */
    @Inject(method = "addClientSystemMessage", at = @At("HEAD"))
    private void xaerodeck$captureNotification(Component message, CallbackInfo ci) {
        try {
            String text = message.getString();
            Notifications.add(text, dev.jawsh.xaerodeck.StyledText.spans(message));
            dev.jawsh.xaerodeck.SeedCapture.check(text);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "addServerSystemMessage", at = @At("HEAD"), require = 0)
    private void xaerodeck$captureServerChat(Component message, CallbackInfo ci) {
        if (!dev.jawsh.xaerodeck.DeckConfig.get().chatRelay) return;
        try {
            dev.jawsh.xaerodeck.ChatBuffer.add(message.getString(), dev.jawsh.xaerodeck.StyledText.spans(message));
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "addPlayerMessage", at = @At("HEAD"), require = 0)
    private void xaerodeck$capturePlayerChat(Component message,
                                            net.minecraft.network.chat.MessageSignature signature,
                                            net.minecraft.client.multiplayer.chat.GuiMessageTag tag,
                                            CallbackInfo ci) {
        if (!dev.jawsh.xaerodeck.DeckConfig.get().chatRelay) return;
        try {
            dev.jawsh.xaerodeck.ChatBuffer.add(message.getString(), dev.jawsh.xaerodeck.StyledText.spans(message));
        } catch (Throwable ignored) {
        }
    }
}

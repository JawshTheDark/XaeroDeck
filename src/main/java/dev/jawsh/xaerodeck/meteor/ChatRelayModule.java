package dev.jawsh.xaerodeck.meteor;

import dev.jawsh.xaerodeck.DeckConfig;
import meteordevelopment.meteorclient.systems.modules.Module;

/**
 * Opt-in: relay in-game chat to companion devices and allow sending chat from them.
 * The module's on/off state IS the opt-in.
 */
public class ChatRelayModule extends Module {
    public ChatRelayModule() {
        super(XaeroDeckAddon.CATEGORY, "chat-relay",
                "Relay in-game chat to XaeroDeck devices and allow sending chat from them.");
    }

    @Override
    public void onActivate() {
        DeckConfig.get().chatRelay = true;
        DeckConfig.get().save();
        info("Chat relay enabled — companion devices can read and send chat.");
    }

    @Override
    public void onDeactivate() {
        DeckConfig.get().chatRelay = false;
        DeckConfig.get().save();
    }
}

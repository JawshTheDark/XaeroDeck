package dev.jawsh.xaerodeck.meteor;

import dev.jawsh.xaerodeck.DeckConfig;
import meteordevelopment.meteorclient.systems.modules.Module;

/**
 * Gates remote control from companion devices: Meteor module toggling/settings
 * and Baritone goto commands. Requires the pairing token either way.
 */
public class RemoteControlModule extends Module {
    public RemoteControlModule() {
        super(XaeroDeckAddon.CATEGORY, "remote-control",
                "Allow XaeroDeck devices to control Meteor modules and Baritone.");
    }

    @Override
    public void onActivate() {
        DeckConfig.get().remoteControl = true;
        DeckConfig.get().save();
        info("Remote control enabled (token: " + DeckConfig.get().token + ")");
    }

    @Override
    public void onDeactivate() {
        DeckConfig.get().remoteControl = false;
        DeckConfig.get().save();
    }
}

package dev.jawsh.xaerodeck.meteor;

import dev.jawsh.xaerodeck.MeteorService;
import meteordevelopment.meteorclient.events.meteor.ActiveModulesChangedEvent;
import meteordevelopment.orbit.EventHandler;

/** Bumps the stream's meteorRev whenever any module toggles, from any source. */
public class MeteorEvents {
    @EventHandler
    private void onActiveModulesChanged(ActiveModulesChangedEvent event) {
        MeteorService.bumpRev();
    }
}

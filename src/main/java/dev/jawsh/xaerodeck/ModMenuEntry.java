package dev.jawsh.xaerodeck;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ModMenuEntry implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return DeckConfigScreen::new;
    }

    static class DeckConfigScreen extends Screen {
        private final Screen parent;

        DeckConfigScreen(Screen parent) {
            super(Component.literal("XaeroDeck"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int cx = width / 2;
            int y = height / 4;
            DeckConfig cfg = DeckConfig.get();

            addRenderableWidget(new StringWidget(cx - 150, y - 24, 300, 20,
                    Component.literal("XaeroDeck — LAN map server on port " + cfg.port), font));

            addRenderableWidget(Button.builder(serverLabel(), b -> {
                        if (XaeroDeck.isRunning()) XaeroDeck.stopServer();
                        else XaeroDeck.startServer();
                        b.setMessage(serverLabel());
                    })
                    .bounds(cx - 100, y, 200, 20).build());

            addRenderableWidget(Button.builder(autoLabel(), b -> {
                        DeckConfig.get().autoStart = !DeckConfig.get().autoStart;
                        DeckConfig.get().save();
                        b.setMessage(autoLabel());
                    })
                    .bounds(cx - 100, y + 24, 200, 20).build());

            addRenderableWidget(Button.builder(sethomeLabel(), b -> {
                        DeckConfig.get().watchSetHome = !DeckConfig.get().watchSetHome;
                        DeckConfig.get().save();
                        b.setMessage(sethomeLabel());
                    })
                    .bounds(cx - 100, y + 72, 200, 20).build());

            addRenderableWidget(Button.builder(rateLabel(), b -> {
                        DeckConfig c = DeckConfig.get();
                        c.streamHz = c.streamHz >= 20 ? 1 : c.streamHz + (c.streamHz < 5 ? 1 : 5);
                        c.save();
                        b.setMessage(rateLabel());
                    })
                    .bounds(cx - 100, y + 48, 200, 20).build());

            addRenderableWidget(new StringWidget(cx - 150, y + 100, 300, 20,
                    Component.literal("Port is set in config/xaerodeck.json (or the Meteor module)"), font));

            addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                    .bounds(cx - 100, y + 128, 200, 20).build());
        }

        private Component sethomeLabel() {
            return Component.literal("/sethome waypoints: " + (DeckConfig.get().watchSetHome ? "ON" : "OFF"));
        }

        private Component serverLabel() {
            return Component.literal("Server: " + (XaeroDeck.isRunning() ? "Running — stop" : "Stopped — start"));
        }

        private Component autoLabel() {
            return Component.literal("Auto-start: " + (DeckConfig.get().autoStart ? "ON" : "OFF"));
        }

        private Component rateLabel() {
            return Component.literal("Stream rate: " + DeckConfig.get().streamHz + "/s");
        }

        @Override
        public void onClose() {
            minecraft.gui.setScreen(parent);
        }
    }
}

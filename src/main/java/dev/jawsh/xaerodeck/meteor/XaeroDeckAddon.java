package dev.jawsh.xaerodeck.meteor;

import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.world.item.Items;

public class XaeroDeckAddon extends MeteorAddon {
    public static final Category CATEGORY =
            new Category("XaeroDeck", () -> Items.FILLED_MAP.getDefaultInstance());

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public void onInitialize() {
        dev.jawsh.xaerodeck.XaeroDeck.LOG.info("XaeroDeck Meteor addon initializing");
        Modules.get().add(new XaeroDeckModule());
        Modules.get().add(new ChatRelayModule());
        Modules.get().add(new RemoteControlModule());
        dev.jawsh.xaerodeck.XaeroDeck.LOG.info("XaeroDeck modules registered in their own category");
    }

    @Override
    public String getPackage() {
        return "dev.jawsh.xaerodeck.meteor";
    }
}

package dev.jawsh.xaerodeck.mixin;

import dev.jawsh.xaerodeck.Autopilot;
import dev.jawsh.xaerodeck.Notifications;
import dev.jawsh.xaerodeck.RouteGen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;

/** Adds XaeroDeck autopilot actions to Xaero WorldMap's right-click menu. */
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class GuiMapMixin {
    @Shadow
    private int rightClickX;
    @Shadow
    private int rightClickZ;

    @Inject(method = "getRightClickOptions", at = @At("RETURN"), require = 0)
    private void xaerodeck$addOptions(CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
        ArrayList<RightClickOption> options = cir.getReturnValue();
        if (options == null) return;
        IRightClickableElement self = (IRightClickableElement) this;

        options.add(new RightClickOption("gui.xaerodeck.fly_here", options.size(), self) {
            @Override
            public void onAction(Screen screen) {
                if (requireModule()) return;
                Autopilot.setTarget(rightClickX, rightClickZ);
                Notifications.add("✈ FLY %d %d".formatted(rightClickX, rightClickZ));
            }
        });
        options.add(new RightClickOption("gui.xaerodeck.orbit_here", options.size(), self) {
            @Override
            public void onAction(Screen screen) {
                if (requireModule()) return;
                Autopilot.setRoute(RouteGen.circle(rightClickX, rightClickZ, 250, 32), true);
                Notifications.add("✈ ORBIT %d %d r=250".formatted(rightClickX, rightClickZ));
            }
        });
        options.add(new RightClickOption("gui.xaerodeck.spiral_here", options.size(), self) {
            @Override
            public void onAction(Screen screen) {
                if (requireModule()) return;
                Autopilot.setRoute(RouteGen.spiral(rightClickX, rightClickZ, 160, 8), false);
                Notifications.add("✈ SPIRAL %d %d".formatted(rightClickX, rightClickZ));
            }
        });
        if (Autopilot.getTarget() != null) {
            options.add(new RightClickOption("gui.xaerodeck.cancel_flight", options.size(), self) {
                @Override
                public void onAction(Screen screen) {
                    Autopilot.clear();
                    Notifications.add("✈ FLIGHT CANCELLED");
                }
            });
        }
    }

    private static boolean requireModule() {
        if (!Autopilot.enabled) {
            Notifications.add("⚠ ENABLE THE DECK-AUTOPILOT MODULE FIRST");
            return true;
        }
        return false;
    }
}

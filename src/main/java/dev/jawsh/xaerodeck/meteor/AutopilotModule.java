package dev.jawsh.xaerodeck.meteor;

import dev.jawsh.xaerodeck.Autopilot;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;

/**
 * Steering-only autopilot for ElytraFly users: aim at a target tapped on the
 * companion app while your fly module provides the propulsion. No packets,
 * no movement — it only turns your head.
 */
public class AutopilotModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> turnSpeed = sgGeneral.add(new DoubleSetting.Builder()
            .name("turn-speed")
            .description("How fast to ease toward the target heading, in degrees per tick.")
            .defaultValue(4.0)
            .min(0.5)
            .max(20.0)
            .sliderRange(0.5, 20.0)
            .onChanged(v -> Autopilot.turnSpeed = v)
            .build());

    private final Setting<Boolean> managePitch = sgGeneral.add(new BoolSetting.Builder()
            .name("manage-pitch")
            .description("Also level your pitch toward cruise-pitch. Turn OFF for pitch40-style bounce flight.")
            .defaultValue(true)
            .onChanged(v -> Autopilot.managePitch = v)
            .build());

    private final Setting<Double> cruisePitch = sgGeneral.add(new DoubleSetting.Builder()
            .name("cruise-pitch")
            .description("Pitch to hold while cruising. 0 = level.")
            .defaultValue(0.0)
            .min(-45.0)
            .max(45.0)
            .sliderRange(-20.0, 20.0)
            .onChanged(v -> Autopilot.cruisePitch = v)
            .build());

    private final Setting<Integer> arrivalRadius = sgGeneral.add(new IntSetting.Builder()
            .name("arrival-radius")
            .description("Stop steering when within this many blocks of the target.")
            .defaultValue(64)
            .min(8)
            .max(512)
            .sliderRange(8, 512)
            .onChanged(v -> Autopilot.arrivalRadius = v)
            .build());

    public AutopilotModule() {
        super(XaeroDeckAddon.CATEGORY, "deck-autopilot",
                "Steer toward targets tapped on the companion app while ElytraFly (or you) provides thrust.");
    }

    @Override
    public void onActivate() {
        Autopilot.turnSpeed = turnSpeed.get();
        Autopilot.managePitch = managePitch.get();
        Autopilot.cruisePitch = cruisePitch.get();
        Autopilot.arrivalRadius = arrivalRadius.get();
        Autopilot.enabled = true;
        info("Autopilot armed — tap a target in the app's FLY mode.");
    }

    @Override
    public void onDeactivate() {
        Autopilot.enabled = false;
        Autopilot.clear();
    }

    @Override
    public String getInfoString() {
        double[] t = Autopilot.getTarget();
        return t == null ? null : (int) t[0] + " " + (int) t[1];
    }
}

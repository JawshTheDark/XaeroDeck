package dev.jawsh.xaerodeck;

import net.minecraft.client.Minecraft;

/**
 * Steering-only elytra autopilot: eases the player's yaw (and optionally pitch)
 * toward a target while something else (e.g. Meteor ElytraFly) provides
 * propulsion. Never sends packets or moves the player itself.
 */
public class Autopilot {
    /** Armed by the Meteor deck-autopilot module. */
    public static volatile boolean enabled = false;

    // config mirrored from the Meteor module settings
    public static volatile double turnSpeed = 4.0;     // degrees per tick
    public static volatile boolean managePitch = true;
    public static volatile double cruisePitch = 0.0;   // degrees, 0 = level
    public static volatile int arrivalRadius = 64;     // blocks

    private static volatile double[] target = null;    // [x, z]
    private static boolean holdingForward = false;
    private static boolean weEnabledElytraFly = false;

    public static void setTarget(double x, double z) {
        target = new double[]{x, z};
        // give the flight a thrust source: enable ElytraFly if it isn't on
        if (!MeteorHook.isElytraFlyActive()) {
            weEnabledElytraFly = MeteorHook.setElytraFly(true);
        }
    }

    public static double[] getTarget() {
        return target;
    }

    public static void clear() {
        target = null;
    }

    /** Called every client tick from XaeroDeck's tick handler. */
    public static void tick(Minecraft mc) {
        double[] t = target;
        if (t == null || !enabled || mc.player == null) {
            releaseForward(mc);
            restoreElytraFly();
            return;
        }

        double dx = t[0] - mc.player.getX();
        double dz = t[1] - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= arrivalRadius) {
            target = null;
            releaseForward(mc);
            restoreElytraFly();
            Notifications.add("✈ ARRIVED — %d, %d".formatted((int) t[0], (int) t[1]));
            return;
        }

        // on the ground: let go of W but keep ElytraFly armed for takeoff
        if (!mc.player.isFallFlying()) {
            releaseForward(mc);
            return;
        }

        // thrust: hold forward like a pressed W key — works with every ElytraFly mode
        mc.options.keyUp.setDown(true);
        holdingForward = true;

        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float delta = wrap(targetYaw - mc.player.getYRot());
        float step = (float) Math.min(Math.abs(delta), turnSpeed);
        mc.player.setYRot(mc.player.getYRot() + Math.copySign(step, delta));

        if (managePitch) {
            float pitchDelta = (float) (cruisePitch - mc.player.getXRot());
            float pitchStep = (float) Math.min(Math.abs(pitchDelta), turnSpeed);
            mc.player.setXRot(mc.player.getXRot() + Math.copySign(pitchStep, pitchDelta));
        }
    }

    private static void releaseForward(Minecraft mc) {
        if (holdingForward) {
            if (mc != null) mc.options.keyUp.setDown(false);
            holdingForward = false;
        }
    }

    /** Hand ElytraFly back to however the user had it. */
    private static void restoreElytraFly() {
        if (weEnabledElytraFly) {
            MeteorHook.setElytraFly(false);
            weEnabledElytraFly = false;
        }
    }

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    /** Meteor classes isolated so they only load when meteor is present. */
    private static class MeteorHook {
        static boolean isElytraFlyActive() {
            try {
                if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("meteor-client")) return false;
                return Inner.active();
            } catch (Throwable t) {
                return false;
            }
        }

        static boolean setElytraFly(boolean on) {
            try {
                if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("meteor-client")) return false;
                return Inner.set(on);
            } catch (Throwable t) {
                return false;
            }
        }

        private static class Inner {
            static boolean active() {
                var m = meteordevelopment.meteorclient.systems.modules.Modules.get()
                        .get(meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly.class);
                return m != null && m.isActive();
            }

            static boolean set(boolean on) {
                var m = meteordevelopment.meteorclient.systems.modules.Modules.get()
                        .get(meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly.class);
                if (m == null || m.isActive() == on) return false;
                m.toggle();
                return true;
            }
        }
    }
}

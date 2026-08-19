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

    public static void setTarget(double x, double z) {
        target = new double[]{x, z};
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
        if (!enabled || t == null || mc.player == null) return;

        double dx = t[0] - mc.player.getX();
        double dz = t[1] - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= arrivalRadius) {
            target = null;
            Notifications.add("✈ ARRIVED — %d, %d".formatted((int) t[0], (int) t[1]));
            return;
        }

        // only steer while actually gliding, so walking around stays manual
        if (!mc.player.isFallFlying()) return;

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

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }
}

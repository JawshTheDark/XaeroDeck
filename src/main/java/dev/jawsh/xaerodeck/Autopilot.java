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

    // route: one or more points followed in order; loop = circle forever
    private static volatile java.util.List<double[]> route = null;
    private static volatile int routeIdx = 0;
    private static volatile boolean routeLoop = false;
    private static volatile double routeSpacing = Double.MAX_VALUE; // typical leg length
    private static boolean holdingForward = false;
    private static boolean weEnabledElytraFly = false;
    // smoothed speed estimate (blocks/tick) for corner anticipation
    private static double lastX = Double.NaN, lastZ = 0;
    private static double speedEst = 0;

    public static void setTarget(double x, double z) {
        setRoute(java.util.List.of(new double[]{x, z}), false);
    }

    public static synchronized void setRoute(java.util.List<double[]> points, boolean loop) {
        if (points == null || points.isEmpty()) {
            clear();
            return;
        }
        route = new java.util.ArrayList<>(points);
        routeIdx = 0;
        routeLoop = loop;
        // typical distance between consecutive points, for arrival auto-scaling
        double minLeg = Double.MAX_VALUE;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1), b2 = points.get(i);
            double d = Math.hypot(b2[0] - a[0], b2[1] - a[1]);
            if (d > 0.5) minLeg = Math.min(minLeg, d);
        }
        routeSpacing = minLeg;
        // give the flight a thrust source: enable ElytraFly if it isn't on
        if (!MeteorHook.isElytraFlyActive()) {
            weEnabledElytraFly = MeteorHook.setElytraFly(true);
        }
    }

    public static double[] getTarget() {
        java.util.List<double[]> r = route;
        int i = routeIdx;
        return (r == null || i >= r.size()) ? null : r.get(i);
    }

    /** Full route for status payloads: points, current index, loop flag. */
    public static Object[] getRoute() {
        java.util.List<double[]> r = route;
        return r == null ? null : new Object[]{r, routeIdx, routeLoop};
    }

    public static void clear() {
        route = null;
        routeIdx = 0;
        routeLoop = false;
        routeSpacing = Double.MAX_VALUE;
    }

    /** Arrival distance scaled down for tightly-spaced routes so tiny orbits work. */
    private static double effectiveArrival() {
        if (routeSpacing == Double.MAX_VALUE) return arrivalRadius;
        return Math.min(arrivalRadius, Math.max(8.0, routeSpacing * 0.4));
    }

    /** Called every client tick from XaeroDeck's tick handler. */
    public static void tick(Minecraft mc) {
        double[] t = getTarget();
        if (t == null || !enabled || mc.player == null) {
            releaseForward(mc);
            restoreElytraFly();
            return;
        }

        double px = mc.player.getX(), pz = mc.player.getZ();
        if (!Double.isNaN(lastX)) {
            double inst = Math.hypot(px - lastX, pz - lastZ);
            if (inst < 40) speedEst = speedEst * 0.8 + inst * 0.2;
        }
        lastX = px; lastZ = pz;

        double dx = t[0] - px;
        double dz = t[1] - pz;
        double dist = Math.sqrt(dx * dx + dz * dz);

        // corner anticipation: hand off to the next leg early by the distance a
        // turn of this angle needs at our current speed and turn rate, so the
        // flown arc hugs the corner instead of overshooting past it
        java.util.List<double[]> r = route;
        boolean hasNext = r != null && (routeIdx + 1 < r.size() || (routeLoop && r.size() > 1));
        double arrive = effectiveArrival();
        double switchDist = arrive;
        if (hasNext && dist > 0.01) {
            double[] next = r.get((routeIdx + 1) % r.size());
            double ox = next[0] - t[0], oz = next[1] - t[1];
            double olen = Math.hypot(ox, oz);
            if (olen > 1) {
                double dot = (dx / dist) * (ox / olen) + (dz / dist) * (oz / olen);
                double theta = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
                double turnRadius = speedEst / Math.toRadians(Math.max(0.5, turnSpeed));
                double lead = turnRadius * Math.tan(Math.min(theta, Math.toRadians(150)) / 2);
                switchDist = Math.max(arrive, Math.min(lead, 600));
            }
        }

        if (dist <= (hasNext ? switchDist : arrive)) {
            if (r != null && routeIdx + 1 < r.size()) {
                routeIdx++;
            } else if (r != null && routeLoop && r.size() > 1) {
                routeIdx = 0;
            } else {
                clear();
                releaseForward(mc);
                restoreElytraFly();
                Notifications.add("✈ ARRIVED — %d, %d".formatted((int) t[0], (int) t[1]));
            }
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

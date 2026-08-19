package dev.jawsh.xaerodeck;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;

import java.util.ArrayList;
import java.util.List;

/** Lock-free snapshot of the player + nearby entities, updated once per client tick. */
public class PositionTracker {
    public record EntityDot(String name, double x, double z, char type) {
        // type: 'f' friend, 'p' player, 'h' hostile, 'n' neutral, 'a' passive
    }

    public record Effect(String name, int seconds, int color) {
        // seconds == -1 means infinite
    }

    public record Snapshot(boolean inGame, double x, double y, double z, float yaw,
                           String dimension, String worldId, long time, List<EntityDot> entities,
                           double bps, int ping, double tps, int totems, int elytraPct, float health,
                           List<Effect> effects) {
        static final Snapshot EMPTY = new Snapshot(false, 0, 0, 0, 0, null, null, 0, List.of(),
                0, 0, 20, 0, -1, 0, List.of());
    }

    private static volatile Snapshot current = Snapshot.EMPTY;
    private static Boolean meteorPresent;
    private static boolean wasDead = false;

    // rings for speed (1s) and tps (10s) estimation
    private static final double[] posX = new double[21];
    private static final double[] posZ = new double[21];
    private static int posIdx = 0;
    private static int posCount = 0;
    private static long lastGameTime = -1;
    private static long lastWallMs = -1;
    private static double tpsEstimate = 20;

    public static Snapshot get() {
        return current;
    }

    /** Called at the end of every client tick (client thread). */
    public static void tick(Minecraft mc, String worldId) {
        if (mc.player == null || mc.level == null) {
            current = Snapshot.EMPTY;
            wasDead = false;
            posCount = 0;
            lastGameTime = -1;
            return;
        }

        // death detection → notification (app vibrates on 💀)
        boolean dead = mc.player.isDeadOrDying();
        if (dead && !wasDead) {
            Notifications.add("💀 Died at %d, %d, %d (%s)".formatted(
                    (int) mc.player.getX(), (int) mc.player.getY(), (int) mc.player.getZ(),
                    mc.level.dimension().identifier().getPath()));
        }
        wasDead = dead;

        // speed over the last second
        posX[posIdx] = mc.player.getX();
        posZ[posIdx] = mc.player.getZ();
        int oldest = posCount < 20 ? 0 : (posIdx + 1) % 21;
        double bps = 0;
        if (posCount >= 5) {
            double dx = posX[posIdx] - posX[oldest];
            double dz = posZ[posIdx] - posZ[oldest];
            bps = Math.sqrt(dx * dx + dz * dz) / (Math.min(posCount, 20) / 20.0);
        }
        posIdx = (posIdx + 1) % 21;
        if (posCount < 21) posCount++;

        // tps estimate from game-time progression vs wall clock (2s windows)
        long gt = mc.level.getGameTime();
        long wall = System.currentTimeMillis();
        if (lastGameTime >= 0 && wall - lastWallMs >= 2000) {
            tpsEstimate = Math.min(20.0, (gt - lastGameTime) * 1000.0 / (wall - lastWallMs));
            lastGameTime = gt;
            lastWallMs = wall;
        } else if (lastGameTime < 0) {
            lastGameTime = gt;
            lastWallMs = wall;
        }

        int ping = 0;
        try {
            var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info != null) ping = info.getLatency();
        } catch (Throwable ignored) {
        }

        int totems = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) totems += stack.getCount();
        }

        int elytraPct = -1;
        var chest = mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
        if (chest.is(net.minecraft.world.item.Items.ELYTRA) && chest.getMaxDamage() > 0) {
            elytraPct = 100 * (chest.getMaxDamage() - chest.getDamageValue()) / chest.getMaxDamage();
        }

        List<Effect> effects = new ArrayList<>(4);
        try {
            for (var inst : mc.player.getActiveEffects()) {
                String name = inst.getEffect().value().getDisplayName().getString();
                int amp = inst.getAmplifier();
                if (amp > 0) name += " " + switch (amp) {
                    case 1 -> "II";
                    case 2 -> "III";
                    case 3 -> "IV";
                    case 4 -> "V";
                    default -> String.valueOf(amp + 1);
                };
                int secs = inst.isInfiniteDuration() ? -1 : inst.getDuration() / 20;
                effects.add(new Effect(name, secs, inst.getEffect().value().getColor()));
            }
            effects.sort((a, b) -> a.name().compareTo(b.name()));
        } catch (Throwable ignored) {
        }

        List<EntityDot> dots = new ArrayList<>(24);
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player) continue;
            dots.add(new EntityDot(p.getName().getString(), p.getX(), p.getZ(),
                    isMeteorFriend(p) ? 'f' : 'p'));
        }
        int mobs = 0;
        double px = mc.player.getX(), pz = mc.player.getZ();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (mobs >= 64) break;
            if (!(e instanceof net.minecraft.world.entity.LivingEntity le) || !le.isAlive()
                    || e instanceof net.minecraft.world.entity.player.Player) continue;
            char type;
            if (e instanceof net.minecraft.world.entity.NeutralMob nm) {
                // matches Xaero's minimap: neutral mobs are yellow unless angry
                type = nm.getPersistentAngerEndTime() > mc.level.getGameTime() ? 'h' : 'n';
            } else if (e instanceof Monster) {
                type = 'h';
            } else if (e instanceof net.minecraft.world.entity.animal.Animal
                    || e instanceof net.minecraft.world.entity.npc.villager.AbstractVillager
                    || e instanceof net.minecraft.world.entity.animal.fish.WaterAnimal) {
                type = 'a';
            } else {
                continue;
            }
            double dx = e.getX() - px, dz = e.getZ() - pz;
            if (dx * dx + dz * dz <= 192 * 192) {
                dots.add(new EntityDot(null, e.getX(), e.getZ(), type));
                mobs++;
            }
        }
        current = new Snapshot(true,
                mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(),
                mc.level.dimension().identifier().toString(), worldId,
                System.currentTimeMillis(), dots,
                Math.round(bps * 10) / 10.0, ping, Math.round(tpsEstimate * 10) / 10.0,
                totems, elytraPct, mc.player.getHealth(), effects);
    }

    private static boolean isMeteorFriend(AbstractClientPlayer p) {
        if (meteorPresent == null) {
            meteorPresent = FabricLoader.getInstance().isModLoaded("meteor-client");
        }
        if (!meteorPresent) return false;
        try {
            return MeteorFriendCheck.isFriend(p);
        } catch (Throwable t) {
            meteorPresent = false;
            return false;
        }
    }

    /** Separate class so meteor classes only load when meteor is present. */
    private static class MeteorFriendCheck {
        static boolean isFriend(AbstractClientPlayer p) {
            return meteordevelopment.meteorclient.systems.friends.Friends.get().isFriend(p);
        }
    }
}

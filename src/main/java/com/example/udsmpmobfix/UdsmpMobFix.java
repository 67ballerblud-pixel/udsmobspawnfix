package com.example.udsmpmobfix;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

/**
 * Companion for UltimateDonutSmp. Its built-in Disable Mob Spawns / Disable Phantom Spawns
 * settings let mobs spawn near a player who has them ON whenever another nearby player has
 * them OFF. This cancels a spawn if ANY player in range has the setting ON.
 *
 * It only reads the settings through the plugin's own methods (reflection), and reuses the
 * plugin's own rules for which mobs and spawn reasons count, so nothing is patched.
 */
public final class UdsmpMobFix extends JavaPlugin implements Listener, CommandExecutor {
    private Plugin udsmp;
    private Method getPdm, pdmGet, mobEnabled, mobUntil, phantomEnabled, phantomUntil;
    private Method isHostile, isBoss, hasName, preventable;
    private boolean ready, failedLogged;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("mobfixcheck");
        if (cmd != null) cmd.setExecutor(this);
    }

    private boolean init() {
        if (ready) return true;
        try {
            udsmp = getServer().getPluginManager().getPlugin("UltimateDonutSmp");
            if (udsmp == null) return false;
            ClassLoader cl = udsmp.getClass().getClassLoader();
            getPdm = udsmp.getClass().getMethod("getPlayerDataManager");
            Class<?> pdmClass = getPdm.getReturnType();
            pdmGet = pdmClass.getMethod("get", Player.class);
            Class<?> pd = cl.loadClass("com.bx.ultimateDonutSmp.models.PlayerData");
            mobEnabled = pd.getMethod("isMobSpawnEnabled");
            mobUntil = pd.getMethod("getMobSpawnDisabledUntil");
            phantomEnabled = pd.getMethod("isPhantomEnabled");
            phantomUntil = pd.getMethod("getPhantomDisabledUntil");
            Class<?> pol = cl.loadClass("com.bx.ultimateDonutSmp.utils.MobSpawnPolicy");
            isHostile = pol.getMethod("isHostileMob", LivingEntity.class);
            isBoss = pol.getMethod("isBoss", EntityType.class);
            hasName = pol.getMethod("hasCustomName", LivingEntity.class);
            preventable = pol.getMethod("isPreventableSpawnReason", CreatureSpawnEvent.SpawnReason.class, boolean.class);
            ready = true;
            getLogger().info("Hooked into UltimateDonutSmp " + udsmp.getPluginMeta().getVersion());
        } catch (Throwable t) {
            if (!failedLogged) {
                failedLogged = true;
                getLogger().warning("Could not hook UltimateDonutSmp (version changed?): " + t);
            }
        }
        return ready;
    }

    private Object data(Player p) throws Exception {
        Object pdm = getPdm.invoke(udsmp);
        return pdm == null ? null : pdmGet.invoke(pdm, p);
    }

    /** True if this player has the "disable spawns" setting ON (and it has not expired). */
    private boolean toggledOn(Player p, boolean phantom) {
        try {
            Object d = data(p);
            if (d == null) return false;
            boolean enabled = (Boolean) (phantom ? phantomEnabled : mobEnabled).invoke(d);
            if (enabled) return false; // spawns enabled = toggle off
            long until = (Long) (phantom ? phantomUntil : mobUntil).invoke(d);
            if (until >= 1_000_000_000_000L) return until > System.currentTimeMillis();        // epoch millis
            if (until >= 1_000_000_000L && until < 100_000_000_000L)
                return until * 1000L > System.currentTimeMillis();                              // epoch seconds
            return true; // no usable expiry info: trust the toggle
        } catch (Throwable t) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        LivingEntity ent = e.getEntity();
        if (ent instanceof Player || !init()) return;
        try {
            boolean trialBlocked = udsmp.getConfig().getBoolean("SETTINGS.MOB-SPAWN-TOGGLE-BLOCKS-TRIAL-SPAWNERS", false);
            if (!(Boolean) preventable.invoke(null, e.getSpawnReason(), trialBlocked)) return;

            boolean phantom = ent.getType() == EntityType.PHANTOM;
            if (!phantom) {
                if (!(Boolean) isHostile.invoke(null, ent)) return;
                if ((Boolean) isBoss.invoke(null, ent.getType())) return;
                if ((Boolean) hasName.invoke(null, ent)) return;
            }
            double radius = udsmp.getConfig().getDouble(
                    phantom ? "SETTINGS.PHANTOM-SPAWN-RADIUS" : "SETTINGS.MOB-SPAWN-RADIUS", phantom ? 40 : 50);
            Location loc = ent.getLocation();
            for (Player p : loc.getWorld().getNearbyPlayers(loc, radius)) {
                if (toggledOn(p, phantom)) {
                    e.setCancelled(true);
                    return;
                }
            }
        } catch (Throwable t) {
            if (!failedLogged) {
                failedLogged = true;
                getLogger().warning("Spawn check failed: " + t);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Players only.");
            return true;
        }
        if (!init()) {
            p.sendMessage("Hook failed, see console.");
            return true;
        }
        try {
            Object d = data(p);
            if (d == null) {
                p.sendMessage("No player data loaded for you.");
                return true;
            }
            p.sendMessage("mobSpawnEnabled=" + mobEnabled.invoke(d) + " disabledUntil=" + mobUntil.invoke(d)
                    + " -> toggle ON: " + toggledOn(p, false));
            p.sendMessage("phantomEnabled=" + phantomEnabled.invoke(d) + " disabledUntil=" + phantomUntil.invoke(d)
                    + " -> toggle ON: " + toggledOn(p, true));
            p.sendMessage("now=" + System.currentTimeMillis());
        } catch (Throwable t) {
            p.sendMessage("Error: " + t);
        }
        return true;
    }
}

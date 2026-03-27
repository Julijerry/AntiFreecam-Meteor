package me.julijerry.antiCheat;

import org.bukkit.plugin.java.JavaPlugin;

public final class AntiCheat extends JavaPlugin {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new me.julijerry.antiCheat.ClientDetector(this), this);
        getLogger().info("AntiCheat started");

    }

    @Override
    public void onDisable() {
        getLogger().info("AntiCheat disabled");
    }
}

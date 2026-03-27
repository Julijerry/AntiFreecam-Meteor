package me.julijerry.antiCheat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ClientDetector implements Listener, PluginMessageListener {

    private final AntiCheat plugin;
    private final Map<UUID, AntiCheatSession> pendingChecks = new HashMap<>();

    private static final String VANILLA_SENTINEL = "\u27E6VANILLA\u27E7";

    private static class AntiCheatSession {
        public Location blockLoc;
        public BlockState oldState;
    }

    public ClientDetector(AntiCheat plugin) {
        this.plugin = plugin;
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, "minecraft:brand", this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> runCheck(player), 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        AntiCheatSession session = pendingChecks.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            session.oldState.update(true, false);
        }
    }

    private void runCheck(Player player) {
        if (!player.isOnline())
            return;
        if (pendingChecks.containsKey(player.getUniqueId()))
            return;

        Location loc = player.getLocation().getBlock().getLocation().add(0, 3, 0);
        Block block = loc.getBlock();
        BlockState oldState = block.getState();

        block.setType(Material.OAK_SIGN, false);
        BlockState state = block.getState();

        if (state instanceof Sign) {
            Sign sign = (Sign) state;
            SignSide front = sign.getSide(Side.FRONT);

            front.line(0, Component.translatable("key.meteor-client.open-gui", VANILLA_SENTINEL));
            front.line(1, Component.translatable("key.meteor-client.open-commands", VANILLA_SENTINEL));
            front.line(2, Component.translatable("key.category.meteor-client.meteor-client", VANILLA_SENTINEL));
            front.line(3, Component.translatable("key.freecam.toggle", VANILLA_SENTINEL));
            sign.update(true, false);

            AntiCheatSession session = new AntiCheatSession();
            session.blockLoc = loc;
            session.oldState = oldState;
            pendingChecks.put(player.getUniqueId(), session);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.openSign(sign);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline()) {
                            player.sendBlockChange(loc, Material.AIR.createBlockData());
                        }
                    }, 1L);
                }
            }, 5L);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                AntiCheatSession s = pendingChecks.remove(player.getUniqueId());
                if (s != null)
                    s.oldState.update(true, false);
            }, 60L);
        }
    }

    private String normalizeLine(String s) {
        return s.trim()
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace("\uFEFF", "")
                .replace("\u00A0", " ");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        AntiCheatSession session = pendingChecks.get(player.getUniqueId());

        if (session == null)
            return;

        Location evLoc = event.getBlock().getLocation();
        Location sesLoc = session.blockLoc;

        if (!evLoc.equals(sesLoc))
            return;

        event.setCancelled(true);
        pendingChecks.remove(player.getUniqueId());
        session.oldState.update(true, false);

        String l0 = normalizeLine(PlainTextComponentSerializer.plainText().serialize(event.line(0)));
        String l1 = normalizeLine(PlainTextComponentSerializer.plainText().serialize(event.line(1)));
        String l2 = normalizeLine(PlainTextComponentSerializer.plainText().serialize(event.line(2)));
        String l3 = normalizeLine(PlainTextComponentSerializer.plainText().serialize(event.line(3)));

        boolean hasMeteor = l0.contains("open-gui") 
                || l0.contains("meteor") 
                || l1.contains("open-commands") 
                || l1.contains("meteor") 
                || l2.contains("meteor");

        boolean hasFreecam = !l3.equals(VANILLA_SENTINEL) && !l3.equals("key.freecam.toggle");

        if (hasMeteor) {
            punishPlayer(player, "Meteor Client");
        } else if (hasFreecam) {
            punishPlayer(player, "Freecam");
        }
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte[] message) {
        if (channel.equals("minecraft:brand")) {
            String brand = new String(message, StandardCharsets.UTF_8).toLowerCase();

            if (brand.contains("meteor") || brand.contains("wurst") 
                    || brand.contains("aristois") || brand.contains("liquidbounce")) {
                Bukkit.getScheduler().runTask(plugin, 
                        () -> punishPlayer(player, "Unzulässiger Client (" + brand.replaceAll("[^a-zA-Z]", "") + ")"));
            }
        }
    }

    private void punishPlayer(Player player, String reason) {
        boolean isFreecam = reason.equals("Freecam");
        String bypassPerm = isFreecam ? "anticheat.freecam.bypass" : "anticheat.client.bypass";

        if (player.hasPermission(bypassPerm)) {
            player.sendMessage("§8[§cAntiCheat§8] §eBypass aktiv. Eigentlicher Grund: " + reason);
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            String punishmentMessage = "§cAntiCheat: Unerlaubte Client-Modifikation (§e" + reason + "§c)";

            if (!isFreecam) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "kick " + player.getName() + " " + punishmentMessage);
                plugin.getLogger().warning("Spieler " + player.getName() + " wurde gekickt. Grund: " + reason);
            } else {
                plugin.getLogger().info("Freecam Verdacht bei " + player.getName() + " - Nur Nachricht an Team gesendet.");
            }

            String alertText = isFreecam ? "nutzt vermutlich Freecam!" : "wurde gekickt! Grund: " + reason;
            String alertPerm = isFreecam ? "essentials.teammode" : "minergames.alert.anticheat";

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission(alertPerm)) {
                    p.sendMessage("§8[§cAntiCheat§8] §e" + player.getName() + " §c" + alertText);
                }
            }
        });
    }
}
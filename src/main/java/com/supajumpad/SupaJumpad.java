package com.supajumpad;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerJumpEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class SupaJumpad extends JavaPlugin implements Listener {
    private final Set<UUID> flightEnabled = new HashSet<>();
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String uuid : getConfig().getStringList("enabled-players")) {
            try { flightEnabled.add(UUID.fromString(uuid)); }
            catch (IllegalArgumentException ignored) { getLogger().warning("Ignoring invalid UUID in enabled-players: " + uuid); }
        }
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("fly").setExecutor((sender, command, label, args) -> handleFlyCommand(sender));
        getLogger().info("SupaJumpad enabled.");
    }

    @Override
    public void onDisable() {
        saveFlightPlayers();
        for (Player player : getServer().getOnlinePlayers()) {
            if (!player.getGameMode().equals(GameMode.CREATIVE)
                    && !player.getGameMode().equals(GameMode.SPECTATOR)
                    && !isAllowedWorld(player.getWorld())) {
                player.setAllowFlight(false);
            }
        }
    }

    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || !isAllowedWorld(player.getWorld())) {
            return;
        }
        if (flightEnabled.contains(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        getServer().getScheduler().runTask(this, () -> {
            if (!player.isOnline() || player.getGameMode() == GameMode.CREATIVE
                    || !isAllowedWorld(player.getWorld())
                    || flightEnabled.contains(player.getUniqueId())) {
                return;
            }
            player.setAllowFlight(false);
            Vector velocity = player.getVelocity();
            velocity.setY(0.8);
            Vector direction = player.getLocation().getDirection().setY(0).normalize();
            if (direction.lengthSquared() > 0) {
                velocity.add(direction.multiply(1.0));
            }
            player.setVelocity(velocity);
            player.playSound(player.getLocation(), Sound.BLOCK_SLIME_BLOCK_FALL, 1.0f, 1.0f);
        });
    }

    @EventHandler
    public void onJump(PlayerJumpEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || !isAllowedWorld(player.getWorld())
                || flightEnabled.contains(player.getUniqueId())) {
            return;
        }

        getServer().getScheduler().runTask(this, () -> {
            if (!player.isOnline() || player.getGameMode() == GameMode.CREATIVE
                    || !isAllowedWorld(player.getWorld())
                    || flightEnabled.contains(player.getUniqueId())) {
                return;
            }
            if (isGrounded(player.getLocation())) {
                player.setAllowFlight(true);
            }
        });
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (isAllowedWorld(player.getWorld())) {
            if (flightEnabled.contains(player.getUniqueId())) {
                player.setAllowFlight(true);
                player.setFlying(true);
            }
        } else {
            player.setAllowFlight(player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR);
            player.setFlying(false);
        }
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() == GameMode.CREATIVE
                || event.getNewGameMode() == GameMode.SPECTATOR) {
            flightEnabled.remove(event.getPlayer().getUniqueId());
            saveFlightPlayers();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isAllowedWorld(player.getWorld())) {
            if (flightEnabled.contains(player.getUniqueId())) {
                if (!player.hasPermission("fly.use")) {
                    flightEnabled.remove(player.getUniqueId());
                    saveFlightPlayers();
                    player.setAllowFlight(false);
                } else {
                    player.setAllowFlight(true);
                    player.setFlying(true);
                }
            } else if (player.getGameMode() != GameMode.CREATIVE
                    && player.getGameMode() != GameMode.SPECTATOR) {
                player.setAllowFlight(false);
            }
        } else if (player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR) {
            player.setAllowFlight(false);
            player.setFlying(false);
        }
    }

    private boolean handleFlyCommand(org.bukkit.command.CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }
        if (!player.hasPermission("fly.use")) {
            String message = getConfig().getString("messages.no-permission",
                    "&cYou don't have permission to use this.");
            send(player, message);
            sendActionBar(player, message);
            return true;
        }
        if (!isAllowedWorld(player.getWorld())) {
            send(player, "&cFlight can only be toggled in the spawn or afk world.");
            return true;
        }
        UUID uuid = player.getUniqueId();
        if (flightEnabled.remove(uuid)) {
            saveFlightPlayers();
            player.setFlying(false);
            player.setAllowFlight(false);
            String message = getConfig().getString("messages.disabled", "<##A8A8A8>Flying disabled.");
            send(player, message);
            sendActionBar(player, message);
        } else {
            flightEnabled.add(uuid);
            saveFlightPlayers();
            player.setAllowFlight(true);
            String message = getConfig().getString("messages.enabled", "<##A8A8A8>Flying enabled.");
            send(player, message);
            sendActionBar(player, message);
        }
        return true;
    }

    private void saveFlightPlayers() {
        getConfig().set("enabled-players", flightEnabled.stream().map(UUID::toString).toList());
        saveConfig();
    }

    private boolean isAllowedWorld(World world) {
        return getConfig().getStringList("allowed-worlds").stream()
                .anyMatch(name -> name.equalsIgnoreCase(world.getName()));
    }

    private boolean isGrounded(Location location) {
        Location below = location.clone().subtract(0, 0.1, 0);
        return below.getBlock().getType().isSolid()
                && !below.getBlock().isLiquid();
    }

    private void send(Player player, String message) {
        player.sendMessage(parse(message));
    }

    private void sendActionBar(Player player, String message) {
        player.sendActionBar(parse(message));
    }

    private Component parse(String input) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<##([A-Fa-f0-9]{6})>").matcher(input);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) {
            StringBuilder legacyHex = new StringBuilder("§x");
            for (char character : matcher.group(1).toCharArray()) {
                legacyHex.append('§').append(character);
            }
            matcher.appendReplacement(expanded,
                    java.util.regex.Matcher.quoteReplacement(legacyHex.toString()));
        }
        matcher.appendTail(expanded);
        return legacy.deserialize(expanded.toString()).decoration(TextDecoration.ITALIC, false);
    }
}

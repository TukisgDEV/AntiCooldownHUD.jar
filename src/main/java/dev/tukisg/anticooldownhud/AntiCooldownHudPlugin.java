package dev.tukisg.anticooldownhud;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.block.sign.Side;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class AntiCooldownHudPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, Probe> probes = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> queued = new HashMap<>();
    private final Map<UUID, String> results = new HashMap<>();
    private PacketListenerAbstract packetListener;
    private boolean kick;
    private String kickMessage;
    private int joinDelay;
    private int timeout;

    private static final class Probe {
        final Player player;
        final Location location;
        final Vector3i position;
        final String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        final String fallbackA = "cg_a_" + nonce;
        final String fallbackB = "cg_b_" + nonce;
        BukkitTask expiry;

        Probe(Player player, Location location) {
            this.player = player;
            this.location = location;
            this.position =
                    new Vector3i(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readSettings();
        getServer().getPluginManager().registerEvents(this, this);
        packetListener =
                new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
                    @Override
                    public void onPacketReceive(PacketReceiveEvent event) {
                        if (event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
                        UUID uuid = event.getUser().getUUID();
                        if (uuid == null) return;
                        Probe probe = probes.get(uuid);
                        if (probe == null) return;
                        WrapperPlayClientUpdateSign packet = new WrapperPlayClientUpdateSign(event);
                        if (!probe.position.equals(packet.getBlockPosition())) return;
                        event.setCancelled(true);
                        if (!packet.isFrontText()) return;
                        String[] lines = Arrays.copyOf(packet.getTextLines(), 4);
                        Bukkit.getScheduler()
                                .runTask(AntiCooldownHudPlugin.this, () -> accept(probe, lines));
                    }
                };
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);
        getLogger()
                .info(
                        "CooldownHUD signatures loaded; action="
                                + (kick ? "KICK" : "LOG")
                                + ". Inconclusive replies never cause a kick.");
    }

    private void readSettings() {
        String action = getConfig().getString("action", "KICK").toUpperCase(Locale.ROOT);
        kick = action.equals("KICK");
        if (!action.equals("KICK") && !action.equals("LOG"))
            getLogger().warning("Unknown action; using LOG.");
        kickMessage = getConfig().getString("kick-message", "У вас был найден запрещенный мод!");
        joinDelay = Math.clamp(getConfig().getInt("join-delay-ticks", 40), 10, 400);
        timeout = Math.clamp(getConfig().getInt("response-timeout-ticks", 80), 20, 400);
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        if (getConfig().getBoolean("check-on-join", true)) queue(event.getPlayer(), joinDelay);
    }

    private void queue(Player player, int delay) {
        cancel(player, false);
        results.put(player.getUniqueId(), "QUEUED");
        queued.put(
                player.getUniqueId(),
                Bukkit.getScheduler()
                        .runTaskLater(
                                this,
                                () -> {
                                    queued.remove(player.getUniqueId());
                                    if (player.isOnline()) check(player);
                                },
                                delay));
    }

    private void check(Player player) {
        if (player.hasPermission("cooldownguard.bypass")) {
            results.put(player.getUniqueId(), "BYPASS");
            return;
        }
        World world = player.getWorld();
        Location base = player.getLocation();
        int y = base.getBlockY() - 16;
        if (y < world.getMinHeight()) y = base.getBlockY() + 16;
        if (y >= world.getMaxHeight()
                || !world.isChunkLoaded(base.getBlockX() >> 4, base.getBlockZ() >> 4)) {
            results.put(player.getUniqueId(), "INCONCLUSIVE: chunk/height unavailable");
            return;
        }
        Location location = new Location(world, base.getBlockX(), y, base.getBlockZ());
        Probe probe = new Probe(player, location);
        probes.put(player.getUniqueId(), probe);
        results.put(player.getUniqueId(), "CHECKING");
        try {
            Sign virtual = (Sign) Material.OAK_SIGN.createBlockData().createBlockState();
            virtual.getSide(Side.FRONT)
                    .line(0, Component.translatable(SignatureCheck.MACE_KEY, probe.fallbackA));
            virtual.getSide(Side.FRONT)
                    .line(1, Component.translatable(SignatureCheck.TOTEM_KEY, probe.fallbackB));
            virtual.getSide(Side.FRONT).line(2, Component.keybind(SignatureCheck.HUD_KEY));
            virtual.getSide(Side.FRONT).line(3, Component.text(probe.nonce));
            player.sendBlockChange(location, virtual.getBlockData());
            player.sendBlockUpdate(location, virtual);
            PacketEvents.getAPI()
                    .getPlayerManager()
                    .sendPacket(player, new WrapperPlayServerOpenSignEditor(probe.position, true));
            probe.expiry =
                    Bukkit.getScheduler()
                            .runTaskLater(
                                    this,
                                    () -> {
                                        if (!probes.remove(player.getUniqueId(), probe)) return;
                                        restore(probe);
                                        record(player, "INCONCLUSIVE: no answer");
                                    },
                                    timeout);
        } catch (RuntimeException exception) {
            probes.remove(player.getUniqueId(), probe);
            restore(probe);
            results.put(player.getUniqueId(), "ERROR");
            getLogger().log(Level.WARNING, "Check failed for " + player.getName(), exception);
        }
    }

    private void accept(Probe probe, String[] lines) {
        Player player = probe.player;
        UUID uuid = player.getUniqueId();
        if (probes.get(uuid) != probe || !player.isOnline()) return;
        if (lines.length != 4 || !probe.nonce.equals(lines[3])) return;
        if (!probes.remove(uuid, probe)) return;
        if (probe.expiry != null) probe.expiry.cancel();
        restore(probe);
        if (!player.getWorld().equals(probe.location.getWorld())) return;
        SignatureCheck.Result result =
                SignatureCheck.evaluate(lines, probe.nonce, probe.fallbackA, probe.fallbackB);
        record(player, result.name());
        if (result == SignatureCheck.Result.DETECTED) {
            getLogger()
                    .warning(
                            player.getName()
                                    + " ("
                                    + uuid
                                    + "): matched CooldownHUD/Activity translations and registered"
                                    + " HUD key.");
            if (kick && !player.hasPermission("cooldownguard.bypass"))
                player.kick(Component.text(kickMessage));
        }
    }

    private void record(Player player, String result) {
        results.put(player.getUniqueId(), result);
        if (result.startsWith("INCONCLUSIVE") && getConfig().getBoolean("log-inconclusive", true))
            getLogger().info(player.getName() + ": " + result + "; no kick.");
    }

    private void restore(Probe probe) {
        Player player = probe.player;
        if (!player.isOnline() || !player.getWorld().equals(probe.location.getWorld())) return;
        World world = player.getWorld();
        if (!world.isChunkLoaded(probe.position.x >> 4, probe.position.z >> 4)) return;
        BlockState current = probe.location.getBlock().getState();
        player.sendBlockChange(probe.location, current.getBlockData());
        if (current instanceof TileState tile) player.sendBlockUpdate(probe.location, tile);
    }

    private void cancel(Player player, boolean forget) {
        UUID uuid = player.getUniqueId();
        BukkitTask task = queued.remove(uuid);
        if (task != null) task.cancel();
        Probe probe = probes.remove(uuid);
        if (probe != null) {
            if (probe.expiry != null) probe.expiry.cancel();
            restore(probe);
        }
        if (forget) results.remove(uuid);
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        cancel(event.getPlayer(), true);
    }

    @EventHandler(ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (probes.containsKey(player.getUniqueId())) queue(player, joinDelay);
    }

    @EventHandler
    public void changedWorld(PlayerChangedWorldEvent event) {
        if (queued.containsKey(event.getPlayer().getUniqueId())
                || probes.containsKey(event.getPlayer().getUniqueId()))
            queue(event.getPlayer(), joinDelay);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("cooldownguard.admin")) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) cancel(player, false);
            reloadConfig();
            readSettings();
            sender.sendMessage("CooldownHUDGuard: настройки обновлены.");
            return true;
        }
        if (args.length == 2) {
            Player player = Bukkit.getPlayerExact(args[1]);
            if (player == null) {
                sender.sendMessage("Игрок не в сети на этом сервере.");
                return true;
            }
            if (args[0].equalsIgnoreCase("check")) {
                queue(player, 1);
                sender.sendMessage(
                        "Проверка "
                                + player.getName()
                                + " запущена. Результат: /cooldownguard status "
                                + player.getName());
                return true;
            }
            if (args[0].equalsIgnoreCase("status")) {
                sender.sendMessage(
                        player.getName()
                                + ": "
                                + results.getOrDefault(player.getUniqueId(), "NOT_CHECKED"));
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("cooldownguard.admin")) return List.of();
        if (args.length == 1)
            return List.of("check", "status", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        if (args.length == 2 && !args[0].equalsIgnoreCase("reload"))
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(
                            s ->
                                    s.toLowerCase(Locale.ROOT)
                                            .startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        return List.of();
    }

    @Override
    public void onDisable() {
        if (packetListener != null)
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) cancel(player, true);
        probes.clear();
        queued.clear();
        results.clear();
    }
}

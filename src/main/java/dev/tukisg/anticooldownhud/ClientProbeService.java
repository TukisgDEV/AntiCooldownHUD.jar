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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.logging.Level;

final class ClientProbeService implements Listener, AutoCloseable {
    record Settings(
            boolean onJoin,
            int joinDelay,
            int timeout,
            int retryDelay,
            int retries,
            int maxConcurrent,
            boolean logInconclusive) {}

    private static final class Probe {
        final Player player;
        final Location location;
        final Vector3i position;
        final DetectionSession session;
        final SignatureCheck.Challenge challenge;
        final AtomicBoolean submitted = new AtomicBoolean();
        BukkitTask expiry;

        Probe(Player player, Location location, DetectionSession session) {
            this.player = player;
            this.location = location;
            this.session = session;
            position =
                    new Vector3i(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            challenge =
                    new SignatureCheck.Challenge(
                            session.profile(), UUID.randomUUID().toString().replace("-", ""));
        }
    }

    private final JavaPlugin plugin;
    private final Settings settings;
    private final List<SignatureCheck.Profile> profiles;
    private final BiConsumer<Player, SignatureCheck.Profile> detected;
    private final Predicate<UUID> pendingBan;
    private final Map<UUID, Probe> probes = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> queued = new HashMap<>();
    private final Map<UUID, String> results = new HashMap<>();
    private final PacketListenerAbstract listener;
    private volatile boolean closed;

    ClientProbeService(
            JavaPlugin plugin,
            Settings settings,
            List<SignatureCheck.Profile> profiles,
            Predicate<UUID> pendingBan,
            BiConsumer<Player, SignatureCheck.Profile> detected) {
        this.plugin = plugin;
        this.settings = settings;
        this.profiles = List.copyOf(profiles);
        this.pendingBan = pendingBan;
        this.detected = detected;
        listener =
                new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
                    @Override
                    public void onPacketReceive(PacketReceiveEvent event) {
                        if (closed || event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN)
                            return;
                        UUID uuid = event.getUser().getUUID();
                        if (uuid == null) return;
                        Probe probe = probes.get(uuid);
                        if (probe == null) return;
                        WrapperPlayClientUpdateSign packet = new WrapperPlayClientUpdateSign(event);
                        if (!probe.position.equals(packet.getBlockPosition())) return;
                        event.setCancelled(true);
                        if (!packet.isFrontText() || probe.submitted.get()) return;
                        String[] lines = packet.getTextLines();
                        if (!probe.challenge.validReply(lines)
                                || !probe.submitted.compareAndSet(false, true)) return;
                        String[] copy = lines.clone();
                        try {
                            Bukkit.getScheduler().runTask(plugin, () -> accept(probe, copy));
                        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
                        }
                    }
                };
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void checkOnlinePlayers() {
        if (settings.onJoin()) for (Player player : Bukkit.getOnlinePlayers()) check(player);
    }

    String status(UUID playerId) {
        return results.getOrDefault(playerId, "NOT_CHECKED");
    }

    void status(Player player, String result) {
        if (player.isOnline()) results.put(player.getUniqueId(), result);
        if (settings.logInconclusive() && result.startsWith("INCONCLUSIVE")) {
            plugin.getLogger().info(player.getName() + ": " + result);
        }
    }

    void check(Player player) {
        cancel(player, false);
        if (closed || !player.isOnline()) return;
        if (player.hasPermission("cooldownguard.bypass")) {
            status(player, "BYPASS");
            return;
        }
        if (pendingBan.test(player.getUniqueId())) {
            status(player, "BAN_PENDING");
            return;
        }
        status(player, "QUEUED");
        schedule(player, new DetectionSession(profiles, settings.retries()), settings.joinDelay());
    }

    private void schedule(Player player, DetectionSession session, int delay) {
        if (closed || !player.isOnline()) return;
        queued.put(
                player.getUniqueId(),
                Bukkit.getScheduler()
                        .runTaskLater(
                                plugin,
                                () -> {
                                    queued.remove(player.getUniqueId());
                                    if (closed || !player.isOnline()) return;
                                    if (player.hasPermission("cooldownguard.bypass")) {
                                        status(player, "BYPASS");
                                        return;
                                    }
                                    if (pendingBan.test(player.getUniqueId())) {
                                        status(player, "BAN_PENDING");
                                        return;
                                    }
                                    if (probes.size() >= settings.maxConcurrent()) {
                                        schedule(player, session, 10);
                                        return;
                                    }
                                    start(player, session);
                                },
                                delay));
    }

    private void start(Player player, DetectionSession session) {
        World world = player.getWorld();
        Location base = player.getLocation();
        int y = base.getBlockY() - 16;
        if (y < world.getMinHeight()) y = base.getBlockY() + 16;
        if (y < world.getMinHeight()
                || y >= world.getMaxHeight()
                || !world.isChunkLoaded(base.getBlockX() >> 4, base.getBlockZ() >> 4)) {
            advance(player, session, session.timeout(), "chunk/height unavailable");
            return;
        }
        Probe probe =
                new Probe(
                        player,
                        new Location(world, base.getBlockX(), y, base.getBlockZ()),
                        session);
        probes.put(player.getUniqueId(), probe);
        status(player, "CHECKING: " + session.profile().id());
        try {
            Sign sign = (Sign) Material.OAK_SIGN.createBlockData().createBlockState();
            var front = sign.getSide(Side.FRONT);
            SignatureCheck.Profile profile = probe.challenge.profile();
            front.line(
                    0, Component.translatable(profile.first().key(), probe.challenge.fallbackA()));
            front.line(
                    1, Component.translatable(profile.second().key(), probe.challenge.fallbackB()));
            front.line(2, Component.keybind(profile.keybind()));
            front.line(3, Component.text(probe.challenge.nonce()));
            player.sendBlockChange(probe.location, sign.getBlockData());
            player.sendBlockUpdate(probe.location, sign);
            PacketEvents.getAPI()
                    .getPlayerManager()
                    .sendPacket(player, new WrapperPlayServerOpenSignEditor(probe.position, true));
            probe.expiry =
                    Bukkit.getScheduler()
                            .runTaskLater(
                                    plugin,
                                    () -> {
                                        if (!probes.remove(player.getUniqueId(), probe)) return;
                                        restore(probe);
                                        advance(
                                                player,
                                                session,
                                                session.timeout(),
                                                "no valid reply: " + profile.id());
                                    },
                                    settings.timeout());
        } catch (RuntimeException exception) {
            probes.remove(player.getUniqueId(), probe);
            restore(probe);
            status(player, "ERROR: packet check failed");
            plugin.getLogger()
                    .log(Level.WARNING, "Check failed for " + player.getName(), exception);
        }
    }

    private void accept(Probe probe, String[] lines) {
        if (closed || !probes.remove(probe.player.getUniqueId(), probe)) return;
        if (probe.expiry != null) probe.expiry.cancel();
        restore(probe);
        if (!probe.player.isOnline() || !probe.player.getWorld().equals(probe.location.getWorld()))
            return;
        if (probe.player.hasPermission("cooldownguard.bypass")) {
            status(probe.player, "BYPASS");
            return;
        }
        SignatureCheck.Profile profile = probe.challenge.profile();
        String detail =
                profile.id()
                        + ", first="
                        + profile.first().values().contains(lines[0])
                        + ", second="
                        + profile.second().values().contains(lines[1])
                        + ", key="
                        + (profile.keybind().equals(lines[2]) ? "missing" : "resolved");
        advance(probe.player, probe.session, probe.session.reply(probe.challenge, lines), detail);
    }

    private void advance(
            Player player, DetectionSession session, DetectionSession.Step step, String detail) {
        switch (step) {
            case NEXT -> schedule(player, session, 4);
            case CONFIRM -> {
                status(player, "CONFIRMING: " + detail);
                schedule(player, session, 10);
            }
            case RETRY -> {
                status(player, "RETRY: " + detail);
                schedule(player, session, settings.retryDelay());
            }
            case DETECTED -> {
                status(player, "DETECTED: " + detail);
                detected.accept(player, session.profile());
            }
            case NO_MATCH -> status(player, "NO_MATCH: " + detail);
            case INCONCLUSIVE -> status(player, "INCONCLUSIVE: " + detail);
        }
    }

    private void restore(Probe probe) {
        Player player = probe.player;
        if (!player.isOnline() || !player.getWorld().equals(probe.location.getWorld())) return;
        if (!player.getWorld().isChunkLoaded(probe.position.x >> 4, probe.position.z >> 4)) return;
        try {
            BlockState state = probe.location.getBlock().getState();
            player.sendBlockChange(probe.location, state.getBlockData());
            if (state instanceof TileState tile) player.sendBlockUpdate(probe.location, tile);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "Could not restore client block", exception);
        }
    }

    private void cancel(Player player, boolean forget) {
        BukkitTask task = queued.remove(player.getUniqueId());
        if (task != null) task.cancel();
        Probe probe = probes.remove(player.getUniqueId());
        if (probe != null) {
            if (probe.expiry != null) probe.expiry.cancel();
            restore(probe);
        }
        if (forget) results.remove(player.getUniqueId());
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        if (settings.onJoin()) check(event.getPlayer());
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        cancel(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (probes.containsKey(id) || queued.containsKey(id)) check(event.getPlayer());
    }

    @EventHandler
    public void changedWorld(PlayerChangedWorldEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (probes.containsKey(id) || queued.containsKey(id)) check(event.getPlayer());
    }

    @Override
    public void close() {
        closed = true;
        PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        HandlerList.unregisterAll(this);
        for (BukkitTask task : queued.values()) task.cancel();
        queued.clear();
        for (Probe probe : new ArrayList<>(probes.values())) {
            if (probe.expiry != null) probe.expiry.cancel();
            restore(probe);
        }
        probes.clear();
        results.clear();
    }
}

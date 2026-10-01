package dev.tukisg.anticooldownhud;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import space.arim.libertybans.api.LibertyBans;
import space.arim.omnibus.OmnibusProvider;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class AntiCooldownHudPlugin extends JavaPlugin {
    static final Duration BAN_DURATION = Duration.ofDays(14);
    static final String BAN_REASON = "У вас был найден запрещенный мод!";
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Moscow"));
    private static final List<String> COMMANDS =
            List.of("check", "status", "bans", "history", "signatures", "reload");

    private final Set<CommandSender> listing = new HashSet<>();
    private LibertyBanGateway gateway;
    private BanService bans;
    private ModPunishments punishments;
    private ClientProbeService probes;
    private List<SignatureCheck.Profile> profiles = List.of();
    private boolean enforce;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            File signatures = new File(getDataFolder(), "signatures.yml");
            if (!signatures.exists()) saveResource("signatures.yml", false);
            LibertyBans api =
                    OmnibusProvider.getOmnibus()
                            .getRegistry()
                            .getProvider(LibertyBans.class)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "LibertyBans API is unavailable on this hub"));
            gateway = new LibertyBanGateway(api);
            bans =
                    new BanService(
                            gateway,
                            new BanHistory(getDataFolder().toPath().resolve("bans.tsv")),
                            getLogger(),
                            BAN_DURATION,
                            BAN_REASON);
            punishments = new ModPunishments(bans);
            loadSettings();
        } catch (Exception | LinkageError exception) {
            getLogger()
                    .log(
                            Level.SEVERE,
                            "Cannot enable CooldownHUDGuard; check LibertyBans, PacketEvents and"
                                    + " configuration",
                            exception);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private void loadSettings() throws Exception {
        List<SignatureCheck.Profile> updated;
        try (var bundled =
                new InputStreamReader(getResource("signatures.yml"), StandardCharsets.UTF_8)) {
            updated = SignatureCatalog.load(new File(getDataFolder(), "signatures.yml"), bundled);
        }
        FileConfiguration config = getConfig();
        String action = config.getString("action", "BAN").toUpperCase(Locale.ROOT);
        boolean migrate = action.equals("KICK");
        if (migrate) action = "BAN";
        if (!action.equals("BAN") && !action.equals("LOG"))
            throw new IllegalArgumentException("action must be BAN or LOG");
        ClientProbeService.Settings settings =
                new ClientProbeService.Settings(
                        config.getBoolean("check-on-join", true),
                        bounded(config, "join-delay-ticks", 40, 10, 400),
                        bounded(config, "response-timeout-ticks", 80, 20, 400),
                        bounded(config, "retry-delay-ticks", 20, 10, 200),
                        bounded(config, "max-timeout-retries", 1, 0, 3),
                        bounded(config, "max-concurrent-checks", 16, 1, 128),
                        config.getBoolean("log-inconclusive", true));
        if (migrate) {
            config.set("action", "BAN");
            config.set("kick-message", null);
            saveConfig();
        }
        if (probes != null) probes.close();
        profiles = updated;
        enforce = action.equals("BAN");
        probes = new ClientProbeService(this, settings, profiles, bans::isPending, this::detected);
        probes.checkOnlinePlayers();
        getLogger()
                .info(
                        "Loaded "
                                + profiles.size()
                                + " signatures; action="
                                + action
                                + "; UUID bans last 14 days; two confirmations required.");
    }

    private static int bounded(
            FileConfiguration config, String key, int fallback, int min, int max) {
        int value = config.getInt(key, fallback);
        if (value < min || value > max)
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return value;
    }

    private void detected(Player player, SignatureCheck.Profile profile) {
        String signature = profile.id();
        if (!enforce || player.hasPermission("cooldownguard.bypass")) {
            getLogger()
                    .info(
                            player.getName()
                                    + ": DETECTED "
                                    + signature
                                    + "; mod="
                                    + profile.modName()
                                    + "; action=LOG/BYPASS");
            return;
        }
        UUID playerId = player.getUniqueId();
        String name = player.getName();
        ClientProbeService source = probes;
        source.status(player, profile.action() + "_PENDING: " + signature);
        punishments
                .apply(
                        playerId,
                        name,
                        profile,
                        reason -> {
                            source.status(player, "KICKED: " + profile.modName());
                            getLogger()
                                    .info(
                                            "Kicked "
                                                    + name
                                                    + " ("
                                                    + playerId
                                                    + "), mod="
                                                    + profile.modName()
                                                    + ", signature="
                                                    + signature
                                                    + ", reason="
                                                    + reason);
                            player.kick(Component.text(reason));
                        })
                .whenComplete(
                        (result, error) -> {
                            if (error == null && result.action() == SignatureCheck.Action.KICK)
                                return;
                            BanService.Outcome outcome = result == null ? null : result.ban();
                            if (error != null)
                                getLogger()
                                        .log(
                                                Level.SEVERE,
                                                "LibertyBans failed to ban "
                                                        + name
                                                        + " ("
                                                        + playerId
                                                        + ")",
                                                error);
                            onMain(
                                    () -> {
                                        Player online = Bukkit.getPlayer(playerId);
                                        if (online == null || source != probes) return;
                                        if (error != null)
                                            source.status(online, "BAN_FAILED: see server log");
                                        else if (outcome.ban().isEmpty())
                                            source.status(
                                                    online,
                                                    "BAN_NOT_CREATED: existing punishment or"
                                                            + " conflict");
                                        else
                                            source.status(
                                                    online,
                                                    "BANNED: #"
                                                            + outcome.ban().get().id()
                                                            + (outcome.historySaved()
                                                                    ? ""
                                                                    : " (history write failed; see"
                                                                            + " log)"));
                                    });
                        });
    }

    private void onMain(Runnable action) {
        if (!isEnabled()) return;
        try {
            Bukkit.getScheduler()
                    .runTask(
                            this,
                            () -> {
                                if (isEnabled()) action.run();
                            });
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("cooldownguard.admin")) return true;
        if (args.length == 0) return false;
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (subcommand.equals("reload") && args.length == 1) {
            try {
                reloadConfig();
                loadSettings();
                sender.sendMessage("CooldownHUDGuard: настройки и сигнатуры обновлены.");
            } catch (Exception exception) {
                sender.sendMessage(
                        "Ошибка конфигурации: "
                                + exception.getMessage()
                                + ". Проверьте журнал сервера.");
                getLogger().log(Level.WARNING, "Configuration reload failed", exception);
            }
            return true;
        }
        if (subcommand.equals("signatures") && args.length == 1) {
            sender.sendMessage(
                    "Сигнатуры ("
                            + profiles.size()
                            + "): "
                            + String.join(
                                    ", ",
                                    profiles.stream()
                                            .map(
                                                    profile ->
                                                            profile.modName()
                                                                    + " ["
                                                                    + profile.id()
                                                                    + ": "
                                                                    + profile.action()
                                                                    + "]")
                                            .toList()));
            return true;
        }
        HistoryQuery history = HistoryQuery.parse(args);
        if (history != null) {
            showHistory(sender, history.page(), history.player());
            return true;
        }
        if ((subcommand.equals("check") || subcommand.equals("status")) && args.length == 2) {
            Player player = Bukkit.getPlayerExact(args[1]);
            if (player == null) {
                sender.sendMessage(
                        "Игрок не в сети на этом хабе. История: /cooldownguard history " + args[1]);
                return true;
            }
            if (subcommand.equals("check")) {
                probes.check(player);
                sender.sendMessage("Проверка запрошена: /cooldownguard status " + player.getName());
            } else
                sender.sendMessage(player.getName() + ": " + probes.status(player.getUniqueId()));
            return true;
        }
        return false;
    }

    private void showHistory(CommandSender sender, String pageArgument, String player) {
        int page;
        try {
            page = Integer.parseInt(pageArgument);
            if (page < 1 || page > 100000) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            sender.sendMessage("Страница должна быть числом от 1 до 100000.");
            return;
        }
        if (!listing.add(sender)) {
            sender.sendMessage("Предыдущий запрос списка ещё выполняется.");
            return;
        }
        bans.page(page, player)
                .thenCompose(
                        history -> {
                            List<CompletableFuture<String>> lines =
                                    history.entries().stream()
                                            .map(
                                                    record ->
                                                            gateway.isActive(record.id())
                                                                    .toCompletableFuture()
                                                                    .orTimeout(10, TimeUnit.SECONDS)
                                                                    .handle(
                                                                            (active, error) ->
                                                                                    "#"
                                                                                            + record
                                                                                                    .id()
                                                                                            + " "
                                                                                            + record
                                                                                                    .playerName()
                                                                                            + " | "
                                                                                            + (error
                                                                                                            != null
                                                                                                    ? "статус"
                                                                                                          + " неизвестен"
                                                                                                    : active
                                                                                                            ? "АКТИВЕН"
                                                                                                            : "завершён/снят")
                                                                                            + " | до"
                                                                                            + " "
                                                                                            + DATE
                                                                                                    .format(
                                                                                                            record
                                                                                                                    .expires())
                                                                                            + " МСК |"
                                                                                            + " "
                                                                                            + record
                                                                                                    .signature()))
                                            .toList();
                            return CompletableFuture.allOf(lines.toArray(CompletableFuture[]::new))
                                    .thenRun(
                                            () ->
                                                    onMain(
                                                            () -> {
                                                                listing.remove(sender);
                                                                if (!sender.hasPermission(
                                                                        "cooldownguard.admin"))
                                                                    return;
                                                                sender.sendMessage(
                                                                        "Автобаны CooldownHUDGuard"
                                                                                + " — страница "
                                                                                + page
                                                                                + (player == null
                                                                                        ? ""
                                                                                        : " — "
                                                                                                + player));
                                                                if (lines.isEmpty())
                                                                    sender.sendMessage(
                                                                            "Записей нет.");
                                                                else
                                                                    lines.forEach(
                                                                            line ->
                                                                                    sender
                                                                                            .sendMessage(
                                                                                                    line
                                                                                                            .join()));
                                                                if (history.hasNext())
                                                                    sender.sendMessage(
                                                                            "Далее: /cooldownguard "
                                                                                    + (player
                                                                                                    == null
                                                                                            ? "bans "
                                                                                            : "history"
                                                                                                  + " "
                                                                                                    + player
                                                                                                    + " ")
                                                                                    + (page + 1));
                                                            }));
                        })
                .exceptionally(
                        error -> {
                            getLogger().log(Level.WARNING, "Cannot read ban history", error);
                            onMain(
                                    () -> {
                                        listing.remove(sender);
                                        sender.sendMessage(
                                                "Не удалось прочитать историю. Подробности в"
                                                        + " журнале сервера.");
                                    });
                            return null;
                        });
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("cooldownguard.admin")) return List.of();
        if (args.length == 1)
            return COMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        if (args.length == 2
                && List.of("check", "status", "history")
                        .contains(args[0].toLowerCase(Locale.ROOT))) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(
                            s ->
                                    s.toLowerCase(Locale.ROOT)
                                            .startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }

    @Override
    public void onDisable() {
        if (probes != null) probes.close();
        if (bans != null) bans.close();
        listing.clear();
    }
}

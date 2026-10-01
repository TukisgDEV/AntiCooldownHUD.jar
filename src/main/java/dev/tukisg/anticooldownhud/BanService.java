package dev.tukisg.anticooldownhud;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

final class BanService implements AutoCloseable {
    record Outcome(Optional<BanRecord> ban, boolean historySaved) {}

    private final BanGateway gateway;
    private final BanHistory history;
    private final Logger logger;
    private final Duration duration;
    private final String reason;
    private final ConcurrentHashMap<UUID, CompletableFuture<Outcome>> pending =
            new ConcurrentHashMap<>();
    private final ExecutorService io =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "CooldownHUDGuard-history");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final AtomicBoolean closed = new AtomicBoolean();

    BanService(
            BanGateway gateway,
            BanHistory history,
            Logger logger,
            Duration duration,
            String reason) {
        if (duration.isZero() || duration.isNegative() || reason.isBlank())
            throw new IllegalArgumentException();
        this.gateway = gateway;
        this.history = history;
        this.logger = logger;
        this.duration = duration;
        this.reason = reason;
    }

    boolean isPending(UUID playerId) {
        return pending.containsKey(playerId);
    }

    synchronized CompletionStage<Outcome> ban(UUID playerId, String name, String signature) {
        return ban(playerId, name, signature, reason);
    }

    synchronized CompletionStage<Outcome> ban(
            UUID playerId, String name, String signature, String punishmentReason) {
        if (punishmentReason == null
                || punishmentReason.isBlank()
                || punishmentReason.length() > 1024
                || punishmentReason.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid ban reason");
        if (closed.get())
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Ban service is closed"));
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        CompletableFuture<Outcome> existing = pending.putIfAbsent(playerId, result);
        if (existing != null) return existing;
        try {
            gateway.ban(playerId, name, signature, duration, punishmentReason)
                    .whenComplete(
                            (ban, error) -> {
                                if (error != null) {
                                    finish(playerId, result, null, error);
                                    return;
                                }
                                io.execute(
                                        () -> {
                                            boolean saved = false;
                                            if (ban.isPresent()) {
                                                BanRecord record = ban.get();
                                                logger.info(
                                                        "LibertyBans created ban #"
                                                                + record.id()
                                                                + " for "
                                                                + name
                                                                + " ("
                                                                + playerId
                                                                + "), signature="
                                                                + signature
                                                                + ", expires="
                                                                + record.expires()
                                                                + ", reason="
                                                                + record.reason());
                                                try {
                                                    history.append(record);
                                                    saved = true;
                                                } catch (Exception exception) {
                                                    logger.log(
                                                            Level.SEVERE,
                                                            "Ban #"
                                                                    + record.id()
                                                                    + " was created but history"
                                                                    + " could not be saved",
                                                            exception);
                                                }
                                            }
                                            finish(playerId, result, new Outcome(ban, saved), null);
                                        });
                            });
        } catch (Exception exception) {
            finish(playerId, result, null, exception);
        }
        return result;
    }

    CompletionStage<BanHistory.Page> page(int page, String player) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return history.page(page, 10, player);
                    } catch (Exception exception) {
                        throw new java.util.concurrent.CompletionException(exception);
                    }
                },
                io);
    }

    private void finish(
            UUID playerId, CompletableFuture<Outcome> result, Outcome outcome, Throwable error) {
        pending.remove(playerId, result);
        if (error == null) result.complete(outcome);
        else result.completeExceptionally(error);
        if (closed.get() && pending.isEmpty()) io.shutdown();
    }

    @Override
    public synchronized void close() {
        closed.set(true);
        if (pending.isEmpty()) io.shutdown();
    }
}

package dev.tukisg.anticooldownhud;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

@Timeout(10)
class BanServiceTest {
    @TempDir Path directory;

    private static final class Gateway implements BanGateway {
        final CompletableFuture<Optional<BanRecord>> pending = new CompletableFuture<>();
        int calls;
        UUID target;
        Duration duration;
        String reason;

        @Override
        public CompletionStage<Optional<BanRecord>> ban(
                UUID id, String name, String signature, Duration duration, String reason) {
            calls++;
            target = id;
            this.duration = duration;
            this.reason = reason;
            return pending;
        }

        @Override
        public CompletionStage<Boolean> isActive(long id) {
            return CompletableFuture.completedFuture(true);
        }
    }

    private BanService service(Gateway gateway, BanHistory history) {
        return new BanService(
                gateway,
                history,
                Logger.getAnonymousLogger(),
                Duration.ofDays(14),
                "У вас был найден запрещенный мод!");
    }

    @Test
    void duplicateDetectionUsesOneRequestAndLogsOnlyConfirmedBan() throws Exception {
        var gateway = new Gateway();
        var history = new BanHistory(directory.resolve("bans.tsv"));
        try (var service = service(gateway, history)) {
            var first = service.ban(BanHistoryTest.ID, "Player", "legacy");
            var second = service.ban(BanHistoryTest.ID, "Player", "legacy");
            assertSame(first, second);
            assertEquals(1, gateway.calls);
            assertEquals(BanHistoryTest.ID, gateway.target);
            assertEquals(Duration.ofDays(14), gateway.duration);
            assertEquals("У вас был найден запрещенный мод!", gateway.reason);
            assertTrue(history.page(1, 10, null).entries().isEmpty());
            gateway.pending.complete(Optional.of(BanHistoryTest.record(1, "Player")));
            assertTrue(first.toCompletableFuture().join().historySaved());
            assertEquals(1, history.page(1, 10, null).entries().size());
            assertFalse(service.isPending(BanHistoryTest.ID));
        }
    }

    @Test
    void emptyApiResultDoesNotAddBanHistory() throws Exception {
        var gateway = new Gateway();
        var history = new BanHistory(directory.resolve("bans.tsv"));
        try (var service = service(gateway, history)) {
            var result = service.ban(BanHistoryTest.ID, "Player", "legacy");
            gateway.pending.complete(Optional.empty());
            assertTrue(result.toCompletableFuture().join().ban().isEmpty());
            assertTrue(history.page(1, 10, null).entries().isEmpty());
        }
    }

    @Test
    void failureDoesNotAddBanHistoryOrKeepPlayerPending() throws Exception {
        var gateway = new Gateway();
        var history = new BanHistory(directory.resolve("bans.tsv"));
        try (var service = service(gateway, history)) {
            var result = service.ban(BanHistoryTest.ID, "Player", "legacy");
            gateway.pending.completeExceptionally(new IllegalStateException("Database offline"));
            assertThrows(CompletionException.class, () -> result.toCompletableFuture().join());
            assertTrue(history.page(1, 10, null).entries().isEmpty());
            assertFalse(service.isPending(BanHistoryTest.ID));
        }
    }

    @Test
    void shutdownStillRecordsAnAlreadyRequestedBan() throws Exception {
        var gateway = new Gateway();
        var history = new BanHistory(directory.resolve("bans.tsv"));
        var service = service(gateway, history);
        var result = service.ban(BanHistoryTest.ID, "Player", "legacy");
        service.close();
        gateway.pending.complete(Optional.of(BanHistoryTest.record(1, "Player")));
        assertTrue(result.toCompletableFuture().join().historySaved());
        assertEquals(1, history.page(1, 10, null).entries().size());
        assertThrows(
                CompletionException.class,
                () ->
                        service.ban(UUID.randomUUID(), "Other", "legacy")
                                .toCompletableFuture()
                                .join());
    }

    @Test
    void historyFailureIsReportedWithoutPretendingTheBanFailed() throws Exception {
        var gateway = new Gateway();
        Path file = directory.resolve("bans.tsv");
        var history = new BanHistory(file);
        Files.delete(file);
        Files.createDirectory(file);
        try (var service = service(gateway, history)) {
            var result = service.ban(BanHistoryTest.ID, "Player", "legacy");
            gateway.pending.complete(Optional.of(BanHistoryTest.record(1, "Player")));
            var outcome = result.toCompletableFuture().join();
            assertTrue(outcome.ban().isPresent());
            assertFalse(outcome.historySaved());
        }
    }
}

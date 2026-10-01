package dev.tukisg.anticooldownhud;

import static dev.tukisg.anticooldownhud.SignatureCheckTest.catalog;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

class ModPunishmentsTest {
    @TempDir Path directory;

    private static final class Gateway implements BanGateway {
        int calls;
        Duration duration;
        String reason;

        @Override
        public CompletionStage<Optional<BanRecord>> ban(
                UUID id, String name, String signature, Duration duration, String reason) {
            calls++;
            this.duration = duration;
            this.reason = reason;
            Instant created = Instant.now();
            return CompletableFuture.completedFuture(
                    Optional.of(
                            new BanRecord(
                                    calls,
                                    id,
                                    name,
                                    signature,
                                    created,
                                    created.plus(duration),
                                    reason)));
        }

        @Override
        public CompletionStage<Boolean> isActive(long id) {
            return CompletableFuture.completedFuture(true);
        }
    }

    @Test
    void allFreecamProfilesKickWithoutCallingLibertyBansOrWritingBanHistory() throws Exception {
        var gateway = new Gateway();
        var history = new BanHistory(directory.resolve("bans.tsv"));
        try (var bans =
                new BanService(
                        gateway,
                        history,
                        Logger.getAnonymousLogger(),
                        Duration.ofDays(14),
                        "Default reason")) {
            var punishments = new ModPunishments(bans);
            var kicks = new ArrayList<String>();
            for (var profile : catalog()) {
                if (!profile.id().startsWith("freecam-")) continue;
                var result =
                        punishments
                                .apply(UUID.randomUUID(), "Player", profile, kicks::add)
                                .toCompletableFuture()
                                .join();
                assertEquals(SignatureCheck.Action.KICK, result.action());
                assertNull(result.ban());
            }
            assertEquals(
                    java.util.List.of(
                            "У вас был найден запрещенный мод: Freecam!",
                            "У вас был найден запрещенный мод: Freecam!"),
                    kicks);
            assertEquals(0, gateway.calls);
            assertTrue(history.page(1, 10, null).entries().isEmpty());
        }
    }

    @Test
    void cooldownHudStillBansForFourteenDaysWithModNameInReasonAndHistory() throws Exception {
        var gateway = new Gateway();
        var history = new BanHistory(directory.resolve("bans.tsv"));
        try (var bans =
                new BanService(
                        gateway,
                        history,
                        Logger.getAnonymousLogger(),
                        Duration.ofDays(14),
                        "Default reason")) {
            var punishments = new ModPunishments(bans);
            var profile = catalog().getFirst();
            var result =
                    punishments
                            .apply(
                                    UUID.randomUUID(),
                                    "Player",
                                    profile,
                                    reason -> fail("CooldownHUD must use LibertyBans"))
                            .toCompletableFuture()
                            .join();
            assertEquals(SignatureCheck.Action.BAN, result.action());
            assertTrue(result.ban().historySaved());
            assertEquals(1, gateway.calls);
            assertEquals(Duration.ofDays(14), gateway.duration);
            assertEquals("У вас был найден запрещенный мод: CooldownHUD!", gateway.reason);
            assertEquals(gateway.reason, history.page(1, 10, null).entries().getFirst().reason());
        }
    }
}

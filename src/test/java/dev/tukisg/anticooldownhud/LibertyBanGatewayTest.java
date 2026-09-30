package dev.tukisg.anticooldownhud;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import space.arim.libertybans.api.ConsoleOperator;
import space.arim.libertybans.api.LibertyBans;
import space.arim.libertybans.api.PlayerVictim;
import space.arim.libertybans.api.PunishmentType;
import space.arim.libertybans.api.punish.DraftPunishment;
import space.arim.libertybans.api.punish.DraftPunishmentBuilder;
import space.arim.libertybans.api.punish.Punishment;
import space.arim.libertybans.api.punish.PunishmentDrafter;
import space.arim.libertybans.api.select.PunishmentSelector;
import space.arim.omnibus.util.concurrent.impl.IndifferentFactoryOfTheFuture;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

class LibertyBanGatewayTest {
    @SuppressWarnings("unchecked")
    private static <T> T mock(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    @Test
    void createsAccountBanForExactlyFourteenDaysAndReturnsRealPunishment() {
        UUID id = UUID.randomUUID();
        Map<String, Object> arguments = new HashMap<>();
        var futures = new IndifferentFactoryOfTheFuture();
        var pending = futures.<Optional<Punishment>>newIncompleteFuture();
        var draft =
                mock(
                        DraftPunishment.class,
                        (p, m, a) -> {
                            assertEquals("enactPunishment", m.getName());
                            return pending;
                        });
        var builder =
                mock(
                        DraftPunishmentBuilder.class,
                        (p, m, a) -> {
                            if (m.getName().equals("build")) return draft;
                            arguments.put(m.getName(), a[0]);
                            return p;
                        });
        var drafter = mock(PunishmentDrafter.class, (p, m, a) -> builder);
        var api = mock(LibertyBans.class, (p, m, a) -> drafter);
        var gateway = new LibertyBanGateway(api);
        var result =
                gateway.ban(
                                id,
                                "Player",
                                "cooldownhud-legacy",
                                AntiCooldownHudPlugin.BAN_DURATION,
                                AntiCooldownHudPlugin.BAN_REASON)
                        .toCompletableFuture();
        assertFalse(result.isDone());
        assertEquals(PunishmentType.BAN, arguments.get("type"));
        assertEquals(PlayerVictim.of(id), arguments.get("victim"));
        assertEquals(ConsoleOperator.INSTANCE, arguments.get("operator"));
        assertEquals(Duration.ofSeconds(1209600), arguments.get("duration"));
        assertEquals("У вас был найден запрещенный мод!", arguments.get("reason"));
        assertFalse(arguments.containsKey("scope"));
        Instant start = Instant.parse("2026-09-30T12:00:00Z");
        var punishment =
                mock(
                        Punishment.class,
                        (p, m, a) ->
                                switch (m.getName()) {
                                    case "getIdentifier" -> 2147483655L;
                                    case "getStartDate" -> start;
                                    case "getEndDate" -> start.plus(Duration.ofDays(14));
                                    case "getReason" -> AntiCooldownHudPlugin.BAN_REASON;
                                    default -> throw new AssertionError(m);
                                });
        pending.complete(Optional.of(punishment));
        BanRecord record = result.join().orElseThrow();
        assertEquals(2147483655L, record.id());
        assertEquals(id, record.playerId());
        assertEquals(start.plus(Duration.ofDays(14)), record.expires());
    }

    @Test
    void existingPunishmentAndApiFailureAreNotSuccessfulBans() {
        var futures = new IndifferentFactoryOfTheFuture();
        var pending = futures.<Optional<Punishment>>newIncompleteFuture();
        var draft = mock(DraftPunishment.class, (p, m, a) -> pending);
        var builder =
                mock(
                        DraftPunishmentBuilder.class,
                        (p, m, a) -> m.getName().equals("build") ? draft : p);
        var drafter = mock(PunishmentDrafter.class, (p, m, a) -> builder);
        var api = mock(LibertyBans.class, (p, m, a) -> drafter);
        var gateway = new LibertyBanGateway(api);
        var result =
                gateway.ban(
                        UUID.randomUUID(), "Player", "signature", Duration.ofDays(14), "reason");
        pending.complete(Optional.empty());
        assertTrue(result.toCompletableFuture().join().isEmpty());
        var failedDraft =
                mock(
                        DraftPunishment.class,
                        (p, m, a) ->
                                futures.failedFuture(
                                        new IllegalStateException("Database unavailable")));
        var failedBuilder =
                mock(
                        DraftPunishmentBuilder.class,
                        (p, m, a) -> m.getName().equals("build") ? failedDraft : p);
        var failedDrafter = mock(PunishmentDrafter.class, (p, m, a) -> failedBuilder);
        var failedApi = mock(LibertyBans.class, (p, m, a) -> failedDrafter);
        assertThrows(
                CompletionException.class,
                () ->
                        new LibertyBanGateway(failedApi)
                                .ban(
                                        UUID.randomUUID(),
                                        "Player",
                                        "signature",
                                        Duration.ofDays(14),
                                        "reason")
                                .toCompletableFuture()
                                .join());
    }

    @Test
    void banListChecksActualActiveStatus() {
        var futures = new IndifferentFactoryOfTheFuture();
        var selector =
                mock(
                        PunishmentSelector.class,
                        (p, m, a) -> {
                            assertEquals("getActivePunishmentByIdAndType", m.getName());
                            assertEquals(99L, a[0]);
                            assertEquals(PunishmentType.BAN, a[1]);
                            return futures.completedFuture(Optional.empty());
                        });
        var api = mock(LibertyBans.class, (p, m, a) -> selector);
        assertFalse(new LibertyBanGateway(api).isActive(99).toCompletableFuture().join());
    }
}

package dev.tukisg.anticooldownhud;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

class BanHistoryTest {
    @TempDir Path directory;
    static final UUID ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    static BanRecord record(long id, String name) {
        return new BanRecord(
                id,
                ID,
                name,
                "cooldownhud-legacy",
                Instant.parse("2026-09-30T12:00:00Z"),
                Instant.parse("2026-10-14T12:00:00Z"),
                "У вас был найден запрещенный мод!");
    }

    @Test
    void persistsAcrossRestartAndPaginatesNewestFirst() throws Exception {
        Path file = directory.resolve("bans.tsv");
        var history = new BanHistory(file);
        for (int i = 1; i <= 35; i++) history.append(record(i, "Player" + i));
        history = new BanHistory(file);
        var first = history.page(1, 10, null);
        assertEquals(35, first.entries().getFirst().id());
        assertEquals(26, first.entries().getLast().id());
        assertTrue(first.hasNext());
        var last = history.page(4, 10, null);
        assertEquals(5, last.entries().size());
        assertEquals(1, last.entries().getLast().id());
        assertFalse(last.hasNext());
        assertTrue(history.page(5, 10, null).entries().isEmpty());
    }

    @Test
    void filtersNamesAndUuidsAndPreservesRussianText() throws Exception {
        var history = new BanHistory(directory.resolve("bans.tsv"));
        history.append(record(1, "PlayerOne"));
        history.append(record(2, "PlayerTwo"));
        assertEquals(record(1, "PlayerOne"), history.page(1, 10, "playerone").entries().getFirst());
        assertEquals(2, history.page(1, 10, ID.toString()).entries().size());
    }

    @Test
    void skipsTruncatedAndOversizedLinesWithoutLosingValidRecords() throws Exception {
        Path file = directory.resolve("bans.tsv");
        var history = new BanHistory(file);
        history.append(record(1, "First"));
        Files.writeString(file, "damaged\n" + "x".repeat(30000), StandardOpenOption.APPEND);
        history.append(record(2, "Second"));
        var page = history.page(1, 10, null);
        assertEquals(2, page.entries().size());
        assertEquals(2, page.entries().getFirst().id());
        assertEquals(1, page.entries().getLast().id());
    }

    @Test
    void validatesPaginationAndHandlesEmptyHistory() throws Exception {
        var history = new BanHistory(directory.resolve("bans.tsv"));
        assertTrue(history.page(1, 10, null).entries().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> history.page(0, 10, null));
        assertThrows(IllegalArgumentException.class, () -> history.page(1, 500, null));
    }
}

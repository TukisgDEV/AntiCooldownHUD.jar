package dev.tukisg.anticooldownhud;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class HistoryQueryTest {
    @TempDir Path directory;

    @Test
    void historyOneListsSavedBansInsteadOfFilteringForPlayerNamedOne() throws Exception {
        var history = new BanHistory(directory.resolve("bans.tsv"));
        history.append(BanHistoryTest.record(1, "DetectedPlayer"));
        var query = HistoryQuery.parse(new String[] {"history", "1"});
        assertEquals(new HistoryQuery("1", null), query);
        var page = history.page(Integer.parseInt(query.page()), 10, query.player());
        assertEquals("DetectedPlayer", page.entries().getFirst().playerName());
    }

    @Test
    void bothCommandsOpenGeneralHistoryWithOptionalPage() {
        for (String command : new String[] {"history", "HISTORY", "bans", "Bans"}) {
            assertEquals(new HistoryQuery("1", null), HistoryQuery.parse(new String[] {command}));
            assertEquals(
                    new HistoryQuery("2", null), HistoryQuery.parse(new String[] {command, "2"}));
        }
    }

    @Test
    void namesAndUuidsStillFilterHistoryWithOptionalPage() {
        for (String player : new String[] {"Player123", BanHistoryTest.ID.toString()}) {
            assertEquals(
                    new HistoryQuery("1", player),
                    HistoryQuery.parse(new String[] {"history", player}));
            assertEquals(
                    new HistoryQuery("3", player),
                    HistoryQuery.parse(new String[] {"history", player, "3"}));
        }
        assertEquals(
                new HistoryQuery("1", "123"),
                HistoryQuery.parse(new String[] {"history", "123", "1"}));
    }

    @Test
    void invalidPageNumbersReachPageValidationInsteadOfPlayerFilter() {
        for (String page : new String[] {"0", "-1", "100001", "99999999999999999999"})
            assertEquals(
                    new HistoryQuery(page, null),
                    HistoryQuery.parse(new String[] {"history", page}));
        assertNull(HistoryQuery.parse(new String[] {}));
        assertNull(HistoryQuery.parse(new String[] {"status", "Player"}));
        assertNull(HistoryQuery.parse(new String[] {"bans", "Player", "1"}));
        assertNull(HistoryQuery.parse(new String[] {"history", "Player", "1", "extra"}));
    }
}

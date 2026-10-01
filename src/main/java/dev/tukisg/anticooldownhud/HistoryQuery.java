package dev.tukisg.anticooldownhud;

import java.util.Locale;

record HistoryQuery(String page, String player) {
    static HistoryQuery parse(String[] args) {
        if (args.length == 0) return null;
        String command = args[0].toLowerCase(Locale.ROOT);
        if (command.equals("bans") && args.length <= 2)
            return new HistoryQuery(args.length == 2 ? args[1] : "1", null);
        if (!command.equals("history") || args.length > 3) return null;
        if (args.length == 1) return new HistoryQuery("1", null);
        if (args.length == 2 && args[1].matches("[+-]?[0-9]+"))
            return new HistoryQuery(args[1], null);
        return new HistoryQuery(args.length == 3 ? args[2] : "1", args[1]);
    }
}

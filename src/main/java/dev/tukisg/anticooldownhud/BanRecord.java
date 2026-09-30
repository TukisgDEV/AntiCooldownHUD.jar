package dev.tukisg.anticooldownhud;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BanRecord(
        long id,
        UUID playerId,
        String playerName,
        String signature,
        Instant created,
        Instant expires,
        String reason) {
    public BanRecord {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(created);
        Objects.requireNonNull(expires);
        if (id <= 0 || !expires.isAfter(created)) throw new IllegalArgumentException("Invalid ban");
        for (String value : new String[] {playerName, signature, reason}) {
            if (value == null || value.isBlank() || value.length() > 1024)
                throw new IllegalArgumentException("Invalid ban text");
        }
    }
}

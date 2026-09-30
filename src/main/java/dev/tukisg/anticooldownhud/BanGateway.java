package dev.tukisg.anticooldownhud;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

interface BanGateway {
    CompletionStage<Optional<BanRecord>> ban(
            UUID playerId, String name, String signature, Duration duration, String reason);

    CompletionStage<Boolean> isActive(long id);
}

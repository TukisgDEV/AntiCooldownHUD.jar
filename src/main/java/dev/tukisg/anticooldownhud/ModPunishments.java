package dev.tukisg.anticooldownhud;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

final class ModPunishments {
    record Result(SignatureCheck.Action action, BanService.Outcome ban) {}

    private final BanService bans;

    ModPunishments(BanService bans) {
        this.bans = bans;
    }

    CompletionStage<Result> apply(
            UUID playerId, String name, SignatureCheck.Profile profile, Consumer<String> kick) {
        if (profile.action() == SignatureCheck.Action.KICK) {
            kick.accept(profile.reason());
            return CompletableFuture.completedFuture(new Result(SignatureCheck.Action.KICK, null));
        }
        return bans.ban(playerId, name, profile.id(), profile.reason())
                .thenApply(outcome -> new Result(SignatureCheck.Action.BAN, outcome));
    }
}

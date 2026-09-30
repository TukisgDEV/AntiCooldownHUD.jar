package dev.tukisg.anticooldownhud;

import space.arim.libertybans.api.ConsoleOperator;
import space.arim.libertybans.api.LibertyBans;
import space.arim.libertybans.api.PlayerVictim;
import space.arim.libertybans.api.PunishmentType;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

final class LibertyBanGateway implements BanGateway {
    private final LibertyBans api;

    LibertyBanGateway(LibertyBans api) {
        this.api = api;
    }

    @Override
    public CompletionStage<Optional<BanRecord>> ban(
            UUID playerId, String name, String signature, Duration duration, String reason) {
        return api.getDrafter()
                .draftBuilder()
                .type(PunishmentType.BAN)
                .victim(PlayerVictim.of(playerId))
                .operator(ConsoleOperator.INSTANCE)
                .duration(duration)
                .reason(reason)
                .build()
                .enactPunishment()
                .thenApply(
                        result ->
                                result.map(
                                        ban ->
                                                new BanRecord(
                                                        ban.getIdentifier(),
                                                        playerId,
                                                        name,
                                                        signature,
                                                        ban.getStartDate(),
                                                        ban.getEndDate(),
                                                        ban.getReason())));
    }

    @Override
    public CompletionStage<Boolean> isActive(long id) {
        return api.getSelector()
                .getActivePunishmentByIdAndType(id, PunishmentType.BAN)
                .thenApply(result -> result.filter(ban -> !ban.isExpired()).isPresent());
    }
}

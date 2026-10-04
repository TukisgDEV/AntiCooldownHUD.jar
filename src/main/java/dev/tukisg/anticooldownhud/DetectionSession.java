package dev.tukisg.anticooldownhud;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class DetectionSession {
    enum Step {
        NEXT,
        CONFIRM,
        RETRY,
        DETECTED,
        NO_MATCH,
        INCONCLUSIVE
    }

    private final List<SignatureCheck.Profile> profiles;
    private final int maxRetries;

    private record MissingKey(SignatureCheck.ProbeFormat format, String keybind) {
        static MissingKey of(SignatureCheck.Profile profile) {
            return new MissingKey(profile.probeFormat(), profile.keybind());
        }
    }

    private final Set<MissingKey> missingKeybinds = new HashSet<>();
    private int index;
    private int matches;
    private int retries;
    private boolean partial;
    private String lastMatchedNonce;

    DetectionSession(List<SignatureCheck.Profile> profiles, int maxRetries) {
        if (profiles.isEmpty() || maxRetries < 0) throw new IllegalArgumentException();
        this.profiles = List.copyOf(profiles);
        this.maxRetries = maxRetries;
    }

    SignatureCheck.Profile profile() {
        return profiles.get(index);
    }

    Step reply(SignatureCheck.Challenge challenge, String[] lines) {
        if (!profile().equals(challenge.profile())) return Step.INCONCLUSIVE;
        SignatureCheck.Result result = challenge.evaluate(lines);
        if (result == SignatureCheck.Result.INVALID) return Step.INCONCLUSIVE;
        if (result == SignatureCheck.Result.DETECTED) {
            if (challenge.nonce().equals(lastMatchedNonce)) return Step.INCONCLUSIVE;
            lastMatchedNonce = challenge.nonce();
            return ++matches >= 2 ? Step.DETECTED : Step.CONFIRM;
        }
        if (matches > 0 || result == SignatureCheck.Result.PARTIAL) partial = true;
        matches = 0;
        if (profile().probeFormat() != SignatureCheck.ProbeFormat.THREE_TRANSLATIONS
                && profile().keybind().equals(lines[2]))
            missingKeybinds.add(MissingKey.of(profile()));
        do {
            index++;
        } while (index < profiles.size() && missingKeybinds.contains(MissingKey.of(profile())));
        if (index < profiles.size()) return Step.NEXT;
        return partial ? Step.INCONCLUSIVE : Step.NO_MATCH;
    }

    Step timeout() {
        matches = 0;
        return retries++ < maxRetries ? Step.RETRY : Step.INCONCLUSIVE;
    }
}

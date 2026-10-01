package dev.tukisg.anticooldownhud;

import java.util.Set;

public final class SignatureCheck {
    public enum Action {
        BAN,
        KICK
    }

    public record Translation(String key, Set<String> values) {
        public Translation {
            if (key == null || !key.matches("[a-zA-Z0-9_.-]{1,160}")) {
                throw new IllegalArgumentException("Invalid translation key");
            }
            values = Set.copyOf(values);
            if (values.isEmpty()
                    || values.stream().anyMatch(s -> s.isBlank() || s.length() > 384)) {
                throw new IllegalArgumentException(
                        "Translation values must contain exact, nonempty texts");
            }
        }
    }

    public record Profile(
            String id,
            Translation first,
            Translation second,
            String keybind,
            boolean requireTranslations,
            String modName,
            Action action) {
        public Profile(String id, Translation first, Translation second, String keybind) {
            this(id, first, second, keybind, true);
        }

        public Profile(
                String id,
                Translation first,
                Translation second,
                String keybind,
                boolean requireTranslations) {
            this(id, first, second, keybind, requireTranslations, "CooldownHUD", Action.BAN);
        }

        public Profile {
            if (id == null || !id.matches("[a-z0-9_-]{1,64}"))
                throw new IllegalArgumentException("Invalid signature id");
            if (first == null || second == null || first.key().equals(second.key())) {
                throw new IllegalArgumentException("Two different translation keys are required");
            }
            if (keybind == null || !keybind.matches("[a-zA-Z0-9_.-]{1,160}"))
                throw new IllegalArgumentException("Invalid keybind");
            if (modName == null
                    || modName.isBlank()
                    || modName.length() > 64
                    || modName.chars().anyMatch(Character::isISOControl)
                    || action == null)
                throw new IllegalArgumentException("Invalid mod name or action");
        }

        public String reason() {
            return "У вас был найден запрещенный мод: " + modName + "!";
        }
    }

    public record Challenge(Profile profile, String nonce) {
        public Challenge {
            if (profile == null || nonce == null || !nonce.matches("[a-zA-Z0-9]{8,32}"))
                throw new IllegalArgumentException("Invalid challenge");
        }

        public String fallbackA() {
            return "cg_a_" + nonce;
        }

        public String fallbackB() {
            return "cg_b_" + nonce;
        }

        public boolean validReply(String[] lines) {
            if (lines == null || lines.length != 4 || !nonce.equals(lines[3])) return false;
            for (String line : lines) {
                if (line == null
                        || line.length() > 384
                        || line.chars().anyMatch(Character::isISOControl)) return false;
            }
            return true;
        }

        public Result evaluate(String[] lines) {
            if (!validReply(lines)) return Result.INVALID;
            boolean bound =
                    !lines[2].isBlank()
                            && lines[2].length() <= 80
                            && !lines[2].equals(profile.keybind())
                            && !lines[2].equals(nonce)
                            && !lines[2].equals(fallbackA())
                            && !lines[2].equals(fallbackB());
            if (bound
                    && (!profile.requireTranslations()
                            || (profile.first().values().contains(lines[0])
                                    && profile.second().values().contains(lines[1]))))
                return Result.DETECTED;
            if (fallbackA().equals(lines[0])
                    && fallbackB().equals(lines[1])
                    && profile.keybind().equals(lines[2])) return Result.NO_MATCH;
            return Result.PARTIAL;
        }
    }

    public enum Result {
        DETECTED,
        NO_MATCH,
        PARTIAL,
        INVALID
    }

    private SignatureCheck() {}
}

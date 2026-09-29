package dev.tukisg.anticooldownhud;

import java.util.Set;

public final class SignatureCheck {
    public static final String MACE_KEY = "activity.module.auto_mace.name";
    public static final String TOTEM_KEY = "activity.module.auto_totem.name";
    public static final String HUD_KEY = "key.cooldown_hud.open";
    private static final Set<String> MACE = Set.of("AutoMace", "Авто-булава (AutoMace)");
    private static final Set<String> TOTEM = Set.of("AutoTotem", "Авто-тотем (AutoTotem)");

    public enum Result {
        DETECTED,
        NO_MATCH,
        INCONCLUSIVE
    }

    public static Result evaluate(
            String[] lines, String nonce, String fallbackA, String fallbackB) {
        if (lines == null || lines.length != 4 || !nonce.equals(lines[3]))
            return Result.INCONCLUSIVE;
        boolean translated = MACE.contains(lines[0]) && TOTEM.contains(lines[1]);
        String key = lines[2];
        boolean bound =
                key != null
                        && !key.isBlank()
                        && key.length() <= 80
                        && !key.equals(HUD_KEY)
                        && !key.equals(fallbackA)
                        && !key.equals(fallbackB)
                        && !key.equals(nonce)
                        && key.chars().noneMatch(Character::isISOControl);
        if (translated && bound) return Result.DETECTED;
        if (fallbackA.equals(lines[0]) && fallbackB.equals(lines[1]) && HUD_KEY.equals(key))
            return Result.NO_MATCH;
        return Result.INCONCLUSIVE;
    }

    private SignatureCheck() {}
}

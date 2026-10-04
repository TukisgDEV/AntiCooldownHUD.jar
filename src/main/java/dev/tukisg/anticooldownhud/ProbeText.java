package dev.tukisg.anticooldownhud;

import net.kyori.adventure.text.Component;

import java.util.List;

final class ProbeText {
    static List<Component> lines(SignatureCheck.Challenge challenge) {
        var profile = challenge.profile();
        return List.of(
                wrap(profile, Component.translatable(profile.first().key(), challenge.fallbackA())),
                wrap(
                        profile,
                        Component.translatable(profile.second().key(), challenge.fallbackB())),
                wrap(profile, Component.keybind(profile.keybind())),
                Component.text(challenge.nonce()));
    }

    private static Component wrap(SignatureCheck.Profile profile, Component content) {
        return profile.probeFormat() == SignatureCheck.ProbeFormat.TRANSLATION_ARGUMENT
                ? Component.translatable("options.value", "%s").arguments(content)
                : content;
    }

    private ProbeText() {}
}

package dev.tukisg.anticooldownhud;

import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.util.adventure.AdventureNBTSerializer;

import net.kyori.adventure.text.Component;

import java.util.List;

final class ProbeWire {
    private static final AdventureNBTSerializer SERIALIZER =
            new AdventureNBTSerializer(ClientVersion.V_1_21_11, false);

    record Result(NBTCompound data, String status) {}

    static Result verify(NBTCompound data, SignatureCheck.Challenge challenge) {
        if (data == null) return new Result(null, "missing");
        var front = data.getCompoundTagOrNull("front_text");
        if (front == null) return new Result(data, "missing-front");
        var messages = front.getTagOfTypeOrNull("messages", NBTList.class);
        if (messages == null
                || messages.size() != 4
                || !Component.text(challenge.nonce()).equals(read(messages.getTag(3)))) {
            return new Result(data, "unrelated");
        }
        var expected = ProbeText.lines(challenge);
        var filtered = front.getTagOfTypeOrNull("filtered_messages", NBTList.class);
        if (matches(messages, expected) && (filtered == null || matches(filtered, expected))) {
            return new Result(data, "intact");
        }
        NBTCompound replacement = data.copy();
        NBTCompound replacementFront = replacement.getCompoundTagOrThrow("front_text");
        var encoded = NBTList.createCompoundList();
        for (Component line : expected) {
            NBT value = SERIALIZER.serialize(line);
            if (value instanceof NBTCompound compound) encoded.addTag(compound);
            else {
                NBTCompound literal = new NBTCompound();
                literal.setTag("text", value);
                encoded.addTag(literal);
            }
        }
        replacementFront.setTag("messages", encoded);
        replacementFront.setTag("filtered_messages", encoded.copy());
        return new Result(replacement, "repaired");
    }

    private static boolean matches(NBTList<?> messages, List<Component> expected) {
        if (messages.size() != expected.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(read(messages.getTag(i)))) return false;
        }
        return true;
    }

    private static Component read(NBT value) {
        try {
            return SERIALIZER.deserialize(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private ProbeWire() {}
}

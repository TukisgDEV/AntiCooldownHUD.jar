package dev.tukisg.anticooldownhud;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

final class BanHistory {
    record Page(List<BanRecord> entries, boolean hasNext) {
        Page {
            entries = List.copyOf(entries);
        }
    }

    private static final int MAX_LINE_BYTES = 24576;
    private final Path file;

    BanHistory(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (FileChannel ignored =
                FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {}
    }

    synchronized void append(BanRecord ban) throws IOException {
        String line =
                "\n"
                        + ban.id()
                        + "\t"
                        + ban.playerId()
                        + "\t"
                        + encode(ban.playerName())
                        + "\t"
                        + encode(ban.signature())
                        + "\t"
                        + ban.created().getEpochSecond()
                        + "\t"
                        + ban.expires().getEpochSecond()
                        + "\t"
                        + encode(ban.reason())
                        + "\n";
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(line);
        try (FileChannel channel =
                FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(false);
        }
    }

    synchronized Page page(int page, int size, String player) throws IOException {
        if (page < 1 || page > 100000 || size < 1 || size > 50)
            throw new IllegalArgumentException("Invalid page");
        long skip = (long) (page - 1) * size;
        List<BanRecord> entries = new ArrayList<>(size);
        ByteBuffer block = ByteBuffer.allocate(8192);
        byte[] reverse = new byte[MAX_LINE_BYTES];
        int length = 0;
        boolean oversized = false;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long position = channel.size();
            while (position > 0) {
                int count = (int) Math.min(position, block.capacity());
                position -= count;
                block.clear().limit(count);
                channel.position(position);
                while (block.hasRemaining() && channel.read(block) > 0) {}
                for (int i = block.position() - 1; i >= 0; i--) {
                    byte value = block.get(i);
                    if (value != '\n') {
                        if (length < reverse.length) reverse[length++] = value;
                        else oversized = true;
                        continue;
                    }
                    BanRecord ban = oversized ? null : decode(reverse, length);
                    length = 0;
                    oversized = false;
                    if (ban == null
                            || (player != null
                                    && !player.equalsIgnoreCase(ban.playerName())
                                    && !player.equalsIgnoreCase(ban.playerId().toString())))
                        continue;
                    if (skip-- > 0) continue;
                    if (entries.size() == size) return new Page(entries, true);
                    entries.add(ban);
                }
            }
            BanRecord ban = oversized ? null : decode(reverse, length);
            if (ban != null
                    && (player == null
                            || player.equalsIgnoreCase(ban.playerName())
                            || player.equalsIgnoreCase(ban.playerId().toString()))
                    && skip <= 0) {
                if (entries.size() == size) return new Page(entries, true);
                entries.add(ban);
            }
        }
        return new Page(entries, false);
    }

    private static BanRecord decode(byte[] reverse, int length) {
        if (length == 0) return null;
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = reverse[length - 1 - i];
        try {
            String[] fields = new String(bytes, StandardCharsets.UTF_8).strip().split("\t", -1);
            if (fields.length != 7) return null;
            return new BanRecord(
                    Long.parseLong(fields[0]),
                    UUID.fromString(fields[1]),
                    text(fields[2]),
                    text(fields[3]),
                    Instant.ofEpochSecond(Long.parseLong(fields[4])),
                    Instant.ofEpochSecond(Long.parseLong(fields[5])),
                    text(fields[6]));
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            return null;
        }
    }

    private static String encode(String text) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(String text) {
        return new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
    }
}

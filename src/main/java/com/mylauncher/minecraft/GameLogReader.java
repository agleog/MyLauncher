package com.mylauncher.minecraft;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

public final class GameLogReader {
    private static final String LOG_FILE = "logs/mylauncher-game.log";
    private static final int MAX_TAIL_BYTES = 256 * 1024;
    private static final Pattern ACCESS_TOKEN = Pattern.compile(
            "(?i)(--accessToken(?:\\s*[,=]\\s*|\\s+))([^\\s,\\]\\[}]+)");

    public String readLatest(Path gameDirectory) throws IOException {
        Path root = gameDirectory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Папка игры отсутствует или небезопасна.");
        }
        Path log = root.resolve(LOG_FILE);
        Path current = root;
        for (Path component : root.relativize(log)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Путь игрового лога содержит символическую ссылку.");
            }
        }
        if (!Files.exists(log, LinkOption.NOFOLLOW_LINKS)) {
            return "Игровой лог пока не создан. Запустите Minecraft, затем нажмите «ОБНОВИТЬ».\n";
        }
        if (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Файл игрового лога имеет небезопасный тип.");
        }

        long size = Files.size(log);
        int length = (int) Math.min(size, MAX_TAIL_BYTES);
        ByteBuffer buffer = ByteBuffer.allocate(length);
        try (SeekableByteChannel channel = Files.newByteChannel(log, StandardOpenOption.READ)) {
            channel.position(Math.max(0, size - length));
            while (buffer.hasRemaining()) {
                int bytesRead = channel.read(buffer);
                if (bytesRead < 0) {
                    break;
                }
            }
        }
        buffer.flip();
        String text = StandardCharsets.UTF_8.decode(buffer).toString();
        StringBuilder safeText = new StringBuilder(text.length());
        for (String line : text.split("\\R", -1)) {
            safeText.append(redactAccessToken(line)).append(System.lineSeparator());
        }
        if (size > length) {
            return "[Показан конец лога; предыдущие строки скрыты]\n" + safeText;
        }
        return safeText.toString();
    }

    static String redactAccessToken(String line) {
        return ACCESS_TOKEN.matcher(line).replaceAll("$1[СКРЫТО]");
    }
}

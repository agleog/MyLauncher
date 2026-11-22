package com.mylauncher.ui;

import com.mylauncher.minecraft.GameLogReader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public final class ConsolePage extends VBox {
    private final TextArea output = new TextArea();
    private final Label status = new Label();
    private final GameLogReader logReader = new GameLogReader();
    private final Path gameDirectory;

    public ConsolePage(Path gameDirectory) {
        this.gameDirectory = gameDirectory.toAbsolutePath().normalize();
        getStyleClass().add("page");
        setSpacing(18);
        setPadding(new Insets(32, 38, 32, 38));

        Label title = new Label("Игровая консоль");
        title.getStyleClass().add("page-heading");
        Label subtitle = new Label("Последние сообщения Minecraft и NeoForge. Токены входа скрываются.");
        subtitle.getStyleClass().add("page-subtitle");

        Button refreshButton = new Button("ОБНОВИТЬ");
        refreshButton.getStyleClass().add("secondary-button");
        refreshButton.setOnAction(event -> refresh());
        Button openLogButton = new Button("ОТКРЫТЬ ФАЙЛ ЛОГА");
        openLogButton.getStyleClass().add("secondary-button");
        openLogButton.setOnAction(event -> openLog());
        HBox actions = new HBox(10, refreshButton, openLogButton, status);
        actions.setAlignment(Pos.CENTER_LEFT);
        status.getStyleClass().add("setting-description");

        output.setEditable(false);
        output.setWrapText(false);
        output.getStyleClass().add("console-output");
        VBox.setVgrow(output, Priority.ALWAYS);
        getChildren().addAll(title, subtitle, actions, output);
        refresh();
    }

    private void refresh() {
        try {
            output.setText(logReader.readLatest(gameDirectory));
            output.positionCaret(output.getLength());
            status.setText("Показаны последние строки");
        } catch (IOException | RuntimeException exception) {
            output.clear();
            status.setText("Не удалось прочитать лог: " + safeMessage(exception));
        }
    }

    private void openLog() {
        try {
            if (Files.isSymbolicLink(gameDirectory)
                    || !Files.isDirectory(gameDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Папка игры отсутствует или небезопасна.");
            }
            Path logDirectory = gameDirectory.resolve("logs");
            if (Files.exists(logDirectory, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(logDirectory)
                        || !Files.isDirectory(logDirectory, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Папка логов имеет небезопасный тип.");
                }
            } else {
                Files.createDirectory(logDirectory);
            }
            Path log = logDirectory.resolve("mylauncher-game.log");
            if (Files.exists(log, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(log) || !Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Файл игрового лога имеет небезопасный тип.");
            }
            if (!Files.exists(log)) {
                Files.createFile(log);
            }
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                throw new IOException("Открытие файлов не поддерживается в этой среде.");
            }
            Desktop.getDesktop().open(log.toFile());
            status.setText("Открыт файл игрового лога");
        } catch (IOException | RuntimeException exception) {
            status.setText("Не удалось открыть лог: " + safeMessage(exception));
        }
    }

    private static String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Неизвестная ошибка" : message;
    }
}

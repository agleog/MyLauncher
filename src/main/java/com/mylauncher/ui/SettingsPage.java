package com.mylauncher.ui;

import com.mylauncher.settings.LauncherSettings;
import com.mylauncher.settings.SettingsManager;
import com.mylauncher.javaruntime.TemurinRuntimeManager;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;

public final class SettingsPage extends VBox {
    private static final int MIN_WINDOW_WIDTH = 854;
    private static final int MAX_WINDOW_WIDTH = 7680;
    private static final int MIN_WINDOW_HEIGHT = 480;
    private static final int MAX_WINDOW_HEIGHT = 4320;

    private final SettingsManager settingsManager;
    private final Slider memorySlider;
    private final Label memoryValue;
    private final Spinner<Integer> widthSpinner;
    private final Spinner<Integer> heightSpinner;
    private final CheckBox fullscreen;
    private final TextField jvmArguments;
    private final TextField launcherDirectory;
    private final TextField gameDirectory;
    private final TextField backgroundImagePath;
    private final Label saveStatus;
    private final TemurinRuntimeManager runtimeManager = new TemurinRuntimeManager();
    private final Label runtimeStatus;
    private final ProgressBar runtimeProgress;
    private final Button checkRuntimeButton;
    private final Button installRuntimeButton;
    private final Button cancelRuntimeButton;
    private Task<TemurinRuntimeManager.RuntimeInfo> runtimeTask;

    public SettingsPage(SettingsManager settingsManager, LauncherSettings settings) {
        this.settingsManager = settingsManager;
        getStyleClass().add("page");
        setSpacing(20);
        setPadding(new Insets(32, 38, 32, 38));

        Label title = new Label("Настройки");
        title.getStyleClass().add("page-heading");
        Label subtitle = new Label("Параметры будут сохранены на этом компьютере.");
        subtitle.getStyleClass().add("page-subtitle");

        VBox memoryCard = settingsCard("Оперативная память");
        int maxMemoryGiB = availableMemoryGiB();
        memorySlider = new Slider(1, maxMemoryGiB, clamp(settings.memoryGiB(), 1, maxMemoryGiB));
        memorySlider.setMajorTickUnit(Math.max(1, Math.ceil(maxMemoryGiB / 8.0)));
        memorySlider.setMinorTickCount(0);
        memorySlider.setBlockIncrement(1);
        memorySlider.setShowTickMarks(true);
        memorySlider.setShowTickLabels(true);
        memoryValue = new Label();
        memoryValue.getStyleClass().add("setting-value");
        memorySlider.valueProperty().addListener((observable, oldValue, newValue) ->
                memoryValue.setText(Math.round(newValue.doubleValue()) + " ГБ"));
        memoryValue.setText(Math.round(memorySlider.getValue()) + " ГБ");
        memoryCard.getChildren().addAll(settingDescription("Объём памяти для будущего запуска игры"),
                memorySlider, memoryValue);

        VBox gameCard = settingsCard("Настройки игры");
        widthSpinner = integerSpinner(MIN_WINDOW_WIDTH, MAX_WINDOW_WIDTH, settings.windowWidth());
        heightSpinner = integerSpinner(MIN_WINDOW_HEIGHT, MAX_WINDOW_HEIGHT, settings.windowHeight());
        HBox dimensions = new HBox(14,
                labeledControl("Ширина", widthSpinner),
                labeledControl("Высота", heightSpinner));
        fullscreen = new CheckBox("Полноэкранный режим");
        fullscreen.setSelected(settings.fullscreen());
        fullscreen.getStyleClass().add("setting-checkbox");
        jvmArguments = new TextField(settings.jvmArguments());
        jvmArguments.setPromptText("Например: -XX:+UseG1GC");
        gameCard.getChildren().addAll(dimensions, fullscreen,
                labeledControl("Дополнительные аргументы JVM", jvmArguments),
                settingDescription("Аргументы применяются при запуске Minecraft после стандартных параметров Java."));

        VBox foldersCard = settingsCard("Папки");
        launcherDirectory = new TextField(settings.launcherDirectory());
        gameDirectory = new TextField(settings.gameDirectory());
        foldersCard.getChildren().addAll(
                directoryRow("Папка лаунчера", launcherDirectory),
                settingDescription("Изменение пути здесь не переносит файлы приложения; папку будет использовать будущий установщик."),
                directoryRow("Папка игры", gameDirectory),
                new HBox(10, folderAction("Открыть папку игры", gameDirectory, ""),
                        folderAction("Открыть mods", gameDirectory, "mods"),
                        folderAction("Открыть config", gameDirectory, "config"))
        );

        VBox backgroundCard = settingsCard("Фон главной страницы");
        backgroundImagePath = new TextField(settings.backgroundImagePath());
        backgroundImagePath.setPromptText("Не выбран");
        Button chooseBackground = new Button("ВЫБРАТЬ ИЗОБРАЖЕНИЕ");
        chooseBackground.getStyleClass().add("secondary-button");
        chooseBackground.setOnAction(event -> chooseBackgroundImage());
        Button clearBackground = new Button("СБРОСИТЬ");
        clearBackground.getStyleClass().add("secondary-button");
        clearBackground.setOnAction(event -> {
            backgroundImagePath.clear();
            saveSettings();
        });
        backgroundCard.getChildren().addAll(
                settingDescription("Выберите изображение для главного экрана. Поддерживаются PNG, JPEG, GIF и BMP."),
                backgroundImagePath,
                new HBox(10, chooseBackground, clearBackground)
        );

        VBox accountCard = settingsCard("Аккаунт Ely.by");
        Hyperlink registrationLink = new Hyperlink("Открыть сайт Ely.by");
        registrationLink.setOnAction(event -> openElyByAccountPage());
        accountCard.getChildren().addAll(
                settingDescription("Для входа используйте аккаунт Ely.by. Пароль и код 2FA лаунчер не сохраняет; игровой токен хранится с защитой Windows."),
                registrationLink,
                settingDescription("Вход через Ely.by не проверяет наличие лицензии Minecraft Java Edition и не предоставляет её.")
        );

        VBox javaCard = settingsCard("Java Runtime");
        runtimeStatus = new Label(initialRuntimeStatus(settings.gameDirectory()));
        runtimeStatus.getStyleClass().add("setting-description");
        runtimeStatus.setWrapText(true);
        runtimeProgress = new ProgressBar();
        runtimeProgress.setMaxWidth(Double.MAX_VALUE);
        runtimeProgress.setVisible(false);
        checkRuntimeButton = new Button("ПРОВЕРИТЬ JAVA");
        checkRuntimeButton.getStyleClass().add("secondary-button");
        checkRuntimeButton.setOnAction(event -> checkRuntime());
        installRuntimeButton = new Button("УСТАНОВИТЬ TEMURIN 21");
        installRuntimeButton.getStyleClass().add("primary-button");
        installRuntimeButton.setOnAction(event -> installRuntime());
        cancelRuntimeButton = new Button("ОТМЕНИТЬ");
        cancelRuntimeButton.getStyleClass().add("secondary-button");
        cancelRuntimeButton.setDisable(true);
        cancelRuntimeButton.setOnAction(event -> {
            if (runtimeTask != null) {
                runtimeTask.cancel(true);
            }
        });
        javaCard.getChildren().addAll(
                settingDescription("Для Minecraft потребуется отдельная Eclipse Temurin Java 21 x64. Она будет установлена в папку игры и проверена перед использованием."),
                runtimeStatus,
                runtimeProgress,
                new HBox(10, checkRuntimeButton, installRuntimeButton, cancelRuntimeButton)
        );

        Button saveButton = new Button("СОХРАНИТЬ НАСТРОЙКИ");
        saveButton.getStyleClass().add("primary-button");
        saveButton.setOnAction(event -> saveSettings());
        saveStatus = new Label();
        saveStatus.getStyleClass().add("save-status");
        HBox footer = new HBox(14, saveButton, saveStatus);
        footer.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(title, subtitle, memoryCard, gameCard, foldersCard, backgroundCard,
                javaCard, accountCard, footer);
    }

    private String initialRuntimeStatus(String configuredGameDirectory) {
        try {
            Path javaExecutable = runtimeManager.runtimeHome(Path.of(configuredGameDirectory))
                    .resolve("bin").resolve("java.exe");
            return Files.isRegularFile(javaExecutable)
                    ? "Найдена Java по пути: " + javaExecutable + ". Нажмите «Проверить Java»."
                    : "Java 21 пока не установлена.";
        } catch (RuntimeException exception) {
            return "Не удалось определить папку Java: " + exception.getMessage();
        }
    }

    private void checkRuntime() {
        final Path gamePath;
        try {
            gamePath = Path.of(settingsManager.load().gameDirectory()).toAbsolutePath().normalize();
        } catch (IOException | RuntimeException exception) {
            showError("Не удалось проверить Java", exception.getMessage());
            return;
        }
        startRuntimeTask(new Task<>() {
            @Override
            protected TemurinRuntimeManager.RuntimeInfo call() throws Exception {
                updateMessage("Проверяем Eclipse Temurin Java 21 x64…");
                return runtimeManager.verify(gamePath);
            }
        });
    }

    private void installRuntime() {
        final Path gamePath;
        try {
            gamePath = Path.of(settingsManager.load().gameDirectory()).toAbsolutePath().normalize();
        } catch (IOException | RuntimeException exception) {
            showError("Не удалось установить Java", exception.getMessage());
            return;
        }
        startRuntimeTask(new Task<>() {
            @Override
            protected TemurinRuntimeManager.RuntimeInfo call() throws Exception {
                return runtimeManager.install(gamePath,
                        (message, completedBytes, totalBytes) -> {
                            updateMessage(message);
                            updateProgress(completedBytes, totalBytes);
                        },
                        this::isCancelled);
            }
        });
    }

    private void startRuntimeTask(Task<TemurinRuntimeManager.RuntimeInfo> task) {
        if (runtimeTask != null && !runtimeTask.isDone()) {
            return;
        }
        runtimeTask = task;
        checkRuntimeButton.setDisable(true);
        installRuntimeButton.setDisable(true);
        cancelRuntimeButton.setDisable(false);
        runtimeProgress.progressProperty().bind(task.progressProperty());
        runtimeProgress.setVisible(true);
        runtimeStatus.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(event -> finishRuntimeTask(
                "Java " + task.getValue().version() + " (Temurin, " + task.getValue().architecture()
                        + ") проверена: " + task.getValue().javaHome().resolve("bin").resolve("java.exe")));
        task.setOnFailed(event -> finishRuntimeTask("Ошибка работы с Java: "
                + (task.getException() == null ? "Неизвестная ошибка" : task.getException().getMessage())));
        task.setOnCancelled(event -> finishRuntimeTask("Операция с Java отменена."));
        Thread thread = new Thread(task, "temurin-java-runtime");
        thread.setDaemon(true);
        thread.start();
    }

    private void finishRuntimeTask(String message) {
        runtimeStatus.textProperty().unbind();
        runtimeStatus.setText(message);
        runtimeProgress.progressProperty().unbind();
        runtimeProgress.setVisible(false);
        checkRuntimeButton.setDisable(false);
        installRuntimeButton.setDisable(false);
        cancelRuntimeButton.setDisable(true);
    }

    private VBox settingsCard(String title) {
        VBox card = new VBox(14);
        card.getStyleClass().add("settings-card");
        Label heading = new Label(title);
        heading.getStyleClass().add("card-heading");
        card.getChildren().add(heading);
        return card;
    }

    private Label settingDescription(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("setting-description");
        label.setWrapText(true);
        return label;
    }

    private VBox labeledControl(String labelText, javafx.scene.Node control) {
        VBox box = new VBox(7);
        Label label = new Label(labelText);
        label.getStyleClass().add("setting-label");
        box.getChildren().addAll(label, control);
        VBox.setVgrow(control, Priority.NEVER);
        return box;
    }

    private HBox directoryRow(String labelText, TextField field) {
        Button browse = new Button("Выбрать");
        browse.getStyleClass().add("secondary-button");
        browse.setOnAction(event -> chooseDirectory(field, getScene() == null ? null : getScene().getWindow()));
        VBox fieldContainer = labeledControl(labelText, field);
        HBox row = new HBox(10, fieldContainer, browse);
        row.setAlignment(Pos.BOTTOM_LEFT);
        HBox.setHgrow(fieldContainer, Priority.ALWAYS);
        return row;
    }

    private Button folderAction(String title, TextField rootField, String child) {
        Button button = new Button(title);
        button.getStyleClass().add("secondary-button");
        button.setOnAction(event -> openDirectory(rootField, child));
        return button;
    }

    private void chooseDirectory(TextField field, Window owner) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Выберите папку");
        Path current = parsePath(field.getText());
        if (Files.isDirectory(current)) {
            chooser.setInitialDirectory(current.toFile());
        }
        java.io.File selected = chooser.showDialog(owner);
        if (selected != null) {
            field.setText(selected.toPath().toAbsolutePath().normalize().toString());
        }
    }

    private void openDirectory(TextField rootField, String child) {
        try {
            Path directory = parsePath(rootField.getText());
            if (!child.isEmpty()) {
                directory = directory.resolve(child);
            }
            Files.createDirectories(directory);
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                throw new IOException("Открытие папок не поддерживается в этой среде.");
            }
            Desktop.getDesktop().open(directory.toFile());
        } catch (IOException | RuntimeException exception) {
            showError("Не удалось открыть папку", exception.getMessage());
        }
    }

    private void saveSettings() {
        try {
            int memoryGiB = (int) Math.round(memorySlider.getValue());
            int width = widthSpinner.getValue();
            int height = heightSpinner.getValue();
            String arguments = jvmArguments.getText().strip();
            if (arguments.contains("\n") || arguments.contains("\r")) {
                throw new IllegalArgumentException("Аргументы JVM должны быть указаны в одной строке.");
            }
            String launcherPath = parsePath(launcherDirectory.getText()).toString();
            String gamePath = parsePath(gameDirectory.getText()).toString();
            String backgroundPath = backgroundImagePath.getText().isBlank()
                    ? "" : validateBackgroundImage(backgroundImagePath.getText()).toString();
            settingsManager.save(new LauncherSettings(memoryGiB, width, height, fullscreen.isSelected(),
                    arguments, launcherPath, gamePath, backgroundPath));
            saveStatus.setText("Настройки сохранены");
        } catch (IOException | IllegalArgumentException exception) {
            saveStatus.setText("");
            showError("Не удалось сохранить настройки", exception.getMessage());
        }
    }

    private Path parsePath(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Укажите папку.");
        }
        return Path.of(text.strip()).toAbsolutePath().normalize();
    }

    private Path validateBackgroundImage(String text) throws IOException {
        Path path = parsePath(text);
        String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!Files.isRegularFile(path)
                || !(fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")
                || fileName.endsWith(".gif") || fileName.endsWith(".bmp"))) {
            throw new IOException("Выберите существующий файл изображения PNG, JPEG, GIF или BMP.");
        }
        return path;
    }

    private void chooseBackgroundImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выберите фон главной страницы");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Изображения", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
        Path current = parseOptionalPath(backgroundImagePath.getText());
        if (current != null && Files.isRegularFile(current)) {
            chooser.setInitialDirectory(current.getParent().toFile());
            chooser.setInitialFileName(current.getFileName().toString());
        }
        java.io.File selected = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (selected != null) {
            backgroundImagePath.setText(selected.toPath().toAbsolutePath().normalize().toString());
            saveSettings();
        }
    }

    private Path parseOptionalPath(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Path.of(text.strip()).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message == null || message.isBlank() ? "Причина не указана." : message);
        alert.showAndWait();
    }

    private void openElyByAccountPage() {
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("Открытие браузера не поддерживается в этой среде.");
            }
            Desktop.getDesktop().browse(URI.create("https://ely.by/"));
        } catch (IOException | RuntimeException exception) {
            showError("Не удалось открыть Ely.by", exception.getMessage());
        }
    }

    private static Spinner<Integer> integerSpinner(int min, int max, int value) {
        Spinner<Integer> spinner = new Spinner<>();
        spinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, clamp(value, min, max), 10));
        spinner.setEditable(false);
        spinner.setPrefWidth(140);
        return spinner;
    }

    private static int availableMemoryGiB() {
        java.lang.management.OperatingSystemMXBean bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        long totalBytes = bean instanceof com.sun.management.OperatingSystemMXBean extended
                ? extended.getTotalMemorySize()
                : Runtime.getRuntime().maxMemory();
        long totalGiB = totalBytes / (1024L * 1024 * 1024);
        return Math.clamp((int) (totalGiB - 1), 1, 64);
    }

    private static int clamp(int value, int min, int max) {
        return Math.clamp(value, min, max);
    }
}

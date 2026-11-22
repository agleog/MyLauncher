package com.mylauncher;

import com.mylauncher.auth.AuthProvider;
import com.mylauncher.auth.ElyByAuthService;
import com.mylauncher.auth.MinecraftAccount;
import com.mylauncher.auth.SecureAccountStore;
import com.mylauncher.minecraft.MinecraftInstaller;
import com.mylauncher.minecraft.MinecraftGameLauncher;
import com.mylauncher.minecraft.NeoForgeInstaller;
import com.mylauncher.javaruntime.TemurinRuntimeManager;
import com.mylauncher.settings.LauncherSettings;
import com.mylauncher.settings.SettingsManager;
import com.mylauncher.updater.LauncherUpdateService;
import com.mylauncher.updater.ReleaseInfo;
import com.mylauncher.ui.SettingsPage;
import com.mylauncher.ui.ConsolePage;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Hyperlink;
import javafx.concurrent.Task;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class Main extends Application {
    private BorderPane root;
    private Button homeButton;
    private Button settingsButton;
    private Button consoleButton;
    private ScrollPane settingsScroll;
    private SettingsManager settingsManager;
    private final LauncherUpdateService updateService = new LauncherUpdateService();
    private final ElyByAuthService authService = new ElyByAuthService();
    private final SecureAccountStore accountStore = new SecureAccountStore();
    private final MinecraftInstaller minecraftInstaller = new MinecraftInstaller();
    private final NeoForgeInstaller neoForgeInstaller = new NeoForgeInstaller();
    private final TemurinRuntimeManager runtimeManager = new TemurinRuntimeManager();
    private final MinecraftGameLauncher gameLauncher = new MinecraftGameLauncher();
    private final List<MinecraftAccount> accounts = new ArrayList<>();
    private ReleaseInfo availableUpdate;
    private String updateStatus = "Проверяем обновления…";
    private String authStatus = "";
    private MinecraftAccount activeAccount;
    private Task<MinecraftAccount> authTask;
    private Task<InstalledGameState> minecraftCheckTask;
    private Task<MinecraftInstaller.InstallResult> minecraftTask;
    private Task<NeoForgeInstaller.InstallResult> neoForgeTask;
    private Task<LaunchResult> gameLaunchTask;
    private Task<MinecraftAccount> accountRefreshTask;
    private Process gameProcess;
    private String minecraftStatus = "Нажмите кнопку, чтобы проверить или загрузить Minecraft 1.21.1";
    private boolean minecraftInstalled;
    private boolean neoForgeInstalled;
    private boolean javaInstalled;

    @Override
    public void start(Stage stage) throws IOException {
        settingsManager = new SettingsManager();
        LauncherSettings settings = settingsManager.load();
        SecureAccountStore.StoredAccounts storedAccounts = accountStore.load();
        accounts.addAll(storedAccounts.accounts());
        activeAccount = accounts.stream()
                .filter(account -> account.uuid().equals(storedAccounts.activeUuid()))
                .findFirst().orElse(accounts.isEmpty() ? null : accounts.getFirst());
        SettingsPage settingsPage = new SettingsPage(settingsManager, settings);
        settingsScroll = new ScrollPane(settingsPage);
        settingsScroll.setFitToWidth(true);
        settingsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        settingsScroll.getStyleClass().add("settings-scroll");

        root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setLeft(createSidebar());
        root.setCenter(createHomePage());

        Scene scene = new Scene(root, 1120, 720);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("launcher.css"), "Missing launcher stylesheet").toExternalForm());

        stage.setTitle("MyLauncher");
        stage.setMinWidth(820);
        stage.setMinHeight(560);
        stage.setScene(scene);
        stage.show();
        checkInstalledGameFiles(settings.gameDirectory());
        checkForUpdates();
        refreshActiveAccount();
    }

    private VBox createSidebar() {
        VBox sidebar = new VBox(28);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(28, 18, 24, 18));

        HBox brand = new HBox(12);
        brand.setAlignment(Pos.CENTER_LEFT);
        Label mark = new Label("M");
        mark.getStyleClass().add("brand-mark");
        VBox brandText = new VBox(2, new Label("MyLauncher"), new Label("NEOFORGE EDITION"));
        brandText.getStyleClass().add("brand-copy");
        brand.getChildren().addAll(mark, brandText);

        VBox navigation = new VBox(8);
        homeButton = navItem("⌂", "Главная", true);
        homeButton.setOnAction(event -> showHome());
        settingsButton = navItem("⚙", "Настройки", true);
        settingsButton.setOnAction(event -> showSettings());
        navigation.getChildren().addAll(homeButton, settingsButton);
        navigation.getChildren().add(navItem("▦", "Моды", false));
        consoleButton = navItem("≡", "Консоль", true);
        consoleButton.setOnAction(event -> showConsole());
        navigation.getChildren().add(consoleButton);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        Label edition = new Label("Minecraft 1.21.1\nNeoForge 21.1.251");
        edition.getStyleClass().add("sidebar-edition");
        sidebar.getChildren().addAll(brand, navigation, spacer, edition);
        return sidebar;
    }

    private Button navItem(String icon, String title, boolean selected) {
        Button button = new Button(icon + "    " + title);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.getStyleClass().add("nav-item");
        if (selected) {
            button.getStyleClass().add("nav-item-selected");
        } else {
            button.setDisable(true);
            button.setAccessibleText(title + ", будет доступно на следующем этапе разработки");
        }
        return button;
    }

    private void showHome() {
        root.setCenter(createHomePage());
        setSelectedNavigation(homeButton);
    }

    private void showSettings() {
        root.setCenter(settingsScroll);
        setSelectedNavigation(settingsButton);
    }

    private void showConsole() {
        try {
            java.nio.file.Path gameDirectory = java.nio.file.Path.of(settingsManager.load().gameDirectory());
            root.setCenter(new ConsolePage(gameDirectory));
            setSelectedNavigation(consoleButton);
        } catch (IOException | RuntimeException exception) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Игровая консоль");
            alert.setHeaderText("Не удалось открыть консоль");
            alert.setContentText(safeMessage(exception));
            alert.show();
        }
    }

    private void setSelectedNavigation(Button selected) {
        for (Button navigationButton : List.of(homeButton, settingsButton, consoleButton)) {
            navigationButton.getStyleClass().remove("nav-item-selected");
        }
        if (!selected.getStyleClass().contains("nav-item-selected")) {
            selected.getStyleClass().add("nav-item-selected");
        }
    }

    private VBox createHomePage() {
        VBox page = new VBox(24);
        page.getStyleClass().add("page");
        page.setPadding(new Insets(32, 38, 32, 38));

        HBox topBar = new HBox(12);
        topBar.setAlignment(Pos.CENTER_RIGHT);
        ImageView avatar = createAccountAvatar(activeAccount);
        Label account = new Label(activeAccount == null ? "АККАУНТ НЕ ПОДКЛЮЧЁН" : activeAccount.username());
        account.getStyleClass().add("account-badge");
        if (activeAccount != null) {
            account.setAccessibleText("Никнейм Minecraft: " + activeAccount.username()
                    + ", UUID: " + activeAccount.uuid());
            account.setTooltip(new javafx.scene.control.Tooltip("Minecraft UUID: " + activeAccount.uuid()));
        }

        ComboBox<MinecraftAccount> accountPicker = new ComboBox<>();
        accountPicker.getItems().setAll(accounts);
        accountPicker.setValue(activeAccount);
        accountPicker.setPromptText("Аккаунт Minecraft");
        accountPicker.setVisible(accounts.size() > 1);
        accountPicker.setManaged(accounts.size() > 1);
        accountPicker.setOnAction(event -> selectAccount(accountPicker.getValue()));

        Button accountButton = new Button(authTask != null
                ? "ОТМЕНИТЬ ВХОД" : "ДОБАВИТЬ ELY.BY");
        accountButton.getStyleClass().add("secondary-button");
        Label accountState = new Label(authStatus);
        accountState.getStyleClass().add("update-status");
        accountState.setMaxWidth(Double.MAX_VALUE);
        accountState.setWrapText(true);
        accountButton.setOnAction(event -> startElyBySignIn(accountButton, accountState));

        Button logoutButton = new Button("ВЫЙТИ");
        logoutButton.getStyleClass().add("secondary-button");
        logoutButton.setVisible(activeAccount != null);
        logoutButton.setManaged(activeAccount != null);
        logoutButton.setOnAction(event -> removeActiveAccount());

        Label updateStatusLabel = new Label(updateStatus);
        updateStatusLabel.getStyleClass().add("update-status");
        Button updateButton = new Button("ОБНОВИТЬ");
        updateButton.getStyleClass().add("primary-button");
        updateButton.setVisible(availableUpdate != null);
        updateButton.setManaged(availableUpdate != null);
        if (availableUpdate != null) {
            updateButton.setOnAction(event -> installUpdate(updateButton, updateStatusLabel));
        }
        topBar.getChildren().addAll(updateStatusLabel, updateButton,
                avatar, account, accountPicker, accountButton, logoutButton);

        VBox hero = new VBox(18);
        hero.getStyleClass().add("hero-card");
        hero.setAlignment(Pos.BOTTOM_LEFT);
        hero.setPadding(new Insets(42));
        VBox.setVgrow(hero, Priority.ALWAYS);

        Label eyebrow = new Label("ВАШЕ ПРИКЛЮЧЕНИЕ НАЧИНАЕТСЯ ЗДЕСЬ");
        eyebrow.getStyleClass().add("eyebrow");
        Label heading = new Label("Добро пожаловать\nв MyLauncher");
        heading.getStyleClass().add("hero-heading");
        heading.setWrapText(true);
        Label description = new Label("Лаунчер для Minecraft 1.21.1 с NeoForge 21.1.251");
        description.getStyleClass().add("hero-description");
        hero.getChildren().addAll(eyebrow, heading, description);

        HBox statusRow = new HBox(18);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        Label gameStatus = new Label(minecraftStatus);
        gameStatus.setWrapText(true);
        Task<?> activeInstallTask = minecraftCheckTask != null ? minecraftCheckTask
                : minecraftTask != null ? minecraftTask
                : neoForgeTask != null ? neoForgeTask : gameLaunchTask;
        ProgressBar gameProgress = new ProgressBar();
        gameProgress.setMaxWidth(Double.MAX_VALUE);
        gameProgress.setVisible(activeInstallTask != null || minecraftInstalled || neoForgeInstalled);
        gameProgress.setManaged(activeInstallTask != null || minecraftInstalled || neoForgeInstalled);
        if (activeInstallTask != null) {
            gameStatus.textProperty().bind(activeInstallTask.messageProperty());
            gameProgress.progressProperty().bind(activeInstallTask.progressProperty());
        } else {
            gameProgress.setProgress(minecraftInstalled || neoForgeInstalled ? 1 : 0);
        }
        VBox status = new VBox(7, new Label("СОСТОЯНИЕ ИГРЫ"), gameStatus, gameProgress);
        status.getStyleClass().add("status-copy");
        HBox.setHgrow(status, Priority.ALWAYS);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button playButton = new Button(activeInstallTask != null
                ? minecraftCheckTask != null ? "ПРОВЕРЯЕМ ФАЙЛЫ…"
                : gameLaunchTask != null ? "ОТМЕНИТЬ ЗАПУСК" : "ОТМЕНИТЬ УСТАНОВКУ"
                : gameProcess != null && gameProcess.isAlive() ? "ИГРА ЗАПУЩЕНА"
                : !minecraftInstalled || !neoForgeInstalled || !javaInstalled
                ? "УСТАНОВИТЬ ИГРУ" : "ИГРАТЬ");
        playButton.getStyleClass().add("play-button");
        playButton.setDisable(minecraftCheckTask != null || gameProcess != null && gameProcess.isAlive());
        playButton.setAccessibleText(!minecraftInstalled || !neoForgeInstalled || !javaInstalled
                ? "Установить Minecraft 1.21.1, NeoForge " + NeoForgeInstaller.VERSION
                + " и Eclipse Temurin Java 21"
                : "Запустить Minecraft 1.21.1");
        playButton.setOnAction(event -> {
            if (minecraftInstalled && neoForgeInstalled && javaInstalled
                    && (activeAccount == null || activeAccount.authProvider() != AuthProvider.ELY_BY)) {
                startElyBySignIn(playButton, accountState);
            } else {
                handleGameAction(playButton, gameStatus);
            }
        });
        Button checkFilesButton = new Button("ПРОВЕРИТЬ ФАЙЛЫ");
        checkFilesButton.getStyleClass().add("secondary-button");
        checkFilesButton.setVisible(minecraftInstalled && activeInstallTask == null
                && (gameProcess == null || !gameProcess.isAlive()));
        checkFilesButton.setManaged(checkFilesButton.isVisible());
        checkFilesButton.setOnAction(event -> installMinecraft(checkFilesButton, gameStatus));
        statusRow.getChildren().addAll(status, spacer, checkFilesButton, playButton);

        Label note = new Label("Проверка скачивает только недостающие файлы. Для запуска нужны Minecraft 1.21.1, "
                + "NeoForge " + NeoForgeInstaller.VERSION + " и Eclipse Temurin Java 21.");
        note.getStyleClass().add("stage-note");

        page.getChildren().addAll(topBar, accountState, hero, statusRow, note);
        return page;
    }

    private void checkForUpdates() {
        Package launcherPackage = Main.class.getPackage();
        String currentVersion = launcherPackage.getImplementationVersion();
        String releaseRepository = launcherPackage.getSpecificationTitle();
        if (releaseRepository == null || releaseRepository.isBlank()
                || currentVersion == null || currentVersion.isBlank()) {
            updateStatus = "Проверка обновлений недоступна в этой сборке";
            refreshHomePage();
            return;
        }

        Task<Optional<ReleaseInfo>> checkTask = new Task<>() {
            @Override
            protected Optional<ReleaseInfo> call() throws Exception {
                return updateService.checkForUpdate(releaseRepository, currentVersion);
            }
        };
        checkTask.setOnSucceeded(event -> {
            availableUpdate = checkTask.getValue().orElse(null);
            updateStatus = availableUpdate == null
                    ? "Установлена последняя версия"
                    : "Доступна версия " + availableUpdate.version();
            refreshHomePage();
        });
        checkTask.setOnFailed(event -> {
            updateStatus = "Не удалось проверить обновления: " + safeMessage(checkTask.getException());
            refreshHomePage();
        });
        Thread checkThread = new Thread(checkTask, "launcher-update-check");
        checkThread.setDaemon(true);
        checkThread.start();
    }

    private void installUpdate(Button updateButton, Label statusLabel) {
        ReleaseInfo release = availableUpdate;
        if (release == null) {
            return;
        }
        updateButton.setDisable(true);
        statusLabel.setText("Загружаем обновление " + release.version() + "…");
        Task<Void> updateTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                updateService.downloadAndApply(release);
                return null;
            }
        };
        updateTask.setOnSucceeded(event -> Platform.exit());
        updateTask.setOnFailed(event -> {
            statusLabel.setText("Ошибка обновления: " + safeMessage(updateTask.getException()));
            updateButton.setDisable(false);
        });
        Thread updateThread = new Thread(updateTask, "launcher-update-install");
        updateThread.setDaemon(true);
        updateThread.start();
    }

    private void installMinecraft(Button actionButton, Label statusLabel) {
        if (minecraftTask != null) {
            minecraftTask.cancel(true);
            actionButton.setText("ОТМЕНА…");
            actionButton.setDisable(true);
            statusLabel.setText("Отменяем загрузку Minecraft…");
            return;
        }
        final java.nio.file.Path gameDirectory;
        try {
            gameDirectory = java.nio.file.Path.of(settingsManager.load().gameDirectory())
                    .toAbsolutePath().normalize();
        } catch (IOException | RuntimeException exception) {
            minecraftStatus = "Не удалось прочитать папку игры: " + safeMessage(exception);
            statusLabel.setText(minecraftStatus);
            return;
        }

        Task<MinecraftInstaller.InstallResult> task = new Task<>() {
            @Override
            protected MinecraftInstaller.InstallResult call() throws Exception {
                long startedAt = System.nanoTime();
                return minecraftInstaller.install(gameDirectory, progress -> {
                    String message = progress.message();
                    if (progress.totalFiles() > 0) {
                        int percentage = (int) Math.min(100,
                                Math.round(progress.completedBytes() * 100.0 / progress.totalBytes()));
                        double elapsedSeconds = Math.max(0.001,
                                (System.nanoTime() - startedAt) / 1_000_000_000.0);
                        double megabytesPerSecond = progress.downloadedBytes()
                                / elapsedSeconds / (1024.0 * 1024.0);
                        int remainingFiles = Math.max(0, progress.totalFiles() - progress.completedFiles());
                        message += String.format(Locale.ROOT, " — %d%%, %.1f МБ/с, файлов осталось: %d",
                                percentage, megabytesPerSecond, remainingFiles);
                        message += String.format(Locale.ROOT, ", скачано повторно: %.1f МБ",
                                progress.downloadedBytes() / (1024.0 * 1024.0));
                        message += " (" + progress.completedFiles() + " из "
                                + progress.totalFiles() + " файлов)";
                    }
                    updateMessage(message);
                    if (progress.totalBytes() > 0) {
                        updateProgress(progress.completedBytes(), progress.totalBytes());
                    } else {
                        updateProgress(-1, 1);
                    }
                });
            }
        };
        minecraftTask = task;
        minecraftStatus = "Подготавливаем установку Minecraft " + MinecraftInstaller.VERSION + "…";
        task.setOnSucceeded(event -> {
            MinecraftInstaller.InstallResult result = task.getValue();
            minecraftInstalled = true;
            minecraftStatus = result.downloadedBytes() == 0
                    ? "Файлы Minecraft " + result.version() + " проверены. Повторно скачивать ничего не пришлось."
                    : "Файлы Minecraft " + result.version() + " проверены; заново скачано "
                    + result.downloadedFiles() + " файлов ("
                    + String.format(Locale.ROOT, "%.1f МБ", result.downloadedBytes() / (1024.0 * 1024.0))
                    + ").";
            minecraftTask = null;
            refreshHomePage();
        });
        task.setOnFailed(event -> {
            minecraftStatus = "Ошибка установки Minecraft: " + safeMessage(task.getException());
            minecraftTask = null;
            refreshHomePage();
        });
        task.setOnCancelled(event -> {
            minecraftStatus = "Загрузка Minecraft отменена. Уже проверенные файлы сохранены.";
            minecraftTask = null;
            refreshHomePage();
        });
        Thread installThread = new Thread(task, "minecraft-install");
        installThread.setDaemon(true);
        installThread.start();
        refreshHomePage();
    }

    private void checkInstalledGameFiles(String configuredGameDirectory) {
        Task<InstalledGameState> task = new Task<>() {
            @Override
            protected InstalledGameState call() throws Exception {
                java.nio.file.Path gameDirectory = java.nio.file.Path.of(configuredGameDirectory)
                        .toAbsolutePath().normalize();
                updateMessage("Ищем уже установленные файлы Minecraft…");
                boolean minecraftReady = minecraftInstaller.isInstalled(gameDirectory);
                boolean neoForgeReady = minecraftReady && neoForgeInstaller.isInstalled(gameDirectory);
                boolean javaReady = false;
                if (neoForgeReady) {
                    try {
                        runtimeManager.verify(gameDirectory);
                        javaReady = true;
                    } catch (IOException ignored) {
                        updateMessage("Для NeoForge потребуется установить Temurin Java 21");
                    }
                }
                return new InstalledGameState(minecraftReady, neoForgeReady, javaReady);
            }
        };
        task.setOnSucceeded(event -> {
            InstalledGameState state = task.getValue();
            minecraftInstalled = state.minecraftInstalled();
            neoForgeInstalled = state.neoForgeInstalled();
            javaInstalled = state.javaInstalled();
            if (minecraftInstalled && neoForgeInstalled && javaInstalled) {
                minecraftStatus = "Minecraft 1.21.1 и NeoForge " + NeoForgeInstaller.VERSION
                        + " найдены, Temurin Java 21 проверена. Игра готова к запуску.";
            } else if (minecraftInstalled && neoForgeInstalled) {
                minecraftStatus = "NeoForge установлен. Для запуска нужно установить Temurin Java 21.";
            } else if (minecraftInstalled) {
                minecraftStatus = "Файлы Minecraft 1.21.1 найдены. Установите NeoForge.";
            } else if (minecraftStatus.isBlank() || minecraftStatus.startsWith("Нажмите")) {
                minecraftStatus = "Minecraft 1.21.1 ещё не установлен.";
            }
            minecraftCheckTask = null;
            refreshHomePage();
        });
        task.setOnFailed(event -> {
            minecraftStatus = "Не удалось проверить установленные файлы: " + safeMessage(task.getException());
            minecraftCheckTask = null;
            refreshHomePage();
        });
        minecraftCheckTask = task;
        Thread checkThread = new Thread(task, "minecraft-installed-check");
        checkThread.setDaemon(true);
        checkThread.start();
    }

    private void handleGameAction(Button actionButton, Label statusLabel) {
        if (minecraftTask != null) {
            installMinecraft(actionButton, statusLabel);
        } else if (neoForgeTask != null) {
            neoForgeTask.cancel(true);
            actionButton.setText("ОТМЕНА…");
            actionButton.setDisable(true);
            statusLabel.setText("Отменяем установку NeoForge…");
        } else if (gameLaunchTask != null) {
            gameLaunchTask.cancel(true);
            actionButton.setText("ОТМЕНА…");
            actionButton.setDisable(true);
            statusLabel.setText("Отменяем подготовку запуска…");
        } else if (!minecraftInstalled || !neoForgeInstalled || !javaInstalled) {
            installNeoForge();
        } else {
            launchMinecraft();
        }
    }

    private void installNeoForge() {
        final java.nio.file.Path gameDirectory;
        try {
            gameDirectory = java.nio.file.Path.of(settingsManager.load().gameDirectory())
                    .toAbsolutePath().normalize();
        } catch (IOException | RuntimeException exception) {
            minecraftStatus = "Не удалось прочитать папку игры: " + safeMessage(exception);
            refreshHomePage();
            return;
        }

        Task<NeoForgeInstaller.InstallResult> task = new Task<>() {
            @Override
            protected NeoForgeInstaller.InstallResult call() throws Exception {
                updateMessage("Проверяем и загружаем Minecraft " + MinecraftInstaller.VERSION + "…");
                MinecraftInstaller.InstallResult minecraftResult = minecraftInstaller.install(gameDirectory,
                        progress -> {
                            updateMessage(progress.message());
                            if (progress.totalBytes() > 0) {
                                updateProgress(progress.completedBytes(), progress.totalBytes());
                            }
                        });
                updateMessage(minecraftResult.downloadedBytes() == 0
                        ? "Minecraft проверен. Подготавливаем Temurin Java 21…"
                        : "Minecraft загружен. Подготавливаем Temurin Java 21…");
                TemurinRuntimeManager.RuntimeInfo runtime;
                try {
                    runtime = runtimeManager.verify(gameDirectory);
                } catch (IOException exception) {
                    updateMessage("Устанавливаем Eclipse Temurin Java 21 для NeoForge…");
                    runtime = runtimeManager.install(gameDirectory,
                            (message, completedBytes, totalBytes) -> {
                                updateMessage(message);
                                if (totalBytes > 0) {
                                    updateProgress(completedBytes, totalBytes);
                                }
                            }, this::isCancelled);
                }
                java.nio.file.Path javaExecutable = runtime.javaHome().resolve("bin").resolve("java.exe");
                updateProgress(-1, 1);
                return neoForgeInstaller.install(gameDirectory, javaExecutable, this::updateMessage,
                        this::isCancelled);
            }
        };
        neoForgeTask = task;
        minecraftStatus = "Подготавливаем установку NeoForge " + NeoForgeInstaller.VERSION + "…";
        task.setOnSucceeded(event -> {
            NeoForgeInstaller.InstallResult result = task.getValue();
            minecraftInstalled = true;
            neoForgeInstalled = true;
            javaInstalled = true;
            minecraftStatus = result.installedNow()
                    ? "Minecraft 1.21.1 и NeoForge " + NeoForgeInstaller.VERSION
                    + " установлены. Игра готова к запуску."
                    : "Minecraft 1.21.1 и NeoForge " + NeoForgeInstaller.VERSION
                    + " проверены. Игра готова к запуску.";
            neoForgeTask = null;
            refreshHomePage();
        });
        task.setOnFailed(event -> {
            minecraftStatus = "Ошибка установки NeoForge: " + safeMessage(task.getException());
            neoForgeTask = null;
            refreshHomePage();
        });
        task.setOnCancelled(event -> {
            minecraftStatus = "Установка NeoForge отменена. Уже проверенные файлы сохранены.";
            neoForgeTask = null;
            refreshHomePage();
        });
        Thread installThread = new Thread(task, "neoforge-install");
        installThread.setDaemon(true);
        installThread.start();
        refreshHomePage();
    }

    private void launchMinecraft() {
        if (activeAccount == null || activeAccount.authProvider() != AuthProvider.ELY_BY) {
            authStatus = "Для запуска войдите в аккаунт Ely.by.";
            minecraftStatus = authStatus;
            refreshHomePage();
            return;
        }
        if (accountRefreshTask != null && !accountRefreshTask.isDone()) {
            minecraftStatus = "Подождите, пока завершится проверка аккаунта Ely.by.";
            refreshHomePage();
            return;
        }

        final LauncherSettings settings;
        final java.nio.file.Path gameDirectory;
        try {
            settings = settingsManager.load();
            gameDirectory = java.nio.file.Path.of(settings.gameDirectory()).toAbsolutePath().normalize();
        } catch (IOException | RuntimeException exception) {
            minecraftStatus = "Не удалось прочитать настройки запуска: " + safeMessage(exception);
            refreshHomePage();
            return;
        }

        MinecraftAccount accountToLaunch = activeAccount;
        java.util.concurrent.atomic.AtomicReference<Process> startedProcess =
                new java.util.concurrent.atomic.AtomicReference<>();
        Task<LaunchResult> task = new Task<>() {
            @Override
            protected LaunchResult call() throws Exception {
                updateMessage("Проверяем файлы Minecraft перед запуском…");
                MinecraftInstaller.InstallResult verified = minecraftInstaller.install(gameDirectory, progress -> {
                    updateMessage(progress.message());
                    if (progress.totalBytes() > 0) {
                        updateProgress(progress.completedBytes(), progress.totalBytes());
                    }
                });
                if (verified.downloadedBytes() > 0) {
                    updateMessage("Восстановлено файлов Minecraft: " + verified.downloadedFiles());
                }
                if (!neoForgeInstaller.isInstalled(gameDirectory)) {
                    throw new IOException("NeoForge отсутствует. Нажмите «УСТАНОВИТЬ NEOFORGE».");
                }

                TemurinRuntimeManager.RuntimeInfo runtime;
                try {
                    runtime = runtimeManager.verify(gameDirectory);
                } catch (IOException exception) {
                    throw new IOException("Temurin Java 21 не прошла проверку. Нажмите «УСТАНОВИТЬ JAVA».",
                            exception);
                }
                updateProgress(-1, 1);
                updateMessage("Проверяем аккаунт Ely.by…");
                MinecraftAccount refreshedAccount = authService.refresh(accountToLaunch);
                if (isCancelled()) {
                    throw new InterruptedException("Запуск игры отменён.");
                }
                java.nio.file.Path javaExecutable = runtime.javaHome().resolve("bin").resolve("java.exe");
                Process process = gameLauncher.launch(gameDirectory, javaExecutable, refreshedAccount, settings,
                        this::updateMessage, this::isCancelled);
                startedProcess.set(process);
                if (isCancelled()) {
                    process.destroyForcibly();
                    throw new InterruptedException("Запуск игры отменён.");
                }
                return new LaunchResult(process, refreshedAccount);
            }
        };
        gameLaunchTask = task;
        minecraftStatus = "Подготавливаем запуск Minecraft…";
        task.setOnSucceeded(event -> {
            LaunchResult result = task.getValue();
            gameLaunchTask = null;
            gameProcess = result.process();
            try {
                saveAccount(result.account());
                authStatus = "Аккаунт Ely.by проверен";
            } catch (IOException exception) {
                authStatus = "Игра запущена, но обновлённый вход не удалось сохранить: "
                        + safeMessage(exception);
            }
            minecraftInstalled = true;
            neoForgeInstalled = true;
            javaInstalled = true;
            minecraftStatus = "Minecraft запущен. Игровые сообщения: logs/mylauncher-game.log";
            result.process().onExit().thenAccept(exited -> Platform.runLater(() -> {
                if (gameProcess == exited) {
                    gameProcess = null;
                    minecraftStatus = exited.exitValue() == 0
                            ? "Minecraft закрыт."
                            : "Minecraft завершился с ошибкой (код " + exited.exitValue()
                            + "). Подробности: logs/mylauncher-game.log";
                    refreshHomePage();
                }
            }));
            refreshHomePage();
        });
        task.setOnFailed(event -> {
            gameLaunchTask = null;
            minecraftStatus = "Не удалось запустить Minecraft: " + safeMessage(task.getException());
            refreshHomePage();
        });
        task.setOnCancelled(event -> {
            Process process = startedProcess.get();
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            gameLaunchTask = null;
            minecraftStatus = "Подготовка запуска отменена.";
            refreshHomePage();
        });
        Thread launchThread = new Thread(task, "minecraft-game-launch");
        launchThread.setDaemon(true);
        launchThread.start();
        refreshHomePage();
    }

    private void refreshHomePage() {
        if (root.getCenter() != null && root.getCenter().getStyleClass().contains("page")) {
            root.setCenter(createHomePage());
        }
    }

    private void startElyBySignIn(Button actionButton, Label statusLabel) {
        if (authTask != null) {
            if (!authTask.isDone()) {
                boolean cancellationRequested = authTask.cancel(true);
                authStatus = cancellationRequested ? "Отменяем вход…" : "Завершаем проверку входа…";
            } else {
                authStatus = "Завершаем проверку входа…";
            }
            actionButton.setText("ОТМЕНА…");
            actionButton.setDisable(true);
            statusLabel.setText(authStatus);
            return;
        }

        Optional<ElyByCredentials> enteredCredentials = showElyByLoginDialog();
        if (enteredCredentials.isEmpty()) {
            return;
        }
        ElyByCredentials credentials = enteredCredentials.get();
        String clientToken = UUID.randomUUID().toString();
        try {
            ElyByAuthService.validateCredentials(
                    credentials.username(), credentials.password(), clientToken);
            if (!credentials.twoFactorCode().isBlank()
                    && !credentials.twoFactorCode().matches("[0-9]{6,8}")) {
                throw new IllegalArgumentException("Код 2FA должен содержать 6–8 цифр.");
            }
        } catch (IllegalArgumentException exception) {
            showElyByAuthError(safeMessage(exception));
            return;
        }

        actionButton.setText("ОТМЕНИТЬ ВХОД");
        statusLabel.setText("Проверяем аккаунт Ely.by…");
        Task<MinecraftAccount> task = new Task<>() {
            @Override
            protected MinecraftAccount call() throws Exception {
                return authService.signIn(credentials.username(), credentials.password(),
                        credentials.twoFactorCode(), clientToken);
            }
        };
        authTask = task;
        task.setOnSucceeded(event -> {
            try {
                saveAccount(task.getValue());
                authStatus = "Аккаунт Ely.by подключён";
            } catch (IOException exception) {
                authStatus = "Вход выполнен, но аккаунт не сохранён: " + safeMessage(exception);
            }
            authTask = null;
            refreshHomePage();
        });
        task.setOnFailed(event -> {
            authStatus = safeMessage(task.getException());
            authTask = null;
            refreshHomePage();
            showElyByAuthError(authStatus);
        });
        task.setOnCancelled(event -> {
            authStatus = "Вход отменён";
            authTask = null;
            refreshHomePage();
        });
        Thread authThread = new Thread(task, "ely-by-login");
        authThread.setDaemon(true);
        authThread.start();
    }

    private Optional<ElyByCredentials> showElyByLoginDialog() {
        Dialog<ElyByCredentials> dialog = new Dialog<>();
        dialog.setTitle("Вход через Ely.by");
        dialog.setHeaderText("Введите данные аккаунта Ely.by");
        ButtonType signInButton = new ButtonType("ВОЙТИ", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(signInButton, ButtonType.CANCEL);

        TextField username = new TextField();
        username.setPromptText("Логин или электронная почта");
        PasswordField password = new PasswordField();
        password.setPromptText("Пароль");
        PasswordField twoFactorCode = new PasswordField();
        twoFactorCode.setPromptText("Код 2FA (если включён)");
        Hyperlink registrationLink = new Hyperlink("Создать аккаунт на Ely.by");
        registrationLink.setOnAction(event -> openElyByRegistrationPage());

        GridPane fields = new GridPane();
        fields.setHgap(12);
        fields.setVgap(12);
        fields.addRow(0, new Label("Логин"), username);
        fields.addRow(1, new Label("Пароль"), password);
        fields.addRow(2, new Label("Код 2FA"), twoFactorCode);
        fields.add(registrationLink, 1, 3);
        GridPane.setColumnSpan(registrationLink, 2);
        dialog.getDialogPane().setContent(fields);
        if (root.getScene() != null) {
            dialog.initOwner(root.getScene().getWindow());
        }
        dialog.setResultConverter(button -> button == signInButton
                ? new ElyByCredentials(username.getText().strip(), password.getText(), twoFactorCode.getText().strip())
                : null);
        Optional<ElyByCredentials> result = dialog.showAndWait();
        password.clear();
        twoFactorCode.clear();
        if (result.isPresent()
                && (result.get().username().isBlank() || result.get().password().isBlank())) {
            showElyByAuthError("Введите логин и пароль Ely.by.");
            return Optional.empty();
        }
        return result;
    }

    private void openElyByRegistrationPage() {
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("Открытие браузера не поддерживается в этой среде.");
            }
            Desktop.getDesktop().browse(URI.create("https://ely.by/"));
        } catch (IOException | RuntimeException exception) {
            showElyByAuthError("Не удалось открыть сайт Ely.by: " + safeMessage(exception));
        }
    }

    private void showElyByAuthError(String message) {
        Alert dialog = new Alert(Alert.AlertType.ERROR);
        dialog.setTitle("Вход через Ely.by");
        dialog.setHeaderText("Не удалось добавить аккаунт Ely.by");
        dialog.setContentText(message);
        if (root.getScene() != null) {
            dialog.initOwner(root.getScene().getWindow());
        }
        dialog.show();
    }

    private void refreshActiveAccount() {
        if (activeAccount == null) {
            return;
        }
        if (activeAccount.authProvider() != AuthProvider.ELY_BY) {
            authStatus = "Сохранён аккаунт от неподдерживаемого способа входа. Удалите его и войдите через Ely.by.";
            refreshHomePage();
            return;
        }
        MinecraftAccount accountToRefresh = activeAccount;
        Task<MinecraftAccount> refreshTask = new Task<>() {
            @Override
            protected MinecraftAccount call() throws Exception {
                return authService.refresh(accountToRefresh);
            }
        };
        accountRefreshTask = refreshTask;
        refreshTask.setOnSucceeded(event -> {
            if (accountRefreshTask == refreshTask) {
                accountRefreshTask = null;
            }
            try {
                saveAccount(refreshTask.getValue());
                authStatus = "Аккаунт Ely.by проверен";
            } catch (IOException exception) {
                authStatus = "Не удалось сохранить обновлённый вход: " + safeMessage(exception);
            }
            refreshHomePage();
        });
        refreshTask.setOnFailed(event -> {
            if (accountRefreshTask == refreshTask) {
                accountRefreshTask = null;
            }
            authStatus = "Нужно повторно войти в Ely.by: " + safeMessage(refreshTask.getException());
            refreshHomePage();
        });
        refreshTask.setOnCancelled(event -> {
            if (accountRefreshTask == refreshTask) {
                accountRefreshTask = null;
            }
        });
        Thread refreshThread = new Thread(refreshTask, "ely-by-session-refresh");
        refreshThread.setDaemon(true);
        refreshThread.start();
    }

    private void saveAccount(MinecraftAccount account) throws IOException {
        List<MinecraftAccount> updated = new ArrayList<>(accounts);
        updated.removeIf(existing -> existing.uuid().equals(account.uuid()));
        updated.add(account);
        accountStore.save(new SecureAccountStore.StoredAccounts(updated, account.uuid()));
        accounts.clear();
        accounts.addAll(updated);
        activeAccount = account;
    }

    private void selectAccount(MinecraftAccount account) {
        if (account == null || account.equals(activeAccount)) {
            return;
        }
        try {
            accountStore.save(new SecureAccountStore.StoredAccounts(accounts, account.uuid()));
            activeAccount = account;
            authStatus = account.authProvider() == AuthProvider.ELY_BY
                    ? "Выбран аккаунт " + account.username()
                    : "Этот аккаунт использует неподдерживаемый способ входа. Удалите его и войдите через Ely.by.";
            refreshHomePage();
        } catch (IOException exception) {
            authStatus = "Не удалось выбрать аккаунт: " + safeMessage(exception);
            refreshHomePage();
        }
    }

    private void removeActiveAccount() {
        if (activeAccount == null) {
            return;
        }
        List<MinecraftAccount> updated = accounts.stream()
                .filter(account -> !account.uuid().equals(activeAccount.uuid())).toList();
        String selectedUuid = updated.isEmpty() ? "" : updated.getFirst().uuid();
        try {
            accountStore.save(new SecureAccountStore.StoredAccounts(updated, selectedUuid));
            accounts.clear();
            accounts.addAll(updated);
            activeAccount = updated.isEmpty() ? null : updated.getFirst();
            authStatus = "Аккаунт удалён с этого компьютера";
            refreshHomePage();
        } catch (IOException exception) {
            authStatus = "Не удалось удалить аккаунт: " + safeMessage(exception);
            refreshHomePage();
        }
    }

    private ImageView createAccountAvatar(MinecraftAccount account) {
        ImageView avatar = new ImageView();
        avatar.setFitWidth(36);
        avatar.setFitHeight(36);
        avatar.setPreserveRatio(false);
        avatar.setSmooth(false);
        avatar.getStyleClass().add("account-avatar");
        if (account != null && !account.skinUrl().isBlank()) {
            Image skin = new Image(account.skinUrl(), 64, 64, false, true, true);
            avatar.setImage(skin);
            avatar.setViewport(new javafx.geometry.Rectangle2D(8, 8, 8, 8));
            avatar.setAccessibleText("Аватар Minecraft: " + account.username());
        }
        return avatar;
    }

    private static String safeMessage(Throwable exception) {
        String message = exception == null ? null : exception.getMessage();
        return message == null || message.isBlank() ? "Неизвестная ошибка" : message;
    }

    public static void main(String[] args) {
        launch(args);
    }

    private record ElyByCredentials(String username, String password, String twoFactorCode) {
    }

    private record InstalledGameState(boolean minecraftInstalled, boolean neoForgeInstalled, boolean javaInstalled) {
    }

    private record LaunchResult(Process process, MinecraftAccount account) {
    }
}

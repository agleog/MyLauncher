package com.mylauncher;

import com.mylauncher.settings.LauncherSettings;
import com.mylauncher.settings.SettingsManager;
import com.mylauncher.ui.SettingsPage;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

public final class Main extends Application {
    private BorderPane root;
    private Button homeButton;
    private Button settingsButton;
    private ScrollPane settingsScroll;

    @Override
    public void start(Stage stage) throws IOException {
        SettingsManager settingsManager = new SettingsManager();
        LauncherSettings settings = settingsManager.load();
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
        navigation.getChildren().add(navItem("≡", "Консоль", false));

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

    private void setSelectedNavigation(Button selected) {
        homeButton.getStyleClass().remove("nav-item-selected");
        settingsButton.getStyleClass().remove("nav-item-selected");
        if (!selected.getStyleClass().contains("nav-item-selected")) {
            selected.getStyleClass().add("nav-item-selected");
        }
    }

    private VBox createHomePage() {
        VBox page = new VBox(24);
        page.getStyleClass().add("page");
        page.setPadding(new Insets(32, 38, 32, 38));

        HBox topBar = new HBox();
        topBar.setAlignment(Pos.CENTER_RIGHT);
        Label account = new Label("АККАУНТ НЕ ПОДКЛЮЧЁН");
        account.getStyleClass().add("account-badge");
        topBar.getChildren().add(account);

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
        VBox status = new VBox(5,
                new Label("СОСТОЯНИЕ ИГРЫ"),
                new Label("Игровые компоненты ещё не установлены"));
        status.getStyleClass().add("status-copy");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button playButton = new Button("ИГРАТЬ");
        playButton.getStyleClass().add("play-button");
        playButton.setDisable(true);
        playButton.setAccessibleText("Запуск игры станет доступен после реализации установки Minecraft");
        statusRow.getChildren().addAll(status, spacer, playButton);

        Label note = new Label("Базовый интерфейс. Установка, вход и запуск игры будут добавлены поэтапно.");
        note.getStyleClass().add("stage-note");

        page.getChildren().addAll(topBar, hero, statusRow, note);
        return page;
    }

    public static void main(String[] args) {
        launch(args);
    }
}

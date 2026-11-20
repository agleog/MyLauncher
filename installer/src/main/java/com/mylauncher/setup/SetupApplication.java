package com.mylauncher.setup;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;

public final class SetupApplication {
    private static final Color BACKGROUND = new Color(17, 20, 25);
    private static final Color CARD = new Color(25, 30, 36);
    private static final Color FOREGROUND = new Color(232, 236, 239);
    private static final Color MUTED = new Color(157, 167, 177);
    private static final Color ACCENT = new Color(166, 238, 85);
    private final Preferences preferences = Preferences.userNodeForPackage(SetupApplication.class);
    private final GithubReleaseClient releaseClient = new GithubReleaseClient();
    private final InstallationService installationService = new InstallationService(
            releaseClient, new ShortcutManager());

    private JFrame window;
    private JTextField repositoryField;
    private JTextField directoryField;
    private JButton installButton;
    private JButton cancelButton;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JCheckBox launchAfterInstall;
    private SwingWorker<Void, InstallProgress> worker;

    private SetupApplication() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new SetupApplication().show());
    }

    private void show() {
        window = new JFrame("Установка MyLauncher");
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        window.setMinimumSize(new Dimension(620, 480));
        window.setSize(680, 540);
        window.setLocationRelativeTo(null);
        window.setContentPane(createContent());
        window.setVisible(true);
    }

    private JPanel createContent() {
        JPanel page = new JPanel(new BorderLayout(0, 20));
        page.setBackground(BACKGROUND);
        page.setBorder(BorderFactory.createEmptyBorder(28, 32, 24, 32));

        JPanel heading = new JPanel(new BorderLayout(0, 7));
        heading.setOpaque(false);
        JLabel title = new JLabel("MyLauncher Setup");
        title.setForeground(FOREGROUND);
        title.setFont(new Font("Segoe UI", Font.BOLD, 26));
        JLabel subtitle = new JLabel("Установка Minecraft 1.21.1 · NeoForge 21.1.251");
        subtitle.setForeground(MUTED);
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        heading.add(title, BorderLayout.NORTH);
        heading.add(subtitle, BorderLayout.CENTER);
        page.add(heading, BorderLayout.NORTH);

        JPanel fields = new JPanel(new GridBagLayout());
        fields.setBackground(CARD);
        fields.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        constraints.insets = new Insets(0, 0, 8, 0);

        fields.add(label("GitHub-репозиторий (владелец/репозиторий)"), constraints);
        repositoryField = new JTextField(preferences.get("repository", configuredRepository()));
        styleTextField(repositoryField);
        constraints.gridy++;
        constraints.insets = new Insets(0, 0, 18, 0);
        fields.add(repositoryField, constraints);

        constraints.gridy++;
        constraints.insets = new Insets(0, 0, 8, 0);
        fields.add(label("Папка лаунчера"), constraints);
        directoryField = new JTextField(defaultInstallDirectory().toString());
        styleTextField(directoryField);
        constraints.gridy++;
        constraints.gridwidth = 1;
        constraints.insets = new Insets(0, 0, 0, 10);
        fields.add(directoryField, constraints);
        JButton browseButton = new JButton("Обзор...");
        styleSecondaryButton(browseButton);
        browseButton.addActionListener(event -> chooseDirectory());
        constraints.gridx = 1;
        constraints.weightx = 0;
        constraints.insets = new Insets(0, 0, 0, 0);
        fields.add(browseButton, constraints);

        JPanel center = new JPanel(new BorderLayout(0, 18));
        center.setOpaque(false);
        center.add(fields, BorderLayout.NORTH);

        JPanel progressPanel = new JPanel(new BorderLayout(0, 8));
        progressPanel.setOpaque(false);
        statusLabel = label("Укажите публичный GitHub-репозиторий с опубликованным релизом.");
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setString("");
        progressBar.setForeground(ACCENT);
        progressBar.setBackground(new Color(42, 49, 56));
        progressBar.setBorderPainted(false);
        progressPanel.add(statusLabel, BorderLayout.NORTH);
        progressPanel.add(progressBar, BorderLayout.CENTER);
        center.add(progressPanel, BorderLayout.CENTER);

        launchAfterInstall = new JCheckBox("Запустить MyLauncher после установки", true);
        launchAfterInstall.setOpaque(false);
        launchAfterInstall.setForeground(FOREGROUND);
        center.add(launchAfterInstall, BorderLayout.SOUTH);
        page.add(center, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        JLabel note = label("Установщик проверяет SHA-256 и сохраняет игровые данные отдельно.");
        footer.add(note, BorderLayout.WEST);
        JPanel actions = new JPanel();
        actions.setOpaque(false);
        cancelButton = new JButton("Отмена");
        styleSecondaryButton(cancelButton);
        cancelButton.setEnabled(false);
        cancelButton.addActionListener(event -> cancelInstallation());
        installButton = new JButton("УСТАНОВИТЬ");
        stylePrimaryButton(installButton);
        installButton.addActionListener(event -> beginInstallation());
        actions.add(cancelButton);
        actions.add(installButton);
        footer.add(actions, BorderLayout.EAST);
        page.add(footer, BorderLayout.SOUTH);
        return page;
    }

    private void beginInstallation() {
        final String repository;
        final Path installDirectory;
        try {
            repository = GithubReleaseClient.validateRepository(repositoryField.getText());
            installDirectory = Path.of(directoryField.getText().strip()).toAbsolutePath().normalize();
            if (installDirectory.toString().isBlank() || installDirectory.getParent() == null) {
                throw new IllegalArgumentException("Выберите корректную папку установки.");
            }
        } catch (IllegalArgumentException exception) {
            showError("Проверьте параметры установки", exception.getMessage());
            return;
        }

        preferences.put("repository", repository);
        setBusy(true);
        progressBar.setIndeterminate(true);
        progressBar.setString("");
        worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                publish(new InstallProgress("Подключение к GitHub Releases...", -1, -1));
                GithubRelease release = releaseClient.fetchLatest(repository);
                publish(new InstallProgress("Найден релиз " + release.version(), -1, -1));
                installationService.install(release, installDirectory,
                        message -> publish(new InstallProgress(message, -1, -1)),
                        bytes -> publish(new InstallProgress("Загрузка файлов...", bytes, release.archiveSize())));
                return null;
            }

            @Override
            protected void process(List<InstallProgress> updates) {
                InstallProgress update = updates.getLast();
                statusLabel.setText(update.message());
                if (update.totalBytes() > 0) {
                    progressBar.setIndeterminate(false);
                    int percentage = (int) Math.min(100, update.completedBytes() * 100 / update.totalBytes());
                    progressBar.setValue(percentage);
                    progressBar.setString(percentage + "%");
                } else if (update.totalBytes() < 0 && !update.message().startsWith("Загрузка")) {
                    progressBar.setIndeterminate(true);
                }
            }

            @Override
            protected void done() {
                setBusy(false);
                progressBar.setIndeterminate(false);
                try {
                    get();
                    progressBar.setValue(100);
                    progressBar.setString("100%");
                    statusLabel.setText("Лаунчер установлен в " + installDirectory);
                    if (launchAfterInstall.isSelected()) {
                        launchInstalled(installDirectory.resolve("MyLauncher.exe"));
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showError("Установка прервана", "Дождитесь завершения отмены и повторите установку.");
                } catch (java.util.concurrent.CancellationException exception) {
                    statusLabel.setText("Установка отменена.");
                    progressBar.setValue(0);
                    progressBar.setString("");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    String message = cause == null ? exception.getMessage() : cause.getMessage();
                    statusLabel.setText("Установка не завершена. Исправьте ошибку и повторите.");
                    showError("Не удалось установить MyLauncher", message);
                }
            }
        };
        worker.execute();
    }

    private void cancelInstallation() {
        if (worker != null) {
            worker.cancel(true);
            statusLabel.setText("Отмена установки...");
            cancelButton.setEnabled(false);
        }
    }

    private void setBusy(boolean busy) {
        installButton.setEnabled(!busy);
        cancelButton.setEnabled(busy);
        repositoryField.setEnabled(!busy);
        directoryField.setEnabled(!busy);
        launchAfterInstall.setEnabled(!busy);
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser(directoryField.getText());
        chooser.setDialogTitle("Выберите папку для установки");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) {
            directoryField.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
        }
    }

    private void launchInstalled(Path executable) {
        try {
            new ProcessBuilder(executable.toString()).directory(executable.getParent().toFile()).start();
        } catch (IOException exception) {
            showError("Лаунчер установлен, но не запустился", exception.getMessage());
        }
    }

    private void showError(String title, String message) {
        JOptionPane.showMessageDialog(window,
                message == null || message.isBlank() ? "Причина не указана." : message,
                title, JOptionPane.ERROR_MESSAGE);
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        label.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        return label;
    }

    private void styleTextField(JTextField field) {
        field.setBackground(new Color(16, 20, 24));
        field.setForeground(FOREGROUND);
        field.setCaretColor(FOREGROUND);
        field.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(48, 57, 67)),
                BorderFactory.createEmptyBorder(9, 10, 9, 10)));
    }

    private void stylePrimaryButton(JButton button) {
        button.setBackground(ACCENT);
        button.setForeground(new Color(23, 32, 15));
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(11, 18, 11, 18));
    }

    private void styleSecondaryButton(JButton button) {
        button.setBackground(new Color(42, 49, 56));
        button.setForeground(FOREGROUND);
        button.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));
    }

    private static Path defaultInstallDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Local")
                : Path.of(localAppData);
        return base.resolve("MyLauncher");
    }

    private static String configuredRepository() {
        String repository = System.getProperty("mylauncher.githubRepository", "");
        try {
            return GithubReleaseClient.validateRepository(repository);
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private record InstallProgress(String message, long completedBytes, long totalBytes) {
    }
}

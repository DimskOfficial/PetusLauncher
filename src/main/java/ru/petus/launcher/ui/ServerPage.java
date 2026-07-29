package ru.petus.launcher.ui;

import com.google.gson.JsonObject;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import ru.petus.launcher.api.ApiClient;
import ru.petus.launcher.api.Models;
import ru.petus.launcher.auth.Account;
import ru.petus.launcher.auth.AccountManager;
import ru.petus.launcher.auth.SessionStore;
import ru.petus.launcher.core.Log;
import ru.petus.launcher.core.Settings;
import ru.petus.launcher.game.FabricInstaller;
import ru.petus.launcher.game.GameLauncher;
import ru.petus.launcher.game.MinecraftInstaller;
import ru.petus.launcher.game.ModpackInstaller;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * One server, one page: hero with the status, the version picker, the big
 * install/play button, the mod list with toggles and the server details.
 *
 * PetusCreate is launcher only — playing always goes through PetusID. PetusMC
 * additionally allows Microsoft and offline accounts, and its cheat mods are
 * installed automatically because the server is anarchy by design.
 */
public final class ServerPage extends ScrollPane {
    private final Models.ServerEntry server;
    private final MainWindow shell;
    private final ComboBox<String> versionBox = new ComboBox<>();
    private final Button primary = new Button("Скачать");
    private final Button folderButton = new Button("Папка");
    private final Label stateLabel = Fx.label("", "faint");
    private final MinecraftInstaller minecraft = new MinecraftInstaller();
    private final ModpackInstaller modpacks = new ModpackInstaller(minecraft.downloader());
    private boolean busy;
    private Process game;

    public ServerPage(Models.ServerEntry server, MainWindow shell) {
        this.server = server;
        this.shell = shell;

        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setContent(buildContent());
        refreshState();
    }

    private VBox buildContent() {
        VBox root = new VBox(18);
        root.setPadding(new Insets(24, 28, 28, 28));
        root.getChildren().add(buildHero());
        if (!server.playable()) {
            root.getChildren().add(buildUnavailableCard());
        } else {
            root.getChildren().add(buildActionCard());
        }
        root.getChildren().add(buildAboutCard());
        if (!server.mods.isEmpty()) {
            root.getChildren().add(buildModsCard());
        }
        if (!server.links.isEmpty()) {
            root.getChildren().add(buildLinksCard());
        }
        return root;
    }

    private VBox buildHero() {
        String accent = server.accent == null ? Theme.ACCENT : server.accent;
        Label name = Fx.label(server.name, "h1");
        Label tagline = Fx.label(server.tagline == null ? "" : server.tagline, "muted");
        tagline.setWrapText(true);

        FlowPane badges = new FlowPane(8, 8);
        badges.getChildren().add(badge(server.statusLabel(), server.playable() ? "success" : "warning"));
        if (server.recommendedVersion != null) {
            badges.getChildren().add(badge("рекомендуется " + server.recommendedVersion, "accent"));
        }
        if (server.coreVersion != null) {
            badges.getChildren().add(badge("ядро " + server.coreVersion, ""));
        }
        if (server.loader != null) {
            badges.getChildren().add(badge(server.loader, ""));
        }
        if (server.addressWithPort() != null && !server.addressWithPort().isBlank()) {
            badges.getChildren().add(badge(server.addressWithPort(), ""));
        }
        if (server.launcherOnly) {
            badges.getChildren().add(badge("только через лаунчер", "accent"));
        }

        VBox hero = new VBox(10, name, tagline, badges);
        hero.getStyleClass().add("hero");
        hero.setStyle("-fx-background-color: linear-gradient(to right, " + Theme.tint(accent, 0.22)
                + ", rgba(19,19,22,0.9));");
        return hero;
    }

    private VBox buildUnavailableCard() {
        Label title = Fx.label("Сервер пока недоступен", "h3");
        Label note = Fx.label(server.statusNote == null
                ? "Ведётся работа — зайти пока нельзя." : server.statusNote, "muted");
        note.setWrapText(true);
        Button disabled = new Button("Недоступно");
        disabled.getStyleClass().add("ghost");
        disabled.setDisable(true);
        VBox card = new VBox(10, title, note, disabled);
        card.getStyleClass().add("card");
        return card;
    }

    private VBox buildActionCard() {
        List<String> versions = new ArrayList<>(server.supportedVersions);
        if (versions.isEmpty() && server.defaultVersion() != null) {
            versions.add(server.defaultVersion());
        }
        versionBox.getItems().setAll(versions);
        String selected = Settings.get().versionFor(server.id, server.defaultVersion());
        versionBox.setValue(versions.contains(selected) ? selected
                : (versions.isEmpty() ? null : versions.get(0)));
        versionBox.setPrefWidth(190);
        versionBox.valueProperty().addListener((observable, old, value) -> {
            if (value != null) {
                Settings settings = Settings.get();
                settings.setVersionFor(server.id, value);
                settings.save();
                refreshState();
            }
        });

        primary.getStyleClass().addAll("primary");
        primary.setOnAction(event -> onPrimary());

        folderButton.getStyleClass().add("ghost");
        folderButton.setOnAction(event -> openInstanceFolder());

        Label versionCaption = Fx.label("Версия Minecraft", "faint");
        VBox versionBoxWrap = new VBox(6, versionCaption, versionBox);

        HBox row = new HBox(12, versionBoxWrap, Fx.spacer(), folderButton, primary);
        row.setAlignment(Pos.BOTTOM_RIGHT);

        VBox card = new VBox(12, row, stateLabel);
        card.getStyleClass().add("card");
        return card;
    }

    private VBox buildAboutCard() {
        Label title = Fx.label("О сервере", "h3");
        Label description = Fx.label(server.description == null ? "" : server.description, "muted");
        description.setWrapText(true);

        FlowPane features = new FlowPane(8, 8);
        for (String feature : server.features) {
            features.getChildren().add(Fx.styled(new Label(feature), "feature-chip"));
        }

        VBox card = new VBox(12, title, description, features);
        card.getStyleClass().add("card");
        return card;
    }

    private VBox buildModsCard() {
        Label title = Fx.label("Моды и сборка", "h3");
        Label hint = Fx.label("Обязательные моды выключить нельзя — без них сервер не пустит.", "faint");
        hint.setWrapText(true);

        VBox list = new VBox(8);
        for (Models.ModEntry mod : server.mods) {
            boolean enabled = mod.required
                    || Settings.get().modEnabled(server.id, mod.id, mod.enabledByDefault);
            CheckBox check = new CheckBox(mod.name);
            check.setSelected(enabled);
            check.setDisable(mod.required);
            check.selectedProperty().addListener((observable, old, value) -> {
                Settings settings = Settings.get();
                settings.setModEnabled(server.id, mod.id, value);
                settings.save();
                refreshState();
            });

            Label summary = Fx.label(mod.summary == null ? "" : mod.summary, "faint");
            summary.setWrapText(true);
            VBox text = new VBox(2, check, summary);
            HBox.setHgrow(text, Priority.ALWAYS);

            HBox row = new HBox(12, text, badge(mod.categoryLabel(),
                    "cheat".equals(mod.category) ? "danger" : ""));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("card-flat");
            list.getChildren().add(row);
        }

        VBox card = new VBox(12, title, hint, list);
        card.getStyleClass().add("card");
        return card;
    }

    private VBox buildLinksCard() {
        FlowPane links = new FlowPane(8, 8);
        for (Models.Link link : server.links) {
            Button button = new Button(link.label);
            button.getStyleClass().add("ghost");
            button.setOnAction(event -> openUrl(link.url));
            links.getChildren().add(button);
        }
        VBox card = new VBox(12, Fx.label("Ссылки", "h3"), links);
        card.getStyleClass().add("card");
        return card;
    }

    private Label badge(String text, String variant) {
        Label label = new Label(text);
        label.getStyleClass().add("badge");
        if (variant != null && !variant.isBlank()) {
            label.getStyleClass().add(variant);
        }
        return label;
    }

    // --- state -----------------------------------------------------------
    private void refreshState() {
        if (!server.playable()) {
            return;
        }
        String version = versionBox.getValue();
        if (version == null) {
            primary.setDisable(true);
            stateLabel.setText("Для этого сервера не указаны версии");
            return;
        }
        boolean ready = installed(version);
        primary.setDisable(busy);
        primary.setText(busy ? "Работаем…" : ready ? "Играть" : "Скачать");
        primary.getStyleClass().removeAll("primary", "play");
        primary.getStyleClass().add(ready && !busy ? "play" : "primary");
        long size = modpacks.installedSize(server.id);
        stateLabel.setText(ready
                ? "Установлено · " + version + " · моды " + ru.petus.launcher.game.Downloader.humanBytes(size)
                : "Не установлено · будет скачана версия " + version);
    }

    private boolean installed(String version) {
        try {
            if (!modpacks.installed(server, version)) {
                return false;
            }
            return Files.exists(minecraft.clientJar(version));
        } catch (RuntimeException error) {
            Log.debug("State check failed: " + error.getMessage());
            return false;
        }
    }

    // --- actions ---------------------------------------------------------
    private void onPrimary() {
        String version = versionBox.getValue();
        if (version == null || busy) {
            return;
        }
        if (installed(version)) {
            play(version);
        } else {
            install(version, false);
        }
    }

    private void install(String version, boolean thenPlay) {
        busy = true;
        refreshState();
        DownloadsView.Job job = shell.downloads().start("Установка " + server.name);
        Fx.async(() -> {
            try {
                JsonObject resolved;
                if (server.loader != null && !"vanilla".equals(server.loader)) {
                    resolved = new FabricInstaller(minecraft).install(version, server.loaderVersion, job);
                } else {
                    resolved = minecraft.install(version, job);
                }
                List<String> warnings = modpacks.install(server, version, job);
                return warnings;
            } catch (Exception error) {
                throw new RuntimeException(error.getMessage(), error);
            }
        }, warnings -> {
            busy = false;
            job.succeed(server.name + " готов к запуску");
            refreshState();
            if (!warnings.isEmpty()) {
                shell.toast("Установлено с замечаниями: " + warnings.get(0), true);
            } else {
                shell.toast(server.name + ": загрузка завершена");
            }
            if (thenPlay) {
                play(version);
            }
        }, error -> {
            busy = false;
            job.fail(Fx.message(error));
            refreshState();
            shell.toast("Не удалось установить: " + Fx.message(error), true);
        });
    }

    private void play(String version) {
        Account account = AccountManager.get().forServer(server.launcherOnly);
        if (account == null) {
            shell.toast("Сначала выберите аккаунт в разделе «Аккаунты»", true);
            return;
        }
        if (server.oauthApp != null && !SessionStore.get().authorizedFor(server.id)) {
            new EmbeddedAuthWindow().show(shell.stage(), server, handoff -> {
                SessionStore.get().markAuthorized(server.id, server.oauthApp);
                launch(version, account);
            }, error -> shell.toast("PetusID: " + Fx.message(error), true));
            return;
        }
        launch(version, account);
    }

    private void launch(String version, Account account) {
        busy = true;
        refreshState();
        DownloadsView.Job job = shell.downloads().start("Запуск " + server.name);
        Fx.async(() -> {
            try {
                job.stage("Получаем ключ входа");
                job.fraction(-1);
                Models.JoinTicket ticket = ApiClient.get().ticket(SessionStore.get().accessToken(),
                        server.id, account.name, account.uuid, account.premium());

                job.stage("Проверяем файлы");
                String versionId = new FabricInstaller(minecraft)
                        .versionIdFor(server.loader, version, server.loaderVersion);
                JsonObject resolved = minecraft.install(versionId, job);
                if (Settings.get().autoUpdateMods) {
                    modpacks.install(server, version, job);
                }

                job.stage("Запускаем Minecraft");
                GameLauncher.LaunchRequest request = new GameLauncher.LaunchRequest(server, versionId,
                        resolved, account, ticket, Settings.get().autoJoinServer);
                return new GameLauncher().launch(request, line -> shell.downloads().append(line));
            } catch (Exception error) {
                throw new RuntimeException(error.getMessage(), error);
            }
        }, process -> {
            game = process;
            busy = false;
            job.succeed(server.name + " запущен");
            refreshState();
            shell.toast("Игра запущена · " + account.name + " · " + account.typeLabel());
            afterLaunch();
            process.onExit().thenAccept(exited -> Fx.ui(() -> {
                shell.downloads().append("Игра завершена, код " + exited.exitValue());
                shell.stage().show();
                shell.stage().setIconified(false);
            }));
        }, error -> {
            busy = false;
            job.fail(Fx.message(error));
            refreshState();
            shell.toast("Не удалось запустить: " + Fx.message(error), true);
        });
    }

    private void afterLaunch() {
        switch (Settings.get().afterLaunch) {
            case "close" -> javafx.application.Platform.exit();
            case "minimize" -> shell.stage().setIconified(true);
            default -> {
            }
        }
    }

    private void openInstanceFolder() {
        try {
            java.nio.file.Path instance = ru.petus.launcher.core.AppDirs.instance(server.id);
            Files.createDirectories(instance);
            java.awt.Desktop.getDesktop().open(instance.toFile());
        } catch (Exception error) {
            shell.toast("Не удалось открыть папку: " + Fx.message(error), true);
        }
    }

    private void openUrl(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception error) {
            shell.toast(url, false);
        }
    }
}

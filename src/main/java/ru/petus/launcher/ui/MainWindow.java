package ru.petus.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import ru.petus.launcher.Bootstrap;
import ru.petus.launcher.api.ApiClient;
import ru.petus.launcher.api.Models;
import ru.petus.launcher.auth.AccountManager;
import ru.petus.launcher.auth.SessionStore;
import ru.petus.launcher.core.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The launcher shell: servers in the left rail, one page per server on the
 * right, plus Downloads / Settings / Accounts. Undecorated on purpose — the
 * custom title bar is part of the look.
 */
public final class MainWindow {
    private final Stage stage = new Stage(StageStyle.UNDECORATED);
    private final StackPane content = new StackPane();
    private final StackPane overlay = new StackPane();
    private final VBox serverNav = new VBox(6);
    private final VBox toolNav = new VBox(6);
    private final DownloadsView downloads = new DownloadsView();
    private final Map<String, Node> pages = new LinkedHashMap<>();
    private final List<HBox> navItems = new ArrayList<>();
    private final Label accountLabel = Fx.label("—", "nav-title");
    private final Label accountType = Fx.label("не выбран", "nav-subtitle");
    private AccountsView accountsView;

    public Stage stage() {
        return stage;
    }

    public DownloadsView downloads() {
        return downloads;
    }

    public void show() {
        HBox titleBar = buildTitleBar();
        VBox sidebar = buildSidebar();

        content.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(content, Priority.ALWAYS);

        overlay.setPickOnBounds(false);
        overlay.setAlignment(Pos.BOTTOM_RIGHT);
        overlay.setPadding(new Insets(0, 24, 24, 0));

        HBox body = new HBox(sidebar, new StackPane(content, overlay));
        HBox.setHgrow(body.getChildren().get(1), Priority.ALWAYS);
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox root = new VBox(titleBar, body);
        root.getStyleClass().add("window-root");

        Scene scene = new Scene(root, 1180, 740);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setMinWidth(1000);
        stage.setMinHeight(660);
        Theme.decorate(stage);
        Fx.makeDraggable(stage, titleBar);
        stage.show();
        stage.centerOnScreen();

        refreshAccount();
        showPage("downloads", downloads);
        loadServers();
    }

    private HBox buildTitleBar() {
        Label title = Fx.label("PetusLauncher · " + Bootstrap.version(), "title");

        Button minimize = new Button("–");
        minimize.getStyleClass().add("window-button");
        minimize.setOnAction(event -> stage.setIconified(true));

        Button maximize = new Button("□");
        maximize.getStyleClass().add("window-button");
        maximize.setOnAction(event -> stage.setMaximized(!stage.isMaximized()));

        Button close = new Button("✕");
        close.getStyleClass().addAll("window-button", "close");
        close.setOnAction(event -> javafx.application.Platform.exit());

        HBox bar = new HBox(8, title, Fx.spacer(), minimize, maximize, close);
        bar.getStyleClass().add("title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private VBox buildSidebar() {
        Label mark = Fx.label("P", "brand-mark");
        mark.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-text-fill: white;");
        VBox brandText = new VBox(0, Fx.label("Petus", "nav-title"), Fx.label("серверы и лаунчер", "nav-subtitle"));
        HBox brand = new HBox(10, mark, brandText);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setPadding(new Insets(6, 8, 14, 8));

        Label serversCaption = Fx.label("СЕРВЕРЫ", "faint");
        serversCaption.setPadding(new Insets(4, 8, 6, 8));
        Label toolsCaption = Fx.label("ЛАУНЧЕР", "faint");
        toolsCaption.setPadding(new Insets(14, 8, 6, 8));

        toolNav.getChildren().addAll(
                navItem("↓", "#7c5cff", "Загрузки", "прогресс и логи", true,
                        () -> showPage("downloads", downloads)),
                navItem("◈", "#38d9a9", "Аккаунты", "Microsoft и оффлайн", true, this::showAccounts),
                navItem("⚙", "#a1a1aa", "Настройки", "память, Java, игра", true, this::showSettings));

        VBox accountCard = new VBox(2, accountLabel, accountType);
        accountCard.getStyleClass().add("card-flat");
        accountCard.setPadding(new Insets(10, 12, 10, 12));
        accountCard.setOnMouseClicked(event -> showAccounts());
        accountCard.setStyle("-fx-cursor: hand;");

        VBox sidebar = new VBox(brand, serversCaption, serverNav, toolsCaption, toolNav,
                Fx.spacer(), accountCard);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(248);
        sidebar.setMinWidth(248);
        return sidebar;
    }

    /** One row in the left rail. */
    private HBox navItem(String glyph, String accent, String title, String subtitle, boolean enabled,
            Runnable action) {
        Label icon = Fx.label(glyph, "server-icon");
        icon.setStyle("-fx-background-color: " + Theme.tint(accent, 0.16) + "; -fx-text-fill: " + accent + ";");

        Label titleLabel = Fx.label(title, "nav-title");
        Label subtitleLabel = Fx.label(subtitle, "nav-subtitle");
        VBox text = new VBox(1, titleLabel, subtitleLabel);

        HBox item = new HBox(11, icon, text);
        item.setAlignment(Pos.CENTER_LEFT);
        item.getStyleClass().add("nav-item");
        if (!enabled) {
            item.getStyleClass().add("disabled");
        }
        item.setOnMouseClicked(event -> {
            if (!enabled) {
                toast("Этот раздел пока недоступен");
                return;
            }
            navItems.forEach(node -> node.getStyleClass().remove("active"));
            item.getStyleClass().add("active");
            action.run();
        });
        navItems.add(item);
        return item;
    }

    private void loadServers() {
        Fx.async(() -> ApiClient.get().servers(), servers -> {
            serverNav.getChildren().clear();
            for (Models.ServerEntry server : servers) {
                ServerPage page = new ServerPage(server, this);
                String accent = server.accent == null ? Theme.ACCENT : server.accent;
                String glyph = server.name == null || server.name.isEmpty()
                        ? "?" : server.name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
                serverNav.getChildren().add(navItem(glyph, accent, server.name,
                        server.playable() ? server.statusLabel() : server.statusLabel(),
                        true, () -> showPage(server.id, page)));
            }
            if (!servers.isEmpty()) {
                Models.ServerEntry first = servers.get(0);
                Node page = pages.get(first.id);
                if (page == null) {
                    page = new ServerPage(first, this);
                }
                showPage(first.id, page);
                if (!serverNav.getChildren().isEmpty()) {
                    navItems.forEach(node -> node.getStyleClass().remove("active"));
                    serverNav.getChildren().get(0).getStyleClass().add("active");
                }
            }
        }, error -> {
            Log.error("Cannot load the server list", error);
            toast("Не удалось загрузить список серверов: " + Fx.message(error));
        });
    }

    void showPage(String key, Node page) {
        pages.put(key, page);
        content.getChildren().setAll(page);
    }

    private void showSettings() {
        showPage("settings", new SettingsView(this));
    }

    private void showAccounts() {
        accountsView = new AccountsView(this);
        showPage("accounts", accountsView);
    }

    /** Refreshes the account chip at the bottom of the rail. */
    public void refreshAccount() {
        AccountManager.get().selected().ifPresentOrElse(account -> {
            accountLabel.setText(account.name == null ? "—" : account.name);
            accountType.setText(account.typeLabel());
        }, () -> {
            accountLabel.setText(SessionStore.get().user().map(Models.UserInfo::label).orElse("—"));
            accountType.setText("PetusID");
        });
    }

    /** Short lived message in the bottom right corner. */
    public void toast(String message) {
        toast(message, false);
    }

    public void toast(String message, boolean error) {
        Fx.ui(() -> {
            Label label = new Label(message);
            label.setWrapText(true);
            label.setMaxWidth(360);
            VBox card = new VBox(label);
            card.getStyleClass().addAll("toast", error ? "error" : "success");
            overlay.getChildren().add(card);
            javafx.animation.PauseTransition pause =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(error ? 8 : 4));
            pause.setOnFinished(event -> overlay.getChildren().remove(card));
            pause.play();
        });
    }
}

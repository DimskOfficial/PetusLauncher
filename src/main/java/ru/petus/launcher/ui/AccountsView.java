package ru.petus.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import ru.petus.launcher.auth.Account;
import ru.petus.launcher.auth.AccountManager;
import ru.petus.launcher.auth.MicrosoftAuth;
import ru.petus.launcher.auth.SessionStore;
import ru.petus.launcher.core.Settings;

/**
 * Accounts tab.
 *
 * PetusID is the launcher identity and is always present. On top of that a
 * player can attach a real Microsoft account (premium skins, licensed servers)
 * or a plain offline nickname for PetusMC.
 */
public final class AccountsView extends ScrollPane {
    private final MainWindow shell;
    private final VBox list = new VBox(10);

    public AccountsView(MainWindow shell) {
        this.shell = shell;
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setContent(build());
        refresh();
    }

    private VBox build() {
        Label title = Fx.label("Аккаунты", "h2");
        Label subtitle = Fx.label("PetusID даёт доступ к серверам, Microsoft и оффлайн нужны для самой игры",
                "muted");

        VBox listCard = new VBox(12, Fx.label("Сохранённые аккаунты", "h3"), list);
        listCard.getStyleClass().add("card");

        VBox root = new VBox(16, new VBox(2, title, subtitle), petusCard(), listCard, addCard());
        root.setPadding(new Insets(24, 28, 28, 28));
        return root;
    }

    private VBox petusCard() {
        String label = SessionStore.get().user()
                .map(user -> user.label())
                .orElse("не выполнен вход");
        Label name = Fx.label(label, "nav-title");
        Label description = Fx.label("Единый вход Petus · id.petus.ru", "faint");

        Button logout = new Button("Выйти из PetusID");
        logout.getStyleClass().add("danger");
        logout.setOnAction(event -> {
            SessionStore.get().logout();
            shell.toast("Сессия PetusID завершена, перезапустите лаунчер");
        });

        HBox row = new HBox(12, new VBox(2, name, description), Fx.spacer(), logout);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(12, Fx.label("PetusID", "h3"), row);
        card.getStyleClass().add("card");
        return card;
    }

    private VBox addCard() {
        TextField nickname = new TextField();
        nickname.setPromptText("Ник для оффлайн-аккаунта");
        nickname.setPrefWidth(220);

        Button addOffline = new Button("Добавить оффлайн");
        addOffline.getStyleClass().add("ghost");
        addOffline.setOnAction(event -> {
            String name = nickname.getText() == null ? "" : nickname.getText().trim();
            if (name.length() < 3) {
                shell.toast("Ник должен быть от 3 символов", true);
                return;
            }
            AccountManager.get().addOffline(name);
            nickname.clear();
            refresh();
            shell.toast("Оффлайн-аккаунт " + name + " добавлен");
        });

        Button addMicrosoft = new Button("Войти в Microsoft");
        addMicrosoft.getStyleClass().add("primary");
        addMicrosoft.setOnAction(event -> startMicrosoft());

        HBox row = new HBox(10, nickname, addOffline, Fx.spacer(), addMicrosoft);
        row.setAlignment(Pos.CENTER_LEFT);

        Label hint = Fx.label("Для входа Microsoft нужен Azure client id в настройках. "
                + "На PetusCreate играть можно только через лаунчер и PetusID.", "faint");
        hint.setWrapText(true);

        VBox card = new VBox(12, Fx.label("Добавить аккаунт", "h3"), row, hint);
        card.getStyleClass().add("card");
        return card;
    }

    private void startMicrosoft() {
        if (!MicrosoftAuth.configured()) {
            shell.toast("Сначала укажите Microsoft client id в настройках", true);
            return;
        }
        MicrosoftAuth auth = new MicrosoftAuth();
        DownloadsView.Job job = shell.downloads().start("Вход Microsoft");
        job.fraction(-1);
        Fx.async(() -> {
            MicrosoftAuth.DeviceCode code = auth.requestDeviceCode();
            Fx.ui(() -> {
                shell.toast("Код " + code.userCode() + " · откроется " + code.verificationUri());
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(code.verificationUri()));
                } catch (Exception ignored) {
                    // The code is in the toast and the log, so this is not fatal.
                }
            });
            job.detail("Код подтверждения: " + code.userCode());
            return auth.completeDeviceCode(code, job::detail);
        }, account -> {
            AccountManager.get().addOrUpdateMicrosoft(account);
            Settings settings = Settings.get();
            settings.selectedAccountId = account.id;
            settings.save();
            job.succeed("Microsoft: " + account.name);
            refresh();
            shell.refreshAccount();
            shell.toast("Аккаунт " + account.name + " подключён");
        }, error -> {
            job.fail(Fx.message(error));
            shell.toast("Microsoft: " + Fx.message(error), true);
        });
    }

    private void refresh() {
        Fx.ui(() -> {
            list.getChildren().clear();
            AccountManager manager = AccountManager.get();
            if (manager.all().isEmpty()) {
                list.getChildren().add(Fx.label("Пока нет игровых аккаунтов", "muted"));
                return;
            }
            String selectedId = manager.selected().map(account -> account.id).orElse("");
            for (Account account : manager.all()) {
                list.getChildren().add(accountRow(account, account.id.equals(selectedId)));
            }
        });
    }

    private HBox accountRow(Account account, boolean selected) {
        Label name = Fx.label(account.name == null ? "—" : account.name, "nav-title");
        Label type = Fx.label(account.typeLabel() + (account.premium() ? " · лицензия" : " · оффлайн"),
                "faint");
        VBox text = new VBox(2, name, type);
        HBox.setHgrow(text, Priority.ALWAYS);

        Label badge = new Label(selected ? "выбран" : " ");
        badge.getStyleClass().addAll("badge", "accent");
        badge.setVisible(selected);

        Button select = new Button("Выбрать");
        select.getStyleClass().add("ghost");
        select.setDisable(selected);
        select.setOnAction(event -> {
            AccountManager.get().select(account.id);
            refresh();
            shell.refreshAccount();
        });

        Button remove = new Button("Удалить");
        remove.getStyleClass().add("danger");
        remove.setDisable(account.type == Account.Type.PETUS);
        remove.setOnAction(event -> {
            AccountManager.get().remove(account.id);
            refresh();
            shell.refreshAccount();
        });

        HBox row = new HBox(12, text, badge, select, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("card-flat");
        return row;
    }
}

package ru.petus.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import ru.petus.launcher.Bootstrap;
import ru.petus.launcher.api.ApiClient;
import ru.petus.launcher.api.Models;
import ru.petus.launcher.api.PetusIdFlow;
import ru.petus.launcher.auth.SessionStore;
import ru.petus.launcher.core.Log;

import java.util.function.Consumer;

/**
 * The small window that greets you when the launcher starts: one PetusID
 * button, nothing else. A stored session is validated silently, so returning
 * players never see it for longer than a blink.
 */
public final class LoginWindow {
    private final Stage stage = new Stage(StageStyle.UNDECORATED);
    private final Label status = Fx.label("Вход через единый аккаунт Petus", "muted");
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Button loginButton = new Button("Войти через PetusID");
    private final Button retryButton = new Button("Отменить");
    private PetusIdFlow flow;

    public void show(Consumer<Models.UserInfo> onSuccess) {
        VBox root = buildRoot(onSuccess);
        Scene scene = new Scene(root, 420, 520);
        Theme.apply(scene);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setScene(scene);
        stage.setResizable(false);
        Theme.decorate(stage);
        stage.show();
        stage.centerOnScreen();

        SessionStore session = SessionStore.get();
        if (session.hasSession()) {
            busy("Проверяем сохранённый вход…");
            Fx.async(session::validate, valid -> {
                if (valid) {
                    finish(onSuccess);
                } else {
                    idle("Сессия истекла — войдите снова");
                }
            }, error -> idle("Не удалось проверить сессию: " + Fx.message(error)));
        }
    }

    private VBox buildRoot(Consumer<Models.UserInfo> onSuccess) {
        Label brand = Fx.label("P", "brand-mark");
        brand.setStyle("-fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: white;");
        brand.setMinSize(48, 48);
        brand.setMaxSize(48, 48);
        brand.setAlignment(Pos.CENTER);

        Label title = Fx.label("PetusLauncher", "h1");
        Label subtitle = Fx.label("Лаунчер серверов PetusCreate и PetusMC", "muted");
        subtitle.setWrapText(true);
        subtitle.setAlignment(Pos.CENTER);

        spinner.setVisible(false);
        spinner.setPrefSize(18, 18);
        spinner.setMaxSize(18, 18);

        loginButton.getStyleClass().addAll("primary");
        loginButton.setMaxWidth(Double.MAX_VALUE);
        loginButton.setOnAction(event -> startLogin(onSuccess));

        retryButton.getStyleClass().addAll("link");
        retryButton.setVisible(false);
        retryButton.setOnAction(event -> {
            if (flow != null) {
                flow.cancel();
            }
            idle("Вход отменён");
        });

        HBox statusRow = new HBox(8, spinner, status);
        statusRow.setAlignment(Pos.CENTER);
        status.setWrapText(true);
        status.setMaxWidth(300);
        status.setAlignment(Pos.CENTER);

        Button close = new Button("✕");
        close.getStyleClass().addAll("window-button", "close");
        close.setOnAction(event -> javafx.application.Platform.exit());
        HBox titleBar = new HBox(Fx.spacer(), close);
        titleBar.getStyleClass().add("title-bar");

        VBox center = new VBox(14, brand, title, subtitle, new StackPane(), loginButton, statusRow, retryButton);
        center.setAlignment(Pos.CENTER);
        center.setPadding(new Insets(0, 40, 0, 40));
        VBox.setVgrow(center, javafx.scene.layout.Priority.ALWAYS);

        Label footer = Fx.label("версия " + Bootstrap.version() + " · id.petus.ru", "faint");
        VBox footerBox = new VBox(footer);
        footerBox.setAlignment(Pos.CENTER);
        footerBox.setPadding(new Insets(0, 0, 22, 0));

        VBox root = new VBox(titleBar, center, footerBox);
        root.getStyleClass().add("window-root");
        Fx.makeDraggable(stage, titleBar);
        Fx.makeDraggable(stage, center);
        return root;
    }

    private void startLogin(Consumer<Models.UserInfo> onSuccess) {
        busy("Открываем браузер…");
        retryButton.setVisible(true);
        Fx.async(() -> {
            try {
                flow = new PetusIdFlow();
                String url = ApiClient.get().startUrl("launcher", flow.port(), null, null, false);
                flow.openBrowser(url);
                Models.Handoff handoff = flow.await(300);
                SessionStore.get().adopt(handoff);
                return handoff;
            } catch (Exception error) {
                throw new RuntimeException(error.getMessage(), error);
            } finally {
                if (flow != null) {
                    flow.close();
                    flow = null;
                }
            }
        }, handoff -> {
            Log.info("PetusID login complete");
            finish(onSuccess);
        }, error -> idle(Fx.message(error)));
    }

    private void finish(Consumer<Models.UserInfo> onSuccess) {
        Models.UserInfo user = SessionStore.get().user().orElse(null);
        stage.close();
        onSuccess.accept(user);
    }

    private void busy(String message) {
        Fx.ui(() -> {
            spinner.setVisible(true);
            loginButton.setDisable(true);
            status.setText(message);
            status.getStyleClass().remove("danger-text");
        });
    }

    private void idle(String message) {
        Fx.ui(() -> {
            spinner.setVisible(false);
            loginButton.setDisable(false);
            retryButton.setVisible(false);
            status.setText(message);
        });
    }
}

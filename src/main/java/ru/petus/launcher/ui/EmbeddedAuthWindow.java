package ru.petus.launcher.ui;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import ru.petus.launcher.api.ApiClient;
import ru.petus.launcher.api.Models;
import ru.petus.launcher.auth.SessionStore;
import ru.petus.launcher.core.Log;

import java.util.function.Consumer;

/**
 * In-launcher PetusID consent window.
 *
 * The first login opens a real browser (so the user can trust the address bar),
 * but per-server authorisation happens right here: the API is called in
 * "embedded" mode, we watch the WebView location and grab the handoff code as
 * soon as it redirects to /oauth/complete. Because the launcher session is
 * passed along, PetusID normally shows a one-click consent screen.
 */
public final class EmbeddedAuthWindow {
    private final Stage stage = new Stage(StageStyle.UNDECORATED);
    private final WebView webView = new WebView();

    public void show(Stage owner, Models.ServerEntry server,
            Consumer<Models.Handoff> onSuccess, Consumer<Throwable> onError) {
        String app = server.oauthApp == null ? "launcher" : server.oauthApp;
        String url = ApiClient.get().startUrl(app, 0, SessionStore.get().sessionId(), server.id, true);

        Label title = Fx.label("PetusID · " + server.name, "h3");
        Label subtitle = Fx.label("Подтвердите доступ к серверу", "faint");
        Button close = new Button("✕");
        close.getStyleClass().addAll("window-button", "close");
        close.setOnAction(event -> {
            stage.close();
            onError.accept(new RuntimeException("Авторизация отменена"));
        });

        VBox titles = new VBox(2, title, subtitle);
        HBox header = new HBox(12, titles, Fx.spacer(), close);
        header.getStyleClass().add("title-bar");
        header.setPadding(new Insets(12, 12, 12, 18));

        ProgressBar loading = new ProgressBar();
        loading.setMaxWidth(Double.MAX_VALUE);
        loading.setProgress(-1);

        webView.setContextMenuEnabled(false);
        webView.getEngine().setUserAgent("PetusLauncher (JavaFX WebView)");
        webView.getEngine().getLoadWorker().progressProperty()
                .addListener((observable, old, value) -> loading.setProgress(value.doubleValue()));
        webView.getEngine().getLoadWorker().stateProperty().addListener((observable, old, state) ->
                loading.setVisible(state == javafx.concurrent.Worker.State.RUNNING));

        final boolean[] claimed = { false };
        webView.getEngine().locationProperty().addListener((observable, old, location) -> {
            if (location == null || claimed[0]) {
                return;
            }
            String handoff = extractHandoff(location);
            if (handoff == null) {
                return;
            }
            claimed[0] = true;
            Log.info("Embedded PetusID consent finished for " + server.id);
            Fx.async(() -> ApiClient.get().claim(handoff), result -> {
                stage.close();
                onSuccess.accept(result);
            }, error -> {
                stage.close();
                onError.accept(error);
            });
        });

        VBox root = new VBox(header, loading, webView);
        root.getStyleClass().add("window-root");
        VBox.setVgrow(webView, javafx.scene.layout.Priority.ALWAYS);
        Fx.makeDraggable(stage, header);

        Scene scene = new Scene(root, 520, 660);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        Theme.decorate(stage);
        webView.getEngine().load(url);
        stage.show();
    }

    /** Pulls the handoff code out of .../oauth/complete?handoff=hof_… */
    static String extractHandoff(String location) {
        int index = location.indexOf("handoff=");
        if (index < 0 || !location.contains("/oauth/complete")) {
            return null;
        }
        String value = location.substring(index + "handoff=".length());
        int ampersand = value.indexOf('&');
        if (ampersand >= 0) {
            value = value.substring(0, ampersand);
        }
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}

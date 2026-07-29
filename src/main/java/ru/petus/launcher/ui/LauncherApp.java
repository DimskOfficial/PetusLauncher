package ru.petus.launcher.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import ru.petus.launcher.auth.AccountManager;
import ru.petus.launcher.auth.SessionStore;
import ru.petus.launcher.core.Log;

/**
 * JavaFX entry point. Decides between the small login window and the main
 * window: a stored PetusID session is validated in the background so returning
 * users land straight on the servers page.
 */
public final class LauncherApp extends Application {
    public static void start(String[] args) {
        Application.launch(LauncherApp.class, args);
    }

    @Override
    public void init() {
        // Nothing heavy here — the UI must appear immediately.
    }

    @Override
    public void start(Stage stage) {
        Theme.install();
        Platform.setImplicitExit(true);

        LoginWindow login = new LoginWindow();
        login.show(session -> {
            if (session != null) {
                AccountManager.get().syncPetusAccount(session.minecraftName, session.minecraftUuid);
            }
            new MainWindow().show();
        });
    }

    @Override
    public void stop() {
        Log.info("PetusLauncher closing");
        SessionStore.get();
    }
}

package ru.petus.launcher.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import ru.petus.launcher.auth.AccountManager;
import ru.petus.launcher.core.Log;
import ru.petus.launcher.core.Settings;

import java.util.List;

/**
 * JavaFX entry point. Decides between the small login window and the main
 * window: a stored PetusID session is validated in the background so returning
 * users land straight on the servers page.
 *
 * Supported command line flags (developer conveniences, not shown in the UI):
 *   --api=&lt;url&gt;  point the launcher at another launcher.petus.ru instance
 *   --preview     skip the PetusID window and open the shell straight away,
 *                 used when reviewing the design against a local API
 */
public final class LauncherApp extends Application {
    public static void start(String[] args) {
        Application.launch(LauncherApp.class, args);
    }

    @Override
    public void start(Stage stage) {
        Theme.install();
        Platform.setImplicitExit(true);

        List<String> arguments = getParameters().getRaw();
        for (String argument : arguments) {
            if (argument.startsWith("--api=")) {
                Settings.get().apiBaseUrl = argument.substring("--api=".length());
                Log.info("API override: " + Settings.get().apiBaseUrl);
            }
        }

        if (arguments.contains("--preview")) {
            Log.warn("Preview mode: PetusID login skipped");
            new MainWindow().show();
            return;
        }

        new LoginWindow().show(session -> {
            if (session != null) {
                AccountManager.get().syncPetusAccount(session.minecraftName, session.minecraftUuid);
            }
            new MainWindow().show();
        });
    }

    @Override
    public void stop() {
        Log.info("PetusLauncher closing");
    }
}

package ru.petus.launcher;

import ru.petus.launcher.core.AppDirs;
import ru.petus.launcher.core.Log;
import ru.petus.launcher.ui.LauncherApp;

/**
 * Entry point. Kept free of JavaFX types so a broken JavaFX runtime still
 * produces a readable error instead of a NoClassDefFoundError on startup.
 */
public final class Bootstrap {
    private Bootstrap() {
    }

    public static void main(String[] args) {
        AppDirs.ensureLayout();
        Log.init();
        Log.info("PetusLauncher " + version() + " starting");
        Log.info("Java " + System.getProperty("java.version") + " (" + System.getProperty("os.name") + " "
                + System.getProperty("os.arch") + ")");
        Log.info("Data directory: " + AppDirs.data());

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            Log.error("Uncaught exception on " + thread.getName(), error);
        });

        try {
            LauncherApp.start(args);
        } catch (Throwable error) {
            Log.error("Fatal startup failure", error);
            System.err.println("PetusLauncher не смог запуститься: " + error);
            System.exit(1);
        }
    }

    public static String version() {
        String fromManifest = Bootstrap.class.getPackage().getImplementationVersion();
        return fromManifest != null ? fromManifest : "3.0.0-dev";
    }
}

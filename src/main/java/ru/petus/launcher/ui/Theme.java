package ru.petus.launcher.ui;

import atlantafx.base.theme.PrimerDark;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import ru.petus.launcher.core.Log;

import java.io.InputStream;
import java.util.Objects;

/**
 * Visual identity of the launcher: AtlantaFX Primer Dark as the base (modern
 * flat controls, proper focus rings) plus petus.css on top, which carries the
 * PetusID palette — zinc surfaces, #7C5CFF accent, 10px radii.
 */
public final class Theme {
    public static final String ACCENT = "#7C5CFF";
    public static final String STYLESHEET = "/ru/petus/launcher/css/petus.css";

    private Theme() {
    }

    public static void install() {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        Log.info("Theme installed: Primer Dark + petus.css");
    }

    public static void apply(Scene scene) {
        scene.getStylesheets().add(Objects.requireNonNull(Theme.class.getResource(STYLESHEET)).toExternalForm());
        scene.setFill(javafx.scene.paint.Color.web("#09090b"));
    }

    public static void decorate(Stage stage) {
        stage.setTitle("PetusLauncher");
        Image icon = image("/ru/petus/launcher/img/petus.png");
        if (icon != null) {
            stage.getIcons().add(icon);
        }
    }

    public static Image image(String resource) {
        try (InputStream stream = Theme.class.getResourceAsStream(resource)) {
            return stream == null ? null : new Image(stream);
        } catch (Exception error) {
            Log.debug("Missing image " + resource);
            return null;
        }
    }

    /** Semi transparent version of a hex colour, for accent backgrounds. */
    public static String tint(String hex, double opacity) {
        javafx.scene.paint.Color color = javafx.scene.paint.Color.web(hex);
        return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.2f)",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255),
                opacity);
    }
}

package ru.petus.launcher.ui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import ru.petus.launcher.core.Log;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Small JavaFX helpers used across the UI. */
public final class Fx {
    private Fx() {
    }

    public static void ui(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /**
     * Runs blocking work off the UI thread and delivers the result (or the
     * failure) back on the UI thread. Every network call in the launcher goes
     * through here so the window never freezes.
     */
    public static <T> Task<T> async(Supplier<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() {
                return work.get();
            }
        };
        task.setOnSucceeded(event -> onSuccess.accept(task.getValue()));
        task.setOnFailed(event -> {
            Throwable error = task.getException() == null
                    ? new RuntimeException("Неизвестная ошибка")
                    : task.getException();
            Log.error("Background task failed", error);
            onFailure.accept(error);
        });
        Thread thread = new Thread(task, "petus-task");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    public static Label label(String text, String... styleClasses) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styleClasses);
        return label;
    }

    public static Region spacer() {
        Region region = new Region();
        javafx.scene.layout.HBox.setHgrow(region, javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.layout.VBox.setVgrow(region, javafx.scene.layout.Priority.ALWAYS);
        return region;
    }

    public static <T extends Node> T styled(T node, String... styleClasses) {
        node.getStyleClass().addAll(styleClasses);
        return node;
    }

    /** Makes an undecorated window draggable by any node (our title bars). */
    public static void makeDraggable(javafx.stage.Stage stage, Node handle) {
        final double[] offset = new double[2];
        handle.setOnMousePressed(event -> {
            offset[0] = event.getScreenX() - stage.getX();
            offset[1] = event.getScreenY() - stage.getY();
        });
        handle.setOnMouseDragged(event -> {
            if (!stage.isMaximized()) {
                stage.setX(event.getScreenX() - offset[0]);
                stage.setY(event.getScreenY() - offset[1]);
            }
        });
    }

    public static Pane fill(Pane pane) {
        pane.setMaxWidth(Double.MAX_VALUE);
        pane.setMaxHeight(Double.MAX_VALUE);
        return pane;
    }

    /** Trims a long error message down to something a card can show. */
    public static String message(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return message.length() > 240 ? message.substring(0, 237) + "…" : message;
    }
}

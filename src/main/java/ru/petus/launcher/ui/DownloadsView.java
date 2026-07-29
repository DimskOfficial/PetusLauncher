package ru.petus.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import ru.petus.launcher.core.AppDirs;
import ru.petus.launcher.core.Log;
import ru.petus.launcher.game.Progress;

import java.awt.Desktop;
import java.io.IOException;

/**
 * "Загрузки" tab: one card per running job (version, mods, launch) with a
 * cancel button, plus the live game log so people can actually see what is
 * happening instead of staring at a frozen button.
 */
public final class DownloadsView extends VBox {
    private final VBox jobs = new VBox(10);
    private final TextArea console = new TextArea();
    private final Label empty = Fx.label("Сейчас ничего не скачивается", "muted");

    public DownloadsView() {
        setSpacing(16);
        setPadding(new Insets(24, 28, 24, 28));

        Label title = Fx.label("Загрузки и логи", "h2");
        Label subtitle = Fx.label("Прогресс установки версий и модов, вывод игры и лаунчера", "muted");

        Button openFolder = new Button("Папка лаунчера");
        openFolder.getStyleClass().add("ghost");
        openFolder.setOnAction(event -> openDataFolder());

        Button openLog = new Button("Файл лога");
        openLog.getStyleClass().add("ghost");
        openLog.setOnAction(event -> openLogFile());

        Button clear = new Button("Очистить");
        clear.getStyleClass().add("link");
        clear.setOnAction(event -> console.clear());

        HBox header = new HBox(10, new VBox(2, title, subtitle), Fx.spacer(), openFolder, openLog, clear);
        header.setAlignment(Pos.CENTER_LEFT);

        jobs.getChildren().add(empty);
        VBox jobsCard = new VBox(12, Fx.label("Активные задачи", "h3"), jobs);
        jobsCard.getStyleClass().add("card");

        console.setEditable(false);
        console.setWrapText(false);
        console.getStyleClass().add("console");
        VBox.setVgrow(console, Priority.ALWAYS);
        console.setPrefRowCount(18);

        VBox consoleCard = new VBox(10, Fx.label("Лог", "h3"), console);
        consoleCard.getStyleClass().add("card");
        VBox.setVgrow(consoleCard, Priority.ALWAYS);

        ScrollPane scroll = new ScrollPane(new VBox(16, jobsCard, consoleCard));
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, scroll);

        Log.recent().forEach(this::append);
        Log.addListener(this::append);
    }

    /** Appends a line to the console, keeping the buffer bounded. */
    public void append(String line) {
        Fx.ui(() -> {
            if (console.getLength() > 400_000) {
                console.deleteText(0, 200_000);
            }
            console.appendText(line.endsWith("\n") ? line : line + "\n");
        });
    }

    public Job start(String title) {
        Job job = new Job(title);
        Fx.ui(() -> {
            jobs.getChildren().remove(empty);
            jobs.getChildren().add(0, job.row);
        });
        return job;
    }

    private void finished(Job job) {
        Fx.ui(() -> {
            javafx.animation.PauseTransition pause =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(6));
            pause.setOnFinished(event -> {
                jobs.getChildren().remove(job.row);
                if (jobs.getChildren().isEmpty()) {
                    jobs.getChildren().add(empty);
                }
            });
            pause.play();
        });
    }

    private void openDataFolder() {
        try {
            Desktop.getDesktop().open(AppDirs.data().toFile());
        } catch (IOException | UnsupportedOperationException error) {
            append("Не удалось открыть папку: " + error.getMessage());
        }
    }

    private void openLogFile() {
        try {
            Desktop.getDesktop().open(Log.fileLocation().toFile());
        } catch (IOException | UnsupportedOperationException error) {
            append("Не удалось открыть лог: " + error.getMessage());
        }
    }

    /** A cancellable progress card. */
    public final class Job extends Progress.Cancellable {
        private final Label stageLabel;
        private final Label detailLabel;
        private final ProgressBar bar = new ProgressBar(0);
        private final Button cancel = new Button("Отмена");
        final VBox row;

        private Job(String title) {
            stageLabel = Fx.label(title, "h3");
            detailLabel = Fx.label("Подготовка…", "faint");
            bar.setMaxWidth(Double.MAX_VALUE);
            cancel.getStyleClass().add("link");
            cancel.setOnAction(event -> {
                cancel();
                detail("Отменяем…");
                cancel.setDisable(true);
            });
            HBox head = new HBox(10, stageLabel, Fx.spacer(), cancel);
            head.setAlignment(Pos.CENTER_LEFT);
            row = new VBox(8, head, bar, detailLabel);
            row.getStyleClass().add("card-flat");
        }

        @Override
        public void stage(String stage) {
            Fx.ui(() -> stageLabel.setText(stage));
        }

        @Override
        public void fraction(double fraction) {
            Fx.ui(() -> bar.setProgress(fraction < 0 ? ProgressBar.INDETERMINATE_PROGRESS : fraction));
        }

        @Override
        public void detail(String detail) {
            Fx.ui(() -> detailLabel.setText(detail));
        }

        @Override
        public void log(String line) {
            append(line);
        }

        public void succeed(String message) {
            Fx.ui(() -> {
                bar.setProgress(1);
                stageLabel.setText(message);
                detailLabel.setText("Готово");
                cancel.setVisible(false);
            });
            finished(this);
        }

        public void fail(String message) {
            Fx.ui(() -> {
                bar.setProgress(0);
                detailLabel.setText(message);
                detailLabel.getStyleClass().setAll("faint");
                cancel.setVisible(false);
            });
            append("Ошибка: " + message);
            finished(this);
        }
    }
}

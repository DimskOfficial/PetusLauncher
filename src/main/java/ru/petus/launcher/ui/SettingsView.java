package ru.petus.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import ru.petus.launcher.core.AppDirs;
import ru.petus.launcher.core.Settings;
import ru.petus.launcher.game.JavaLocator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Settings tab: memory, Java, game window, downloads and advanced options. */
public final class SettingsView extends ScrollPane {
    private final MainWindow shell;
    private final Settings settings = Settings.get();

    public SettingsView(MainWindow shell) {
        this.shell = shell;
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setContent(build());
    }

    private VBox build() {
        Label title = Fx.label("Настройки", "h2");
        Label subtitle = Fx.label("Всё, что влияет на загрузку и запуск игры", "muted");

        VBox root = new VBox(16, new VBox(2, title, subtitle),
                memoryCard(), javaCard(), gameCard(), downloadsCard(), advancedCard(), aboutCard());
        root.setPadding(new Insets(24, 28, 28, 28));
        return root;
    }

    private VBox card(String heading, javafx.scene.Node... rows) {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");
        card.getChildren().add(Fx.label(heading, "h3"));
        card.getChildren().addAll(rows);
        return card;
    }

    private HBox row(String label, String hint, javafx.scene.Node control) {
        Label name = Fx.label(label, "nav-title");
        Label description = Fx.label(hint, "faint");
        description.setWrapText(true);
        VBox text = new VBox(2, name, description);
        text.setMinWidth(280);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(16, text, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private VBox memoryCard() {
        Label value = Fx.label(settings.memoryMb + " МБ", "nav-title");
        Slider slider = new Slider(1024, Math.max(4096, maxMemory()), settings.memoryMb);
        slider.setBlockIncrement(512);
        slider.setMajorTickUnit(2048);
        slider.setPrefWidth(320);
        slider.valueProperty().addListener((observable, old, current) -> {
            int megabytes = (int) (Math.round(current.doubleValue() / 256.0) * 256);
            settings.memoryMb = megabytes;
            value.setText(megabytes + " МБ");
        });
        slider.setOnMouseReleased(event -> settings.save());

        return card("Память",
                row("Выделено игре", "Для Create с модами лучше 4–6 ГБ. Всего в системе ≈ "
                        + maxMemory() + " МБ", new HBox(12, slider, value)));
    }

    private int maxMemory() {
        long bytes = ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory
                .getOperatingSystemMXBean()).getTotalMemorySize();
        return (int) (bytes / (1024 * 1024));
    }

    private VBox javaCard() {
        TextField path = new TextField(settings.javaPath);
        path.setPromptText(JavaLocator.current());
        path.setPrefWidth(360);
        path.textProperty().addListener((observable, old, value) -> settings.javaPath = value.trim());
        path.focusedProperty().addListener((observable, old, focused) -> {
            if (!focused) {
                settings.save();
            }
        });

        ComboBox<String> detected = new ComboBox<>();
        List<String> candidates = new ArrayList<>();
        candidates.add(JavaLocator.current());
        for (Path candidate : JavaLocator.candidates()) {
            candidates.add(candidate.toString());
        }
        detected.getItems().setAll(candidates);
        detected.setPromptText("Найденные сборки Java");
        detected.setPrefWidth(360);
        detected.valueProperty().addListener((observable, old, value) -> {
            if (value != null) {
                path.setText(value);
                settings.javaPath = value;
                settings.save();
            }
        });

        TextField jvmArgs = new TextField(settings.jvmArgs);
        jvmArgs.setPrefWidth(360);
        jvmArgs.textProperty().addListener((observable, old, value) -> settings.jvmArgs = value);
        jvmArgs.focusedProperty().addListener((observable, old, focused) -> {
            if (!focused) {
                settings.save();
            }
        });

        return card("Java",
                row("Путь к Java", "Пусто — используется Java лаунчера (21)", path),
                row("Найдено в системе", "Выберите, если нужна другая сборка", detected),
                row("Аргументы JVM", "Флаги G1GC по умолчанию подобраны под Minecraft", jvmArgs));
    }

    private VBox gameCard() {
        Spinner<Integer> width = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                640, 7680, settings.windowWidth, 16));
        width.setPrefWidth(110);
        width.valueProperty().addListener((observable, old, value) -> {
            settings.windowWidth = value;
            settings.save();
        });

        Spinner<Integer> height = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                480, 4320, settings.windowHeight, 16));
        height.setPrefWidth(110);
        height.valueProperty().addListener((observable, old, value) -> {
            settings.windowHeight = value;
            settings.save();
        });

        CheckBox fullscreen = new CheckBox("Запускать в полноэкранном режиме");
        fullscreen.setSelected(settings.fullscreen);
        fullscreen.selectedProperty().addListener((observable, old, value) -> {
            settings.fullscreen = value;
            settings.save();
        });

        ComboBox<String> afterLaunch = new ComboBox<>();
        afterLaunch.getItems().setAll("keep", "minimize", "close");
        afterLaunch.setValue(settings.afterLaunch);
        afterLaunch.setPrefWidth(180);
        afterLaunch.valueProperty().addListener((observable, old, value) -> {
            settings.afterLaunch = value;
            settings.save();
        });

        CheckBox autoJoin = new CheckBox("Сразу подключаться к серверу");
        autoJoin.setSelected(settings.autoJoinServer);
        autoJoin.selectedProperty().addListener((observable, old, value) -> {
            settings.autoJoinServer = value;
            settings.save();
        });

        return card("Игра",
                row("Размер окна", "Ширина и высота окна Minecraft", new HBox(8, width, height)),
                fullscreen,
                autoJoin,
                row("После запуска", "keep — оставить, minimize — свернуть, close — закрыть",
                        afterLaunch));
    }

    private VBox downloadsCard() {
        Spinner<Integer> threads = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                1, 32, settings.downloadThreads, 1));
        threads.setPrefWidth(110);
        threads.valueProperty().addListener((observable, old, value) -> {
            settings.downloadThreads = value;
            settings.save();
        });

        CheckBox verify = new CheckBox("Проверять SHA-1 скачанных файлов");
        verify.setSelected(settings.verifyFileHashes);
        verify.selectedProperty().addListener((observable, old, value) -> {
            settings.verifyFileHashes = value;
            settings.save();
        });

        CheckBox autoMods = new CheckBox("Обновлять моды перед запуском");
        autoMods.setSelected(settings.autoUpdateMods);
        autoMods.selectedProperty().addListener((observable, old, value) -> {
            settings.autoUpdateMods = value;
            settings.save();
        });

        CheckBox snapshots = new CheckBox("Показывать снапшоты Minecraft");
        snapshots.setSelected(settings.showSnapshots);
        snapshots.selectedProperty().addListener((observable, old, value) -> {
            settings.showSnapshots = value;
            settings.save();
        });

        return card("Загрузки",
                row("Потоки загрузки", "Больше потоков — быстрее ресурсы, сильнее нагрузка на сеть",
                        threads),
                verify, autoMods, snapshots);
    }

    private VBox advancedCard() {
        TextField api = new TextField(settings.apiBaseUrl);
        api.setPrefWidth(360);
        api.textProperty().addListener((observable, old, value) -> settings.apiBaseUrl = value.trim());
        api.focusedProperty().addListener((observable, old, focused) -> {
            if (!focused) {
                settings.save();
            }
        });

        TextField microsoft = new TextField(settings.microsoftClientId);
        microsoft.setPromptText("Azure client id для входа Microsoft");
        microsoft.setPrefWidth(360);
        microsoft.textProperty().addListener((observable, old, value) -> settings.microsoftClientId = value.trim());
        microsoft.focusedProperty().addListener((observable, old, focused) -> {
            if (!focused) {
                settings.save();
            }
        });

        Button openFolder = new Button("Открыть папку данных");
        openFolder.getStyleClass().add("ghost");
        openFolder.setOnAction(event -> {
            try {
                java.awt.Desktop.getDesktop().open(AppDirs.data().toFile());
            } catch (Exception error) {
                shell.toast(Fx.message(error), true);
            }
        });

        return card("Дополнительно",
                row("API лаунчера", "По умолчанию https://launcher.petus.ru", api),
                row("Microsoft client id", "Нужен только для входа в лицензию Minecraft", microsoft),
                row("Файлы лаунчера", AppDirs.data().toString(), openFolder));
    }

    private VBox aboutCard() {
        Label version = Fx.label("PetusLauncher " + ru.petus.launcher.Bootstrap.version(), "nav-title");
        Label note = Fx.label("Серверы PetusCreate и PetusMC · вход через PetusID", "faint");
        VBox card = new VBox(6, version, note);
        card.getStyleClass().add("card");
        return card;
    }
}

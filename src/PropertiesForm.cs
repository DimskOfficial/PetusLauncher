namespace PetusLauncher;

// Свойства окно for a game: tabbed dialog (Общие / Обновления / Версии /
// Проверка целостности / Информация). Opened from the right-click menu or the
// Свойства button. Kept as a normal modal Form so it can float above the
// launcher without fighting the in-window modal overlay.
internal class PropertiesForm : Form
{
    readonly GameDef _g;

    public PropertiesForm(GameDef g)
    {
        _g = g;
        Text = $"Свойства — {g.Name}";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterParent;
        MaximizeBox = false; MinimizeBox = false;
        Size = new Size(520, 420);
        BackColor = Theme.Bg;
        Font = new Font(Theme.FontName, 9);
        try { Icon = new Icon(Path.Combine(AppContext.BaseDirectory, "assets", "icon.ico")); } catch { }

        var tabs = new TabControl { Dock = DockStyle.Fill, Padding = new Point(12, 4) };
        tabs.TabPages.Add(BuildGeneral());
        tabs.TabPages.Add(BuildUpdates());
        if (_g.Type == "mc") tabs.TabPages.Add(BuildVersions());
        if (_g.HashCheck) tabs.TabPages.Add(BuildIntegrity());
        tabs.TabPages.Add(BuildInfo());
        Controls.Add(tabs);
    }

    TabPage Page(string title) => new TabPage(title) { BackColor = Theme.Panel, Padding = new Padding(14) };

    Label Head(string t) => new Label { Text = t, Font = new Font(Theme.FontName, 11, FontStyle.Bold), ForeColor = Theme.Title, AutoSize = true, Location = new Point(14, 12) };

    // ---- Общие ----
    TabPage BuildGeneral()
    {
        var p = Page("Общие");
        p.Controls.Add(Head(_g.Name));
        var desc = new Label { Text = _g.Description, ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9), AutoSize = false, Location = new Point(14, 44), Size = new Size(464, 90) };
        p.Controls.Add(desc);

        int y = 144;
        var playBtn = Theme.MakeButton("Играть", true);
        playBtn.Location = new Point(14, y);
        playBtn.Click += (_, _) => { Tag = "play"; Close(); };
        var folderBtn = Theme.MakeButton("Локальные файлы", false);
        folderBtn.Location = new Point(playBtn.Right + 8, y + 3);
        folderBtn.Click += (_, _) => GameOps.OpenFolder(_g);
        var shortcutBtn = Theme.MakeButton("Ярлык на рабочий стол", false);
        shortcutBtn.Location = new Point(folderBtn.Right + 8, y + 3);
        shortcutBtn.Click += (_, _) => { try { GameOps.CreateDesktopShortcut(_g); MessageBox.Show("Ярлык создан.", "PetusLauncher"); } catch (Exception ex) { MessageBox.Show(ex.Message); } };
        p.Controls.AddRange(new Control[] { playBtn, folderBtn, shortcutBtn });

        y += 46;
        var backupBtn = Theme.MakeButton("Создать резервную копию", false);
        backupBtn.Location = new Point(14, y);
        backupBtn.Click += (_, _) =>
        {
            try { var z = GameOps.Backup(_g); MessageBox.Show("Резервная копия создана:\n" + z, "PetusLauncher"); }
            catch (Exception ex) { MessageBox.Show(ex.Message, "PetusLauncher"); }
        };
        var deleteBtn = Theme.MakeButton("Удалить с устройства", false);
        deleteBtn.BackColor = Color.FromArgb(0xC0, 0x39, 0x2B);
        deleteBtn.FlatAppearance.BorderColor = Color.FromArgb(0x8F, 0x2A, 0x20);
        deleteBtn.Location = new Point(backupBtn.Right + 8, y);
        deleteBtn.Click += (_, _) =>
        {
            if (MessageBox.Show($"Удалить {_g.Name} с устройства? Файлы будут стёрты.", "PetusLauncher", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) == DialogResult.Yes)
            {
                try { GameOps.DeleteInstall(_g); MessageBox.Show("Игра удалена.", "PetusLauncher"); Tag = "deleted"; Close(); }
                catch (Exception ex) { MessageBox.Show(ex.Message, "PetusLauncher"); }
            }
        };
        p.Controls.AddRange(new Control[] { backupBtn, deleteBtn });
        return p;
    }

    // ---- Обновления ----
    TabPage BuildUpdates()
    {
        var p = Page("Обновления");
        p.Controls.Add(Head("История обновлений"));
        var list = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true, Location = new Point(14, 46), Size = new Size(464, 300) };
        foreach (var u in _g.Changelog)
        {
            var row = new Label
            {
                Text = $"v{u.Version} — {u.Notes}",
                ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9),
                AutoSize = false, Width = 440, Height = 40,
                BorderStyle = BorderStyle.None,
            };
            list.Controls.Add(row);
        }
        if (_g.Type == "gdps")
        {
            var upBtn = Theme.MakeButton("Проверить обновления", false);
            upBtn.Click += async (_, _) =>
            {
                upBtn.Enabled = false; upBtn.Text = "Проверка…";
                try { await Updater.EnsureUpToDateAsync((_, _) => { }); upBtn.Text = "Актуально"; }
                catch (Exception ex) { MessageBox.Show(ex.Message); upBtn.Text = "Проверить обновления"; upBtn.Enabled = true; }
            };
            list.Controls.Add(upBtn);
        }
        p.Controls.Add(list);
        return p;
    }

    // ---- Версии игры (MC) ----
    TabPage BuildVersions()
    {
        var p = Page("Версии");
        p.Controls.Add(Head("Версия клиента Minecraft"));
        var hint = new Label { Text = $"Сервер работает на {Config.McServerVersion}. Рекомендуется та же версия клиента.", ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 9), AutoSize = false, Location = new Point(14, 44), Size = new Size(464, 34) };
        p.Controls.Add(hint);

        var box = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 200, Location = new Point(14, 84), Font = new Font(Theme.FontName, 10) };
        box.Items.Add("Загрузка…"); box.SelectedIndex = 0;
        p.Controls.Add(box);
        _ = LoadVersions(box);

        var ramLbl = new Label { Text = "Память (ОЗУ), МБ:", ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9), AutoSize = true, Location = new Point(14, 128) };
        var ram = new NumericUpDown { Minimum = 1024, Maximum = 16384, Increment = 512, Value = Math.Clamp(LauncherSettings.McRamMb, 1024, 16384), Width = 100, Location = new Point(140, 124), Font = new Font(Theme.FontName, 9) };
        p.Controls.AddRange(new Control[] { ramLbl, ram });

        var save = Theme.MakeButton("Сохранить", true);
        save.Location = new Point(14, 168);
        save.Click += (_, _) =>
        {
            if (box.SelectedItem is string v && !v.StartsWith("Загрузка")) LauncherSettings.McVersion = v;
            LauncherSettings.McRamMb = (int)ram.Value;
            MessageBox.Show("Настройки сохранены.", "PetusLauncher");
        };
        p.Controls.Add(save);
        return p;
    }

    async Task LoadVersions(ComboBox box)
    {
        try
        {
            var versions = await MinecraftLauncher.GetVersionsAsync();
            if (!box.IsHandleCreated) return;
            box.BeginInvoke(() =>
            {
                box.Items.Clear();
                foreach (var v in versions) box.Items.Add(v);
                var want = LauncherSettings.McVersion;
                if (string.IsNullOrEmpty(want)) want = Config.McServerVersion;
                int idx = box.Items.IndexOf(want);
                box.SelectedIndex = idx >= 0 ? idx : 0;
            });
        }
        catch { }
    }

    // ---- Проверка целостности (GDPS only) ----
    TabPage BuildIntegrity()
    {
        var p = Page("Целостность");
        p.Controls.Add(Head("Проверка целостности файлов"));
        var hint = new Label { Text = "Сверяет хэши EXE и модов с эталоном после установки. Только для PetusGDPS.", ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 9), AutoSize = false, Location = new Point(14, 44), Size = new Size(464, 34) };
        p.Controls.Add(hint);
        var result = new Label { Text = "", ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9), AutoSize = false, Location = new Point(14, 122), Size = new Size(464, 200) };

        var btn = Theme.MakeButton("Проверить целостность", true);
        btn.Location = new Point(14, 84);
        btn.Click += (_, _) =>
        {
            var r = Stats.Verify(_g.Id, GameOps.InstallDir(_g));
            if (r.NoSnapshot) result.Text = "Игра ещё не установлена — нечего проверять.";
            else if (r.Ok) { result.ForeColor = Color.FromArgb(0x4A, 0x7D, 0x3A); result.Text = "✔ Проверка пройдена: файлы игры не изменены."; }
            else { result.ForeColor = Color.FromArgb(0xC0, 0x39, 0x2B); result.Text = "⚠ Изменены файлы:\n" + string.Join("\n", r.Changed) + "\n\nПереустанови игру."; }
        };
        var folder = Theme.MakeButton("Логи", false);
        folder.Location = new Point(btn.Right + 8, 87);
        folder.Click += (_, _) => GameOps.OpenSubfolder(_g, Path.Combine("geode", "logs"));
        p.Controls.AddRange(new Control[] { btn, folder, result });
        return p;
    }

    // ---- Информация ----
    TabPage BuildInfo()
    {
        var p = Page("Информация");
        p.Controls.Add(Head("Информация об установке"));
        var st = Stats.Get(_g.Id);
        var dir = GameOps.InstallDir(_g);
        bool installed = GameOps.IsInstalled(_g);
        long size = st.SizeBytes > 0 ? st.SizeBytes : 0;

        string txt =
            $"Название:  {_g.Name}\n" +
            $"Тип:  {(_g.Type == "mc" ? "Minecraft" : "Geometry Dash")}\n" +
            $"Установлена:  {(installed ? "да" : "нет")}\n" +
            $"Папка:  {dir}\n" +
            $"Размер:  {FmtSize(size)}\n" +
            $"Наиграно:  {FmtDuration(st.PlaySeconds)}\n" +
            $"Последний запуск:  {FmtDate(st.LastPlayed)}\n" +
            (_g.Type == "mc" ? $"Версия клиента:  {(string.IsNullOrEmpty(LauncherSettings.McVersion) ? Config.McServerVersion : LauncherSettings.McVersion)}\n" : "") +
            (_g.Ip != null ? $"IP сервера:  {_g.Ip}\n" : "");

        var lbl = new Label { Text = txt, ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9), AutoSize = false, Location = new Point(14, 46), Size = new Size(474, 240) };
        p.Controls.Add(lbl);

        var logs = Theme.MakeButton("Крашлоги", false);
        logs.Location = new Point(14, 300);
        logs.Click += (_, _) => GameOps.OpenSubfolder(_g, _g.Type == "mc" ? "crash-reports" : Path.Combine("geode", "crashlogs"));
        p.Controls.Add(logs);
        return p;
    }

    static string FmtSize(long bytes)
    {
        if (bytes <= 0) return "—";
        double mb = bytes / (1024.0 * 1024.0);
        return mb >= 1024 ? $"{mb / 1024:0.00} ГБ" : $"{mb:0} МБ";
    }
    static string FmtDuration(long sec)
    {
        if (sec <= 0) return "0 мин";
        long h = sec / 3600, m = (sec % 3600) / 60;
        return h > 0 ? $"{h} ч {m} мин" : $"{m} мин";
    }
    static readonly string[] MonthsAbbr = { "янв.", "февр.", "мар.", "апр.", "мая", "июн.", "июл.", "авг.", "сент.", "окт.", "нояб.", "дек." };
    static string FmtDate(long ms)
    {
        if (ms <= 0) return "никогда";
        var d = DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime;
        var m = MonthsAbbr[d.Month - 1];
        return d.Year == DateTime.Now.Year ? $"{d.Day} {m}" : $"{d.Day} {m} {d.Year} г.";
    }
}

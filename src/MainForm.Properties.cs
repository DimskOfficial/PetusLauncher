using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;

namespace PetusLauncher;

// Свойства as a draggable, launcher-skinned in-window dialog (LauncherDialog)
// instead of a Windows Form. A custom flat tab strip switches between panels:
// Общие / Обновления / Версии (MC) / Проверка целостности (GDPS) / Информация.
public partial class MainForm
{
    void ShowProperties(GameDef g)
    {
        var d = new LauncherDialog($"Свойства — {g.Name}", 560, 400);
        var body = d.Body;

        // Custom tab strip along the top; content panel fills the rest.
        var tabBar = new Panel { Dock = DockStyle.Top, Height = 34, BackColor = Theme.PanelHead2 };
        tabBar.Paint += (s, e) =>
        {
            using var pen = new Pen(Theme.Border);
            e.Graphics.DrawLine(pen, 0, tabBar.Height - 1, tabBar.Width, tabBar.Height - 1);
        };
        var content = new Panel { Dock = DockStyle.Fill, BackColor = Theme.Panel };
        body.Controls.Add(content);
        body.Controls.Add(tabBar);

        var tabs = new List<(string title, Func<Control> build)>
        {
            ("Общие", () => BuildGeneralTab(g, d)),
            ("Обновления", () => BuildUpdatesTab(g)),
        };
        if (g.Type == "mc") tabs.Add(("Версии", () => BuildVersionsTab(g)));
        if (g.HashCheck) tabs.Add(("Целостность", () => BuildIntegrityTab(g)));
        tabs.Add(("Информация", () => BuildInfoTab(g)));

        var tabButtons = new List<Button>();
        int tx = 8;
        void select(int idx)
        {
            content.Controls.Clear();
            var c = tabs[idx].build();
            c.Dock = DockStyle.Fill;
            content.Controls.Add(c);
            for (int i = 0; i < tabButtons.Count; i++)
            {
                bool on = i == idx;
                tabButtons[i].ForeColor = on ? Theme.Title : Theme.Muted;
                tabButtons[i].Font = new Font(Theme.FontName, 9, on ? FontStyle.Bold : FontStyle.Regular);
                tabButtons[i].BackColor = on ? Theme.Panel : Theme.PanelHead2;
            }
        }
        for (int i = 0; i < tabs.Count; i++)
        {
            int idx = i;
            int w = TextRenderer.MeasureText(tabs[i].title, new Font(Theme.FontName, 9, FontStyle.Bold)).Width + 22;
            var tb = new Button
            {
                Text = tabs[i].title,
                FlatStyle = FlatStyle.Flat,
                Font = new Font(Theme.FontName, 9),
                ForeColor = Theme.Muted,
                BackColor = Theme.PanelHead2,
                Location = new Point(tx, 4),
                Size = new Size(w, 28),
                Cursor = Cursors.Hand,
                TabStop = false,
            };
            tb.FlatAppearance.BorderSize = 0;
            tb.Click += (_, _) => select(idx);
            tabBar.Controls.Add(tb);
            tabButtons.Add(tb);
            tx += w + 2;
        }

        select(0);
        ShowLauncherDialog(d);
    }

    // ---- shared building blocks ----
    Label PropHead(string t) => new Label { Text = t, Font = new Font(Theme.FontName, 11, FontStyle.Bold), ForeColor = Theme.Title, AutoSize = true, Location = new Point(16, 14) };

    // ---- Общие: vertical action buttons ----
    Control BuildGeneralTab(GameDef g, LauncherDialog d)
    {
        var p = new Panel { BackColor = Theme.Panel, Padding = new Padding(4) };
        p.Controls.Add(PropHead(g.Name));

        int y = 52;
        Button stacked(string text, bool primary, Color? bg = null)
        {
            var b = Theme.MakeButton(text, primary);
            b.Width = 260;
            b.Location = new Point(16, y);
            if (bg is { } c)
            {
                b.BackColor = c;
                b.FlatAppearance.BorderColor = ControlPaint.Dark(c, 0.1f);
            }
            y += b.Height + 8;
            p.Controls.Add(b);
            return b;
        }

        var play = stacked("Играть", true);
        play.Click += (_, _) => { d.Close(); StartGame(g); };
        var files = stacked("Локальные файлы", false);
        files.Click += (_, _) => GameOps.OpenFolder(g);
        var shortcut = stacked("Ярлык на рабочий стол", false);
        shortcut.Click += (_, _) => { try { GameOps.CreateDesktopShortcut(g); MessageBox.Show("Ярлык создан.", "PetusLauncher"); } catch (Exception ex) { MessageBox.Show(ex.Message); } };
        var backup = stacked("Резервная копия", false);
        backup.Click += (_, _) =>
        {
            try { var z = GameOps.Backup(g); MessageBox.Show("Резервная копия создана:\n" + z, "PetusLauncher"); }
            catch (Exception ex) { MessageBox.Show(ex.Message, "PetusLauncher"); }
        };
        var del = stacked("Удалить с устройства", false, Color.FromArgb(0xC0, 0x39, 0x2B));
        del.Click += (_, _) =>
        {
            if (MessageBox.Show($"Удалить {g.Name} с устройства? Файлы будут стёрты.", "PetusLauncher", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) == DialogResult.Yes)
            {
                try { GameOps.DeleteInstall(g); MessageBox.Show("Игра удалена.", "PetusLauncher"); d.Close(); ShowGame(g.Id); }
                catch (Exception ex) { MessageBox.Show(ex.Message, "PetusLauncher"); }
            }
        };
        return p;
    }

    // ---- Обновления: styled rows ----
    Control BuildUpdatesTab(GameDef g)
    {
        var p = new Panel { BackColor = Theme.Panel, Padding = new Padding(4) };
        p.Controls.Add(PropHead("История обновлений"));
        var accent = g.Type == "gdps" ? Color.FromArgb(0x7B, 0x61, 0xFF) : Theme.Green2;
        var list = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true, Location = new Point(16, 46), Size = new Size(510, 300), BackColor = Theme.Panel };
        foreach (var u in g.Changelog)
        {
            var uu = u;
            var row = new Panel { Width = 486, Height = 46, BackColor = Theme.Panel, Margin = new Padding(0, 0, 0, 4) };
            var chip = VersionChip(uu.Version, accent);
            chip.Location = new Point(2, 4);
            var note = new Label
            {
                Text = uu.Notes,
                ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9),
                AutoSize = false, Location = new Point(2, 24), Size = new Size(478, 20),
                TextAlign = ContentAlignment.MiddleLeft,
            };
            var sep = new Panel { BackColor = Theme.BorderLight, Location = new Point(2, 45), Size = new Size(478, 1) };
            row.Controls.Add(chip); row.Controls.Add(note); row.Controls.Add(sep);
            list.Controls.Add(row);
        }
        p.Controls.Add(list);
        return p;
    }

    // ---- Версии (MC): version dropdown + RAM slider ----
    Control BuildVersionsTab(GameDef g)
    {
        var p = new Panel { BackColor = Theme.Panel, Padding = new Padding(4) };
        p.Controls.Add(PropHead("Версия клиента Minecraft"));
        var hint = new Label { Text = $"Сервер работает на {Config.McServerVersion}. Рекомендуется та же версия клиента.", ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 9), AutoSize = false, Location = new Point(16, 44), Size = new Size(510, 20) };
        p.Controls.Add(hint);

        var box = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 240, Location = new Point(16, 74), Font = new Font(Theme.FontName, 10) };
        box.Items.Add("Загрузка…"); box.SelectedIndex = 0;
        p.Controls.Add(box);
        _ = LoadPropVersions(box);

        // RAM slider: 1024 .. total physical RAM (MB). Default persisted value.
        int totalMb = (int)Math.Min(int.MaxValue, TotalPhysicalMemoryMb());
        int maxMb = Math.Max(2048, totalMb);
        var ramTitle = new Label { Text = "Выделенная память (ОЗУ):", ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9), AutoSize = true, Location = new Point(16, 118) };
        var ramVal = new Label { Text = "", ForeColor = Theme.Title, Font = new Font(Theme.FontName, 10, FontStyle.Bold), AutoSize = true, Location = new Point(200, 117) };
        var slider = new TrackBar
        {
            Minimum = 1024, Maximum = maxMb, TickFrequency = 1024, SmallChange = 256, LargeChange = 1024,
            Location = new Point(14, 142), Width = 512, TickStyle = TickStyle.BottomRight,
            Value = Math.Clamp(LauncherSettings.McRamMb, 1024, maxMb),
        };
        var presets = new Label
        {
            Text = "Подсказка: 4096 МБ — рекомендуется · 8192 МБ — для сборок с модами",
            ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 8), AutoSize = true, Location = new Point(16, 192),
        };
        // Snap to the nearest 256 MB for tidy values.
        void refreshVal() => ramVal.Text = $"Память: {slider.Value} МБ ({slider.Value / 1024.0:0.0} ГБ)";
        slider.ValueChanged += (_, _) =>
        {
            int snapped = (int)(Math.Round(slider.Value / 256.0) * 256);
            snapped = Math.Clamp(snapped, slider.Minimum, slider.Maximum);
            if (snapped != slider.Value) { slider.Value = snapped; return; }
            refreshVal();
        };
        refreshVal();
        p.Controls.AddRange(new Control[] { ramTitle, ramVal, slider, presets });

        var save = Theme.MakeButton("Сохранить", true);
        save.Location = new Point(16, 220);
        save.Click += (_, _) =>
        {
            if (box.SelectedItem is string v && !v.StartsWith("Загрузка"))
                LauncherSettings.McVersion = StripRecommended(v);
            LauncherSettings.McRamMb = slider.Value;
            MessageBox.Show("Настройки сохранены.", "PetusLauncher");
        };
        p.Controls.Add(save);
        return p;
    }

    static string StripRecommended(string label)
    {
        int i = label.IndexOf(" (", StringComparison.Ordinal);
        return i > 0 ? label[..i] : label;
    }

    async Task LoadPropVersions(ComboBox box)
    {
        try
        {
            var versions = await MinecraftLauncher.GetVersionsAsync();
            if (!box.IsHandleCreated) return;
            box.BeginInvoke(() =>
            {
                box.Items.Clear();
                foreach (var v in versions)
                    box.Items.Add(v == Config.McServerVersion ? $"{v} (Рекомендуемое)" : v);
                var want = LauncherSettings.McVersion;
                if (string.IsNullOrEmpty(want)) want = Config.McServerVersion;
                int idx = -1;
                for (int i = 0; i < box.Items.Count; i++)
                    if (StripRecommended((string)box.Items[i]!) == want) { idx = i; break; }
                box.SelectedIndex = idx >= 0 ? idx : 0;
            });
        }
        catch { }
    }

    // ---- Проверка целостности (GDPS): real progress bar ----
    Control BuildIntegrityTab(GameDef g)
    {
        var p = new Panel { BackColor = Theme.Panel, Padding = new Padding(4) };
        p.Controls.Add(PropHead("Проверка целостности файлов"));
        var hint = new Label { Text = "Сверяет хэши EXE и модов с эталоном после установки. Только для PetusGDPS.", ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 9), AutoSize = false, Location = new Point(16, 44), Size = new Size(510, 20) };
        p.Controls.Add(hint);

        var bar = new ProgressBar { Style = ProgressBarStyle.Continuous, Maximum = 1000, Location = new Point(16, 118), Size = new Size(510, 14), Visible = false };
        var result = new Label { Text = "", ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9), AutoSize = false, Location = new Point(16, 144), Size = new Size(510, 190) };

        var btn = Theme.MakeButton("Проверить целостность", true);
        btn.Location = new Point(16, 78);
        btn.Click += (_, _) =>
        {
            btn.Enabled = false;
            bar.Visible = true; bar.Value = 0;
            result.ForeColor = Theme.Text; result.Text = "Проверка…";
            var dir = GameOps.InstallDir(g);
            // Hash on a background thread, marshal progress + result back to the UI.
            System.Threading.Tasks.Task.Run(() =>
            {
                var r = Stats.VerifyWithProgress(g.Id, dir, (done, total) =>
                {
                    if (bar.IsHandleCreated)
                        bar.BeginInvoke(() => bar.Value = total == 0 ? 1000 : Math.Min(1000, done * 1000 / total));
                });
                if (!p.IsHandleCreated) return;
                p.BeginInvoke(() =>
                {
                    bar.Visible = false;
                    btn.Enabled = true;
                    if (r.NoSnapshot) { result.ForeColor = Theme.Muted; result.Text = "Игра ещё не установлена — нечего проверять."; }
                    else if (r.Ok) { result.ForeColor = Color.FromArgb(0x4A, 0x7D, 0x3A); result.Text = "✔ Проверка пройдена: файлы игры не изменены."; }
                    else { result.ForeColor = Color.FromArgb(0xC0, 0x39, 0x2B); result.Text = "⚠ Изменены файлы:\n" + string.Join("\n", r.Changed) + "\n\nПереустанови игру."; }
                });
            });
        };
        var logs = Theme.MakeButton("Логи", false);
        logs.Location = new Point(btn.Right + 8, 81);
        logs.Click += (_, _) => GameOps.OpenSubfolder(g, Path.Combine("geode", "logs"));
        p.Controls.AddRange(new Control[] { btn, logs, bar, result });
        return p;
    }

    // ---- Информация: labelled key→value rows ----
    Control BuildInfoTab(GameDef g)
    {
        var p = new Panel { BackColor = Theme.Panel, Padding = new Padding(4), AutoScroll = true };
        p.Controls.Add(PropHead("Информация об установке"));
        var st = Stats.Get(g.Id);
        var dir = GameOps.InstallDir(g);
        bool installed = GameOps.IsInstalled(g);

        var rows = new List<(string, string)>
        {
            ("Название", g.Name),
            ("Тип", g.Type == "mc" ? "Minecraft" : "Geometry Dash"),
            ("Установлена", installed ? "да" : "нет"),
            ("Папка", dir),
            ("Размер", FmtSize(st.SizeBytes)),
            ("Наиграно", FmtDuration(st.PlaySeconds)),
            ("Последний запуск", FmtDate(st.LastPlayed)),
        };
        if (g.Type == "mc")
            rows.Add(("Версия клиента", string.IsNullOrEmpty(LauncherSettings.McVersion) ? Config.McServerVersion : LauncherSettings.McVersion));
        if (g.Ip != null) rows.Add(("IP сервера", g.Ip));

        int y = 48;
        foreach (var (k, v) in rows)
        {
            var kl = new Label { Text = k, ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 9), AutoSize = false, Location = new Point(16, y), Size = new Size(150, 18), TextAlign = ContentAlignment.MiddleLeft };
            var vl = new Label { Text = v, ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9, FontStyle.Bold), AutoSize = false, Location = new Point(172, y), Size = new Size(356, 18), TextAlign = ContentAlignment.MiddleLeft };
            var sep = new Panel { BackColor = Theme.BorderLight, Location = new Point(16, y + 21), Size = new Size(512, 1) };
            p.Controls.Add(kl); p.Controls.Add(vl); p.Controls.Add(sep);
            y += 26;
        }

        y += 6;
        var crash = Theme.MakeButton("Крашлоги", false);
        crash.Location = new Point(16, y);
        crash.Click += (_, _) => GameOps.OpenSubfolder(g, g.Type == "mc" ? "crash-reports" : Path.Combine("geode", "crashlogs"));
        var logs = Theme.MakeButton("Логи", false);
        logs.Location = new Point(crash.Right + 8, y);
        logs.Click += (_, _) => GameOps.OpenSubfolder(g, g.Type == "mc" ? "logs" : Path.Combine("geode", "logs"));
        p.Controls.AddRange(new Control[] { crash, logs });
        return p;
    }

    // Total physical RAM in MB via GlobalMemoryStatusEx (no VB dependency).
    static ulong TotalPhysicalMemoryMb()
    {
        try
        {
            var stat = new MEMORYSTATUSEX { dwLength = (uint)Marshal.SizeOf<MEMORYSTATUSEX>() };
            if (GlobalMemoryStatusEx(ref stat)) return stat.ullTotalPhys / (1024 * 1024);
        }
        catch { }
        return 8192; // sane fallback
    }

    [StructLayout(LayoutKind.Sequential)]
    struct MEMORYSTATUSEX
    {
        public uint dwLength;
        public uint dwMemoryLoad;
        public ulong ullTotalPhys;
        public ulong ullAvailPhys;
        public ulong ullTotalPageFile;
        public ulong ullAvailPageFile;
        public ulong ullTotalVirtual;
        public ulong ullAvailVirtual;
        public ulong ullAvailExtendedVirtual;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    static extern bool GlobalMemoryStatusEx(ref MEMORYSTATUSEX lpBuffer);
}

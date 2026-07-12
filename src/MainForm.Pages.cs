namespace PetusLauncher;

// Page rendering: login gate, game store pages, and the in-window modal.
public partial class MainForm
{
    Panel? _modalOverlay;

    // ---------------- properties + management ----------------
    void ShowProperties(GameDef g)
    {
        using var pf = new PropertiesForm(g);
        pf.ShowDialog(this);
        if (pf.Tag as string == "play") { StartGame(g); }
        else if (pf.Tag as string == "deleted") { ShowGame(g.Id); }
    }

    // Central "play/install" entry so the context menu and Properties both use it.
    void StartGame(GameDef g)
    {
        ShowGame(g.Id); // ensure the page is visible, then trigger its main button
        var main = _content.Controls.OfType<Button>().FirstOrDefault(b => b.Text is "Играть" or "Установить игру");
        main?.PerformClick();
    }

    // Right-click context menu for a game (sidebar button or page).
    ContextMenuStrip BuildGameContextMenu(GameDef g)
    {
        var menu = new ContextMenuStrip { Font = new Font(Theme.FontName, 9) };
        menu.Items.Add("Играть", null, (_, _) => StartGame(g));

        var manage = new ToolStripMenuItem("Управление");
        manage.DropDownItems.Add("Удалить с устройства", null, (_, _) =>
        {
            if (MessageBox.Show($"Удалить {g.Name} с устройства?", "PetusLauncher", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) == DialogResult.Yes)
            {
                try { GameOps.DeleteInstall(g); ShowGame(g.Id); } catch (Exception ex) { MessageBox.Show(ex.Message); }
            }
        });
        manage.DropDownItems.Add("Просмотреть локальные файлы", null, (_, _) => GameOps.OpenFolder(g));
        manage.DropDownItems.Add("Создать ярлык на рабочем столе", null, (_, _) =>
        { try { GameOps.CreateDesktopShortcut(g); MessageBox.Show("Ярлык создан.", "PetusLauncher"); } catch (Exception ex) { MessageBox.Show(ex.Message); } });
        manage.DropDownItems.Add("Создать резервную копию", null, (_, _) =>
        { try { var z = GameOps.Backup(g); MessageBox.Show("Резервная копия:\n" + z, "PetusLauncher"); } catch (Exception ex) { MessageBox.Show(ex.Message); } });
        menu.Items.Add(manage);

        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Свойства", null, (_, _) => ShowProperties(g));
        return menu;
    }

    // ---------------- settings (theme) ----------------
    void ShowSettings()
    {
        var host = new Panel { Dock = DockStyle.Fill, BackColor = Theme.Panel, Padding = new Padding(16) };
        var lbl = new Label { Text = "Тема оформления лаунчера:", ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9), AutoSize = true, Location = new Point(16, 14) };
        var box = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 200, Location = new Point(16, 40), Font = new Font(Theme.FontName, 10) };
        box.Items.AddRange(new object[] { "Светлая (ВК 2010)", "Тёмная" });
        box.SelectedIndex = LauncherSettings.ThemeName == "dark" ? 1 : 0;

        var apply = Theme.MakeButton("Применить", true);
        apply.Location = new Point(16, 82);
        apply.Click += (_, _) =>
        {
            LauncherSettings.ThemeName = box.SelectedIndex == 1 ? "dark" : "light";
            _modalOverlay?.Dispose(); _modalOverlay = null;
            ReapplyTheme();
        };
        host.Controls.AddRange(new Control[] { lbl, box, apply });
        ShowModalControl("Настройки", host, 320, 170);
    }

    // Re-read the palette everywhere after a theme switch.
    void ReapplyTheme()
    {
        BackColor = Theme.Bg;
        _content.BackColor = Theme.Bg;
        _sidebar.BackColor = Theme.SidebarBg;
        _settingsBtn.BackColor = Theme.SidebarBg;
        _settingsBtn.ForeColor = Theme.Link;
        _titleBar.Invalidate();
        _sidebar.Invalidate();
        BuildSidebar();
        HighlightSidebar();
        if (_auth != null && _currentGameId != "") ShowGame(_currentGameId);
        else if (_auth == null) ShowLogin();
    }

    void ClearContent()
    {
        foreach (Control c in _content.Controls) c.Dispose();
        _content.Controls.Clear();
    }

    // ---------------- login ----------------
    void ShowLogin()
    {
        ClearContent();
        var card = new Panel
        {
            Width = 340,
            Height = 240,
            BackColor = Theme.Panel,
            BorderStyle = BorderStyle.FixedSingle,
        };
        var logo = new Label
        {
            Text = "▲",
            ForeColor = Color.White,
            BackColor = Color.FromArgb(0x7B, 0x61, 0xFF),
            Font = new Font(Theme.FontName, 22, FontStyle.Bold),
            TextAlign = ContentAlignment.MiddleCenter,
            Size = new Size(60, 60),
            Location = new Point(140, 22),
        };
        var title = new Label
        {
            Text = "Вход в Петус",
            ForeColor = Theme.Title,
            Font = new Font(Theme.FontName, 14, FontStyle.Bold),
            TextAlign = ContentAlignment.MiddleCenter,
            AutoSize = false,
            Size = new Size(340, 26),
            Location = new Point(0, 92),
        };
        var sub = new Label
        {
            Text = "Войди через Petus ID, чтобы играть.\nРегистрация и пароли не нужны.",
            ForeColor = Theme.Muted,
            Font = new Font(Theme.FontName, 9),
            TextAlign = ContentAlignment.MiddleCenter,
            AutoSize = false,
            Size = new Size(340, 40),
            Location = new Point(0, 120),
        };
        var btn = Theme.MakeButton("Войти через Petus ID", true, 10);
        btn.Location = new Point((340 - btn.Width) / 2, 175);
        btn.Click += async (_, _) =>
        {
            btn.Enabled = false;
            btn.Text = "Открыт браузер — войди там…";
            try
            {
                _auth = await Auth.LoginAsync();
                RenderAuthState();
            }
            catch (Exception ex)
            {
                if (!ex.Message.Contains("timeout"))
                    MessageBox.Show("Не удалось войти: " + ex.Message, "PetusLauncher");
                btn.Text = "Войти через Petus ID";
                btn.Enabled = true;
            }
        };
        card.Controls.AddRange(new Control[] { logo, title, sub, btn });

        _content.Controls.Add(card);
        void center()
        {
            card.Location = new Point(
                Math.Max(0, (_content.ClientSize.Width - card.Width) / 2),
                Math.Max(0, (_content.ClientSize.Height - card.Height) / 2));
        }
        center();
        _content.Resize -= _centerHandler;
        _centerHandler = (_, _) => center();
        _content.Resize += _centerHandler;
    }
    EventHandler _centerHandler = (_, _) => { };

    // ---------------- game pages ----------------
    void ShowGame(string id)
    {
        _currentGameId = id;
        _content.Resize -= _centerHandler;
        HighlightSidebar();
        var g = Games.All.FirstOrDefault(x => x.Id == id);
        if (g == null) return;
        ClearContent();

        int y = 0;
        // Banner
        var banner = new Panel { Location = new Point(0, y), Height = 150, Width = _content.ClientSize.Width - 20, Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
        var (bl, br) = g.Type == "gdps"
            ? (Color.FromArgb(0x3A, 0x2C, 0x74), Color.FromArgb(0x7B, 0x61, 0xFF))
            : (Color.FromArgb(0x24, 0x4A, 0x24), Color.FromArgb(0x5F, 0x91, 0x40));
        banner.Paint += (_, e) =>
        {
            Theme.PaintHGradient(e.Graphics, banner.ClientRectangle, bl, br);
            using var tf = new Font(Theme.FontName, 20, FontStyle.Bold);
            using var sf = new Font(Theme.FontName, 10);
            e.Graphics.DrawString(g.Name, tf, Brushes.White, 22, 92);
            e.Graphics.DrawString(g.Tagline, sf, Brushes.White, 24, 124);
        };
        _content.Controls.Add(banner);
        y += 150;

        if (g.Type == "gdps") RenderGdps(g, ref y);
        else RenderMc(g, ref y);
    }

    Panel MakeCard(string title, int width, int y, out Panel body)
    {
        var card = new Panel { Location = new Point(24, y), Width = width, BackColor = Theme.Panel, BorderStyle = BorderStyle.FixedSingle, Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
        var head = new Label
        {
            Text = title,
            Dock = DockStyle.Top,
            Height = 26,
            Font = new Font(Theme.FontName, 9, FontStyle.Bold),
            ForeColor = Theme.Title,
            TextAlign = ContentAlignment.MiddleLeft,
            Padding = new Padding(10, 0, 0, 0),
        };
        head.Paint += (s, e) => Theme.PaintVGradient(e.Graphics, head.ClientRectangle, Theme.PanelHead1, Theme.PanelHead2);
        head.Click += (s, e) => { };
        body = new Panel { Dock = DockStyle.Fill, BackColor = Theme.Panel, AutoSize = true };
        card.Controls.Add(body);
        card.Controls.Add(head);
        return card;
    }

    void RenderGdps(GameDef g, ref int y)
    {
        int cw = _content.ClientSize.Width - 48;
        var st = Stats.Get(g.Id);
        bool installed = Updater.IsInstalled();

        // action row — folder/integrity moved into Свойства (right-click the game).
        y += 14;
        var mainBtn = Theme.MakeButton(installed ? "Играть" : "Установить игру", true, 11);
        mainBtn.Location = new Point(24, y);

        var status = new Label { ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 9), AutoSize = true, Text = installed ? "Готов к запуску" : "Игра не установлена" };
        var progress = new ProgressBar { Style = ProgressBarStyle.Continuous, Width = 360, Height = 14, Visible = false, Maximum = 1000 };

        mainBtn.Click += async (_, _) =>
        {
            mainBtn.Enabled = false;
            try
            {
                await Updater.EnsureUpToDateAsync((stage, frac) => BeginInvoke(() =>
                {
                    var TXT = new Dictionary<string, string> { ["check"] = "Проверка…", ["download"] = "Загрузка…", ["install"] = "Установка…", ["ready"] = "Готово", ["uptodate"] = "Актуальная версия" };
                    status.Text = TXT.GetValueOrDefault(stage, stage);
                    if (stage == "download") { progress.Visible = true; progress.Value = Math.Min(1000, (int)(frac * 1000)); }
                    else if (stage is "ready" or "uptodate") progress.Visible = false;
                }));
                Stats.UpdateSize(g.Id, Config.GameDir);
                Stats.Snapshot(g.Id, Config.GameDir);
                GameLauncher.Launch(_auth!);
                status.Text = "Игра запущена!";
                ShowGame(g.Id); // refresh (button becomes Играть, size updates)
            }
            catch (Exception ex)
            {
                status.Text = "Ошибка: " + ex.Message;
                mainBtn.Enabled = true;
            }
        };

        var propBtn = Theme.MakeButton("Свойства", false);
        propBtn.Location = new Point(mainBtn.Right + 10, y + 3);
        propBtn.Click += (_, _) => ShowProperties(g);
        _content.Controls.AddRange(new Control[] { mainBtn, propBtn });
        y += 44;
        status.Location = new Point(24, y); _content.Controls.Add(status); y += 22;
        progress.Location = new Point(24, y); _content.Controls.Add(progress); y += 8;

        // about card
        y += 8;
        var aboutCard = MakeCard("Об игре", cw, y, out var abody);
        var about = new Label { Text = g.Description, ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9), AutoSize = false, Dock = DockStyle.Top, Height = 64, Padding = new Padding(12, 8, 12, 0) };
        abody.Controls.Add(about);
        aboutCard.Height = 26 + 64;
        _content.Controls.Add(aboutCard);
        y += aboutCard.Height + 14;

        // stats card
        var statsCard = MakeCard("Статистика", cw, y, out var sbody);
        var grid = new TableLayoutPanel { ColumnCount = 3, RowCount = 1, Dock = DockStyle.Top, Height = 56, BackColor = Theme.Panel };
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.3f));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.3f));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.3f));
        grid.Controls.Add(StatCell("Наиграно", FmtDuration(st.PlaySeconds)), 0, 0);
        grid.Controls.Add(StatCell("Размер", FmtSize(st.SizeBytes)), 1, 0);
        grid.Controls.Add(StatCell("Последний запуск", FmtDate(st.LastPlayed)), 2, 0);
        sbody.Controls.Add(grid);
        statsCard.Height = 26 + 56;
        _content.Controls.Add(statsCard);
        y += statsCard.Height + 14;

        // updates card
        var upCard = MakeUpdatesCard(g, cw, y);
        _content.Controls.Add(upCard);
        y += upCard.Height + 20;
    }

    void RenderMc(GameDef g, ref int y)
    {
        int cw = _content.ClientSize.Width - 48;
        var st = Stats.Get(g.Id);
        var acc = LauncherSettings.McAccount;

        // action row: Играть + версия + аккаунт
        y += 14;
        var playBtn = Theme.MakeButton("Играть", true, 11);
        playBtn.Location = new Point(24, y);

        var online = new Label
        {
            Text = "● проверка статуса…",
            ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 9),
            AutoSize = true, Location = new Point(playBtn.Right + 14, y + 9),
        };
        _content.Controls.Add(online);
        _ = LoadMcStatus(g.Ip!, online);
        _content.Controls.Add(playBtn);
        y += 44;

        var status = new Label { ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 9), AutoSize = true, Location = new Point(24, y) };
        var progress = new ProgressBar { Style = ProgressBarStyle.Continuous, Width = 360, Height = 14, Visible = false, Maximum = 1000, Location = new Point(24, y + 20) };
        _content.Controls.Add(status);
        _content.Controls.Add(progress);
        y += 46;

        // settings card: версия клиента + аккаунт
        var setCard = MakeCard("Запуск", cw, y, out var setBody);
        var verLbl = new Label { Text = "Версия клиента:", ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9), AutoSize = true, Location = new Point(12, 12) };
        var verBox = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 150, Location = new Point(120, 9), Font = new Font(Theme.FontName, 9) };
        verBox.Items.Add("Загрузка версий…");
        verBox.SelectedIndex = 0;
        var verHint = new Label { Text = $"Сервер работает на {Config.McServerVersion} (рекомендуется)", ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 8), AutoSize = true, Location = new Point(280, 13) };

        var accLbl = new Label { Text = "Аккаунт:", ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9), AutoSize = true, Location = new Point(12, 44) };
        var accName = new Label { Text = acc != null ? $"{acc.Name} ({(acc.Type == "microsoft" ? "Microsoft" : "офлайн")})" : "не выбран", ForeColor = acc != null ? Theme.Title : Theme.Muted, Font = new Font(Theme.FontName, 9, FontStyle.Bold), AutoSize = true, Location = new Point(120, 44) };
        var loginBtn = Theme.MakeButton(acc != null ? "Сменить аккаунт" : "Войти", false);
        loginBtn.Location = new Point(280, 40);
        loginBtn.Click += (_, _) => ShowMcLoginModal(g);

        setBody.Controls.AddRange(new Control[] { verLbl, verBox, verHint, accLbl, accName, loginBtn });
        setCard.Height = 26 + 78;
        _content.Controls.Add(setCard);
        y += setCard.Height + 14;

        // populate versions async
        _ = PopulateMcVersions(verBox);

        playBtn.Click += async (_, _) =>
        {
            var account = LauncherSettings.McAccount;
            if (account == null) { ShowMcLoginModal(g); return; }
            if (verBox.SelectedItem is not string ver || ver.StartsWith("Загрузка")) { status.Text = "Выбери версию."; return; }
            LauncherSettings.McVersion = ver;
            playBtn.Enabled = false; loginBtn.Enabled = false;
            long startedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            try
            {
                // Silently refresh a Microsoft token if it's near expiry so the
                // session stays valid without re-prompting the user.
                if (account.Type == "microsoft" &&
                    account.RefreshExpires - DateTimeOffset.UtcNow.ToUnixTimeSeconds() < 300)
                {
                    status.Text = "Обновление сессии Microsoft…";
                    var refreshed = await MicrosoftAuth.RefreshAsync(account);
                    if (refreshed != null) { account = refreshed; LauncherSettings.McAccount = refreshed; }
                }
                var TXT = new Dictionary<string, string> { ["manifest"] = "Получение манифеста…", ["libraries"] = "Загрузка библиотек…", ["assets"] = "Загрузка ресурсов…", ["java"] = "Загрузка Java…", ["launch"] = "Запуск…" };
                var proc = await MinecraftLauncher.InstallAndLaunchAsync(ver, account, LauncherSettings.McRamMb,
                    (stage, frac) => BeginInvoke(() =>
                    {
                        status.Text = TXT.GetValueOrDefault(stage, stage);
                        progress.Visible = frac > 0 && frac < 1;
                        progress.Value = Math.Min(1000, (int)(frac * 1000));
                    }), g.Ip);
                Stats.SetLastPlayed(g.Id, startedAt);
                Stats.UpdateSize(g.Id, Config.McDir);
                status.Text = "Minecraft запущен!";
                progress.Visible = false;
                proc.Exited += (_, _) =>
                {
                    double secs = (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - startedAt) / 1000.0;
                    Stats.AddPlaytime(g.Id, secs, startedAt);
                    try { BeginInvoke(() => { if (_currentGameId == g.Id) ShowGame(g.Id); }); } catch { }
                };
                ShowGame(g.Id);
            }
            catch (Exception ex)
            {
                status.Text = "Ошибка: " + ex.Message;
                progress.Visible = false;
                playBtn.Enabled = true; loginBtn.Enabled = true;
            }
        };

        // about card
        var about = MakeCard("О сервере", cw, y, out var abody);
        var lbl = new Label
        {
            Text = g.Description + $"\n\nIP: {g.Ip}",
            ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9),
            AutoSize = false, Dock = DockStyle.Top, Height = 96, Padding = new Padding(12, 8, 12, 0),
        };
        abody.Controls.Add(lbl);
        about.Height = 26 + 96;
        _content.Controls.Add(about);
        y += about.Height + 14;

        // stats card (playtime + last launch)
        var statsCard = MakeCard("Статистика", cw, y, out var sbody);
        var grid = new TableLayoutPanel { ColumnCount = 3, RowCount = 1, Dock = DockStyle.Top, Height = 56, BackColor = Theme.Panel };
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.3f));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.3f));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.3f));
        grid.Controls.Add(StatCell("Наиграно", FmtDuration(st.PlaySeconds)), 0, 0);
        grid.Controls.Add(StatCell("Размер", FmtSize(st.SizeBytes)), 1, 0);
        grid.Controls.Add(StatCell("Последний запуск", FmtDate(st.LastPlayed)), 2, 0);
        sbody.Controls.Add(grid);
        statsCard.Height = 26 + 56;
        _content.Controls.Add(statsCard);
        y += statsCard.Height + 14;

        var upCard = MakeUpdatesCard(g, cw, y);
        _content.Controls.Add(upCard);
        y += upCard.Height + 20;
    }

    // Fetch release versions and select the recommended server version by default.
    async Task PopulateMcVersions(ComboBox box)
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
                if (idx < 0) idx = box.Items.IndexOf(Config.McServerVersion);
                box.SelectedIndex = idx >= 0 ? idx : 0;
            });
        }
        catch
        {
            if (box.IsHandleCreated)
                box.BeginInvoke(() => { box.Items.Clear(); box.Items.Add(Config.McServerVersion); box.SelectedIndex = 0; });
        }
    }

    // Login modal: Microsoft (device code) OR offline (just a nickname).
    void ShowMcLoginModal(GameDef g)
    {
        var host = new Panel { Dock = DockStyle.Fill, BackColor = Theme.Panel, Padding = new Padding(16) };

        var msBtn = Theme.MakeButton("Войти через Microsoft", true);
        msBtn.Location = new Point(16, 14); msBtn.Width = 340;

        var or = new Label { Text = "— или офлайн-аккаунт —", ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 9), AutoSize = false, TextAlign = ContentAlignment.MiddleCenter, Width = 340, Location = new Point(16, 58) };

        var nickBox = new TextBox { Font = new Font(Theme.FontName, 11), Width = 220, Location = new Point(16, 86), Text = LauncherSettings.McAccount?.Name ?? "" };
        var offBtn = Theme.MakeButton("Войти", false);
        offBtn.Location = new Point(244, 84);

        var info = new Label { Text = "", ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 9), AutoSize = false, Width = 340, Height = 60, Location = new Point(16, 122) };

        offBtn.Click += (_, _) =>
        {
            var nick = nickBox.Text.Trim();
            if (nick.Length < 3) { info.Text = "Ник должен быть не короче 3 символов."; info.ForeColor = Color.FromArgb(0xC0, 0x39, 0x2B); return; }
            LauncherSettings.McAccount = MicrosoftAuth.OfflineAccount(nick);
            _modalOverlay?.Dispose(); _modalOverlay = null;
            ShowGame(g.Id);
        };

        msBtn.Click += async (_, _) =>
        {
            msBtn.Enabled = false; offBtn.Enabled = false;
            info.ForeColor = Theme.Text;
            try
            {
                var cts = new CancellationTokenSource(TimeSpan.FromMinutes(10));
                var acc = await MicrosoftAuth.LoginAsync((code, uri) => BeginInvoke(() =>
                {
                    info.Text = $"Открой {uri}\nи введи код: {code}";
                    try { Clipboard.SetText(code); } catch { }
                    Auth.OpenBrowser(uri);
                }), cts.Token);
                LauncherSettings.McAccount = acc;
                _modalOverlay?.Dispose(); _modalOverlay = null;
                ShowGame(g.Id);
            }
            catch (Exception ex)
            {
                info.Text = "Не удалось войти: " + ex.Message;
                info.ForeColor = Color.FromArgb(0xC0, 0x39, 0x2B);
                msBtn.Enabled = true; offBtn.Enabled = true;
            }
        };

        host.Controls.AddRange(new Control[] { msBtn, or, nickBox, offBtn, info });
        ShowModalControl("Вход в PetusMC", host, 388, 220);
    }

    // Query the public Minecraft status API and show online / players.
    async Task LoadMcStatus(string ip, Label label)
    {
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(8) };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("PetusLauncher/2.0");
            var json = await http.GetStringAsync($"https://api.mcsrvstat.us/3/{ip}");
            using var doc = System.Text.Json.JsonDocument.Parse(json);
            var root = doc.RootElement;
            bool onlineFlag = root.TryGetProperty("online", out var o) && o.GetBoolean();
            if (!onlineFlag)
            {
                if (label.IsHandleCreated) label.BeginInvoke(() => { label.Text = "● сервер оффлайн"; label.ForeColor = Color.FromArgb(0xC0, 0x39, 0x2B); });
                return;
            }
            int cur = 0, max = 0;
            if (root.TryGetProperty("players", out var p))
            {
                cur = p.TryGetProperty("online", out var pc) ? pc.GetInt32() : 0;
                max = p.TryGetProperty("max", out var pm) ? pm.GetInt32() : 0;
            }
            if (label.IsHandleCreated)
                label.BeginInvoke(() =>
                {
                    label.Text = $"● онлайн · {cur}/{max} игроков";
                    label.ForeColor = Color.FromArgb(0x4A, 0x7D, 0x3A);
                });
        }
        catch
        {
            if (label.IsHandleCreated) label.BeginInvoke(() => { label.Text = "● статус недоступен"; });
        }
    }

    Panel MakeUpdatesCard(GameDef g, int cw, int y)
    {
        var card = MakeCard("Обновления", cw, y, out var body);
        var list = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, WrapContents = false, Dock = DockStyle.Top, AutoSize = true, BackColor = Theme.Panel };
        int rows = 0;
        foreach (var u in g.Changelog)
        {
            var row = new Button
            {
                Text = $"   v{u.Version}    {(u.Notes.Length > 52 ? u.Notes[..52] + "…" : u.Notes)}",
                TextAlign = ContentAlignment.MiddleLeft,
                FlatStyle = FlatStyle.Flat,
                ForeColor = Theme.Text,
                Font = new Font(Theme.FontName, 9),
                Width = cw - 4,
                Height = 30,
                BackColor = Theme.Panel,
                Cursor = Cursors.Hand,
            };
            row.FlatAppearance.BorderSize = 0;
            row.FlatAppearance.MouseOverBackColor = Color.FromArgb(0xEE, 0xF3, 0xF8);
            var uu = u;
            row.Click += (_, _) => ShowModal($"{g.Name} — обновление",
                $"Версия {uu.Version}\n\n{uu.Notes}");
            list.Controls.Add(row);
            rows++;
        }
        body.Controls.Add(list);
        card.Height = 26 + rows * 30 + 2;
        return card;
    }

    Panel StatCell(string label, string value)
    {
        var p = new Panel { Dock = DockStyle.Fill, BackColor = Theme.Panel };
        var v = new Label { Text = value, ForeColor = Theme.Text, Font = new Font(Theme.FontName, 11, FontStyle.Bold), TextAlign = ContentAlignment.MiddleCenter, Dock = DockStyle.Top, Height = 28 };
        var l = new Label { Text = label, ForeColor = Color.FromArgb(0x86, 0x97, 0xA8), Font = new Font(Theme.FontName, 8), TextAlign = ContentAlignment.MiddleCenter, Dock = DockStyle.Top, Height = 18 };
        p.Controls.Add(l); p.Controls.Add(v);
        return p;
    }

    // ---------------- modals ----------------
    void ShowModal(string title, string body)
    {
        var content = new Label
        {
            Text = body,
            ForeColor = Theme.Text,
            Font = new Font(Theme.FontName, 9),
            AutoSize = false,
            Dock = DockStyle.Fill,
            Padding = new Padding(16),
        };
        ShowModalControl(title, content, 380, 200);
    }

    void ShowModalControl(string title, Control body, int w, int h)
    {
        _modalOverlay?.Dispose();
        // Floating, centered card — the page behind stays visible (no full dim
        // that would hide the store page). A soft shadow gives depth.
        _modalOverlay = new Panel { BackColor = Color.Transparent, Width = w + 12, Height = h + 12 };

        var shadow = new Panel
        {
            Bounds = new Rectangle(4, 5, w, h),
            BackColor = Color.FromArgb(30, 0, 0, 0),
        };
        var modal = new Panel
        {
            Bounds = new Rectangle(0, 0, w, h),
            BackColor = Theme.Panel,
            BorderStyle = BorderStyle.FixedSingle,
        };
        var head = new Panel { Dock = DockStyle.Top, Height = 30 };
        head.Paint += (s, e) => Theme.PaintVGradient(e.Graphics, head.ClientRectangle, Theme.PanelHead1, Theme.PanelHead2);
        var htext = new Label { Text = title, ForeColor = Theme.Title, Font = new Font(Theme.FontName, 9, FontStyle.Bold), AutoSize = true, Location = new Point(12, 8), BackColor = Color.Transparent };
        var x = new Button { Text = "✕", FlatStyle = FlatStyle.Flat, ForeColor = Theme.Muted, Size = new Size(30, 30), Dock = DockStyle.Right, Cursor = Cursors.Hand };
        x.FlatAppearance.BorderSize = 0;
        x.Click += (_, _) => { _modalOverlay?.Dispose(); _modalOverlay = null; };
        head.Controls.Add(htext); head.Controls.Add(x);
        modal.Controls.Add(body);
        modal.Controls.Add(head);

        _modalOverlay.Controls.Add(modal);
        _modalOverlay.Controls.Add(shadow);
        shadow.SendToBack();

        Controls.Add(_modalOverlay);
        _modalOverlay.BringToFront();
        CenterModal();
    }

    void CenterModal()
    {
        if (_modalOverlay == null) return;
        int areaX = _sidebar.Visible ? 190 : 0;
        int areaW = ClientSize.Width - areaX;
        int areaH = ClientSize.Height - 40;
        _modalOverlay.Location = new Point(
            areaX + Math.Max(0, (areaW - _modalOverlay.Width) / 2),
            40 + Math.Max(0, (areaH - _modalOverlay.Height) / 2));
    }

    // ---------------- format helpers ----------------
    static string FmtDuration(long sec)
    {
        if (sec <= 0) return "0 мин";
        long h = sec / 3600, m = (sec % 3600) / 60;
        return h > 0 ? $"{h} ч {m} мин" : $"{m} мин";
    }
    static string FmtSize(long bytes)
    {
        if (bytes <= 0) return "—";
        double mb = bytes / (1024.0 * 1024.0);
        return mb >= 1024 ? $"{mb / 1024:0.00} ГБ" : $"{mb:0} МБ";
    }
    static readonly string[] MonthsAbbr =
        { "янв.", "февр.", "мар.", "апр.", "мая", "июн.", "июл.", "авг.", "сент.", "окт.", "нояб.", "дек." };

    // "28 дек. 2025 г." — or "30 июн." when it happened this calendar year.
    static string FmtDate(long ms)
    {
        if (ms <= 0) return "никогда";
        var d = DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime;
        var m = MonthsAbbr[d.Month - 1];
        return d.Year == DateTime.Now.Year
            ? $"{d.Day} {m}"
            : $"{d.Day} {m} {d.Year} г.";
    }
}

using System.Drawing.Drawing2D;

namespace PetusLauncher;

// Page rendering: login gate, game store pages, and the in-window modal.
public partial class MainForm
{
    Panel? _modalOverlay;

    // Central "play/install" entry so the context menu and Properties both use it.
    void StartGame(GameDef g)
    {
        ShowGame(g.Id); // ensure the page is visible, then trigger its main button
        var main = _content.Controls.OfType<Button>()
            .FirstOrDefault(b => b.Text is "Играть" or "Установить" or "Обновить");
        main?.PerformClick();
    }

    // Right-click context menu for a game (sidebar button or page).
    ContextMenuStrip BuildGameContextMenu(GameDef g)
    {
        var menu = new ContextMenuStrip { Font = new Font(Theme.FontName, 9) };

        // The primary action reads green, like the site's play button.
        var play = new ToolStripMenuItem("Играть")
        {
            ForeColor = Theme.Green2,
            Font = new Font(Theme.FontName, 9, FontStyle.Bold),
        };
        play.Click += (_, _) => StartGame(g);
        menu.Items.Add(play);

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

    // ---------------- settings (theme + self-update) ----------------
    void ShowSettings()
    {
        var d = new LauncherDialog("Настройки", 400, 316);
        var body = d.Body;

        var themeLbl = new Label { Text = "Тема оформления лаунчера:", ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9), AutoSize = true, Location = new Point(18, 16) };
        var box = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 220, Location = new Point(18, 40), Font = new Font(Theme.FontName, 10) };
        box.Items.AddRange(new object[] { "Светлая (ВК 2010)", "Тёмная" });
        box.SelectedIndex = LauncherSettings.ThemeName == "dark" ? 1 : 0;

        var apply = Theme.MakeButton("Применить тему", true);
        apply.Location = new Point(18, 74);
        apply.Click += (_, _) =>
        {
            LauncherSettings.ThemeName = box.SelectedIndex == 1 ? "dark" : "light";
            d.Close();
            ReapplyTheme();
        };

        var divider = new Panel { BackColor = Theme.BorderLight, Location = new Point(18, 122), Size = new Size(346, 1) };

        var verLbl = new Label { Text = $"Версия лаунчера: {SelfUpdate.CurrentVersion}", ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 9), AutoSize = true, Location = new Point(18, 136) };
        var check = Theme.MakeButton("Проверить обновления", false);
        check.Location = new Point(18, 162);
        var upStatus = new Label { Text = "", ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9), AutoSize = true, Location = new Point(18, 202) };
        var upBar = new ProgressBar { Style = ProgressBarStyle.Continuous, Maximum = 1000, Visible = false, Location = new Point(18, 226), Size = new Size(346, 12) };

        check.Click += async (_, _) =>
        {
            check.Enabled = false;
            upStatus.ForeColor = Theme.Text;
            upStatus.Text = "Проверка…";
            try
            {
                var info = await SelfUpdate.CheckAsync();
                if (info != null && SelfUpdate.IsNewer(info.Version))
                {
                    upStatus.Text = $"Доступна версия {info.Version}. Загрузка…";
                    upBar.Visible = true;
                    await SelfUpdate.DownloadAndApplyAsync(info, frac => BeginInvoke(() =>
                        upBar.Value = Math.Min(1000, (int)(frac * 1000))));
                    Application.Exit();
                }
                else
                {
                    upStatus.ForeColor = Theme.Green2;
                    upStatus.Text = "У вас актуальная версия.";
                    check.Enabled = true;
                }
            }
            catch (Exception ex)
            {
                upStatus.ForeColor = Color.FromArgb(0xC0, 0x39, 0x2B);
                upStatus.Text = "Ошибка: " + ex.Message;
                check.Enabled = true;
            }
        };

        body.Controls.AddRange(new Control[] { themeLbl, box, apply, divider, verLbl, check, upStatus, upBar });
        ShowLauncherDialog(d);
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
        _scrollOffset = 0;
    }

    // Embedded-image cache (assets are EmbeddedResource in the csproj).
    static readonly Dictionary<string, Image?> _assetCache = new();
    static Image? LoadAsset(string name)
    {
        if (_assetCache.TryGetValue(name, out var cached)) return cached;
        Image? img = null;
        try
        {
            var asm = System.Reflection.Assembly.GetExecutingAssembly();
            var res = asm.GetManifestResourceNames().FirstOrDefault(n => n.EndsWith(name, StringComparison.OrdinalIgnoreCase));
            if (res != null)
            {
                using var s = asm.GetManifestResourceStream(res);
                if (s != null) img = Image.FromStream(s);
            }
        }
        catch { }
        _assetCache[name] = img;
        return img;
    }

    // ---------------- downloads page ----------------
    void ShowDownloads()
    {
        _currentGameId = "";
        HighlightSidebar();
        ClearContent();

        int y = 16;
        var title = new Label { Text = "Загрузки", Font = new Font(Theme.FontName, 15, FontStyle.Bold), ForeColor = Theme.Title, AutoSize = true, Location = new Point(24, y) };
        _content.Controls.Add(title);
        y += 40;

        foreach (var g in Games.All)
        {
            var row = BuildDownloadRow(g, y);
            _content.Controls.Add(row);
            y += row.Height + 12;
        }

        // Live-refresh the downloads page while anything is active.
        HookDownloadRefresh(() => { if (_currentGameId == "" && _downloadsOpen) ShowDownloads(); });
        _downloadsOpen = true;
    }
    bool _downloadsOpen;

    Panel BuildDownloadRow(GameDef g, int y)
    {
        int cw = _content.ClientSize.Width - 48;
        var card = new Panel { Location = new Point(24, y), Width = cw, Height = 76, BackColor = Theme.Panel, BorderStyle = BorderStyle.FixedSingle, Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };

        // Icon.
        var icon = g.Type == "mc" ? LoadAsset("petusmc-icon.png") : LoadAsset("icon.png");
        var pic = new PictureBox { SizeMode = PictureBoxSizeMode.Zoom, Size = new Size(52, 52), Location = new Point(12, 12), Image = icon, BackColor = Color.Transparent };
        card.Controls.Add(pic);

        var name = new Label { Text = g.Name, Font = new Font(Theme.FontName, 11, FontStyle.Bold), ForeColor = Theme.Title, AutoSize = true, Location = new Point(76, 12) };
        var desc = new Label { Text = g.Tagline, Font = new Font(Theme.FontName, 8), ForeColor = Theme.Muted, AutoSize = true, Location = new Point(76, 34) };
        card.Controls.Add(name); card.Controls.Add(desc);

        var job = DownloadManager.Get(g.Id);
        bool installed = GameOps.IsInstalled(g);
        bool active = DownloadManager.IsActive(g.Id);

        // Right side: progress + analytics OR an action button.
        if (active && job != null)
        {
            var bar = new ProgressBar { Style = ProgressBarStyle.Continuous, Maximum = 1000, Value = Math.Min(1000, (int)(job.Fraction * 1000)), Width = 220, Height = 12, Location = new Point(cw - 250, 24) };
            var meta = new Label
            {
                AutoSize = false, Width = 240, Height = 16, Location = new Point(cw - 250, 42),
                ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 8),
                Text = $"{job.Status}  ·  {FmtSpeed(job.SpeedBps)}  ·  {FmtSize(job.BytesDone)}/{FmtSize(job.BytesTotal)}  ·  ~{FmtEta(job.EtaSeconds)}",
            };
            card.Controls.Add(bar); card.Controls.Add(meta);
        }
        else
        {
            string label = !installed ? "Установить" : (g.Id == "petusgdps" && _gdpsUpdateAvailable ? "Обновить" : "Играть");
            bool blue = !installed || label == "Обновить";
            var btn = Theme.MakeButton(label, true, primaryColor: blue ? Theme.Blue2 : Theme.Green2);
            btn.Location = new Point(cw - btn.Width - 16, 20);
            btn.Anchor = AnchorStyles.Top | AnchorStyles.Right;
            btn.Click += (_, _) => { ShowGame(g.Id); StartGame(g); };
            card.Controls.Add(btn);
        }
        return card;
    }

    static string FmtSpeed(double bps) => bps <= 0 ? "—" : bps >= 1048576 ? $"{bps / 1048576:0.0} МБ/с" : $"{bps / 1024:0} КБ/с";
    static string FmtEta(double s) => s <= 0 ? "—" : s >= 60 ? $"{(int)(s / 60)} мин" : $"{(int)s} сек";

    // Subscribe a UI refresh to DownloadManager for the lifetime of the page.
    void HookDownloadRefresh(Action refresh)
    {
        if (_dlHandler != null) DownloadManager.Changed -= _dlHandler;
        _dlHandler = _ => { try { BeginInvoke(refresh); } catch { } };
        DownloadManager.Changed += _dlHandler;
    }
    Action<DownloadManager.Job>? _dlHandler;

    // ---------------- login ----------------
    void ShowLogin()
    {
        _downloadsOpen = false;
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
        _downloadsOpen = false;
        _content.Resize -= _centerHandler;
        HighlightSidebar();
        var g = Games.All.FirstOrDefault(x => x.Id == id);
        if (g == null) return;
        ClearContent();

        // While this game is downloading, live-refresh the page from the manager.
        if (DownloadManager.IsActive(id))
            HookDownloadRefresh(() => { if (_currentGameId == id) ShowGame(id); });

        int y = 0;
        // Banner
        var banner = new Panel { Location = new Point(0, y), Height = 150, Width = _content.ClientSize.Width - 20, Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
        var bannerImg = g.Type == "mc" ? LoadAsset("petusmc-banner.png") : null;
        var (bl, br) = g.Type == "gdps"
            ? (Color.FromArgb(0x3A, 0x2C, 0x74), Color.FromArgb(0x7B, 0x61, 0xFF))
            : (Color.FromArgb(0x24, 0x4A, 0x24), Color.FromArgb(0x5F, 0x91, 0x40));
        banner.Paint += (_, e) =>
        {
            if (bannerImg != null)
            {
                // Cover-fit the artwork across the banner area.
                var r = banner.ClientRectangle;
                float scale = Math.Max((float)r.Width / bannerImg.Width, (float)r.Height / bannerImg.Height);
                int dw = (int)(bannerImg.Width * scale), dh = (int)(bannerImg.Height * scale);
                e.Graphics.DrawImage(bannerImg, new Rectangle((r.Width - dw) / 2, (r.Height - dh) / 2, dw, dh));
            }
            else
            {
                Theme.PaintHGradient(e.Graphics, banner.ClientRectangle, bl, br);
                using var tf = new Font(Theme.FontName, 20, FontStyle.Bold);
                using var sf = new Font(Theme.FontName, 10);
                e.Graphics.DrawString(g.Name, tf, Brushes.White, 22, 92);
                e.Graphics.DrawString(g.Tagline, sf, Brushes.White, 24, 124);
            }
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
        bool updateAvail = installed && _gdpsUpdateAvailable;

        // action row — Свойства lives in the right-click menu now.
        y += 16;
        bool needsDownload = !installed || updateAvail;
        string mainLabel = !installed ? "Установить" : updateAvail ? "Обновить" : "Играть";
        // Install/Update = blue, Play = green.
        var mainBtn = Theme.MakeButton(mainLabel, true, 11, primaryColor: needsDownload ? Theme.Blue2 : Theme.Green2);
        mainBtn.Location = new Point(24, y);

        var job = DownloadManager.Get(g.Id);
        bool downloading = DownloadManager.IsActive(g.Id);

        var status = new Label
        {
            ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 9), AutoSize = true,
            Text = downloading ? (job?.Status ?? "Загрузка…")
                 : !installed ? "Игра не установлена"
                 : updateAvail ? "Доступно обновление" : "Готов к запуску",
        };
        var progress = ThinProgress();

        // Compact inline stats to the RIGHT of the button.
        var group = new FlowLayoutPanel { FlowDirection = FlowDirection.LeftToRight, WrapContents = false, AutoSize = true, BackColor = Color.Transparent, Location = new Point(mainBtn.Right + 22, y - 2) };
        group.Controls.Add(InlineStat("Наиграно", FmtDuration(st.PlaySeconds), out _));
        group.Controls.Add(InlineStat("Последний запуск", FmtDate(st.LastPlayed), out _));
        Label? sizeVal = null;
        if (needsDownload)
            group.Controls.Add(InlineStat(updateAvail ? "Размер обновления" : "Размер загрузки", "…", out sizeVal));
        _content.Controls.Add(group);

        mainBtn.Click += (_, _) =>
        {
            if (installed && !updateAvail && !downloading)
            {
                // Ready → just launch.
                try { Stats.Snapshot(g.Id, Config.GameDir); GameLauncher.Launch(_auth!); status.Text = "Игра запущена!"; }
                catch (Exception ex) { status.Text = "Ошибка: " + ex.Message; }
                return;
            }
            // Install/update via the central manager so it survives navigation.
            DownloadManager.StartGdps();
            ShowGame(g.Id); // re-render into the "downloading" state
        };

        _content.Controls.Add(mainBtn);
        y += 44;
        status.Location = new Point(24, y); _content.Controls.Add(status); y += 22;
        // Only occupy space for the progress bar while a download is running.
        if (downloading)
        {
            progress.Location = new Point(24, y);
            if (job != null) progress.Value = Math.Min(1000, (int)(job.Fraction * 1000));
            _content.Controls.Add(progress);
            y += 16;
        }

        if (sizeVal != null) _ = FillRemoteSize(sizeVal);

        // updates card
        y += 12;
        var upCard = MakeUpdatesCard(g, cw, y);
        _content.Controls.Add(upCard);
        y += upCard.Height + 20;
    }

    void RenderMc(GameDef g, ref int y)
    {
        int cw = _content.ClientSize.Width - 48;
        var st = Stats.Get(g.Id);
        var acc = LauncherSettings.McAccount;

        // action row: Играть/Установить + inline stats
        y += 16;
        bool mcInstalled = GameOps.McInstalled();
        var playBtn = Theme.MakeButton(mcInstalled ? "Играть" : "Установить", true, 11,
            primaryColor: mcInstalled ? Theme.Green2 : Theme.Blue2);
        playBtn.Location = new Point(24, y);

        var group = new FlowLayoutPanel { FlowDirection = FlowDirection.LeftToRight, WrapContents = false, AutoSize = true, BackColor = Color.Transparent, Location = new Point(playBtn.Right + 22, y - 2) };
        group.Controls.Add(InlineStat("Наиграно", FmtDuration(st.PlaySeconds), out _));
        group.Controls.Add(InlineStat("Последний запуск", FmtDate(st.LastPlayed), out _));
        _content.Controls.Add(group);
        _content.Controls.Add(playBtn);
        y += 44;

        // Online status on its own tidy line under the action row.
        var online = new Label
        {
            Text = "● проверка статуса…",
            ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 9),
            AutoSize = true, Location = new Point(24, y),
        };
        _content.Controls.Add(online);
        _ = LoadMcStatus(g.Ip!, online);
        y += 22;

        var status = new Label { ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 9), AutoSize = true, Location = new Point(24, y) };
        var progress = ThinProgress();
        progress.Location = new Point(24, y + 18);
        _content.Controls.Add(status);
        _content.Controls.Add(progress);
        y += 44;

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
        y += setCard.Height + 16;

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
                var TXT = new Dictionary<string, string> { ["manifest"] = "Получение манифеста…", ["libraries"] = "Загрузка библиотек…", ["assets"] = "Загрузка ресурсов…", ["java"] = "Загрузка Java…", ["forge"] = "Установка Forge…", ["meteor"] = "Загрузка Meteor…", ["launch"] = "Запуск…" };
                var proc = await MinecraftLauncher.InstallAndLaunchAsync(ver, account, LauncherSettings.McRamMb,
                    (stage, frac) => BeginInvoke(() =>
                    {
                        status.Text = TXT.GetValueOrDefault(stage, stage);
                        progress.Visible = frac > 0 && frac < 1;
                        progress.Value = Math.Min(1000, (int)(frac * 1000));
                    }), g.Ip, forgeMeteor: true);
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
    }

    // A thin, unobtrusive progress bar shown only while downloading.
    static ProgressBar ThinProgress() =>
        new ProgressBar { Style = ProgressBarStyle.Continuous, Width = 300, Height = 8, Visible = false, Maximum = 1000 };

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

    // A polished "Обновления" card: version chips + notes, click a row for the
    // full note in a launcher dialog. GDPS only (removed from MC).
    Panel MakeUpdatesCard(GameDef g, int cw, int y)
    {
        var card = MakeCard("Обновления", cw, y, out var body);
        var accent = g.Type == "gdps" ? Color.FromArgb(0x7B, 0x61, 0xFF) : Theme.Green2;
        var list = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, WrapContents = false, Dock = DockStyle.Top, AutoSize = true, BackColor = Theme.Panel, Padding = new Padding(8, 6, 8, 8) };
        int rowW = cw - 20;
        foreach (var u in g.Changelog)
        {
            var uu = u;
            var row = new Panel { Width = rowW, Height = 34, BackColor = Theme.Panel, Cursor = Cursors.Hand, Margin = new Padding(0, 0, 0, 4) };
            var chip = VersionChip(uu.Version, accent);
            chip.Location = new Point(2, (row.Height - chip.Height) / 2);
            var note = new Label
            {
                Text = uu.Notes.Length > 64 ? uu.Notes[..64] + "…" : uu.Notes,
                ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9),
                AutoSize = false, TextAlign = ContentAlignment.MiddleLeft,
                Location = new Point(chip.Right + 10, 0), Size = new Size(rowW - chip.Width - 20, row.Height),
            };
            void hover(bool on) => row.BackColor = on ? (LauncherSettings.ThemeName == "dark" ? Theme.PanelHead1 : Color.FromArgb(0xEE, 0xF3, 0xF8)) : Theme.Panel;
            void open(object? s, EventArgs e) => OpenNoteDialog(g, uu);
            foreach (Control c in new Control[] { row, chip, note })
            {
                c.Click += open;
                c.MouseEnter += (_, _) => hover(true);
                c.MouseLeave += (_, _) => hover(false);
            }
            row.Controls.Add(note);
            row.Controls.Add(chip);
            list.Controls.Add(row);
        }
        body.Controls.Add(list);
        card.Height = 26 + g.Changelog.Length * 38 + 14;
        return card;
    }

    void OpenNoteDialog(GameDef g, ChangelogEntry u)
    {
        var d = new LauncherDialog($"{g.Name} — обновление {u.Version}", 380, 220);
        var note = new Label
        {
            Text = u.Notes,
            ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9),
            AutoSize = false, Dock = DockStyle.Fill, Padding = new Padding(16),
        };
        d.Body.Controls.Add(note);
        ShowLauncherDialog(d);
    }

    // A rounded VK-style version pill.
    Panel VersionChip(string text, Color color)
    {
        var font = new Font(Theme.FontName, 8, FontStyle.Bold);
        int w = Math.Max(46, TextRenderer.MeasureText(text, font).Width + 18);
        var chip = new Panel { Height = 20, Width = w, BackColor = Color.Transparent, Cursor = Cursors.Hand };
        chip.Paint += (s, e) =>
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            var r = new Rectangle(0, 0, chip.Width - 1, chip.Height - 1);
            using var path = RoundedRect(r, 7);
            using var b = new SolidBrush(color);
            e.Graphics.FillPath(b, path);
            TextRenderer.DrawText(e.Graphics, text, font, new Rectangle(0, 0, chip.Width, chip.Height), Color.White,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
        };
        return chip;
    }

    static GraphicsPath RoundedRect(Rectangle r, int radius)
    {
        int d = radius * 2;
        var path = new GraphicsPath();
        path.AddArc(r.X, r.Y, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    // Small stacked value/label used in the inline stats group next to the button.
    Panel InlineStat(string label, string value, out Label valueLabel)
    {
        var p = new Panel { AutoSize = false, Width = Math.Max(96, TextRenderer.MeasureText(value, new Font(Theme.FontName, 10, FontStyle.Bold)).Width + 18), Height = 40, BackColor = Color.Transparent, Margin = new Padding(0, 0, 18, 0) };
        var v = new Label { Text = value, ForeColor = Theme.Text, Font = new Font(Theme.FontName, 10, FontStyle.Bold), AutoSize = false, Dock = DockStyle.Top, Height = 20, TextAlign = ContentAlignment.MiddleLeft };
        var l = new Label { Text = label, ForeColor = Theme.Muted, Font = new Font(Theme.FontName, 8), AutoSize = false, Dock = DockStyle.Top, Height = 16, TextAlign = ContentAlignment.MiddleLeft };
        p.Controls.Add(l);
        p.Controls.Add(v);
        valueLabel = v;
        return p;
    }

    async Task FillRemoteSize(Label target)
    {
        try
        {
            long sz = await Updater.RemoteSizeAsync();
            if (target.IsHandleCreated)
                target.BeginInvoke(() => target.Text = sz > 0 ? FmtSize(sz) : "—");
        }
        catch { }
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
        _modalOverlay = new Panel { BackColor = Color.Transparent, Width = w + 12, Height = h + 12, Visible = false };

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

        // Position while invisible, then reveal — no top-left → center jump.
        Controls.Add(_modalOverlay);
        CenterModal();
        _modalOverlay.Visible = true;
        _modalOverlay.BringToFront();
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

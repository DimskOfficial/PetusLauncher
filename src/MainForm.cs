using System.Runtime.InteropServices;

namespace PetusLauncher;

// Frameless main window: custom VK-2010 title bar, left game sidebar, right
// content area that renders the selected game's store-style page.
public partial class MainForm : Form, IMessageFilter
{
    // --- title bar ---
    Panel _titleBar = null!;
    Label _brand = null!;
    Panel _userChip = null!;
    Label _userName = null!;
    ContextMenuStrip _userMenu = null!;

    // --- body ---
    Panel _sidebar = null!;
    FlowLayoutPanel _gameList = null!;
    Button _settingsBtn = null!;
    Panel _content = null!;

    // --- state ---
    AuthData? _auth;
    string _currentGameId = "";
    readonly Dictionary<string, Button> _sidebarButtons = new();

    // Custom (scrollbar-less) vertical scrolling of the content area.
    int _scrollOffset;
    // Startup update check runs once; result stashed so the GDPS page can show
    // an "Обновить" button without re-hitting the network on every render.
    bool _startupChecked;
    bool _gdpsUpdateAvailable;

    public MainForm()
    {
        Text = "PetusLauncher";
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.CenterScreen;
        Size = new Size(780, 520);
        MinimumSize = new Size(680, 460);
        BackColor = Theme.Bg;
        Font = new Font(Theme.FontName, 9);
        DoubleBuffered = true;
        try { Icon = new Icon(Path.Combine(AppContext.BaseDirectory, "assets", "icon.ico")); } catch { }

        BuildTitleBar();
        BuildBody();

        // Thin light border around the whole frameless window.
        Padding = new Padding(1);
        Paint += (_, e) =>
        {
            using var pen = new Pen(Theme.AppBorder);
            e.Graphics.DrawRectangle(pen, 0, 0, Width - 1, Height - 1);
        };

        GameLauncher.GameClosed += _ => BeginInvoke(() => { if (_currentGameId != "") ShowGame(_currentGameId); });

        // Route mouse-wheel to the content area even when it doesn't hold focus.
        Application.AddMessageFilter(this);
        FormClosed += (_, _) => Application.RemoveMessageFilter(this);

        Load += (_, _) => Boot();
    }

    // WM_MOUSEWHEEL routing: scroll the content page when the cursor is over it.
    public bool PreFilterMessage(ref Message m)
    {
        const int WM_MOUSEWHEEL = 0x020A;
        if (m.Msg != WM_MOUSEWHEEL || _content == null || !_content.IsHandleCreated) return false;
        var screen = new Point((short)((long)m.LParam & 0xFFFF), (short)(((long)m.LParam >> 16) & 0xFFFF));
        if (!_content.RectangleToScreen(_content.ClientRectangle).Contains(screen)) return false;
        int delta = (short)(((long)m.WParam >> 16) & 0xFFFF);
        ScrollContent(delta);
        return true;
    }

    async void Boot()
    {
        BuildSidebar();
        _auth = Auth.Load();
        RenderAuthState();
        // Startup: check for launcher + game updates.
        await RunStartupUpdateCheck();
    }

    // ---------------- title bar ----------------
    void BuildTitleBar()
    {
        _titleBar = new Panel { Height = 40 };  // positioned manually in DoLayout
        _titleBar.Paint += (_, e) =>
            Theme.PaintVGradient(e.Graphics, _titleBar.ClientRectangle, Theme.BarTop, Theme.BarBot);
        EnableDrag(_titleBar);

        _brand = new Label
        {
            Text = "  PetusLauncher",
            ForeColor = Color.White,
            Font = new Font(Theme.FontName, 11, FontStyle.Bold),
            AutoSize = true,
            Location = new Point(12, 10),
            BackColor = Color.Transparent,
        };
        EnableDrag(_brand);
        _titleBar.Controls.Add(_brand);

        // Window buttons (right).
        var btnClose = TitleButton("✕", Color.FromArgb(0xC0, 0x39, 0x2B));
        btnClose.Click += (_, _) => Close();
        var btnMin = TitleButton("─", Color.FromArgb(90, 255, 255, 255));
        btnMin.Click += (_, _) => WindowState = FormWindowState.Minimized;

        _titleBar.Controls.Add(btnClose);
        _titleBar.Controls.Add(btnMin);

        // User chip (right, before window buttons).
        _userChip = new Panel { Height = 26, Width = 130, BackColor = Color.Transparent, Cursor = Cursors.Hand, Visible = false };
        _userName = new Label
        {
            ForeColor = Color.White,
            Font = new Font(Theme.FontName, 9),
            AutoSize = true,
            Location = new Point(6, 5),
            BackColor = Color.Transparent,
            Text = "Player ▾",
        };
        _userChip.Controls.Add(_userName);
        _titleBar.Controls.Add(_userChip);

        _userMenu = new ContextMenuStrip { Font = new Font(Theme.FontName, 9) };
        _userMenu.Items.Add("Профиль", null, (_, _) =>
            Auth.OpenBrowser("https://id.petus.ru"));
        _userMenu.Items.Add("Настройки", null, (_, _) =>
            Auth.OpenBrowser("https://id.petus.ru/personal"));
        _userMenu.Items.Add(new ToolStripSeparator());
        _userMenu.Items.Add("Выйти", null, (_, _) =>
        {
            Auth.Clear();
            _auth = null;
            RenderAuthState();
        });
        void openMenu(object? s, EventArgs e) => _userMenu.Show(_userChip, new Point(0, _userChip.Height));
        _userChip.Click += openMenu;
        _userName.Click += openMenu;

        _titleBar.Resize += (_, _) => LayoutTitleBar();
        Controls.Add(_titleBar);
        LayoutTitleBar();
    }

    Button TitleButton(string text, Color hover)
    {
        var b = new Button
        {
            Text = text,
            FlatStyle = FlatStyle.Flat,
            ForeColor = Color.White,
            Font = new Font(Theme.FontName, 10),
            Size = new Size(42, 40),
            BackColor = Color.Transparent,
            Cursor = Cursors.Hand,
            TabStop = false,
        };
        b.FlatAppearance.BorderSize = 0;
        b.FlatAppearance.MouseOverBackColor = hover;
        return b;
    }

    void LayoutTitleBar()
    {
        int w = _titleBar.Width;
        var close = (Button)_titleBar.Controls[1];
        var min = (Button)_titleBar.Controls[2];
        close.Location = new Point(w - 42, 0);
        min.Location = new Point(w - 84, 0);
        _userChip.Location = new Point(w - 84 - _userChip.Width - 6, 7);
        _userName.Location = new Point(6, 5);
    }

    // ---------------- body ----------------
    void BuildBody()
    {
        // Manual layout (no Dock) so the title bar spans the full width and the
        // sidebar never overlaps the content — positions are set in DoLayout().
        _sidebar = new Panel { BackColor = Theme.SidebarBg };
        _sidebar.Paint += (_, e) =>
        {
            using var pen = new Pen(Theme.Border);
            e.Graphics.DrawLine(pen, _sidebar.Width - 1, 0, _sidebar.Width - 1, _sidebar.Height);
        };
        var sideTitle = new Label
        {
            Text = "ИГРЫ",
            ForeColor = Color.FromArgb(0x86, 0x97, 0xA8),
            Font = new Font(Theme.FontName, 8),
            AutoSize = true,
            Location = new Point(14, 12),
        };
        _sidebar.Controls.Add(sideTitle);
        _gameList = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoScroll = false,      // no scrollbar in the sidebar
            Location = new Point(0, 34),
            Width = 190,
        };
        _sidebar.Controls.Add(_gameList);

        // Settings button pinned to the bottom-left of the sidebar.
        _settingsBtn = new Button
        {
            Text = "  ⚙  Настройки",
            TextAlign = ContentAlignment.MiddleLeft,
            FlatStyle = FlatStyle.Flat,
            ForeColor = Theme.Link,
            Font = new Font(Theme.FontName, 9),
            Width = 190,
            Height = 34,
            BackColor = Theme.SidebarBg,
            Cursor = Cursors.Hand,
        };
        _settingsBtn.FlatAppearance.BorderSize = 0;
        _settingsBtn.FlatAppearance.MouseOverBackColor = Color.FromArgb(0xE4, 0xEA, 0xF1);
        _settingsBtn.Click += (_, _) => ShowSettings();
        _sidebar.Controls.Add(_settingsBtn);

        Controls.Add(_sidebar);

        _content = new Panel { BackColor = Theme.Bg, AutoScroll = false };
        // No visible scrollbar: scroll the content by mouse wheel instead.
        _content.MouseWheel += (_, e) => ScrollContent(e.Delta);
        Controls.Add(_content);

        Resize += (_, _) => DoLayout();
        DoLayout();
    }

    void DoLayout()
    {
        int w = ClientSize.Width, h = ClientSize.Height;
        _titleBar.SetBounds(0, 0, w, 40);
        LayoutTitleBar();
        bool sideVisible = _sidebar.Visible;
        int sideW = sideVisible ? 190 : 0;
        if (sideVisible) _sidebar.SetBounds(0, 40, sideW, h - 40);
        _gameList.Height = h - 40 - 34 - 40;   // leave room for the settings button
        if (_settingsBtn != null)
            _settingsBtn.Location = new Point(0, (h - 40) - 34);
        _content.SetBounds(sideW, 40, w - sideW, h - 40);
        CenterModal();
    }

    void BuildSidebar()
    {
        _gameList.Controls.Clear();
        _sidebarButtons.Clear();
        foreach (var g in Games.All)
        {
            var btn = new Button
            {
                Text = "   " + g.Name,
                TextAlign = ContentAlignment.MiddleLeft,
                FlatStyle = FlatStyle.Flat,
                ForeColor = Theme.Link,
                Font = new Font(Theme.FontName, 10),
                Width = 188,
                Height = 38,
                BackColor = Theme.SidebarBg,
                Cursor = Cursors.Hand,
                Tag = g.Id,
            };
            btn.FlatAppearance.BorderSize = 0;
            btn.FlatAppearance.MouseOverBackColor = Color.FromArgb(0xE4, 0xEA, 0xF1);
            var dotColor = g.Type == "gdps" ? Color.FromArgb(0x7B, 0x61, 0xFF) : Color.FromArgb(0x5F, 0x91, 0x40);
            btn.Paint += (s, e) =>
            {
                using var br = new SolidBrush(dotColor);
                e.Graphics.FillEllipse(br, 10, btn.Height / 2 - 4, 8, 8);
            };
            btn.Click += (_, _) => ShowGame(g.Id);
            btn.ContextMenuStrip = BuildGameContextMenu(g);
            _gameList.Controls.Add(btn);
            _sidebarButtons[g.Id] = btn;
        }
    }

    void HighlightSidebar()
    {
        foreach (var (id, btn) in _sidebarButtons)
        {
            bool active = id == _currentGameId;
            btn.BackColor = active ? Theme.SidebarActive : Theme.SidebarBg;
            btn.Font = new Font(Theme.FontName, 10, active ? FontStyle.Bold : FontStyle.Regular);
        }
    }

    // ---------------- auth state ----------------
    void RenderAuthState()
    {
        if (_auth != null && !string.IsNullOrEmpty(_auth.Token))
        {
            _userChip.Visible = true;
            _userName.Text = (_auth.Name ?? "Player") + " ▾";
            _userName.AutoSize = true;
            _userChip.Width = _userName.PreferredWidth + 20;
            _sidebar.Visible = true;
            DoLayout();
            if (_currentGameId == "") ShowGame(Games.All[0].Id);
            else ShowGame(_currentGameId);
        }
        else
        {
            _userChip.Visible = false;
            _sidebar.Visible = false;
            DoLayout();
            ShowLogin();
        }
    }

    // The rest (ShowLogin, ShowGame, modals) is in MainForm.Pages.cs

    // ---------------- custom content scrolling (no visible scrollbar) --------
    // Because _content.AutoScroll is off, tall pages are scrolled by shifting all
    // child controls vertically. _scrollOffset is the current shift (0 = top,
    // negative = scrolled down). Reset to 0 whenever the page is re-rendered.
    void ScrollContent(int delta)
    {
        if (_content.Controls.Count == 0) return;
        int viewH = _content.ClientSize.Height;
        int contentH = 0;
        foreach (Control c in _content.Controls)
            contentH = Math.Max(contentH, c.Bottom - _scrollOffset); // height in unscrolled coords
        if (contentH <= viewH) return;                               // nothing to scroll

        int min = -(contentH - viewH + 12);                          // small bottom breathing room
        int newOffset = Math.Clamp(_scrollOffset + delta / 2, min, 0);
        int shift = newOffset - _scrollOffset;
        if (shift == 0) return;
        _content.SuspendLayout();
        foreach (Control c in _content.Controls) c.Top += shift;
        _content.ResumeLayout();
        _scrollOffset = newOffset;
    }

    // ---------------- launcher dialogs (no open-lag) ------------------------
    // Content-area rectangle used to center in-window dialogs.
    Rectangle DialogArea()
    {
        int x = _sidebar.Visible ? 190 : 0;
        return new Rectangle(x, 40, ClientSize.Width - x, ClientSize.Height - 40);
    }

    // Add + center a LauncherDialog with NO visible jump: it is positioned while
    // invisible, then shown in place.
    void ShowLauncherDialog(LauncherDialog d)
    {
        d.Root.Visible = false;
        Controls.Add(d.Root);
        d.CenterIn(DialogArea());
        d.Root.Visible = true;
        d.Root.BringToFront();
    }

    // ---------------- startup update check ----------------------------------
    async Task RunStartupUpdateCheck()
    {
        if (_startupChecked) return;
        _startupChecked = true;

        var dlg = new LauncherDialog("Проверка обновлений…", 340, 128);
        var lbl = new Label
        {
            Text = "Проверяем наличие обновлений…",
            ForeColor = Theme.Text, Font = new Font(Theme.FontName, 9),
            AutoSize = false, Location = new Point(18, 20), Size = new Size(300, 40),
        };
        var bar = new ProgressBar
        {
            Style = ProgressBarStyle.Continuous, Maximum = 1000, Visible = false,
            Location = new Point(18, 66), Size = new Size(300, 14),
        };
        dlg.Body.Controls.Add(lbl);
        dlg.Body.Controls.Add(bar);
        ShowLauncherDialog(dlg);

        try
        {
            var info = await SelfUpdate.CheckAsync();
            if (info != null && SelfUpdate.IsNewer(info.Version))
            {
                lbl.Text = $"Загрузка обновления лаунчера {info.Version}…";
                bar.Visible = true;
                await SelfUpdate.DownloadAndApplyAsync(info, frac => BeginInvoke(() =>
                    bar.Value = Math.Min(1000, (int)(frac * 1000))));
                Application.Exit();
                return;
            }
            // No launcher update — pre-check the GDPS game update in the background
            // so its page can offer an "Обновить" button.
            _gdpsUpdateAvailable = await Updater.UpdateAvailableAsync();
        }
        catch { /* offline or transient: continue silently */ }

        dlg.Close();
        if (_gdpsUpdateAvailable && _currentGameId != "")
            ShowGame(_currentGameId); // refresh so "Обновить" appears
    }

    void EnableDrag(Control c)
    {
        c.MouseDown += (_, e) =>
        {
            if (e.Button == MouseButtons.Left)
            {
                ReleaseCapture();
                SendMessage(Handle, 0xA1, 0x2, 0);
            }
        };
    }

    [DllImport("user32.dll")] static extern int SendMessage(IntPtr hWnd, int Msg, int wParam, int lParam);
    [DllImport("user32.dll")] static extern bool ReleaseCapture();
}

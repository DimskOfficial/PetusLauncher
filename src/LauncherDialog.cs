namespace PetusLauncher;

// A double-buffered panel — avoids flicker/smear when the dialog is dragged.
class BufferedPanel : Panel
{
    public BufferedPanel()
    {
        SetStyle(ControlStyles.OptimizedDoubleBuffer | ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint, true);
        DoubleBuffered = true;
    }
}

// A custom, launcher-styled floating dialog rendered INSIDE the main window
// (not a Windows dialog). Draggable by its title bar, with a VK-gradient header
// and a close button. Used for Settings, Updates and Properties so everything
// matches the launcher skin. Add its Root to the form and position it centered.
class LauncherDialog
{
    public Panel Root { get; }
    public Panel Body { get; }
    readonly Panel _shadow;
    public event Action? Closed;

    public LauncherDialog(string title, int w, int h)
    {
        Root = new BufferedPanel { BackColor = Theme.Bg, Width = w + 12, Height = h + 12 };

        _shadow = new Panel { Bounds = new Rectangle(4, 5, w, h), BackColor = Color.FromArgb(40, 0, 0, 0) };

        var modal = new BufferedPanel { Bounds = new Rectangle(0, 0, w, h), BackColor = Theme.Panel, BorderStyle = BorderStyle.FixedSingle };

        var head = new Panel { Dock = DockStyle.Top, Height = 32, Cursor = Cursors.SizeAll };
        head.Paint += (s, e) => Theme.PaintVGradient(e.Graphics, head.ClientRectangle, Theme.BarTop, Theme.BarBot);
        var htext = new Label { Text = title, ForeColor = Color.White, Font = new Font(Theme.FontName, 9, FontStyle.Bold), AutoSize = true, Location = new Point(12, 8), BackColor = Color.Transparent };
        htext.Cursor = Cursors.SizeAll;
        var x = new Button { Text = "✕", FlatStyle = FlatStyle.Flat, ForeColor = Color.White, Size = new Size(32, 32), Dock = DockStyle.Right, Cursor = Cursors.Hand, TabStop = false };
        x.FlatAppearance.BorderSize = 0;
        x.FlatAppearance.MouseOverBackColor = Color.FromArgb(0xC0, 0x39, 0x2B);
        x.Click += (_, _) => Close();
        head.Controls.Add(htext); head.Controls.Add(x);

        Body = new Panel { Dock = DockStyle.Fill, BackColor = Theme.Panel };
        modal.Controls.Add(Body);
        modal.Controls.Add(head);

        Root.Controls.Add(modal);
        Root.Controls.Add(_shadow);
        _shadow.SendToBack();

        // Dragging: move Root while the title bar is held.
        EnableDrag(head);
        EnableDrag(htext);
    }

    public void Close()
    {
        Root.Parent?.Controls.Remove(Root);
        Root.Dispose();
        Closed?.Invoke();
    }

    // Center the dialog inside a rectangle (typically the content area).
    public void CenterIn(Rectangle area)
    {
        Root.Location = new Point(
            area.X + Math.Max(0, (area.Width - Root.Width) / 2),
            area.Y + Math.Max(0, (area.Height - Root.Height) / 2));
    }

    Point _dragStart;
    bool _dragging;
    void EnableDrag(Control c)
    {
        c.MouseDown += (_, e) => { if (e.Button == MouseButtons.Left) { _dragging = true; _dragStart = e.Location; } };
        c.MouseMove += (_, e) =>
        {
            if (!_dragging) return;
            var old = Root.Bounds;
            Root.Location = new Point(Root.Left + e.X - _dragStart.X, Root.Top + e.Y - _dragStart.Y);
            // Repaint the region the dialog just vacated so no ghost trail is left.
            Root.Parent?.Invalidate(old, true);
        };
        c.MouseUp += (_, _) => _dragging = false;
    }
}

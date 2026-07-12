using System.Drawing.Drawing2D;

namespace PetusLauncher;

// VK-2010 palette + small UI helpers (flat buttons, gradient panels).
// Two palettes: the classic light VK-2010 skin and a dark variant. The active
// palette is chosen from LauncherSettings.ThemeName; all colours are exposed as
// properties so the whole UI just re-reads them after a theme switch + rebuild.
static class Theme
{
    static bool Dark => LauncherSettings.ThemeName == "dark";

    static Color Pick(Color light, Color dark) => Dark ? dark : light;

    public static Color Bg          => Pick(Color.FromArgb(0xE9, 0xED, 0xF3), Color.FromArgb(0x1E, 0x22, 0x28));
    public static Color BarTop      => Pick(Color.FromArgb(0x5F, 0x7F, 0xA6), Color.FromArgb(0x2C, 0x3A, 0x4C));
    public static Color BarBot      => Pick(Color.FromArgb(0x4A, 0x6A, 0x91), Color.FromArgb(0x22, 0x2E, 0x3D));
    public static Color Panel       => Pick(Color.White,                      Color.FromArgb(0x26, 0x2B, 0x33));
    public static Color PanelHead1  => Pick(Color.FromArgb(0xF7, 0xF9, 0xFB), Color.FromArgb(0x2E, 0x34, 0x3D));
    public static Color PanelHead2  => Pick(Color.FromArgb(0xEE, 0xF1, 0xF5), Color.FromArgb(0x28, 0x2D, 0x35));
    public static Color Border      => Pick(Color.FromArgb(0xC3, 0xCC, 0xD8), Color.FromArgb(0x3A, 0x42, 0x4D));
    public static Color BorderLight => Pick(Color.FromArgb(0xE4, 0xE9, 0xEF), Color.FromArgb(0x33, 0x3A, 0x44));
    public static Color Title       => Pick(Color.FromArgb(0x2B, 0x58, 0x7A), Color.FromArgb(0x9C, 0xC4, 0xE6));
    public static Color Link        => Pick(Color.FromArgb(0x2B, 0x58, 0x7A), Color.FromArgb(0xB8, 0xCF, 0xE6));
    public static Color Muted       => Pick(Color.FromArgb(0x77, 0x77, 0x77), Color.FromArgb(0x8B, 0x93, 0x9E));
    public static Color Text        => Pick(Color.FromArgb(0x33, 0x33, 0x33), Color.FromArgb(0xD5, 0xDA, 0xE0));
    public static Color SidebarBg   => Pick(Color.FromArgb(0xF0, 0xF2, 0xF5), Color.FromArgb(0x22, 0x27, 0x2E));
    public static Color SidebarActive => Pick(Color.FromArgb(0xDB, 0xE4, 0xEF), Color.FromArgb(0x33, 0x3C, 0x49));

    // Green primary button
    public static readonly Color Green1 = Color.FromArgb(0x7F, 0xAE, 0x5F);
    public static readonly Color Green2 = Color.FromArgb(0x5F, 0x91, 0x40);
    // Blue secondary button
    public static readonly Color Blue1 = Color.FromArgb(0x6D, 0x8D, 0xB3);
    public static readonly Color Blue2 = Color.FromArgb(0x4F, 0x70, 0x95);

    public static readonly string FontName = "Tahoma";

    public static void PaintVGradient(Graphics g, Rectangle r, Color top, Color bot)
    {
        if (r.Width <= 0 || r.Height <= 0) return;
        using var b = new LinearGradientBrush(r, top, bot, LinearGradientMode.Vertical);
        g.FillRectangle(b, r);
    }

    public static void PaintHGradient(Graphics g, Rectangle r, Color left, Color right)
    {
        if (r.Width <= 0 || r.Height <= 0) return;
        using var b = new LinearGradientBrush(r, left, right, LinearGradientMode.Horizontal);
        g.FillRectangle(b, r);
    }

    // A flat button styled like the site's .vk-btn / .vk-btn-primary.
    public static Button MakeButton(string text, bool primary, int fontSize = 9)
    {
        var btn = new Button
        {
            Text = text,
            FlatStyle = FlatStyle.Flat,
            ForeColor = Color.White,
            Font = new Font(FontName, fontSize, primary ? FontStyle.Bold : FontStyle.Regular),
            AutoSize = false,
            Height = primary ? 34 : 28,
            Cursor = Cursors.Hand,
            UseVisualStyleBackColor = false,
        };
        btn.FlatAppearance.BorderSize = 1;
        btn.FlatAppearance.BorderColor = primary
            ? Color.FromArgb(0x4E, 0x7A, 0x34)
            : Color.FromArgb(0x59, 0x75, 0xA0);
        btn.BackColor = primary ? Green2 : Blue2;
        btn.FlatAppearance.MouseOverBackColor = primary
            ? Color.FromArgb(0x6A, 0xA0, 0x49)
            : Color.FromArgb(0x56, 0x78, 0x9F);
        // Auto width from text.
        using var g = btn.CreateGraphics();
        var sz = g.MeasureString(text, btn.Font);
        btn.Width = Math.Max(primary ? 150 : 90, (int)sz.Width + 34);
        return btn;
    }
}

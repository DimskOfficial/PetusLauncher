using System.Drawing.Drawing2D;

namespace PetusLauncher;

// VK-2010 palette + small UI helpers (flat buttons, gradient panels).
static class Theme
{
    public static readonly Color Bg = Color.FromArgb(0xE9, 0xED, 0xF3);
    public static readonly Color BarTop = Color.FromArgb(0x5F, 0x7F, 0xA6);
    public static readonly Color BarBot = Color.FromArgb(0x4A, 0x6A, 0x91);
    public static readonly Color Panel = Color.White;
    public static readonly Color PanelHead1 = Color.FromArgb(0xF7, 0xF9, 0xFB);
    public static readonly Color PanelHead2 = Color.FromArgb(0xEE, 0xF1, 0xF5);
    public static readonly Color Border = Color.FromArgb(0xC3, 0xCC, 0xD8);
    public static readonly Color BorderLight = Color.FromArgb(0xE4, 0xE9, 0xEF);
    public static readonly Color Title = Color.FromArgb(0x2B, 0x58, 0x7A);
    public static readonly Color Link = Color.FromArgb(0x2B, 0x58, 0x7A);
    public static readonly Color Muted = Color.FromArgb(0x77, 0x77, 0x77);
    public static readonly Color Text = Color.FromArgb(0x33, 0x33, 0x33);
    public static readonly Color SidebarBg = Color.FromArgb(0xF0, 0xF2, 0xF5);
    public static readonly Color SidebarActive = Color.FromArgb(0xDB, 0xE4, 0xEF);

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

using System.Net;
using System.Text.Json;

namespace PetusLauncher;

record AuthData(string Token, string Name, string Account);

// Petus ID login via the external browser + a localhost loopback server.
// We open the handoff URL in the user's default browser (already signed into
// Petus ID) with ?redirect=http://127.0.0.1:<port>/cb, and the site sends the
// game token back to our loopback listener.
static class Auth
{
    public static AuthData? Load()
    {
        try
        {
            var json = File.ReadAllText(Config.AuthFile);
            var d = JsonSerializer.Deserialize<AuthData>(json);
            return d != null && !string.IsNullOrEmpty(d.Token) ? d : null;
        }
        catch { return null; }
    }

    public static void Save(AuthData auth)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Config.AuthFile)!);
        File.WriteAllText(Config.AuthFile, JsonSerializer.Serialize(auth));
    }

    public static void Clear()
    {
        try { File.Delete(Config.AuthFile); } catch { }
    }

    // Runs the browser login flow. Blocks until the token arrives, the user
    // cancels (timeout), or an error occurs. Returns the AuthData on success.
    public static async Task<AuthData> LoginAsync(CancellationToken ct = default)
    {
        using var listener = new HttpListener();
        int port = FreePort();
        string prefix = $"http://127.0.0.1:{port}/";
        listener.Prefixes.Add(prefix);
        listener.Start();

        var redirect = $"http://127.0.0.1:{port}/cb";
        var url = $"{Config.HandoffUrl}?redirect={Uri.EscapeDataString(redirect)}";
        OpenBrowser(url);

        // 5-minute overall timeout.
        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutCts.CancelAfter(TimeSpan.FromMinutes(5));

        while (true)
        {
            var ctxTask = listener.GetContextAsync();
            var completed = await Task.WhenAny(ctxTask, Task.Delay(Timeout.Infinite, timeoutCts.Token));
            if (completed != ctxTask) throw new OperationCanceledException("login timeout");

            var context = await ctxTask;
            var req = context.Request;
            if (req.Url?.AbsolutePath != "/cb")
            {
                Respond(context, 404, "not found");
                continue;
            }

            var token = req.QueryString["token"] ?? "";
            var name = req.QueryString["name"] ?? "Player";
            var account = req.QueryString["account"] ?? "";

            Respond(context, 200, token.Length > 0
                ? "<!doctype html><meta charset='utf-8'><title>PetusLauncher</title>" +
                  "<body style='font-family:Tahoma;background:#e9edf3;color:#333;text-align:center;padding:60px'>" +
                  "<h3 style='color:#2b587a'>Готово!</h3><p>Можно вернуться в лаунчер PetusGDPS — вход выполнен.</p></body>"
                : "<!doctype html><meta charset='utf-8'><body style='font-family:Tahoma;text-align:center;padding:60px'>" +
                  "<h3>Ошибка входа</h3><p>Токен не получен.</p></body>");

            listener.Stop();
            if (token.Length == 0) throw new Exception("no token");
            var auth = new AuthData(token, name, account);
            Save(auth);
            return auth;
        }
    }

    static void Respond(HttpListenerContext ctx, int code, string html)
    {
        try
        {
            var bytes = System.Text.Encoding.UTF8.GetBytes(html);
            ctx.Response.StatusCode = code;
            ctx.Response.ContentType = "text/html; charset=utf-8";
            ctx.Response.ContentLength64 = bytes.Length;
            ctx.Response.OutputStream.Write(bytes, 0, bytes.Length);
            ctx.Response.OutputStream.Close();
        }
        catch { }
    }

    static int FreePort()
    {
        var l = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        l.Start();
        int port = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        return port;
    }

    public static void OpenBrowser(string url)
    {
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = url,
                UseShellExecute = true,
            });
        }
        catch { }
    }
}

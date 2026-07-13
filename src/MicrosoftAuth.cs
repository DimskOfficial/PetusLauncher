using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace PetusLauncher;

// Microsoft account auth for Minecraft. Full device-code → Xbox Live → XSTS →
// Minecraft services chain, plus silent refresh and offline accounts.
// Uses the public MC client id (Config.MsClientId), device-code flow, so no
// client secret or redirect server is needed.
static class MicrosoftAuth
{
    const string DeviceCodeUrl = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    const string TokenUrl = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    const string XblUrl = "https://user.auth.xboxlive.com/user/authenticate";
    const string XstsUrl = "https://xsts.auth.xboxlive.com/xsts/authorize";
    const string McLoginUrl = "https://api.minecraftservices.com/authentication/login_with_xbox";
    const string McProfileUrl = "https://api.minecraftservices.com/minecraft/profile";
    const string Scope = "XboxLive.signin offline_access";

    static readonly HttpClient Http = MakeClient();

    static HttpClient MakeClient()
    {
        var c = new HttpClient { Timeout = TimeSpan.FromSeconds(60) };
        c.DefaultRequestHeaders.UserAgent.ParseAdd("PetusLauncher/2.1");
        c.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        return c;
    }

    // Device-code login. showCode(userCode, verificationUri) lets the UI tell the
    // user where to go. Polls until authorized, runs the full chain, returns a
    // ready Microsoft McAccount. Throws on failure / timeout / expiry.
    public static async Task<McAccount> LoginAsync(Action<string, string> showCode, CancellationToken ct)
    {
        // 1) Request a device code.
        using var codeResp = await Http.PostAsync(DeviceCodeUrl, Form(new()
        {
            ["client_id"] = Config.MsClientId,
            ["scope"] = Scope,
        }), ct);
        var codeBody = await codeResp.Content.ReadAsStringAsync(ct);
        if (!codeResp.IsSuccessStatusCode)
        {
            // Surface Microsoft's real reason instead of a generic 400 so the
            // error is actionable (usually: the client_id isn't authorized for
            // the device-code flow / consumers endpoint).
            string detail = codeBody;
            try
            {
                var e = JsonDocument.Parse(codeBody).RootElement;
                detail = (e.TryGetProperty("error_description", out var d) ? d.GetString() : null)
                       ?? (e.TryGetProperty("error", out var er) ? er.GetString() : null)
                       ?? codeBody;
            }
            catch { }
            throw new Exception($"Microsoft отклонил запрос ({(int)codeResp.StatusCode}): {detail}");
        }
        var root = JsonDocument.Parse(codeBody).RootElement;
        var deviceCode = root.GetProperty("device_code").GetString()!;
        var userCode = root.GetProperty("user_code").GetString()!;
        var verifyUri = root.GetProperty("verification_uri").GetString()!;
        int interval = root.TryGetProperty("interval", out var iv) ? iv.GetInt32() : 5;
        int expiresIn = root.TryGetProperty("expires_in", out var ex) ? ex.GetInt32() : 900;

        showCode(userCode, verifyUri);

        // 2) Poll the token endpoint until the user authorizes (or it expires).
        var deadline = DateTime.UtcNow.AddSeconds(expiresIn);
        (string accessToken, string refreshToken) ms;
        while (true)
        {
            ct.ThrowIfCancellationRequested();
            if (DateTime.UtcNow >= deadline)
                throw new TimeoutException("Время ожидания входа истекло. Попробуйте снова.");
            await Task.Delay(TimeSpan.FromSeconds(interval), ct);

            using var poll = await Http.PostAsync(TokenUrl, Form(new()
            {
                ["client_id"] = Config.MsClientId,
                ["grant_type"] = "urn:ietf:params:oauth:grant-type:device_code",
                ["device_code"] = deviceCode,
            }), ct);
            using var pollJson = await ParseJson(poll);
            var pr = pollJson.RootElement;

            if (poll.IsSuccessStatusCode)
            {
                ms = (pr.GetProperty("access_token").GetString()!,
                      pr.GetProperty("refresh_token").GetString()!);
                break;
            }

            var error = pr.TryGetProperty("error", out var e) ? e.GetString() : "unknown";
            if (error == "authorization_pending") continue;      // user hasn't finished yet
            if (error == "slow_down") { interval += 5; continue; } // back off as requested
            if (error == "authorization_declined") throw new Exception("Вход отклонён пользователем.");
            if (error == "expired_token") throw new TimeoutException("Код устарел. Попробуйте снова.");
            throw new Exception($"Ошибка входа Microsoft: {error}");
        }

        return await FinishChainAsync(ms.accessToken, ms.refreshToken, ct);
    }

    // Silent refresh from a stored refresh token. Returns null when the token is
    // no longer valid (user must log in again). Non-Microsoft accounts → null.
    public static async Task<McAccount?> RefreshAsync(McAccount acc)
    {
        if (acc.Type != "microsoft" || string.IsNullOrEmpty(acc.RefreshToken)) return null;
        try
        {
            using var resp = await Http.PostAsync(TokenUrl, Form(new()
            {
                ["client_id"] = Config.MsClientId,
                ["grant_type"] = "refresh_token",
                ["refresh_token"] = acc.RefreshToken,
                ["scope"] = Scope,
            }));
            if (!resp.IsSuccessStatusCode) return null;
            using var json = await ParseJson(resp);
            var r = json.RootElement;
            var access = r.GetProperty("access_token").GetString()!;
            var refresh = r.TryGetProperty("refresh_token", out var rt) ? rt.GetString()! : acc.RefreshToken;
            return await FinishChainAsync(access, refresh, CancellationToken.None);
        }
        catch { return null; }
    }

    // Offline account: name only. UUID is a name-based v3 (MD5) UUID over
    // "OfflinePlayer:<name>", matching the vanilla offline-mode convention.
    public static McAccount OfflineAccount(string name)
    {
        var uuid = NameUuidV3("OfflinePlayer:" + name);
        return new McAccount(name, uuid, "0", "offline", 0, "");
    }

    // -------------------------------------------------------- the token chain

    // MS access token → XBL → XSTS → Minecraft → profile → McAccount.
    static async Task<McAccount> FinishChainAsync(string msAccess, string refreshToken, CancellationToken ct)
    {
        // Xbox Live user token.
        var (xblToken, uhs) = await XblAuthAsync(msAccess, ct);
        // XSTS token (authorizes for the Minecraft relying party).
        var xstsToken = await XstsAuthAsync(xblToken, ct);
        // Minecraft services access token.
        var mcToken = await McLoginAsync(uhs, xstsToken, ct);
        // Profile (name + uuid). Fails if the account owns no copy of the game.
        var (name, uuid) = await McProfileAsync(mcToken, ct);

        // MS refresh tokens live ~90 days; store an approximate expiry.
        long refreshExpires = DateTimeOffset.UtcNow.AddDays(80).ToUnixTimeSeconds();
        return new McAccount(name, uuid, mcToken, "microsoft", refreshExpires, refreshToken);
    }

    static async Task<(string token, string uhs)> XblAuthAsync(string msAccess, CancellationToken ct)
    {
        var body = new
        {
            Properties = new
            {
                AuthMethod = "RPS",
                SiteName = "user.auth.xboxlive.com",
                RpsTicket = "d=" + msAccess, // 'd=' prefix required for MSA tokens
            },
            RelyingParty = "http://auth.xboxlive.com",
            TokenType = "JWT",
        };
        using var resp = await Http.PostAsync(XblUrl, JsonContent(body), ct);
        if (!resp.IsSuccessStatusCode)
            throw new Exception("Xbox Live отклонил вход (XBL).");
        using var json = await ParseJson(resp);
        var r = json.RootElement;
        var token = r.GetProperty("Token").GetString()!;
        var uhs = r.GetProperty("DisplayClaims").GetProperty("xui")[0].GetProperty("uhs").GetString()!;
        return (token, uhs);
    }

    static async Task<string> XstsAuthAsync(string xblToken, CancellationToken ct)
    {
        var body = new
        {
            Properties = new
            {
                SandboxId = "RETAIL",
                UserTokens = new[] { xblToken },
            },
            RelyingParty = "rp://api.minecraftservices.com/",
            TokenType = "JWT",
        };
        using var resp = await Http.PostAsync(XstsUrl, JsonContent(body), ct);
        using var json = await ParseJson(resp);
        var r = json.RootElement;
        if (!resp.IsSuccessStatusCode)
        {
            // Known XSTS error codes → friendly messages.
            long xerr = r.TryGetProperty("XErr", out var xe) ? xe.GetInt64() : 0;
            throw xerr switch
            {
                2148916233 => new Exception("У этого аккаунта Microsoft нет профиля Xbox. Создайте его на xbox.com."),
                2148916235 => new Exception("Xbox Live недоступен в вашем регионе."),
                2148916236 or 2148916237 => new Exception("Требуется подтверждение возраста для этого аккаунта."),
                2148916238 => new Exception("Детский аккаунт: добавьте его в семейную группу Microsoft."),
                _ => new Exception($"Ошибка XSTS ({xerr}).")
            };
        }
        return r.GetProperty("Token").GetString()!;
    }

    static async Task<string> McLoginAsync(string uhs, string xstsToken, CancellationToken ct)
    {
        var body = new { identityToken = $"XBL3.0 x={uhs};{xstsToken}" };
        using var resp = await Http.PostAsync(McLoginUrl, JsonContent(body), ct);
        if (!resp.IsSuccessStatusCode)
            throw new Exception("Не удалось войти в сервисы Minecraft.");
        using var json = await ParseJson(resp);
        return json.RootElement.GetProperty("access_token").GetString()!;
    }

    static async Task<(string name, string uuid)> McProfileAsync(string mcToken, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, McProfileUrl);
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", mcToken);
        using var resp = await Http.SendAsync(req, ct);
        if (!resp.IsSuccessStatusCode)
            throw new Exception("На этом аккаунте нет купленной копии Minecraft.");
        using var json = await ParseJson(resp);
        var r = json.RootElement;
        var name = r.GetProperty("name").GetString()!;
        var id = r.GetProperty("id").GetString()!; // 32 hex chars, no dashes
        return (name, id);
    }

    // ----------------------------------------------------------- small helpers

    static FormUrlEncodedContent Form(Dictionary<string, string> d) => new(d);

    static StringContent JsonContent(object o)
        => new(JsonSerializer.Serialize(o), Encoding.UTF8, "application/json");

    static async Task<JsonDocument> ReadJson(HttpResponseMessage resp)
    {
        resp.EnsureSuccessStatusCode();
        return JsonDocument.Parse(await resp.Content.ReadAsStringAsync());
    }

    // Parse a response body as JSON regardless of status (callers inspect errors).
    static async Task<JsonDocument> ParseJson(HttpResponseMessage resp)
        => JsonDocument.Parse(await resp.Content.ReadAsStringAsync());

    // Name-based v3 (MD5) UUID, RFC 4122: set version + variant bits.
    static string NameUuidV3(string input)
    {
        byte[] hash = MD5.HashData(Encoding.UTF8.GetBytes(input));
        hash[6] = (byte)((hash[6] & 0x0F) | 0x30); // version 3
        hash[8] = (byte)((hash[8] & 0x3F) | 0x80); // RFC 4122 variant
        var hex = Convert.ToHexString(hash).ToLowerInvariant();
        return $"{hex[..8]}-{hex[8..12]}-{hex[12..16]}-{hex[16..20]}-{hex[20..]}";
    }
}

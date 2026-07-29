package ru.petus.launcher.api;

import com.sun.net.httpserver.HttpServer;
import ru.petus.launcher.core.Log;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Browser side of the PetusID login.
 *
 * The launcher can not hold the OAuth client secret, so the flow is:
 *   1. bind a loopback HTTP server on a free port
 *   2. open {api}/oauth/launcher/start?port=... in the system browser
 *   3. the backend finishes the code exchange and redirects the browser to
 *      http://127.0.0.1:{port}/cb?handoff=...
 *   4. we exchange the one-time handoff code for real tokens
 *
 * Nothing secret ever touches the launcher binary, and the browser keeps the
 * user's existing PetusID cookie, so most logins are a single click.
 */
public final class PetusIdFlow implements AutoCloseable {
    private final HttpServer server;
    private final CompletableFuture<String> handoff = new CompletableFuture<>();
    private final int port;

    public PetusIdFlow() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/cb", exchange -> {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String code = query.get("handoff");
            String error = query.get("error");
            byte[] body = (code != null ? successHtml() : errorHtml(error)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(code != null ? 200 : 400, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            if (code != null) {
                handoff.complete(code);
            } else {
                handoff.completeExceptionally(new IOException(error == null ? "login_failed" : error));
            }
        });
        server.setExecutor(null);
        server.start();
        Log.info("Loopback OAuth listener on 127.0.0.1:" + port);
    }

    public int port() {
        return port;
    }

    public void openBrowser(String url) {
        Log.info("Opening browser for PetusID login");
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception error) {
            Log.warn("Desktop.browse failed: " + error);
        }
        fallbackOpen(url);
    }

    private void fallbackOpen(String url) {
        try {
            String[] command = switch (ru.petus.launcher.core.AppDirs.os()) {
                case WINDOWS -> new String[] { "rundll32", "url.dll,FileProtocolHandler", url };
                case MACOS -> new String[] { "open", url };
                case LINUX -> new String[] { "xdg-open", url };
            };
            new ProcessBuilder(command).start();
        } catch (IOException error) {
            Log.error("Cannot open a browser for " + url, error);
        }
    }

    /** Waits for the browser round trip and returns launcher tokens. */
    public Models.Handoff await(long timeoutSeconds) throws IOException {
        try {
            String code = handoff.get(timeoutSeconds, TimeUnit.SECONDS);
            return ApiClient.get().claim(code);
        } catch (TimeoutException timeout) {
            throw new IOException("Время ожидания входа истекло. Попробуйте ещё раз.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Вход отменён");
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IOException(failure.getCause() == null ? failure.getMessage()
                    : failure.getCause().getMessage());
        }
    }

    public void cancel() {
        handoff.completeExceptionally(new IOException("Вход отменён"));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            int split = pair.indexOf('=');
            if (split <= 0) {
                continue;
            }
            values.put(java.net.URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    private static String page(String title, String message, String accent) {
        return """
                <!doctype html><html lang="ru"><head><meta charset="utf-8">
                <title>PetusLauncher</title>
                <style>
                  :root { color-scheme: dark }
                  body { margin:0; min-height:100vh; display:grid; place-items:center;
                         background:radial-gradient(1200px 600px at 50% -10%, #1b1b22, #0f0f13 60%);
                         color:#f4f4f5; font:15px/1.5 -apple-system, "Segoe UI", Inter, system-ui, sans-serif }
                  .card { width:min(420px, 90vw); padding:32px; border-radius:16px; text-align:center;
                          background:#17171c; border:1px solid rgba(255,255,255,.08);
                          box-shadow:0 24px 60px rgba(0,0,0,.45) }
                  .dot { width:52px; height:52px; margin:0 auto 18px; border-radius:16px;
                         display:grid; place-items:center; font-size:26px; background:ACCENT22; color:ACCENT }
                  h1 { margin:0 0 8px; font-size:20px; letter-spacing:-.01em }
                  p { margin:0; color:#a1a1aa }
                </style></head><body><div class="card">
                <div class="dot">✓</div><h1>TITLE</h1><p>MESSAGE</p>
                </div></body></html>
                """
                .replace("TITLE", title)
                .replace("MESSAGE", message)
                .replace("ACCENT22", accent + "22")
                .replace("ACCENT", accent);
    }

    private static String successHtml() {
        return page("Вход выполнен", "Можно закрыть эту вкладку и вернуться в PetusLauncher.", "#7C5CFF");
    }

    private static String errorHtml(String error) {
        return page("Войти не получилось", error == null ? "PetusID отклонил авторизацию." : error, "#E5484D");
    }
}

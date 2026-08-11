package com.example.voward;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serves the browser block page locally without an internet connection. */
final class StaticBlockPageServer implements AutoCloseable {
    private static final String TAG = "BlockPageServer";
    private static final int MAX_REQUEST_BYTES = 8 * 1024;
    private static final byte[] PAGE_BYTES = buildPage().getBytes(StandardCharsets.UTF_8);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread serverThread;
    private String pageAddress;

    /**
     * Starts a loopback-only HTTP listener and returns its block-page address.
     * Returns {@code null} when the listener cannot be created, allowing callers to retain
     * their browser-native blank-page fallback.
     */
    synchronized String start() {
        if (running.get()) return pageAddress;
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
            serverSocket = socket;
            pageAddress = "http://127.0.0.1:" + socket.getLocalPort() + "/blocked";
            running.set(true);
            serverThread = new Thread(this::serve, "habit-rewire-block-page");
            serverThread.setDaemon(true);
            serverThread.start();
            return pageAddress;
        } catch (IOException e) {
            Log.e(TAG, "Could not start the local block page", e);
            close();
            return null;
        }
    }

    private void serve() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                serveClient(client);
            } catch (IOException e) {
                if (running.get()) Log.w(TAG, "Local block-page request failed", e);
            }
        }
    }

    private void serveClient(Socket client) {
        try (Socket socket = client;
             BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            socket.setSoTimeout(2000);
            String requestLine = readRequestLine(input);
            if (requestLine == null) return;
            boolean headOnly = requestLine.startsWith("HEAD ");
            if (!headOnly && !requestLine.startsWith("GET ")) {
                writeResponse(output, "405 Method Not Allowed", new byte[0], true);
                return;
            }
            writeResponse(output, "200 OK", PAGE_BYTES, headOnly);
        } catch (IOException e) {
            Log.d(TAG, "Browser closed the local block-page connection", e);
        }
    }

    private static String readRequestLine(BufferedInputStream input) throws IOException {
        byte[] bytes = new byte[MAX_REQUEST_BYTES];
        int count = 0;
        int previous = -1;
        while (count < bytes.length) {
            int current = input.read();
            if (current < 0) break;
            bytes[count++] = (byte) current;
            if (previous == '\r' && current == '\n') {
                return new String(bytes, 0, count - 2, StandardCharsets.US_ASCII)
                        .toUpperCase(Locale.ROOT);
            }
            previous = current;
        }
        return null;
    }

    private static void writeResponse(
            BufferedOutputStream output, String status, byte[] body, boolean headOnly)
            throws IOException {
        String headers = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store, max-age=0\r\n"
                + "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; "
                + "img-src data:\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        if (!headOnly) output.write(body);
        output.flush();
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
        serverSocket = null;
        serverThread = null;
        pageAddress = null;
    }

    private static String buildPage() {
        return "<!doctype html><html lang=\"en\"><head>"
                + "<meta charset=\"utf-8\"><meta name=\"viewport\" "
                + "content=\"width=device-width,initial-scale=1,viewport-fit=cover\">"
                + "<meta name=\"color-scheme\" content=\"light dark\">"
                + "<title>Pause · Voward</title><style>"
                + ":root{color-scheme:light dark;font-family:Inter,ui-sans-serif,system-ui,-apple-system,"
                + "BlinkMacSystemFont,\"Segoe UI\",sans-serif;--ink:#17213a;--muted:#64708b;"
                + "--card:rgba(255,255,255,.78);--line:rgba(26,58,107,.12);--accent:#4263eb;"
                + "--accent2:#0ca6a6;--shadow:0 30px 90px rgba(30,48,91,.18)}"
                + "*{box-sizing:border-box}body{margin:0;min-height:100svh;display:grid;place-items:center;"
                + "padding:max(24px,env(safe-area-inset-top)) max(20px,env(safe-area-inset-right)) "
                + "max(24px,env(safe-area-inset-bottom)) max(20px,env(safe-area-inset-left));color:var(--ink);"
                + "background:radial-gradient(circle at 12% 12%,rgba(66,99,235,.20),transparent 34%),"
                + "radial-gradient(circle at 88% 82%,rgba(12,166,166,.20),transparent 36%),#f4f7ff}"
                + ".card{position:relative;overflow:hidden;width:min(100%,620px);padding:clamp(30px,7vw,64px);"
                + "border:1px solid var(--line);border-radius:32px;background:var(--card);"
                + "box-shadow:var(--shadow);backdrop-filter:blur(18px)}"
                + ".card:after{content:\"\";position:absolute;width:220px;height:220px;border-radius:50%;"
                + "right:-110px;top:-120px;background:linear-gradient(135deg,var(--accent),var(--accent2));"
                + "opacity:.10}.mark{width:72px;height:72px;display:grid;place-items:center;border-radius:24px;"
                + "background:linear-gradient(135deg,var(--accent),var(--accent2));box-shadow:0 14px 36px "
                + "rgba(66,99,235,.26);color:white}.mark svg{width:34px;height:34px}"
                + ".eyebrow{margin:28px 0 12px;font-size:.76rem;font-weight:750;letter-spacing:.16em;"
                + "text-transform:uppercase;color:var(--accent)}h1{margin:0;font-size:clamp(2.25rem,8vw,4.2rem);"
                + "line-height:1.02;letter-spacing:-.055em;max-width:9ch}p{margin:22px 0 0;max-width:46ch;"
                + "font-size:clamp(1rem,3.2vw,1.18rem);line-height:1.65;color:var(--muted)}"
                + ".hint{display:flex;align-items:center;gap:11px;margin-top:34px;padding-top:24px;"
                + "border-top:1px solid var(--line);font-size:.9rem;color:var(--muted)}"
                + ".dot{width:9px;height:9px;border-radius:50%;background:var(--accent2);"
                + "box-shadow:0 0 0 6px rgba(12,166,166,.12)}"
                + "@media(prefers-color-scheme:dark){:root{--ink:#eef2ff;--muted:#abb6d0;"
                + "--card:rgba(18,26,49,.82);--line:rgba(201,213,255,.13);"
                + "--shadow:0 30px 100px rgba(0,0,0,.4)}body{background:"
                + "radial-gradient(circle at 12% 12%,rgba(86,112,255,.20),transparent 34%),"
                + "radial-gradient(circle at 88% 82%,rgba(19,180,171,.17),transparent 36%),#0b1020}}"
                + "</style></head><body><main class=\"card\"><div class=\"mark\" aria-hidden=\"true\">"
                + "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" "
                + "stroke-linecap=\"round\"><path d=\"M8 5v14M16 5v14\"/></svg></div>"
                + "<div class=\"eyebrow\">Voward</div><h1>This page can wait.</h1>"
                + "<p>Your attention budget is protecting this moment. Take a breath, then choose a "
                + "destination that supports what you meant to do.</p><div class=\"hint\"><span class=\"dot\">"
                + "</span><span>Close this tab or enter another address above.</span></div>"
                + "</main></body></html>";
    }
}

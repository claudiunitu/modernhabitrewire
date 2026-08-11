package com.example.voward;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class StaticBlockPageServerTest {
    private StaticBlockPageServer server;

    @Before
    public void setUp() {
        server = new StaticBlockPageServer();
    }

    @After
    public void tearDown() {
        server.close();
    }

    @Test
    public void startIsIdempotentAndCloseAllowsCleanRestart() throws Exception {
        String first = server.start();
        assertNotNull(first);
        assertEquals(first, server.start());
        server.close();
        String restarted = server.start();
        assertNotNull(restarted);
        assertEquals(200, open(restarted, "GET").getResponseCode());
    }

    @Test
    public void getReturnsSelfContainedSecureNoStorePage() throws Exception {
        HttpURLConnection connection = open(server.start(), "GET");
        assertEquals(200, connection.getResponseCode());
        assertEquals("text/html; charset=utf-8", connection.getContentType());
        assertEquals("no-store, max-age=0", connection.getHeaderField("Cache-Control"));
        assertEquals("nosniff", connection.getHeaderField("X-Content-Type-Options"));
        assertEquals("no-referrer", connection.getHeaderField("Referrer-Policy"));
        assertTrue(connection.getHeaderField("Content-Security-Policy")
                .contains("default-src 'none'"));
        String page;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            page = reader.lines().collect(Collectors.joining("\n"));
        }
        assertTrue(page.contains("This page can wait."));
        assertTrue(page.contains("Voward"));
        assertFalse(page.toLowerCase().contains("<script"));
        connection.disconnect();
    }

    @Test
    public void headReturnsHeadersWithoutBody() throws Exception {
        HttpURLConnection connection = open(server.start(), "HEAD");
        assertEquals(200, connection.getResponseCode());
        assertTrue(connection.getContentLengthLong() > 0);
        assertEquals(-1, connection.getInputStream().read());
        connection.disconnect();
    }

    @Test
    public void unsupportedMethodReturns405AndNoBody() throws Exception {
        URL url = new URL(server.start());
        try (Socket socket = new Socket(url.getHost(), url.getPort())) {
            socket.setSoTimeout(2_000);
            OutputStream output = socket.getOutputStream();
            output.write(("POST /blocked HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
            String response = new String(socket.getInputStream().readAllBytes(),
                    StandardCharsets.US_ASCII);
            assertTrue(response.startsWith("HTTP/1.1 405 Method Not Allowed"));
            assertTrue(response.contains("Content-Length: 0"));
        }
    }

    private static HttpURLConnection open(String address, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        return connection;
    }
}

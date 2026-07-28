package com.partygames.app;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

public class PartyGamesServer extends NanoWSD {

    private static final String TAG = "PartyGames";
    private final Context context;
    private final Map<String, WebSocket> clients = new ConcurrentHashMap<>();
    private String hostIp = "127.0.0.1";
    private int port;

    public PartyGamesServer(Context context, int port) {
        super(port);
        this.context = context;
        this.port = port;
        this.hostIp = getWifiIp();
    }

    private String getWifiIp() {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wifi.getConnectionInfo();
            int ip = info.getIpAddress();
            if (ip != 0) {
                return String.format("%d.%d.%d.%d",
                    (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get WiFi IP", e);
        }
        return "127.0.0.1";
    }

    public String getHostIp() { return hostIp; }
    public int getPort() { return port; }

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        String clientId = handshake.getRemoteIpAddress() + "-" + System.currentTimeMillis();
        GameSocket socket = new GameSocket(handshake, clientId);
        return socket;
    }

    @Override
    public Response serveHttp(IHTTPSession session) {
        String uri = session.getUri();

        // Serve index.html
        if ("/".equals(uri) || "/index.html".equals(uri)) {
            try {
                InputStream is = context.getAssets().open("public/index.html");
                String html = readStream(is);
                return newFixedLengthResponse(Response.Status.OK, "text/html", html);
            } catch (IOException e) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
            }
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404");
    }

    private String readStream(InputStream is) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append("\n");
        r.close();
        return sb.toString();
    }

    // ── WebSocket Implementation ──

    private class GameSocket extends WebSocket {
        private final String clientId;

        public GameSocket(IHTTPSession handshake, String clientId) {
            super(handshake);
            this.clientId = clientId;
        }

        @Override
        protected void onOpen() {
            clients.put(clientId, this);
            Log.d(TAG, "WS open: " + clientId + " (total: " + clients.size() + ")");
        }

        @Override
        protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
            clients.remove(clientId);
            Log.d(TAG, "WS close: " + clientId + " (total: " + clients.size() + ")");
        }

        @Override
        protected void onMessage(WebSocketFrame message) {
            String text = message.getTextPayload();
            // Relay to ALL other clients (broadcast)
            for (Map.Entry<String, WebSocket> entry : clients.entrySet()) {
                if (!entry.getKey().equals(clientId)) {
                    try {
                        entry.getValue().send(text);
                    } catch (IOException e) {
                        Log.e(TAG, "Send failed", e);
                    }
                }
            }
        }

        @Override
        protected void onPong(WebSocketFrame pong) {}

        @Override
        protected void onException(IOException exception) {
            Log.e(TAG, "WS error: " + clientId, exception);
        }
    }
}

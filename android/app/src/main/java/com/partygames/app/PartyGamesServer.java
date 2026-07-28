package com.partygames.app;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

public class PartyGamesServer extends NanoWSD {

    private static final String TAG = "PartyGames";
    private final Context context;
    private String hostIp = "127.0.0.1";
    private int port;

    // Game state (same as Node.js server)
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

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

    // ── HTTP: serve index.html ──

    @Override
    public Response serveHttp(IHTTPSession session) {
        String uri = session.getUri();
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

    // ── WebSocket: game logic ──

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        return new GameSocket(handshake);
    }

    private class GameSocket extends WebSocket {
        private String playerId;
        private String roomCode;
        private String playerName;
        private String playerAvatar;
        private boolean isHost = false;

        public GameSocket(IHTTPSession handshake) {
            super(handshake);
        }

        @Override
        protected void onOpen() {
            Log.d(TAG, "WS connected");
        }

        @Override
        protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean remote) {
            if (roomCode != null && rooms.containsKey(roomCode)) {
                Room room = rooms.get(roomCode);
                room.players.remove(playerId);
                room.scores.remove(playerId);

                if (room.players.isEmpty()) {
                    rooms.remove(roomCode);
                } else {
                    if (playerId != null && playerId.equals(room.hostId) && !room.players.isEmpty()) {
                        // Transfer host
                        room.hostId = room.players.keySet().iterator().next();
                        sendToOne(room.players.get(room.hostId).socket, msg("you_are_host"));
                    }
                    broadcastRoom(room, msg("player_list", "players", getPlayerList(room)));
                }
            }
        }

        @Override
        protected void onMessage(WebSocketFrame message) {
            String text = message.getTextPayload();
            Map<String, Object> msg;
            try {
                msg = parseJson(text);
            } catch (Exception e) {
                return;
            }

            String type = (String) msg.get("type");
            if (type == null) return;

            switch (type) {
                case "create_room": {
                    roomCode = genRoomCode();
                    playerId = genId();
                    playerName = (String) msg.getOrDefault("name", "Host");
                    playerAvatar = (String) msg.getOrDefault("avatar", "🦊");
                    isHost = true;

                    Room room = new Room();
                    room.hostId = playerId;
                    room.players.put(playerId, new Player(this, playerName, playerAvatar));
                    room.scores.put(playerId, 0);
                    rooms.put(roomCode, room);

                    sendToMe(msg("room_created",
                        "roomCode", roomCode,
                        "playerId", playerId,
                        "isHost", true));
                    sendToMe(msg("player_list", "players", getPlayerList(room)));
                    break;
                }

                case "join_room": {
                    roomCode = (String) msg.get("roomCode");
                    playerId = genId();
                    playerName = (String) msg.getOrDefault("name", "Player");
                    playerAvatar = (String) msg.getOrDefault("avatar", "🐱");

                    if (!rooms.containsKey(roomCode)) {
                        sendToMe(msg("error", "message", "Room not found"));
                        return;
                    }

                    Room room = rooms.get(roomCode);
                    room.players.put(playerId, new Player(this, playerName, playerAvatar));
                    room.scores.put(playerId, 0);

                    sendToMe(msg("joined",
                        "roomCode", roomCode,
                        "playerId", playerId,
                        "isHost", false));
                    broadcastRoom(room, msg("player_list", "players", getPlayerList(room)));
                    break;
                }

                case "start_game": {
                    if (!isHost || roomCode == null || !rooms.containsKey(roomCode)) return;
                    Room room = rooms.get(roomCode);
                    room.currentGame = (String) msg.get("game");

                    broadcastRoom(room, msg("game_starting", "game", room.currentGame));

                    // After 3s, start the game
                    final String game = room.currentGame;
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (!rooms.containsKey(roomCode)) return;
                        broadcastRoom(rooms.get(roomCode),
                            msg("game_start", "game", game, "round", 1, "config", new HashMap<>()));
                    }, 3000);
                    break;
                }

                case "game_result": {
                    if (roomCode == null || !rooms.containsKey(roomCode)) return;
                    Room room = rooms.get(roomCode);
                    String hostId = room.hostId;
                    if (hostId != null && room.players.containsKey(hostId)) {
                        sendToOne(room.players.get(hostId).socket,
                            msg("player_result",
                                "playerId", playerId,
                                "name", playerName,
                                "result", msg));
                    }
                    break;
                }

                case "broadcast_event": {
                    if (!isHost || roomCode == null || !rooms.containsKey(roomCode)) return;
                    broadcastRoom(rooms.get(roomCode),
                        msg("game_event", "event", msg.get("event"), "data", msg.get("data")));
                    break;
                }

                case "update_scores": {
                    if (!isHost || roomCode == null || !rooms.containsKey(roomCode)) return;
                    Room room = rooms.get(roomCode);
                    Map<String, Object> scoresMap = (Map<String, Object>) msg.get("scores");
                    if (scoresMap != null) {
                        for (Map.Entry<String, Object> e : scoresMap.entrySet()) {
                            int existing = room.scores.getOrDefault(e.getKey(), 0);
                            room.scores.put(e.getKey(), existing + toInt(e.getValue()));
                        }
                    }
                    broadcastRoom(room, msg("scoreboard",
                        "scores", room.scores,
                        "players", getPlayerList(room)));
                    break;
                }

                case "back_to_lobby": {
                    if (!isHost || roomCode == null || !rooms.containsKey(roomCode)) return;
                    Room room = rooms.get(roomCode);
                    room.currentGame = null;
                    broadcastRoom(room, msg("back_to_lobby",
                        "scores", room.scores,
                        "players", getPlayerList(room)));
                    break;
                }

                case "get_players": {
                    if (roomCode == null || !rooms.containsKey(roomCode)) return;
                    sendToMe(msg("player_list", "players", getPlayerList(rooms.get(roomCode))));
                    break;
                }
            }
        }

        @Override
        protected void onPong(WebSocketFrame pong) {}

        @Override
        protected void onException(IOException exception) {
            Log.e(TAG, "WS error", exception);
        }
    }

    // ── Helpers ──

    private String genRoomCode() {
        return String.valueOf(100000 + (int)(Math.random() * 900000));
    }

    private String genId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private int toInt(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    private void sendToMe(Map<String, Object> msg) {
        try { send(json(msg)); } catch (IOException e) { Log.e(TAG, "sendToMe failed", e); }
    }

    private void sendToOne(WebSocket ws, Map<String, Object> msg) {
        try { ws.send(json(msg)); } catch (IOException e) { Log.e(TAG, "sendToOne failed", e); }
    }

    private void broadcastRoom(Room room, Map<String, Object> msg) {
        String text = json(msg);
        for (Player p : room.players.values()) {
            try { p.socket.send(text); } catch (IOException e) { /* skip */ }
        }
    }

    private List<Map<String, Object>> getPlayerList(Room room) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Player> e : room.players.entrySet()) {
            Map<String, Object> p = new HashMap<>();
            p.put("id", e.getKey());
            p.put("name", e.getValue().name);
            p.put("avatar", e.getValue().avatar);
            p.put("isHost", e.getKey().equals(room.hostId));
            list.add(p);
        }
        return list;
    }

    // ── JSON (stdlib only — no Gson needed) ──

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String text) {
        // Minimal JSON parser for our simple message format
        Map<String, Object> map = new HashMap<>();
        text = text.trim();
        if (!text.startsWith("{") || !text.endsWith("}")) return map;
        text = text.substring(1, text.length() - 1);

        StringBuilder key = new StringBuilder();
        StringBuilder val = new StringBuilder();
        boolean inKey = true;
        boolean inString = false;
        int braceDepth = 0, bracketDepth = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') { val.append(c); if (i + 1 < text.length()) val.append(text.charAt(++i)); }
                else if (c == '"') inString = false;
                else val.append(c);
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') { braceDepth++; val.append(c); }
            else if (c == '}') { braceDepth--; val.append(c); }
            else if (c == '[') { bracketDepth++; val.append(c); }
            else if (c == ']') { bracketDepth--; val.append(c); }
            else if (c == ':' && braceDepth == 0 && bracketDepth == 0) {
                inKey = false;
            } else if (c == ',' && braceDepth == 0 && bracketDepth == 0) {
                map.put(strip(key.toString()), parseValue(strip(val.toString())));
                key.setLength(0); val.setLength(0);
                inKey = true;
            } else if (inKey) {
                key.append(c);
            } else {
                val.append(c);
            }
        }
        if (key.length() > 0) {
            map.put(strip(key.toString()), parseValue(strip(val.toString())));
        }
        return map;
    }

    private String strip(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    private Object parseValue(String v) {
        if (v.equals("true")) return true;
        if (v.equals("false")) return false;
        if (v.equals("null")) return null;
        try { return Integer.parseInt(v); } catch (Exception e) {}
        try { return Double.parseDouble(v); } catch (Exception e) {}
        if (v.startsWith("{") && v.endsWith("}")) return parseJson(v);
        return v;
    }

    private String json(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":");
            sb.append(jsonValue(e.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    private String jsonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return "\"" + escape((String) v) + "\"";
        if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
        if (v instanceof Map) return json((Map<String, Object>) v);
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (List) v) {
                if (!first) sb.append(",");
                first = false;
                sb.append(jsonValue(item));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escape(String.valueOf(v)) + "\"";
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private Map<String, Object> msg(String type) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        return m;
    }

    private Map<String, Object> msg(String type, String k1, Object v1) {
        Map<String, Object> m = msg(type);
        m.put(k1, v1);
        return m;
    }

    private Map<String, Object> msg(String type, String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = msg(type, k1, v1);
        m.put(k2, v2);
        return m;
    }

    private Map<String, Object> msg(String type, String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        Map<String, Object> m = msg(type, k1, v1, k2, v2);
        m.put(k3, v3);
        return m;
    }

    private Map<String, Object> msg(String type, String k1, Object v1, String k2, Object v2, String k3, Object v3, String k4, Object v4) {
        Map<String, Object> m = msg(type, k1, v1, k2, v2, k3, v3);
        m.put(k4, v4);
        return m;
    }

    // ── Data classes ──

    private static class Room {
        String hostId;
        Map<String, Player> players = new ConcurrentHashMap<>();
        Map<String, Integer> scores = new ConcurrentHashMap<>();
        String currentGame;
    }

    private static class Player {
        WebSocket socket;
        String name;
        String avatar;

        Player(WebSocket socket, String name, String avatar) {
            this.socket = socket;
            this.name = name;
            this.avatar = avatar;
        }
    }
}

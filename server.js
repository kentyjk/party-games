const { WebSocketServer } = require('ws');
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = process.env.PORT || 3456;

// Room storage
const rooms = new Map(); // roomCode -> { host, players: Map<id, {ws, name, avatar}>, gameState, scores }

function genRoomCode() {
  return Math.floor(100000 + Math.random() * 900000).toString();
}

function genId() {
  return crypto.randomBytes(4).toString('hex');
}

// HTTP server to serve static files
const server = http.createServer((req, res) => {
  const urlPath = req.url.split('?')[0];
  const filePath = urlPath === '/' ? '/index.html' : urlPath;
  const fullPath = path.join(__dirname, filePath);

  const mime = {
    '.html': 'text/html',
    '.js': 'application/javascript',
    '.css': 'text/css',
    '.png': 'image/png',
    '.svg': 'image/svg+xml',
    '.json': 'application/json',
  };

  const ext = path.extname(fullPath);
  const ct = mime[ext] || 'text/plain';

  fs.readFile(fullPath, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end('Not found');
      return;
    }
    res.writeHead(200, { 'Content-Type': ct });
    res.end(data);
  });
});

const wss = new WebSocketServer({ server });

function broadcast(room, msg, excludeId = null) {
  if (!rooms.has(room)) return;
  const data = JSON.stringify(msg);
  for (const [id, p] of rooms.get(room).players) {
    if (id !== excludeId && p.ws.readyState === 1) {
      p.ws.send(data);
    }
  }
}

function broadcastAll(room, msg) {
  if (!rooms.has(room)) return;
  const data = JSON.stringify(msg);
  for (const [id, p] of rooms.get(room).players) {
    if (p.ws.readyState === 1) p.ws.send(data);
  }
}

function send(ws, msg) {
  if (ws.readyState === 1) ws.send(JSON.stringify(msg));
}

wss.on('connection', (ws) => {
  let playerId = null;
  let roomCode = null;
  let isHost = false;

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }

    switch (msg.type) {

      // HOST creates room
      case 'create_room': {
        roomCode = genRoomCode();
        playerId = genId();
        isHost = true;
        rooms.set(roomCode, {
          host: playerId,
          players: new Map(),
          gameState: null,
          scores: {},
          currentGame: null,
        });
        const room = rooms.get(roomCode);
        room.players.set(playerId, { ws, name: msg.name || 'Host', avatar: msg.avatar || '🦊' });
        room.scores[playerId] = 0;
        send(ws, { type: 'room_created', roomCode, playerId, isHost: true });
        // Also send initial player list
        send(ws, { type: 'player_list', players: getPlayerList(room) });
        break;
      }

      // PLAYER joins room
      case 'join_room': {
        roomCode = msg.roomCode;
        playerId = genId();
        if (!rooms.has(roomCode)) {
          send(ws, { type: 'error', message: 'Room not found' });
          return;
        }
        const room = rooms.get(roomCode);
        room.players.set(playerId, { ws, name: msg.name || 'Player', avatar: msg.avatar || '🐱' });
        room.scores[playerId] = 0;
        send(ws, { type: 'joined', roomCode, playerId, isHost: false });

        // Tell host about new player
        const hostWs = room.players.get(room.host)?.ws;
        if (hostWs) send(hostWs, { type: 'player_list', players: getPlayerList(room) });

        // Broadcast updated player list
        broadcastAll(roomCode, { type: 'player_list', players: getPlayerList(room) });
        break;
      }

      // HOST starts a game
      case 'start_game': {
        if (!isHost || !rooms.has(roomCode)) return;
        const room = rooms.get(roomCode);
        room.currentGame = msg.game;
        room.gameState = { round: 0, phase: 'countdown' };

        // Tell everyone which game is starting
        broadcastAll(roomCode, { type: 'game_starting', game: msg.game });

        // After 3s countdown, start the game
        setTimeout(() => {
          if (!rooms.has(roomCode)) return;
          room.gameState.phase = 'playing';
          room.gameState.round = 1;
          broadcastAll(roomCode, { type: 'game_start', game: msg.game, round: 1, config: msg.config || {} });
        }, 3000);
        break;
      }

      // Player submits their result
      case 'game_result': {
        if (!rooms.has(roomCode)) return;
        const room = rooms.get(roomCode);
        const player = room.players.get(playerId);
        if (!player) return;
        player.lastResult = msg;
        // Send result to host to aggregate
        const hostWs = room.players.get(room.host)?.ws;
        if (hostWs) send(hostWs, { type: 'player_result', playerId, name: player.name, result: msg });
        break;
      }

      // Host broadcasts game event to all
      case 'broadcast_event': {
        if (!isHost || !rooms.has(roomCode)) return;
        broadcastAll(roomCode, { type: 'game_event', event: msg.event, data: msg.data });
        break;
      }

      // Host sends score update
      case 'update_scores': {
        if (!isHost || !rooms.has(roomCode)) return;
        const room = rooms.get(roomCode);
        if (msg.scores) {
          for (const [id, score] of Object.entries(msg.scores)) {
            room.scores[id] = (room.scores[id] || 0) + score;
          }
        }
        broadcastAll(roomCode, { type: 'scoreboard', scores: room.scores, players: getPlayerList(room) });
        break;
      }

      // Return to lobby
      case 'back_to_lobby': {
        if (!isHost || !rooms.has(roomCode)) return;
        const room = rooms.get(roomCode);
        room.currentGame = null;
        room.gameState = null;
        // Reset per-round results
        for (const [, p] of room.players) p.lastResult = null;
        broadcastAll(roomCode, { type: 'back_to_lobby', scores: room.scores, players: getPlayerList(room) });
        break;
      }

      // Request player list
      case 'get_players': {
        if (!rooms.has(roomCode)) return;
        send(ws, { type: 'player_list', players: getPlayerList(rooms.get(roomCode)) });
        break;
      }
    }
  });

  ws.on('close', () => {
    if (!roomCode || !rooms.has(roomCode)) return;
    const room = rooms.get(roomCode);
    room.players.delete(playerId);
    delete room.scores[playerId];

    if (room.players.size === 0) {
      rooms.delete(roomCode);
    } else {
      // If host left, assign new host
      if (playerId === room.host && room.players.size > 0) {
        room.host = room.players.keys().next().value;
        const newHostWs = room.players.get(room.host)?.ws;
        if (newHostWs) send(newHostWs, { type: 'you_are_host' });
      }
      broadcastAll(roomCode, { type: 'player_list', players: getPlayerList(room) });
    }
  });
});

function getPlayerList(room) {
  const list = [];
  for (const [id, p] of room.players) {
    list.push({ id, name: p.name, avatar: p.avatar, isHost: id === room.host });
  }
  return list;
}

server.listen(PORT, '0.0.0.0', () => {
  console.log(`🎮 Party Games server running on port ${PORT}`);
  console.log(`   Open http://localhost:${PORT} on your phone`);
});

'use strict';

const http = require('http');
const { URL } = require('url');

const PORT = Number(process.env.PORT || 8787);
const ROOM_TTL_MS = Number(process.env.ROOM_TTL_MS || 24 * 60 * 60 * 1000);
const PLAYER_STALE_MS = Number(process.env.PLAYER_STALE_MS || 5 * 60 * 1000);
const rooms = new Map();

function now() { return Date.now(); }
function norm(v) { return String(v || '').trim().toUpperCase(); }
function pkey(v) { return String(v || '').trim().toLowerCase(); }
function clampLong(v, min = 0) {
  const n = Number(v);
  return Number.isFinite(n) ? Math.max(min, Math.trunc(n)) : min;
}
function cleanName(v, fallback = '') {
  const s = String(v || '').trim();
  return (s || fallback).slice(0, 64);
}
function json(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
    'Cache-Control': 'no-store'
  });
  res.end(body);
}
function ok(res, room, message = '') { json(res, 200, { ok: true, message, room: snapshot(room) }); }
function bad(res, status, message) { json(res, status, { ok: false, message, room: null }); }
function snapshot(room) {
  if (!room) return null;
  const players = [...room.players.values()]
    .sort((a, b) => (b.score - a.score) || a.playerName.localeCompare(b.playerName))
    .map(p => ({
      playerName: p.playerName,
      score: p.score,
      remainingMilliseconds: p.remainingMilliseconds,
      loggedIn: p.loggedIn,
      raceState: p.raceState,
      lastSeen: p.lastSeen
    }));
  return {
    roomCode: room.roomCode,
    raceName: room.raceName,
    durationMilliseconds: room.durationMilliseconds,
    startingAllowance: room.startingAllowance,
    players
  };
}
function readJson(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', chunk => {
      data += chunk;
      if (data.length > 128 * 1024) {
        reject(new Error('Request too large'));
        req.destroy();
      }
    });
    req.on('end', () => {
      if (!data) return resolve({});
      try { resolve(JSON.parse(data)); }
      catch { reject(new Error('Invalid JSON')); }
    });
    req.on('error', reject);
  });
}
function getRoom(code) { return rooms.get(norm(code)); }
function touch(room) { room.lastActivity = now(); }

async function handleApi(req, res, url) {
  if (req.method === 'OPTIONS') return json(res, 204, {});

  if (req.method === 'GET' && url.pathname === '/health') {
    return json(res, 200, { ok: true, message: '0GP Race hosted API is running', rooms: rooms.size });
  }

  if (req.method === 'GET' && url.pathname === '/api/room') {
    const room = getRoom(url.searchParams.get('roomCode'));
    if (!room) return bad(res, 404, 'Room not found');
    touch(room);
    return ok(res, room);
  }

  if (req.method !== 'POST') return bad(res, 405, 'POST required');

  let body;
  try { body = await readJson(req); }
  catch (e) { return bad(res, 400, e.message || 'Invalid request'); }

  if (url.pathname === '/api/create') {
    const code = norm(body.roomCode);
    const player = cleanName(body.playerName);
    const duration = clampLong(body.durationMilliseconds);
    if (!code || !player || duration <= 0) return bad(res, 400, 'Missing room, player or duration');
    if (!/^0GP-[A-Z0-9]{4,10}$/.test(code)) return bad(res, 400, 'Invalid room code');
    if (rooms.has(code)) return bad(res, 409, 'Room code already exists - create again for a new code');

    const room = {
      roomCode: code,
      raceName: cleanName(body.raceName, '0GP Race'),
      durationMilliseconds: duration,
      startingAllowance: clampLong(body.startingAllowance),
      createdAt: now(),
      lastActivity: now(),
      players: new Map()
    };
    room.players.set(pkey(player), {
      playerName: player,
      score: Math.trunc(Number(body.score) || 0),
      remainingMilliseconds: clampLong(body.remainingMilliseconds),
      loggedIn: Boolean(body.loggedIn),
      raceState: 'RUNNING',
      lastSeen: now(),
      sequence: 0
    });
    rooms.set(code, room);
    return ok(res, room);
  }

  if (url.pathname === '/api/join') {
    const room = getRoom(body.roomCode);
    const player = cleanName(body.playerName);
    if (!room) return bad(res, 404, 'Room not found');
    if (!player) return bad(res, 400, 'Player name is required');
    touch(room);
    if (!room.players.has(pkey(player))) {
      room.players.set(pkey(player), {
        playerName: player,
        score: room.startingAllowance,
        remainingMilliseconds: room.durationMilliseconds,
        loggedIn: true,
        raceState: 'RUNNING',
        lastSeen: now(),
        sequence: 0
      });
    }
    return ok(res, room);
  }

  if (url.pathname === '/api/sync') {
    const room = getRoom(body.roomCode);
    const player = cleanName(body.playerName);
    if (!room) return bad(res, 404, 'Room not found');
    if (!player) return bad(res, 400, 'Player name is required');
    const sequence = clampLong(body.sequence);
    const key = pkey(player);
    const existing = room.players.get(key);
    if (existing && sequence < existing.sequence) return ok(res, room);
    room.players.set(key, {
      playerName: player,
      score: Math.trunc(Number(body.score) || 0),
      remainingMilliseconds: clampLong(body.remainingMilliseconds),
      loggedIn: Boolean(body.loggedIn),
      raceState: cleanName(body.raceState, 'RUNNING').toUpperCase(),
      lastSeen: now(),
      sequence
    });
    touch(room);
    return ok(res, room);
  }

  if (url.pathname === '/api/leave') {
    const room = getRoom(body.roomCode);
    const player = cleanName(body.playerName);
    if (!room) return bad(res, 404, 'Room not found');
    room.players.delete(pkey(player));
    touch(room);
    return ok(res, room);
  }

  return bad(res, 404, 'Unknown API endpoint');
}

function cleanup() {
  const cutoff = now() - ROOM_TTL_MS;
  for (const [code, room] of rooms) {
    if (room.lastActivity < cutoff) {
      rooms.delete(code);
      continue;
    }
    const stale = now() - PLAYER_STALE_MS;
    for (const player of room.players.values()) {
      if (player.lastSeen < stale && player.raceState === 'RUNNING') {
        player.loggedIn = false;
        player.raceState = 'PAUSED';
      }
    }
  }
}
setInterval(cleanup, 60_000).unref();

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  if (url.pathname === '/') {
    const body = '0GP Race API\n\nHealth: /health\nRoom lookup: /api/room?roomCode=0GP-1234\n';
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end(body);
  }
  handleApi(req, res, url).catch(err => {
    console.error(err);
    if (!res.headersSent) bad(res, 500, 'Internal server error');
    else res.end();
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`0GP Race hosted API listening on port ${PORT}`);
});

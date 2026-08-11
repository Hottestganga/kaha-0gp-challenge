'use strict';

const http = require('http');
const { URL } = require('url');

const PORT = Number(process.env.PORT || 8787);
const DISCORD_FINISHED_RACES_WEBHOOK = process.env.DISCORD_FINISHED_RACES_WEBHOOK || '';
const PUBLIC_WEBSITE_URL = (process.env.PUBLIC_WEBSITE_URL || '').replace(/\/$/, '');
const ROOM_TTL_MS = Number(process.env.ROOM_TTL_MS || 24 * 60 * 60 * 1000);
const PLAYER_STALE_MS = Number(process.env.PLAYER_STALE_MS || 5 * 60 * 1000);
const DEAD_ROOM_GRACE_MS = Number(process.env.DEAD_ROOM_GRACE_MS || 10 * 60 * 1000);
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

function ok(res, room, message = '') {
  json(res, 200, { ok: true, message, room: snapshot(room) });
}

function bad(res, status, message) {
  json(res, status, { ok: false, message, room: null });
}

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
    createdAt: room.createdAt,
    lastActivity: room.lastActivity,
    deadSince: room.deadSince || null,
    finishedAt: room.finishedAt || null,
    players
  };
}

function isActiveRoom(room) {
  if (!room || isDeadRoom(room)) {
    return false;
  }

  return [...room.players.values()].some(player => {
    const state = normalizedRaceState(player);
    return state !== 'FINISHED' && state !== 'DQ' && state !== 'DISQUALIFIED';
  });
}

function activeRoomSnapshots() {
  return [...rooms.values()]
    .filter(isActiveRoom)
    .sort((a, b) => b.lastActivity - a.lastActivity)
    .map(snapshot);
}

function normalizedRaceState(player) {
  return cleanName(player && player.raceState, '').toUpperCase();
}

function isDisqualifiedState(player) {
  const state = normalizedRaceState(player);
  return state === 'DQ' || state === 'DISQUALIFIED';
}

function allPlayersDisqualified(room) {
  if (!room || room.players.size === 0) {
    return false;
  }

  return [...room.players.values()].every(isDisqualifiedState);
}

function isDeadRoom(room) {
  if (!room) {
    return true;
  }

  // Empty rooms are abandoned. Rooms where every remaining participant is DQ'd
  // are also considered dead. Finished races are deliberately NOT dead.
  return room.players.size === 0 || allPlayersDisqualified(room);
}

function isFinishedRoom(room) {
  if (!room || room.players.size === 0 || isDeadRoom(room)) {
    return false;
  }

  const players = [...room.players.values()];

  // A legitimate completed race has at least one FINISHED player and no
  // still-active participant states. Mixed FINISHED + DQ is preserved.
  const hasFinished = players.some(player =>
    normalizedRaceState(player) === 'FINISHED'
  );

  if (!hasFinished) {
    return false;
  }

  return players.every(player => {
    const state = normalizedRaceState(player);
    return state === 'FINISHED' || state === 'DQ' || state === 'DISQUALIFIED';
  });
}

function finishedRoomSnapshots() {
  return [...rooms.values()]
    .filter(isFinishedRoom)
    .sort((a, b) => b.lastActivity - a.lastActivity)
    .map(room => {
      const data = snapshot(room);
      const standings = [...data.players].sort((a, b) =>
        (b.score - a.score) || a.playerName.localeCompare(b.playerName)
      );

      const winner = standings.find(player =>
        normalizedRaceState(player) === 'FINISHED'
      ) || standings[0] || null;

      return {
        ...data,
        finishedAt: room.finishedAt || room.lastActivity,
        winner: winner ? {
          playerName: winner.playerName,
          score: winner.score
        } : null
      };
    });
}


function shortGp(value) {
  const n = Number(value) || 0;
  const abs = Math.abs(n);

  if (abs >= 1_000_000_000) return `${(n / 1_000_000_000).toFixed(2)}B GP`;
  if (abs >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M GP`;
  if (abs >= 1_000) return `${(n / 1_000).toFixed(1)}K GP`;

  return `${Math.trunc(n).toLocaleString('en-AU')} GP`;
}

function formatDuration(milliseconds) {
  let seconds = Math.max(0, Math.floor(Number(milliseconds || 0) / 1000));
  const days = Math.floor(seconds / 86400);
  seconds %= 86400;
  const hours = Math.floor(seconds / 3600);
  seconds %= 3600;
  const minutes = Math.floor(seconds / 60);

  const parts = [];
  if (days) parts.push(`${days}d`);
  if (hours) parts.push(`${hours}h`);
  if (minutes || parts.length === 0) parts.push(`${minutes}m`);
  return parts.join(' ');
}

function discordStandings(room) {
  return [...room.players.values()]
    .filter(player => !isDisqualifiedState(player))
    .sort((a, b) => (b.score - a.score) || a.playerName.localeCompare(b.playerName));
}

async function sendFinishedRaceToDiscord(room) {
  if (!room || room.discordFinishedPosted) {
    return;
  }

  if (!DISCORD_FINISHED_RACES_WEBHOOK) {
    console.warn(`Discord finished-race webhook not configured; skipping ${room.roomCode}`);
    return;
  }

  const standings = discordStandings(room);
  const winner = standings[0] || null;

  if (!winner) {
    console.warn(`No eligible winner found for finished room ${room.roomCode}`);
    return;
  }

  // Mark before awaiting so multiple near-simultaneous syncs cannot double-post.
  room.discordFinishedPosted = true;

  const podium = standings.slice(0, 10).map((player, index) => {
    const rank = index === 0 ? '🥇' : index === 1 ? '🥈' : index === 2 ? '🥉' : `${index + 1}.`;
    return `${rank} **${player.playerName}** — ${shortGp(player.score)}`;
  }).join('\n');

  const raceUrl = PUBLIC_WEBSITE_URL
    ? `${PUBLIC_WEBSITE_URL}/race/${encodeURIComponent(room.roomCode)}`
    : '';

  const embed = {
    title: `🏆 ${room.raceName || '0GP Race'} — Race Complete`,
    description: [
      `**Winner:** 🥇 ${winner.playerName}`,
      `**Winning Score:** ${shortGp(winner.score)}`,
      '',
      podium
    ].join('\n'),
    color: 0xE4B63F,
    fields: [
      {
        name: 'Room',
        value: room.roomCode,
        inline: true
      },
      {
        name: 'Racers',
        value: String(standings.length),
        inline: true
      },
      {
        name: 'Race Duration',
        value: formatDuration(room.durationMilliseconds),
        inline: true
      }
    ],
    footer: {
      text: '0GP Race • Start from nothing. Take the crown.'
    },
    timestamp: new Date(room.finishedAt || now()).toISOString()
  };

  if (raceUrl) {
    embed.url = raceUrl;
  }

  try {
    const response = await fetch(DISCORD_FINISHED_RACES_WEBHOOK, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        username: '0GP Race',
        embeds: [embed]
      })
    });

    if (!response.ok) {
      room.discordFinishedPosted = false;
      throw new Error(`Discord webhook failed with HTTP ${response.status}`);
    }

    console.log(`Discord finished-race announcement posted for ${room.roomCode}`);
  } catch (error) {
    room.discordFinishedPosted = false;
    console.error(`Discord finished-race announcement failed for ${room.roomCode}:`, error);
  }
}

function refreshRoomLifecycle(room) {
  if (!room) {
    return;
  }

  if (isDeadRoom(room)) {
    if (!room.deadSince) {
      room.deadSince = now();
    }
  } else {
    room.deadSince = null;
  }

  if (isFinishedRoom(room)) {
    if (!room.finishedAt) {
      room.finishedAt = now();

      // Fire-and-forget: API sync responses should not wait for Discord.
      void sendFinishedRaceToDiscord(room);
    }
  } else {
    room.finishedAt = null;
  }
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
    return json(res, 200, {
      ok: true,
      message: '0GP Race hosted API is running',
      rooms: rooms.size,
      activeRooms: activeRoomSnapshots().length,
      finishedRooms: finishedRoomSnapshots().length,
      deadRooms: [...rooms.values()].filter(isDeadRoom).length
    });
  }

  if (req.method === 'GET' && url.pathname === '/api/rooms') {
    return json(res, 200, {
      ok: true,
      message: '',
      rooms: activeRoomSnapshots()
    });
  }

  if (req.method === 'GET' && url.pathname === '/api/finished') {
    return json(res, 200, {
      ok: true,
      message: '',
      rooms: finishedRoomSnapshots()
    });
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

    const timestamp = now();
    const room = {
      roomCode: code,
      raceName: cleanName(body.raceName, '0GP Race'),
      durationMilliseconds: duration,
      startingAllowance: clampLong(body.startingAllowance),
      createdAt: timestamp,
      lastActivity: timestamp,
      deadSince: null,
      finishedAt: null,
      discordFinishedPosted: false,
      players: new Map()
    };

    room.players.set(pkey(player), {
      playerName: player,
      score: Math.trunc(Number(body.score) || 0),
      remainingMilliseconds: clampLong(body.remainingMilliseconds),
      loggedIn: Boolean(body.loggedIn),
      raceState: Boolean(body.loggedIn) ? 'RUNNING' : 'PAUSED',
      lastSeen: timestamp,
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

    refreshRoomLifecycle(room);
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

    if (existing && sequence < existing.sequence) {
      return ok(res, room);
    }

    const timestamp = now();
    const incomingRemaining = clampLong(body.remainingMilliseconds);
    let remaining = incomingRemaining;

    if (existing) {
      let serverRemaining = existing.remainingMilliseconds;

      if (existing.loggedIn && existing.raceState === 'RUNNING') {
        serverRemaining = Math.max(
          0,
          existing.remainingMilliseconds - (timestamp - existing.lastSeen)
        );
      }

      remaining = Math.min(incomingRemaining, serverRemaining);
    }

    const loggedIn = Boolean(body.loggedIn);
    let raceState = cleanName(
      body.raceState,
      loggedIn ? 'RUNNING' : 'PAUSED'
    ).toUpperCase();

    if (!loggedIn && raceState === 'RUNNING') {
      raceState = 'PAUSED';
    }

    if (remaining <= 0) {
      remaining = 0;
      raceState = 'FINISHED';
    }

    room.players.set(key, {
      playerName: player,
      score: Math.trunc(Number(body.score) || 0),
      remainingMilliseconds: remaining,
      loggedIn,
      raceState,
      lastSeen: timestamp,
      sequence
    });

    touch(room);
    refreshRoomLifecycle(room);
    return ok(res, room);
  }

  if (url.pathname === '/api/leave') {
    const room = getRoom(body.roomCode);
    const player = cleanName(body.playerName);

    if (!room) return bad(res, 404, 'Room not found');

    room.players.delete(pkey(player));
    touch(room);
    refreshRoomLifecycle(room);
    return ok(res, room);
  }

  return bad(res, 404, 'Unknown API endpoint');
}

function cleanup() {
  const timestamp = now();
  const cutoff = timestamp - ROOM_TTL_MS;

  for (const [code, room] of rooms) {
    // First update stale RUNNING players to PAUSED. A stale player is still a
    // legitimate participant, so this does not make the room "dead".
    const stale = timestamp - PLAYER_STALE_MS;
    for (const player of room.players.values()) {
      if (player.lastSeen < stale && player.raceState === 'RUNNING') {
        player.loggedIn = false;
        player.raceState = 'PAUSED';
      }
    }

    refreshRoomLifecycle(room);

    // Empty rooms and all-DQ rooms disappear from Active Races immediately,
    // but remain addressable for a short grace period in case of a transient
    // leave/rejoin or debugging need.
    if (room.deadSince) {
      if (timestamp - room.deadSince >= DEAD_ROOM_GRACE_MS) {
        rooms.delete(code);
      }
      continue;
    }

    // Legitimate finished races are NOT treated as dead. They can remain
    // addressable for the normal room TTL (currently 24 hours) and can later
    // be moved into a persistent archive without changing this rule.
    if (room.lastActivity < cutoff) {
      rooms.delete(code);
    }
  }
}

setInterval(cleanup, 60_000).unref();

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  if (url.pathname === '/') {
    const body =
      '0GP Race API\n\n' +
      'Health: /health\n' +
      'Active rooms: /api/rooms\n' +
      'Room lookup: /api/room?roomCode=0GP-1234\n';

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

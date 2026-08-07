import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalRaceServer
{
    private static final Map<String, Room> ROOMS = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception
    {
        int port = 8787;
        if (args.length > 0)
        {
            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) { }
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/health", exchange -> send(exchange, 200, "{\"ok\":true,\"message\":\"0GP Race local server is running\"}"));
        server.createContext("/api/create", new JsonHandler(LocalRaceServer::createRoom));
        server.createContext("/api/join", new JsonHandler(LocalRaceServer::joinRoom));
        server.createContext("/api/sync", new JsonHandler(LocalRaceServer::syncRoom));
        server.createContext("/api/leave", new JsonHandler(LocalRaceServer::leaveRoom));
        server.createContext("/api/room", LocalRaceServer::getRoom);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("0GP Race local multiplayer server");
        System.out.println("Listening on http://127.0.0.1:" + port);
        System.out.println("Keep this window open while testing multiplayer.");
    }

    private static Result createRoom(String body)
    {
        String code = normalise(stringField(body, "roomCode"));
        String raceName = stringField(body, "raceName");
        String player = stringField(body, "playerName");
        long duration = longField(body, "durationMilliseconds");
        long allowance = longField(body, "startingAllowance");
        long score = longField(body, "score");
        long remaining = longField(body, "remainingMilliseconds");
        boolean loggedIn = boolField(body, "loggedIn");

        if (code.isEmpty() || player.isEmpty() || duration <= 0L)
        {
            return Result.bad(400, "Missing room, player or duration");
        }
        if (ROOMS.containsKey(code))
        {
            return Result.bad(409, "Room code already exists - create again for a new code");
        }

        Room room = new Room(code, raceName.isEmpty() ? "0GP Race" : raceName, duration, Math.max(0L, allowance));
        room.players.put(key(player), new Player(player, score, remaining, loggedIn, "RUNNING", System.currentTimeMillis(), 0L));
        ROOMS.put(code, room);
        return Result.ok(room);
    }

    private static Result joinRoom(String body)
    {
        String code = normalise(stringField(body, "roomCode"));
        String player = stringField(body, "playerName");
        Room room = ROOMS.get(code);
        if (room == null)
        {
            return Result.bad(404, "Room not found");
        }
        if (player.isEmpty())
        {
            return Result.bad(400, "Player name is required");
        }

        room.players.computeIfAbsent(key(player), k -> new Player(player, room.startingAllowance,
            room.durationMilliseconds, true, "RUNNING", System.currentTimeMillis(), 0L));
        return Result.ok(room);
    }

    private static Result syncRoom(String body)
    {
        String code = normalise(stringField(body, "roomCode"));
        String player = stringField(body, "playerName");
        Room room = ROOMS.get(code);
        if (room == null)
        {
            return Result.bad(404, "Room not found");
        }
        if (player.isEmpty())
        {
            return Result.bad(400, "Player name is required");
        }

        long sequence = longField(body, "sequence");
        Player existing = room.players.get(key(player));
        if (existing != null && sequence < existing.sequence)
        {
            return Result.ok(room);
        }

        Player updated = new Player(
            player,
            longField(body, "score"),
            Math.max(0L, longField(body, "remainingMilliseconds")),
            boolField(body, "loggedIn"),
            stringField(body, "raceState"),
            System.currentTimeMillis(),
            sequence);
        room.players.put(key(player), updated);
        return Result.ok(room);
    }

    private static Result leaveRoom(String body)
    {
        String code = normalise(stringField(body, "roomCode"));
        String player = stringField(body, "playerName");
        Room room = ROOMS.get(code);
        if (room == null)
        {
            return Result.bad(404, "Room not found");
        }
        room.players.remove(key(player));
        return Result.ok(room);
    }

    private static void getRoom(HttpExchange exchange) throws IOException
    {
        if (handleOptions(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()))
        {
            send(exchange, 405, errorJson("GET required"));
            return;
        }
        String query = exchange.getRequestURI().getRawQuery();
        String code = normalise(queryParam(query, "roomCode"));
        Room room = ROOMS.get(code);
        if (room == null)
        {
            send(exchange, 404, errorJson("Room not found"));
            return;
        }
        send(exchange, 200, successJson(room));
    }

    private interface Action { Result apply(String body); }

    private static final class JsonHandler implements HttpHandler
    {
        private final Action action;
        private JsonHandler(Action action) { this.action = action; }

        @Override
        public void handle(HttpExchange exchange) throws IOException
        {
            if (handleOptions(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod()))
            {
                send(exchange, 405, errorJson("POST required"));
                return;
            }
            String body = readAll(exchange.getRequestBody());
            Result result = action.apply(body);
            send(exchange, result.status, result.ok ? successJson(result.room) : errorJson(result.message));
        }
    }

    private static boolean handleOptions(HttpExchange exchange) throws IOException
    {
        addCors(exchange.getResponseHeaders());
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod()))
        {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    private static void addCors(Headers headers)
    {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
        headers.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        headers.set("Content-Type", "application/json; charset=utf-8");
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException
    {
        addCors(exchange.getResponseHeaders());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private static String successJson(Room room)
    {
        return "{\"ok\":true,\"message\":\"\",\"room\":" + roomJson(room) + "}";
    }

    private static String errorJson(String message)
    {
        return "{\"ok\":false,\"message\":\"" + escape(message) + "\",\"room\":null}";
    }

    private static String roomJson(Room room)
    {
        List<Player> players = new ArrayList<>(room.players.values());
        players.sort(Comparator.comparingLong((Player p) -> p.score).reversed()
            .thenComparing(p -> p.playerName.toLowerCase(Locale.ROOT)));

        StringBuilder b = new StringBuilder();
        b.append("{\"roomCode\":\"").append(escape(room.roomCode))
            .append("\",\"raceName\":\"").append(escape(room.raceName))
            .append("\",\"durationMilliseconds\":").append(room.durationMilliseconds)
            .append(",\"startingAllowance\":").append(room.startingAllowance)
            .append(",\"players\":[");
        for (int i = 0; i < players.size(); i++)
        {
            if (i > 0) b.append(',');
            Player p = players.get(i);
            b.append("{\"playerName\":\"").append(escape(p.playerName))
                .append("\",\"score\":").append(p.score)
                .append(",\"remainingMilliseconds\":").append(p.remainingMilliseconds)
                .append(",\"loggedIn\":").append(p.loggedIn)
                .append(",\"raceState\":\"").append(escape(p.raceState))
                .append("\",\"lastSeen\":").append(p.lastSeen).append('}');
        }
        return b.append("]}").toString();
    }

    private static String readAll(InputStream in) throws IOException
    {
        byte[] buffer = new byte[8192];
        StringBuilder b = new StringBuilder();
        int n;
        while ((n = in.read(buffer)) >= 0)
        {
            b.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    private static String stringField(String json, String field)
    {
        Pattern p = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
        Matcher m = p.matcher(json == null ? "" : json);
        return m.find() ? unescape(m.group(1)) : "";
    }

    private static long longField(String json, String field)
    {
        Pattern p = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*(-?\\d+)");
        Matcher m = p.matcher(json == null ? "" : json);
        if (!m.find()) return 0L;
        try { return Long.parseLong(m.group(1)); } catch (NumberFormatException ex) { return 0L; }
    }

    private static boolean boolField(String json, String field)
    {
        Pattern p = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(json == null ? "" : json);
        return m.find() && Boolean.parseBoolean(m.group(1));
    }

    private static String queryParam(String query, String name)
    {
        if (query == null) return "";
        for (String part : query.split("&"))
        {
            int eq = part.indexOf('=');
            String key = eq < 0 ? part : part.substring(0, eq);
            if (name.equals(decode(key))) return eq < 0 ? "" : decode(part.substring(eq + 1));
        }
        return "";
    }

    private static String decode(String s)
    {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception ex) { return s; }
    }

    private static String normalise(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT); }
    private static String key(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
    private static String escape(String s)
    {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
    private static String unescape(String s)
    {
        if (s == null) return "";
        return s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r").replace("\\\\", "\\");
    }

    private static final class Room
    {
        final String roomCode, raceName;
        final long durationMilliseconds, startingAllowance;
        final Map<String, Player> players = new ConcurrentHashMap<>();
        Room(String roomCode, String raceName, long durationMilliseconds, long startingAllowance)
        {
            this.roomCode = roomCode; this.raceName = raceName;
            this.durationMilliseconds = durationMilliseconds; this.startingAllowance = startingAllowance;
        }
    }

    private static final class Player
    {
        final String playerName, raceState;
        final long score, remainingMilliseconds, lastSeen, sequence;
        final boolean loggedIn;
        Player(String playerName, long score, long remainingMilliseconds, boolean loggedIn,
            String raceState, long lastSeen, long sequence)
        {
            this.playerName = playerName; this.score = score; this.remainingMilliseconds = remainingMilliseconds;
            this.loggedIn = loggedIn; this.raceState = raceState == null || raceState.isEmpty() ? "RUNNING" : raceState;
            this.lastSeen = lastSeen; this.sequence = sequence;
        }
    }

    private static final class Result
    {
        final int status; final boolean ok; final String message; final Room room;
        private Result(int status, boolean ok, String message, Room room)
        { this.status = status; this.ok = ok; this.message = message; this.room = room; }
        static Result ok(Room room) { return new Result(200, true, "", room); }
        static Result bad(int status, String message) { return new Result(status, false, message, null); }
    }
}

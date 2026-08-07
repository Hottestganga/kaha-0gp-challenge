package com.ganga.zerogprace.network;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.Objects;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class MultiplayerClient
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public interface ResultCallback
    {
        void onSuccess(RaceRoomSnapshot room);
        void onError(String message);
    }

    private final OkHttpClient httpClient;
    private final Gson gson;
    private volatile NetworkStatus status = NetworkStatus.READY;

    public MultiplayerClient(OkHttpClient httpClient, Gson gson)
    {
        this.httpClient = Objects.requireNonNull(httpClient);
        this.gson = Objects.requireNonNull(gson);
    }

    public NetworkStatus getStatus()
    {
        return status;
    }

    public void reset()
    {
        status = NetworkStatus.READY;
    }

    public void createRoom(String apiBase, CreateRoomRequest payload, ResultCallback callback)
    {
        post(apiBase + "/api/create", payload, callback);
    }

    public void joinRoom(String apiBase, JoinRoomRequest payload, ResultCallback callback)
    {
        post(apiBase + "/api/join", payload, callback);
    }

    public void sync(String apiBase, RaceSyncPayload payload, ResultCallback callback)
    {
        post(apiBase + "/api/sync", payload, callback);
    }

    public void leave(String apiBase, LeaveRoomRequest payload, ResultCallback callback)
    {
        post(apiBase + "/api/leave", payload, callback);
    }

    public void fetchRoom(String apiBase, String roomCode, ResultCallback callback)
    {
        status = NetworkStatus.CONNECTING;
        Request request = new Request.Builder()
            .url(apiBase + "/api/room?roomCode=" + urlEncode(roomCode))
            .get()
            .build();
        execute(request, callback);
    }

    private void post(String url, Object payload, ResultCallback callback)
    {
        status = NetworkStatus.CONNECTING;
        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(JSON, gson.toJson(payload)))
            .build();
        execute(request, callback);
    }

    private void execute(Request request, ResultCallback callback)
    {
        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                status = NetworkStatus.ERROR;
                callback.onError(exception.getMessage() == null ? "Connection failed" : exception.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (Response closable = response)
                {
                    String body = closable.body() == null ? "" : closable.body().string();
                    ApiResponse apiResponse;
                    try
                    {
                        apiResponse = gson.fromJson(body, ApiResponse.class);
                    }
                    catch (Exception ex)
                    {
                        status = NetworkStatus.ERROR;
                        callback.onError("Invalid server response");
                        return;
                    }

                    if (!closable.isSuccessful() || apiResponse == null || !apiResponse.isOk())
                    {
                        status = NetworkStatus.ERROR;
                        String message = apiResponse == null ? "Server error " + closable.code() : apiResponse.getMessage();
                        callback.onError(message.isEmpty() ? "Server error " + closable.code() : message);
                        return;
                    }

                    status = NetworkStatus.CONNECTED;
                    callback.onSuccess(apiResponse.getRoom());
                }
            }
        });
    }

    private static String urlEncode(String value)
    {
        try
        {
            return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8");
        }
        catch (java.io.UnsupportedEncodingException impossible)
        {
            return value == null ? "" : value;
        }
    }
}

package com.ganga.zerogprace.network;

public final class ApiResponse
{
    private boolean ok;
    private String message;
    private RaceRoomSnapshot room;

    public boolean isOk()
    {
        return ok;
    }

    public String getMessage()
    {
        return message == null ? "" : message;
    }

    public RaceRoomSnapshot getRoom()
    {
        return room;
    }
}

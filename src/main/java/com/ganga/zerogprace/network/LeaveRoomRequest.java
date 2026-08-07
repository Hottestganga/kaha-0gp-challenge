package com.ganga.zerogprace.network;

public final class LeaveRoomRequest
{
    private final String roomCode;
    private final String playerName;

    public LeaveRoomRequest(String roomCode, String playerName)
    {
        this.roomCode = roomCode;
        this.playerName = playerName;
    }
}

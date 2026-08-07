package com.ganga.zerogprace.network;

public final class JoinRoomRequest
{
    private final String roomCode;
    private final String playerName;

    public JoinRoomRequest(String roomCode, String playerName)
    {
        this.roomCode = roomCode;
        this.playerName = playerName;
    }
}

package com.ganga.zerogprace.network;

public final class CreateRoomRequest
{
    private final String roomCode;
    private final String raceName;
    private final long durationMilliseconds;
    private final long startingAllowance;
    private final String playerName;
    private final long score;
    private final long remainingMilliseconds;
    private final boolean loggedIn;

    public CreateRoomRequest(String roomCode, String raceName, long durationMilliseconds,
        long startingAllowance, String playerName, long score,
        long remainingMilliseconds, boolean loggedIn)
    {
        this.roomCode = roomCode;
        this.raceName = raceName;
        this.durationMilliseconds = durationMilliseconds;
        this.startingAllowance = startingAllowance;
        this.playerName = playerName;
        this.score = score;
        this.remainingMilliseconds = remainingMilliseconds;
        this.loggedIn = loggedIn;
    }
}

package com.ganga.zerogprace.network;

public final class RaceSyncPayload
{
    private final String roomCode;
    private final String playerName;
    private final long score;
    private final long remainingMilliseconds;
    private final boolean loggedIn;
    private final String raceState;
    private final long sequence;

    public RaceSyncPayload(String roomCode, String playerName, long score,
        long remainingMilliseconds, boolean loggedIn, String raceState, long sequence)
    {
        this.roomCode = roomCode;
        this.playerName = playerName;
        this.score = score;
        this.remainingMilliseconds = remainingMilliseconds;
        this.loggedIn = loggedIn;
        this.raceState = raceState;
        this.sequence = sequence;
    }
}

package com.ganga.zerogprace.network;

public final class RacePlayerSnapshot
{
    private String playerName;
    private long score;
    private long remainingMilliseconds;
    private boolean loggedIn;
    private String raceState;
    private long lastSeen;

    public String getPlayerName()
    {
        return playerName == null ? "" : playerName;
    }

    public long getScore()
    {
        return score;
    }

    public long getRemainingMilliseconds()
    {
        return remainingMilliseconds;
    }

    public boolean isLoggedIn()
    {
        return loggedIn;
    }

    public String getRaceState()
    {
        return raceState == null ? "UNKNOWN" : raceState;
    }

    public long getLastSeen()
    {
        return lastSeen;
    }
}

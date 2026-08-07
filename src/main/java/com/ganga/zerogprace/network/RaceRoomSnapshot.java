package com.ganga.zerogprace.network;

import java.util.Collections;
import java.util.List;

public final class RaceRoomSnapshot
{
    private String roomCode;
    private String raceName;
    private long durationMilliseconds;
    private long startingAllowance;
    private List<RacePlayerSnapshot> players;

    public String getRoomCode()
    {
        return roomCode == null ? "" : roomCode;
    }

    public String getRaceName()
    {
        return raceName == null ? "0GP Race" : raceName;
    }

    public long getDurationMilliseconds()
    {
        return durationMilliseconds;
    }

    public long getStartingAllowance()
    {
        return startingAllowance;
    }

    public List<RacePlayerSnapshot> getPlayers()
    {
        return players == null ? Collections.emptyList() : players;
    }
}

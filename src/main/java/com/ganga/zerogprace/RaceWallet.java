package com.ganga.zerogprace.wallet;

final class RaceWallet
{
    private long raceScoreGp;
    private long bankValueGp;
    private long savedPauseValueGp = -1L;
    private boolean paused;
    private boolean saveExists;

    long getRaceScoreGp() { return raceScoreGp; }
    void setRaceScoreGp(long value) { raceScoreGp = value; }

    long getBankValueGp() { return bankValueGp; }
    void setBankValueGp(long value) { bankValueGp = value; }

    long getSavedPauseValueGp() { return savedPauseValueGp; }
    void setSavedPauseValueGp(long value) { savedPauseValueGp = value; }

    boolean isPaused() { return paused; }
    void setPaused(boolean value) { paused = value; }

    boolean hasSavedProgress() { return saveExists; }
    void setSaveExists(boolean value) { saveExists = value; }

    void reset()
    {
        raceScoreGp = 0L;
        bankValueGp = 0L;
        savedPauseValueGp = -1L;
        paused = false;
        saveExists = false;
    }
}

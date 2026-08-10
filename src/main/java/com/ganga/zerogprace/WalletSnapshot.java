package com.ganga.zerogprace.wallet;

public final class WalletSnapshot
{
    private final long raceScoreGp;
    private final long bankValueGp;
    private final long savedPauseValueGp;
    private final boolean paused;
    private final boolean saveExists;

    public WalletSnapshot(long raceScoreGp, long bankValueGp, long savedPauseValueGp, boolean paused, boolean saveExists)
    {
        this.raceScoreGp = raceScoreGp;
        this.bankValueGp = bankValueGp;
        this.savedPauseValueGp = savedPauseValueGp;
        this.paused = paused;
        this.saveExists = saveExists;
    }

    public long getRaceScoreGp() { return raceScoreGp; }
    public long getBankValueGp() { return bankValueGp; }
    public long getSavedPauseValueGp() { return savedPauseValueGp; }
    public boolean isPaused() { return paused; }
    public boolean hasSavedProgress() { return saveExists; }
}

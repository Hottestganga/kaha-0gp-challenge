package com.ganga.zerogprace.wallet;

public final class WalletManager
{
    private final RaceWallet wallet = new RaceWallet();
    private final BankLedger bankLedger = new BankLedger();
    private final SavedProgress savedProgress = new SavedProgress();
    private final InventoryBasisLedger inventoryBasis = new InventoryBasisLedger();

    public WalletSnapshot snapshot()
    {
        return new WalletSnapshot(
            wallet.getRaceScoreGp(),
            bankLedger.getValueGp(),
            savedProgress.getInventoryValueGp(),
            wallet.isPaused(),
            savedProgress.exists());
    }

    public long getRaceScoreGp() { return wallet.getRaceScoreGp(); }
    public long getBankValueGp() { return bankLedger.getValueGp(); }
    public long getSavedPauseValueGp() { return savedProgress.getInventoryValueGp(); }
    public boolean isPaused() { return wallet.isPaused(); }
    public boolean hasSavedProgress() { return savedProgress.exists(); }

    public void addRaceScore(long valueGp)
    {
        if (valueGp != 0L)
        {
            wallet.setRaceScoreGp(wallet.getRaceScoreGp() + valueGp);
        }
    }

    public void setRaceScore(long valueGp)
    {
        wallet.setRaceScoreGp(valueGp);
    }

    public void startRace(long startingAllowanceGp)
    {
        reset();
        wallet.setRaceScoreGp(0L);
        bankLedger.set(Math.max(0L, startingAllowanceGp));
        wallet.setBankValueGp(bankLedger.getValueGp());
    }

    public void addBankValue(long valueGp)
    {
        bankLedger.add(valueGp);
        wallet.setBankValueGp(bankLedger.getValueGp());
    }

    public void removeBankValue(long valueGp)
    {
        bankLedger.remove(valueGp);
        wallet.setBankValueGp(bankLedger.getValueGp());
    }

    public void setBankValue(long valueGp)
    {
        bankLedger.set(valueGp);
        wallet.setBankValueGp(valueGp);
    }

    public boolean isBankOverdrawn()
    {
        return bankLedger.getValueGp() < 0L;
    }

    public void addInventoryBasis(int itemId, int quantity, long valueGp)
    {
        inventoryBasis.add(itemId, quantity, valueGp);
    }

    public long consumeInventoryBasis(int itemId, int quantity)
    {
        return inventoryBasis.consume(itemId, quantity);
    }

    public void clearInventoryBasis()
    {
        inventoryBasis.clear();
    }

    public void saveRaceProgress(long inventoryValueGp)
    {
        savedProgress.save(inventoryValueGp);
        wallet.setSavedPauseValueGp(savedProgress.getInventoryValueGp());
        wallet.setSaveExists(true);
    }

    public void clearSavedProgress()
    {
        savedProgress.clear();
        wallet.setSavedPauseValueGp(-1L);
        wallet.setSaveExists(false);
    }

    public void setPaused(boolean paused)
    {
        wallet.setPaused(paused);
    }

    public boolean canResumeWith(long inventoryValueGp, long equipmentValueGp)
    {
        return savedProgress.exists()
            && equipmentValueGp <= 0L
            && inventoryValueGp == savedProgress.getInventoryValueGp();
    }

    public long resumeDifference(long inventoryValueGp)
    {
        if (!savedProgress.exists()) return 0L;
        return savedProgress.getInventoryValueGp() - inventoryValueGp;
    }

    public void reset()
    {
        wallet.reset();
        bankLedger.reset();
        savedProgress.clear();
        inventoryBasis.clear();
    }
}

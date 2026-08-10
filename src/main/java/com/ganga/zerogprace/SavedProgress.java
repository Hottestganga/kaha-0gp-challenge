package com.ganga.zerogprace.wallet;

final class SavedProgress
{
    private long inventoryValueGp = -1L;

    boolean exists() { return inventoryValueGp >= 0L; }
    long getInventoryValueGp() { return inventoryValueGp; }

    void save(long inventoryValueGp)
    {
        this.inventoryValueGp = Math.max(0L, inventoryValueGp);
    }

    void clear()
    {
        inventoryValueGp = -1L;
    }
}

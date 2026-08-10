package com.ganga.zerogprace.wallet;

import java.util.HashMap;
import java.util.Map;

final class InventoryBasisLedger
{
    private final Map<Integer, Integer> quantities = new HashMap<>();
    private final Map<Integer, Long> values = new HashMap<>();

    void add(int itemId, int quantity, long valueGp)
    {
        if (itemId <= 0 || quantity <= 0 || valueGp <= 0L)
        {
            return;
        }

        quantities.merge(itemId, quantity, Integer::sum);
        values.merge(itemId, valueGp, Long::sum);
    }

    long consume(int itemId, int quantity)
    {
        if (itemId <= 0 || quantity <= 0)
        {
            return 0L;
        }

        int heldQty = quantities.getOrDefault(itemId, 0);
        long heldValue = values.getOrDefault(itemId, 0L);

        if (heldQty <= 0 || heldValue <= 0L)
        {
            return 0L;
        }

        int usedQty = Math.min(quantity, heldQty);
        long usedValue;

        if (usedQty >= heldQty)
        {
            usedValue = heldValue;
            quantities.remove(itemId);
            values.remove(itemId);
        }
        else
        {
            usedValue = (heldValue * usedQty) / heldQty;
            int remainingQty = heldQty - usedQty;
            long remainingValue = Math.max(0L, heldValue - usedValue);

            quantities.put(itemId, remainingQty);
            values.put(itemId, remainingValue);
        }

        return Math.max(0L, usedValue);
    }

    void clear()
    {
        quantities.clear();
        values.clear();
    }
}



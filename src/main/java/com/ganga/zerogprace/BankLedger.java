package com.ganga.zerogprace.wallet;

final class BankLedger
{
    private long valueGp;

    long getValueGp() { return valueGp; }

    void add(long value)
    {
        if (value > 0L) valueGp += value;
    }

    void remove(long value)
    {
        if (value > 0L) valueGp -= value;
    }

    void set(long value) { valueGp = value; }

    void reset() { valueGp = 0L; }
}

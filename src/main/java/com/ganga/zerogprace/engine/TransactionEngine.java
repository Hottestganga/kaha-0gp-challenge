package com.ganga.zerogprace.engine;

import com.ganga.zerogprace.model.RaceTransaction;
import com.ganga.zerogprace.stats.RaceStatistics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TransactionEngine
{
    private final List<RaceTransaction> transactions = new ArrayList<>();
    private long score;

    public void reset(long startingAllowance)
    {
        transactions.clear();
        score = Math.max(0L, startingAllowance);
    }

    public void record(RaceTransaction transaction)
    {
        if (transaction == null)
        {
            return;
        }
        transactions.add(transaction);
        score += transaction.getScoreDelta();
    }

    public long getScore()
    {
        return score;
    }

    public List<RaceTransaction> getTransactions()
    {
        return Collections.unmodifiableList(new ArrayList<>(transactions));
    }

    public RaceStatistics getStatistics()
    {
        return RaceStatistics.from(transactions, score);
    }
}

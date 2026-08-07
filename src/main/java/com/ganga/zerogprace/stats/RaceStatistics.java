package com.ganga.zerogprace.stats;

import com.ganga.zerogprace.model.RaceSource;
import com.ganga.zerogprace.model.RaceTransaction;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class RaceStatistics
{
    private final long score;
    private final long positiveGp;
    private final long importedGp;
    private final int acceptedTransactions;
    private final String biggestItem;
    private final long biggestValue;
    private final Map<RaceSource, Long> gpBySource;

    private RaceStatistics(long score, long positiveGp, long importedGp, int acceptedTransactions,
                           String biggestItem, long biggestValue, Map<RaceSource, Long> gpBySource)
    {
        this.score = score;
        this.positiveGp = positiveGp;
        this.importedGp = importedGp;
        this.acceptedTransactions = acceptedTransactions;
        this.biggestItem = biggestItem;
        this.biggestValue = biggestValue;
        this.gpBySource = gpBySource;
    }

    public static RaceStatistics from(List<RaceTransaction> transactions, long score)
    {
        long positive = 0L;
        long imported = 0L;
        int accepted = 0;
        long biggest = 0L;
        String biggestItem = "None";
        Map<RaceSource, Long> bySource = new EnumMap<>(RaceSource.class);

        for (RaceTransaction transaction : transactions)
        {
            long delta = transaction.getScoreDelta();
            bySource.merge(transaction.getSource(), delta, Long::sum);
            if (delta > 0L)
            {
                positive += delta;
                accepted++;
                if (delta > biggest && !transaction.getItemName().isEmpty())
                {
                    biggest = delta;
                    biggestItem = transaction.getItemName();
                }
            }
            else if (delta < 0L)
            {
                imported += -delta;
            }
        }

        return new RaceStatistics(score, positive, imported, accepted, biggestItem, biggest, bySource);
    }

    public long getScore() { return score; }
    public long getPositiveGp() { return positiveGp; }
    public long getImportedGp() { return importedGp; }
    public int getAcceptedTransactions() { return acceptedTransactions; }
    public String getBiggestItem() { return biggestItem; }
    public long getBiggestValue() { return biggestValue; }
    public Map<RaceSource, Long> getGpBySource() { return gpBySource; }
}

package com.ganga.zerogprace.model;

public final class RaceTransaction
{
    private final long timestamp;
    private final RaceSource source;
    private final String itemName;
    private final int quantity;
    private final long marketValue;
    private final long scoreDelta;
    private final OwnershipType ownership;
    private final TransactionStatus status;
    private final String note;

    public RaceTransaction(long timestamp, RaceSource source, String itemName, int quantity,
                           long marketValue, long scoreDelta, OwnershipType ownership,
                           TransactionStatus status, String note)
    {
        this.timestamp = timestamp;
        this.source = source == null ? RaceSource.UNKNOWN : source;
        this.itemName = itemName == null ? "" : itemName;
        this.quantity = Math.max(0, quantity);
        this.marketValue = Math.max(0L, marketValue);
        this.scoreDelta = scoreDelta;
        this.ownership = ownership == null ? OwnershipType.NONE : ownership;
        this.status = status == null ? TransactionStatus.INFO : status;
        this.note = note == null ? "" : note;
    }

    public long getTimestamp() { return timestamp; }
    public RaceSource getSource() { return source; }
    public String getItemName() { return itemName; }
    public int getQuantity() { return quantity; }
    public long getMarketValue() { return marketValue; }
    public long getScoreDelta() { return scoreDelta; }
    public OwnershipType getOwnership() { return ownership; }
    public TransactionStatus getStatus() { return status; }
    public String getNote() { return note; }
}

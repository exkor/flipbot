package com.exkor.flipbot.flipper.trading;

public class Trade {
    public enum State {
        PLANNED, BUY_OPEN, FILLED, CLAIMED, SELL_OPEN, SOLD, DONE, CANCELLED, ABANDONED
    }

    public String productId;
    public String displayName;
    public long qty;
    public long filledQty;
    public double buyPrice;
    public double sellPrice;
    public double cost;
    public double revenue;
    public State state = State.PLANNED;
    public long createdAt;
    public long filledAt;
    public long listedAt;
    public long soldAt;
    public int outbidChecks;
    public double lastBestBid;
    public int notFoundChecks;
    public long lastRelistedAt;
    /** Craft flipper: the batch this trade belongs to (null for flips). */
    public String craftId;
    /** Craft flipper: an ingredient buy order. Its items are crafted, never sold, and it is not profit by itself. */
    public boolean craftIngredient;
    /** Craft flipper: ingredient units claimed into the inventory so far. */
    public long claimedQty;

    public boolean isOpen() {
        return state == State.PLANNED || state == State.BUY_OPEN || state == State.FILLED
                || state == State.CLAIMED || state == State.SELL_OPEN || state == State.SOLD;
    }
}

package com.exkor.flipbot.flipper.practice;

import com.exkor.flipbot.flipper.data.MarketData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Practice mode's simulated order book. Pure Java (no Minecraft), so it is unit tested.
 *
 * <p>The Trader walks the real menus for a buy order up to the confirm screen, backs out and calls
 * {@link #recordBuy}. {@link #tick} then simulates, against the Bazaar API:
 * <ul>
 *   <li>Buy order: fills only while it is still the top buy order (best bid at or below its price), at the
 *       item's insta-sell rate (sellMovingWeek per minute). Outbid for {@code outbidTicksBeforeCancel} ticks:
 *       cancelled, like the Trader does, which frees the slot for a new plan; any filled part is sold.</li>
 *   <li>Filled buy: a sell offer at best ask - 0.1, like the Trader's "Best Offer -0.1".</li>
 *   <li>Sell offer: fills while it is the lowest offer, at the insta-buy rate (buyMovingWeek per minute).
 *       Undercut: relisted at best ask - 0.1, never below break-even after tax.</li>
 * </ul>
 * Profit is counted after tax when a sell offer completes. Predicted coins/hour integrates the analyzer's
 * coinsPerHour of every open position over the same running time.
 */
public final class PracticeBook {
    public enum Side { BUY, SELL }

    public enum State { OPEN, DONE, CANCELLED }

    public static final class Order {
        public String id;
        public String productId;
        public String name;
        public Side side;
        public State state = State.OPEN;
        public double price;
        public long qty;
        /** Fractional fill progress; filled units = floor(filled). */
        public double filled;
        /** Sell offers: what the units cost. */
        public double cost;
        /** Sell offers: coins received so far, after tax. */
        public double proceeds;
        public double predictedCph;
        public int outbidTicks;
        public long placedAt;
    }

    /** Minutes in a week, for the *MovingWeek volumes. */
    static final double WEEK_MINUTES = 7 * 24 * 60;
    /** A restart or a stopped bot must not count as hours of fills. */
    static final double MAX_TICK_MINUTES = 5;

    public final List<Order> orders = new ArrayList<>();
    public double realizedProfit;
    public double predictedCoins;
    public double activeMinutes;
    public long lastTickAt;
    public int placedBuys;
    public int filledBuys;
    public int completedSells;
    public int cancelledBuys;
    private int nextId = 1;

    /** Receives one event per change, for practice.jsonl. Not saved with the book. */
    public transient Consumer<Map<String, Object>> events = e -> { };

    public Order recordBuy(String productId, String name, double price, long qty, double predictedCph, long now) {
        Order o = new Order();
        o.id = "P" + nextId++;
        o.productId = productId;
        o.name = name;
        o.side = Side.BUY;
        o.price = price;
        o.qty = qty;
        o.predictedCph = predictedCph;
        o.placedAt = now;
        orders.add(o);
        placedBuys++;
        emit(now, "PLACE", o, qty);
        return o;
    }

    public int openCount() {
        int n = 0;
        for (Order o : orders) if (o.state == State.OPEN) n++;
        return n;
    }

    public Set<String> openProductIds() {
        Set<String> ids = new HashSet<>();
        for (Order o : orders) if (o.state == State.OPEN) ids.add(o.productId);
        return ids;
    }

    /** Coins tied up: unfilled buy orders plus the cost of unsold units in sell offers. */
    public double investedCoins() {
        double sum = 0;
        for (Order o : orders) {
            if (o.state != State.OPEN) continue;
            if (o.side == Side.BUY) sum += o.price * o.qty;
            else sum += o.cost * (1.0 - units(o) / (double) o.qty);
        }
        return sum;
    }

    /** One simulation step; call about once a minute while the bot runs. */
    public void tick(MarketData data, double taxPercent, int outbidTicksBeforeCancel, long now) {
        if (lastTickAt == 0 || data == null) {
            lastTickAt = now;
            return;
        }
        double minutes = Math.min(MAX_TICK_MINUTES, Math.max(0, (now - lastTickAt) / 60_000.0));
        lastTickAt = now;
        if (minutes <= 0) return;
        double taxFactor = 1.0 - taxPercent / 100.0;
        activeMinutes += minutes;
        for (Order o : List.copyOf(orders)) {
            if (o.state != State.OPEN) continue;
            predictedCoins += o.predictedCph * minutes / 60.0;
            MarketData.Product p = data.products.get(o.productId);
            if (p == null) continue;
            if (o.side == Side.BUY) tickBuy(o, p, minutes, outbidTicksBeforeCancel, now);
            else tickSell(o, p, minutes, taxFactor, now);
        }
    }

    private void tickBuy(Order o, MarketData.Product p, double minutes, int outbidTicksBeforeCancel, long now) {
        if (p.bestBid > o.price + 1e-6) {
            if (++o.outbidTicks >= Math.max(1, outbidTicksBeforeCancel)) {
                o.state = State.CANCELLED;
                cancelledBuys++;
                long got = units(o);
                emit(now, "CANCEL", o, o.qty - got);
                if (got > 0) startSell(o, got, p, now);
            }
            return;
        }
        o.outbidTicks = 0;
        long before = units(o);
        o.filled = Math.min(o.qty, o.filled + p.sellMovingWeek / WEEK_MINUTES * minutes);
        long after = units(o);
        if (after > before) emit(now, "FILL", o, after - before);
        if (after >= o.qty) {
            o.state = State.DONE;
            filledBuys++;
            startSell(o, o.qty, p, now);
        }
    }

    private void startSell(Order buy, long qty, MarketData.Product p, long now) {
        Order s = new Order();
        s.id = buy.id + "S";
        s.productId = buy.productId;
        s.name = buy.name;
        s.side = Side.SELL;
        s.qty = qty;
        s.cost = buy.price * qty;
        s.price = round1(p.bestAsk - 0.1);
        s.predictedCph = buy.predictedCph;
        s.placedAt = now;
        orders.add(s);
        emit(now, "PLACE", s, qty);
    }

    private void tickSell(Order o, MarketData.Product p, double minutes, double taxFactor, long now) {
        if (p.bestAsk > 0 && p.bestAsk < o.price - 1e-6) {
            double breakEven = round1(o.cost / o.qty / taxFactor + 0.1);
            double relist = Math.max(round1(p.bestAsk - 0.1), breakEven);
            if (relist < o.price - 1e-6) {
                o.price = relist;
                emit(now, "RELIST", o, o.qty - units(o));
            }
            if (p.bestAsk < o.price - 1e-6) return; // still undercut (holding at break-even)
        }
        long before = units(o);
        o.filled = Math.min(o.qty, o.filled + p.buyMovingWeek / WEEK_MINUTES * minutes);
        long after = units(o);
        if (after > before) {
            o.proceeds += (after - before) * o.price * taxFactor;
            emit(now, "FILL", o, after - before);
        }
        if (after >= o.qty) {
            o.state = State.DONE;
            completedSells++;
            realizedProfit += o.proceeds - o.cost;
        }
    }

    public Report report() {
        return new Report(placedBuys, filledBuys, completedSells, cancelledBuys, openCount(), realizedProfit,
                activeMinutes / 60.0, investedCoins(), predictedCoins);
    }

    public record Report(int placedBuys, int filledBuys, int completedSells, int cancelledBuys, int open,
                         double realizedProfit, double hours, double invested, double predictedCoins) {
        public double realizedPerHour() {
            return hours > 0 ? realizedProfit / hours : 0;
        }

        public double predictedPerHour() {
            return hours > 0 ? predictedCoins / hours : 0;
        }
    }

    private void emit(long now, String event, Order o, long qty) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("time", now);
        e.put("event", event);
        e.put("id", o.id);
        e.put("item", o.productId);
        e.put("side", o.side.name());
        e.put("price", o.price);
        e.put("qty", qty);
        events.accept(e);
    }

    private static long units(Order o) {
        return (long) Math.floor(o.filled + 1e-9);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}

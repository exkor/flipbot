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

    /**
     * A simulated craft batch: ingredient buy orders fill at each ingredient's insta-sell rate while they are the top
     * buy order (outbid: they wait); once all are in, the result is listed at best ask - 0.1 (never below break-even)
     * and fills at its insta-buy rate.
     */
    public static final class Craft {
        public String id;
        public String output;
        public String name;
        public int outputCount;
        public long crafts;
        public State state = State.OPEN;
        public Map<String, Long> need = new LinkedHashMap<>();
        public Map<String, Double> price = new LinkedHashMap<>();
        public Map<String, Double> filled = new LinkedHashMap<>();
        public boolean selling;
        public double cost;
        public long units;
        public double sellPrice;
        public double sold;
        public double proceeds;
        public long placedAt;
    }

    public final List<Order> orders = new ArrayList<>();
    public final List<Craft> crafts = new ArrayList<>();
    public int completedCrafts;
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

    /** Starts a simulated craft batch. {@code perCraft} and {@code price} are per ingredient. */
    public Craft recordCraft(String output, String name, int outputCount, long crafts, Map<String, Integer> perCraft,
                             Map<String, Double> price, long now) {
        Craft c = new Craft();
        c.id = "PC" + nextId++;
        c.output = output;
        c.name = name;
        c.outputCount = outputCount;
        c.crafts = crafts;
        for (Map.Entry<String, Integer> e : perCraft.entrySet()) {
            long need = (long) e.getValue() * crafts;
            double p = price.getOrDefault(e.getKey(), 0.0);
            c.need.put(e.getKey(), need);
            c.price.put(e.getKey(), p);
            c.filled.put(e.getKey(), 0.0);
            c.cost += need * p;
        }
        c.units = (long) outputCount * crafts;
        c.placedAt = now;
        this.crafts.add(c);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("time", now);
        e.put("event", "CRAFT_START");
        e.put("id", c.id);
        e.put("item", output);
        e.put("crafts", crafts);
        e.put("cost", c.cost);
        events.accept(e);
        return c;
    }

    /** The craft batch in progress, or null. */
    public Craft openCraft() {
        for (Craft c : crafts) if (c.state == State.OPEN) return c;
        return null;
    }

    public int openCount() {
        int n = 0;
        for (Order o : orders) if (o.state == State.OPEN) n++;
        Craft c = openCraft();
        if (c != null) n += c.selling ? 1 : c.need.size();
        return n;
    }

    public Set<String> openProductIds() {
        Set<String> ids = new HashSet<>();
        for (Order o : orders) if (o.state == State.OPEN) ids.add(o.productId);
        Craft c = openCraft();
        if (c != null) {
            ids.addAll(c.need.keySet());
            ids.add(c.output);
        }
        return ids;
    }

    /** Coins tied up: unfilled buy orders plus the cost of unsold units in sell offers (and the open craft). */
    public double investedCoins() {
        double sum = 0;
        for (Order o : orders) {
            if (o.state != State.OPEN) continue;
            if (o.side == Side.BUY) sum += o.price * o.qty;
            else sum += o.cost * (1.0 - units(o) / (double) o.qty);
        }
        Craft c = openCraft();
        if (c != null) sum += c.selling ? c.cost * (1.0 - Math.floor(c.sold + 1e-9) / c.units) : c.cost;
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
        Craft c = openCraft();
        if (c != null) tickCraft(c, data, minutes, taxFactor, now);
    }

    private void tickCraft(Craft c, MarketData data, double minutes, double taxFactor, long now) {
        if (!c.selling) {
            boolean all = true;
            for (Map.Entry<String, Long> e : c.need.entrySet()) {
                double have = c.filled.getOrDefault(e.getKey(), 0.0);
                MarketData.Product p = data.products.get(e.getKey());
                if (have < e.getValue() && p != null && p.bestBid <= c.price.get(e.getKey()) + 1e-6) {
                    have = Math.min(e.getValue(), have + p.sellMovingWeek / WEEK_MINUTES * minutes);
                    c.filled.put(e.getKey(), have);
                }
                if (Math.floor(have + 1e-9) < e.getValue()) all = false;
            }
            if (!all) return;
            MarketData.Product out = data.products.get(c.output);
            if (out == null || out.bestAsk <= 0) return;
            c.selling = true;
            c.sellPrice = Math.max(round1(out.bestAsk - 0.1), breakEven(c, taxFactor));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("time", now);
            e.put("event", "CRAFTED");
            e.put("id", c.id);
            e.put("item", c.output);
            e.put("qty", c.units);
            e.put("price", c.sellPrice);
            events.accept(e);
            return;
        }
        MarketData.Product p = data.products.get(c.output);
        if (p == null) return;
        if (p.bestAsk > 0 && p.bestAsk < c.sellPrice - 1e-6) {
            c.sellPrice = Math.max(round1(p.bestAsk - 0.1), breakEven(c, taxFactor));
            if (p.bestAsk < c.sellPrice - 1e-6) return; // holding at break-even
        }
        long before = (long) Math.floor(c.sold + 1e-9);
        c.sold = Math.min(c.units, c.sold + p.buyMovingWeek / WEEK_MINUTES * minutes);
        long after = (long) Math.floor(c.sold + 1e-9);
        if (after > before) c.proceeds += (after - before) * c.sellPrice * taxFactor;
        if (after >= c.units) {
            c.state = State.DONE;
            completedSells++;
            completedCrafts++;
            realizedProfit += c.proceeds - c.cost;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("time", now);
            e.put("event", "CRAFT_SOLD");
            e.put("id", c.id);
            e.put("item", c.output);
            e.put("profit", c.proceeds - c.cost);
            events.accept(e);
        }
    }

    private static double breakEven(Craft c, double taxFactor) {
        return c.units > 0 ? round1(c.cost / c.units / taxFactor + 0.1) : 0;
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

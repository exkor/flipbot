package com.exkor.flipbot.flipper.trading;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class OrderLedger {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final List<Trade> trades = new ArrayList<>();

    public OrderLedger(Path file) {
        this.file = file;
        load();
    }

    public synchronized void load() {
        trades.clear();
        try {
            if (!Files.exists(file)) {
                return;
            }
            List<Trade> loaded = GSON.fromJson(Files.readString(file), new TypeToken<List<Trade>>() {}.getType());
            if (loaded != null) {
                trades.addAll(loaded);
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(trades));
        } catch (IOException ignored) {
        }
    }

    public synchronized void add(Trade trade) {
        trades.add(trade);
        save();
    }

    public synchronized void remove(Trade trade) {
        trades.remove(trade);
        save();
    }

    public synchronized List<Trade> all() {
        return new ArrayList<>(trades);
    }

    public synchronized List<Trade> inState(Trade.State state) {
        List<Trade> result = new ArrayList<>();
        for (Trade trade : trades) {
            if (trade.state == state) {
                result.add(trade);
            }
        }
        return result;
    }

    public synchronized Trade firstMatching(String displayName, Trade.State... states) {
        for (Trade trade : trades) {
            if (!ScreenUtil.sameName(trade.displayName, displayName)) { // FlipBot: ignore item-name symbols
                continue;
            }
            for (Trade.State state : states) {
                if (trade.state == state) {
                    return trade;
                }
            }
        }
        return null;
    }

    public synchronized boolean hasOpenTradeFor(String productId) {
        for (Trade trade : trades) {
            if (trade.productId.equals(productId) && trade.isOpen()) {
                return true;
            }
        }
        return false;
    }

    public synchronized int openOrderCount() {
        int count = 0;
        for (Trade trade : trades) {
            if (trade.state == Trade.State.BUY_OPEN
                    || trade.state == Trade.State.SELL_OPEN) {
                count++;
            }
        }
        return count;
    }

    public synchronized void update(Trade trade) {
        save();
    }

    /**
     * Profit of finished flips. Only trades with a known cost count: adopted sell offers (cost unknown) and leftovers
     * of partly claimed orders (0 items) would otherwise add their whole sale price.
     */
    public synchronized double realizedProfit() {
        double profit = 0;
        for (Trade trade : trades) {
            if (countsAsProfit(trade)) {
                profit += trade.revenue - trade.cost;
            }
        }
        return profit;
    }

    static boolean countsAsProfit(Trade trade) {
        return trade.state == Trade.State.DONE && trade.qty > 0 && trade.cost > 0 && !trade.craftIngredient;
    }

    public synchronized double totalInvested() {
        double invested = 0;
        for (Trade trade : trades) {
            if (trade.isOpen()) {
                invested += trade.cost;
            }
        }
        return invested;
    }

    public synchronized long tradesCompleted() {
        long count = 0;
        for (Trade trade : trades) {
            if (countsAsProfit(trade)) {
                count++;
            }
        }
        return count;
    }
}

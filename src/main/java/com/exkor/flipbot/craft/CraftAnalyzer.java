package com.exkor.flipbot.craft;

import com.exkor.flipbot.flipper.FlipperConfig;
import com.exkor.flipbot.flipper.data.MarketData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Which Bazaar crafts pay. Ingredients are priced as buy orders (best bid + 0.1), the result as a sell offer
 * (best ask - 0.1) after tax. Pure Java, unit tested.
 */
public final class CraftAnalyzer {
    private CraftAnalyzer() {}

    public record Plan(Recipe recipe, double costPerCraft, double revenuePerCraft, double profitPerCraft,
                       double marginPercent, double craftsPerHour, long batchCrafts, double batchProfit) {}

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * Profitable crafts, best batch profit first (then profit per hour, so a zero budget still ranks them). One
     * batch is as many crafts as the budget, the free inventory
     * slots (the ingredients must fit) and an hour of market volume all allow.
     */
    public static List<Plan> plans(MarketData data, List<Recipe> recipes, FlipperConfig cfg, double budget, int freeSlots) {
        List<Plan> out = new ArrayList<>();
        if (data == null) return out;
        double taxFactor = 1.0 - cfg.taxPercent / 100.0;
        for (Recipe r : recipes) {
            Plan p = plan(data, r, cfg, taxFactor, budget, freeSlots);
            if (p != null) out.add(p);
        }
        out.sort(Comparator.comparingDouble(Plan::batchProfit)
                .thenComparingDouble(p -> p.profitPerCraft() * p.craftsPerHour()).reversed());
        return out;
    }

    static Plan plan(MarketData data, Recipe r, FlipperConfig cfg, double taxFactor, double budget, int freeSlots) {
        MarketData.Product result = data.products.get(r.output());
        if (result == null || result.bestAsk <= 0) return null;
        double sell = round1(result.bestAsk - 0.1);
        if (!nearAverage(sell, result.avgBuyPrice, cfg)) return null;

        double cost = 0;
        double craftsPerHour = result.buyMovingWeek / 168.0 / r.outputCount();
        for (Map.Entry<String, Integer> in : r.ingredients().entrySet()) {
            MarketData.Product p = data.products.get(in.getKey());
            if (p == null || p.bestBid <= 0) return null;
            double buy = round1(p.bestBid + 0.1);
            if (!nearAverage(buy, p.avgSellPrice, cfg)) return null;
            cost += buy * in.getValue();
            craftsPerHour = Math.min(craftsPerHour, p.sellMovingWeek / 168.0 / in.getValue());
        }
        if (cost <= 0) return null;
        double revenue = r.outputCount() * sell * taxFactor;
        double profit = revenue - cost;
        double margin = profit / cost * 100.0;
        if (profit <= 0 || margin < cfg.minMarginPercent || margin > cfg.maxMarginPercent) return null;
        if (craftsPerHour < 0.5) return null;
        long batch = batchCrafts(r, cost, budget, freeSlots, craftsPerHour);
        return new Plan(r, cost, revenue, profit, margin, craftsPerHour, batch, profit * batch);
    }

    /** Crafts in one batch: what the budget buys, what fits in the free slots, and an hour of market volume. */
    static long batchCrafts(Recipe r, double cost, double budget, int freeSlots, double craftsPerHour) {
        int itemsPerCraft = r.ingredients().values().stream().mapToInt(Integer::intValue).sum();
        long byBudget = budget > 0 ? (long) Math.floor(budget / cost) : 0;
        long byInventory = itemsPerCraft > 0 ? (long) Math.floor(freeSlots * 64.0 / itemsPerCraft) : 0;
        long byMarket = (long) Math.ceil(craftsPerHour);
        return Math.min(byBudget, Math.min(byInventory, byMarket));
    }

    /** The same "price too far from the weekly average" guard as the flip analyzer. */
    private static boolean nearAverage(double price, double average, FlipperConfig cfg) {
        return average <= 0 || Math.abs(price - average) / average * 100.0 <= cfg.maxPriceDeviationPercent;
    }
}

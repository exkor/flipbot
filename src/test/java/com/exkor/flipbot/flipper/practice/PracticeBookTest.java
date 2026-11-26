package com.exkor.flipbot.flipper.practice;

import com.exkor.flipbot.flipper.data.MarketData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PracticeBookTest {
    private static final double TAX = 1.25;
    private static final long MIN = 60_000L;
    /** 10,080 per week = exactly 1 unit per minute. */
    private static final double ONE_PER_MINUTE = PracticeBook.WEEK_MINUTES;

    private static MarketData market(double bid, double ask, double sellWeek, double buyWeek) {
        MarketData data = new MarketData();
        MarketData.Product p = new MarketData.Product();
        p.productId = "GEM";
        p.bestBid = bid;
        p.bestAsk = ask;
        p.sellMovingWeek = sellWeek;
        p.buyMovingWeek = buyWeek;
        data.products.put("GEM", p);
        return data;
    }

    /** A book whose clock has started at t=0 (the first tick only sets the clock). */
    private static PracticeBook started(MarketData m) {
        PracticeBook book = new PracticeBook();
        book.tick(m, TAX, 2, 1);
        return book;
    }

    @Test
    void firstTickOnlyStartsTheClock() {
        PracticeBook book = new PracticeBook();
        book.recordBuy("GEM", "Gem", 100.1, 5, 1000, 0);
        book.tick(market(100, 120, ONE_PER_MINUTE, ONE_PER_MINUTE), TAX, 2, 10 * MIN);
        assertEquals(0, book.orders.get(0).filled);
        assertEquals(0, book.activeMinutes);
    }

    @Test
    void buyFillsAtInstaSellRateWhileOnTopThenListsSellOffer() {
        MarketData m = market(100.0, 120.0, ONE_PER_MINUTE, ONE_PER_MINUTE);
        PracticeBook book = started(m);
        PracticeBook.Order buy = book.recordBuy("GEM", "Gem", 100.1, 5, 1000, 1);
        assertEquals(500.5, book.investedCoins(), 1e-9);
        assertEquals(1, book.openCount());

        book.tick(m, TAX, 2, 1 + 3 * MIN); // 3 units
        assertEquals(3.0, buy.filled, 1e-9);
        assertEquals(PracticeBook.State.OPEN, buy.state);

        book.tick(m, TAX, 2, 1 + 6 * MIN); // capped at qty 5
        assertEquals(PracticeBook.State.DONE, buy.state);
        assertEquals(1, book.filledBuys);
        PracticeBook.Order sell = book.orders.get(1);
        assertEquals(PracticeBook.Side.SELL, sell.side);
        assertEquals(119.9, sell.price, 1e-9, "Best Offer -0.1");
        assertEquals(500.5, sell.cost, 1e-9);
        assertEquals(Set_of("GEM"), book.openProductIds());
    }

    @Test
    void completedFlipBooksProfitAfterTax() {
        MarketData m = market(100.0, 120.0, ONE_PER_MINUTE * 10, ONE_PER_MINUTE * 10);
        PracticeBook book = started(m);
        book.recordBuy("GEM", "Gem", 100.1, 5, 1000, 1);
        book.tick(m, TAX, 2, 1 + MIN);       // buy fills (10/min)
        book.tick(m, TAX, 2, 1 + 2 * MIN);   // sell fills
        assertEquals(1, book.completedSells);
        assertEquals(0, book.openCount());
        double expected = 5 * 119.9 * (1 - TAX / 100) - 5 * 100.1;
        assertEquals(expected, book.realizedProfit, 1e-6);
        assertEquals(0, book.investedCoins(), 1e-9);
    }

    @Test
    void outbidBuyIsCancelledAfterTheConfiguredTicksAndFilledPartIsSold() {
        MarketData top = market(100.0, 120.0, ONE_PER_MINUTE, ONE_PER_MINUTE);
        PracticeBook book = started(top);
        PracticeBook.Order buy = book.recordBuy("GEM", "Gem", 100.1, 10, 1000, 1);
        book.tick(top, TAX, 2, 1 + 2 * MIN); // 2 filled
        MarketData outbid = market(101.0, 120.0, ONE_PER_MINUTE, ONE_PER_MINUTE);
        book.tick(outbid, TAX, 2, 1 + 3 * MIN);
        assertEquals(PracticeBook.State.OPEN, buy.state, "one outbid tick is not enough");
        assertEquals(2.0, buy.filled, 1e-9, "no fills while outbid");
        book.tick(outbid, TAX, 2, 1 + 4 * MIN);
        assertEquals(PracticeBook.State.CANCELLED, buy.state);
        assertEquals(1, book.cancelledBuys);
        PracticeBook.Order sell = book.orders.get(1);
        assertEquals(2, sell.qty);
        assertEquals(200.2, sell.cost, 1e-9);
    }

    @Test
    void outbidCounterResetsWhenBackOnTop() {
        PracticeBook book = started(market(100, 120, 0, 0));
        PracticeBook.Order buy = book.recordBuy("GEM", "Gem", 100.1, 10, 1000, 1);
        book.tick(market(101, 120, 0, 0), TAX, 2, 1 + MIN);
        book.tick(market(100, 120, 0, 0), TAX, 2, 1 + 2 * MIN);
        book.tick(market(101, 120, 0, 0), TAX, 2, 1 + 3 * MIN);
        assertEquals(PracticeBook.State.OPEN, buy.state);
    }

    @Test
    void undercutSellRelistsButNeverBelowBreakEven() {
        MarketData m = market(100.0, 120.0, ONE_PER_MINUTE * 10, 0);
        PracticeBook book = started(m);
        book.recordBuy("GEM", "Gem", 100.1, 5, 1000, 1);
        book.tick(m, TAX, 2, 1 + MIN);
        PracticeBook.Order sell = book.orders.get(1);
        assertEquals(119.9, sell.price, 1e-9);

        book.tick(market(100.0, 110.0, 0, ONE_PER_MINUTE), TAX, 2, 1 + 2 * MIN);
        assertEquals(109.9, sell.price, 1e-9, "relisted at best ask - 0.1");
        assertEquals(1.0, sell.filled, 1e-9, "fills again once lowest");

        book.tick(market(100.0, 95.0, 0, ONE_PER_MINUTE), TAX, 2, 1 + 3 * MIN);
        double breakEven = Math.round((100.1 / (1 - TAX / 100) + 0.1) * 10) / 10.0;
        assertEquals(breakEven, sell.price, 1e-9, "floored at break-even after tax");
        assertEquals(1.0, sell.filled, 1e-9, "no fills while still undercut");
    }

    @Test
    void longGapsAreCappedSoARestartIsNotHoursOfFills() {
        MarketData m = market(100.0, 120.0, ONE_PER_MINUTE, ONE_PER_MINUTE);
        PracticeBook book = started(m);
        PracticeBook.Order buy = book.recordBuy("GEM", "Gem", 100.1, 100, 1000, 1);
        book.tick(m, TAX, 2, 1 + 120 * MIN);
        assertEquals(PracticeBook.MAX_TICK_MINUTES, buy.filled, 1e-9);
        assertEquals(PracticeBook.MAX_TICK_MINUTES, book.activeMinutes, 1e-9);
    }

    @Test
    void reportComparesRealAndPredictedCoinsPerHour() {
        MarketData m = market(100.0, 120.0, 0, 0); // never fills
        PracticeBook book = started(m);
        book.recordBuy("GEM", "Gem", 100.1, 5, 6000, 1); // predicted 6000/h = 100/min
        for (int i = 1; i <= 12; i++) book.tick(m, TAX, 99, 1 + i * 5 * MIN); // 1 hour
        PracticeBook.Report r = book.report();
        assertEquals(1.0, r.hours(), 1e-9);
        assertEquals(6000, r.predictedPerHour(), 1e-6);
        assertEquals(0, r.realizedPerHour(), 1e-9);
        assertEquals(1, r.open());
    }

    @Test
    void unknownProductIsLeftAlone() {
        PracticeBook book = started(market(100, 120, 1, 1));
        PracticeBook.Order buy = book.recordBuy("GONE", "Gone", 5, 1, 0, 1);
        book.tick(market(100, 120, 1, 1), TAX, 1, 1 + MIN);
        assertEquals(PracticeBook.State.OPEN, buy.state);
    }

    @Test
    void storeSavesTheBookAndLogsEvents(@TempDir Path dir) throws Exception {
        PracticeStore store = new PracticeStore(dir);
        MarketData m = market(100.0, 120.0, ONE_PER_MINUTE * 10, ONE_PER_MINUTE * 10);
        PracticeBook book = store.book();
        book.tick(m, TAX, 2, 1);
        book.recordBuy("GEM", "Gem", 100.1, 5, 1000, 1);
        book.tick(m, TAX, 2, 1 + MIN);
        store.save();

        PracticeBook reloaded = new PracticeStore(dir).book();
        assertEquals(2, reloaded.orders.size());
        assertEquals(1, reloaded.filledBuys);
        assertEquals(book.lastTickAt, reloaded.lastTickAt);
        assertEquals("P2", reloaded.recordBuy("GEM", "Gem", 1, 1, 0, 2).id, "ids continue after reload");

        List<String> lines = Files.readAllLines(dir.resolve("practice.jsonl"));
        List<String> events = new ArrayList<>();
        for (String line : lines) events.add(com.google.gson.JsonParser.parseString(line).getAsJsonObject().get("event").getAsString());
        assertEquals(List.of("PLACE", "FILL", "PLACE", "PLACE"), events);
        Map<?, ?> first = new com.google.gson.Gson().fromJson(lines.get(0), Map.class);
        assertTrue(first.keySet().containsAll(List.of("time", "item", "side", "price", "qty")));
    }

    private static java.util.Set<String> Set_of(String... ids) {
        return java.util.Set.of(ids);
    }
}

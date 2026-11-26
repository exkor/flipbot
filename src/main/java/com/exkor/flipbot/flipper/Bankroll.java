package com.exkor.flipbot.flipper;

/**
 * How much the bot may trade with. Pure Java, unit tested.
 *
 * <ul>
 *   <li><b>Profit only</b> ({@code tradeWithProfitOnly}, default): the bot never has more coins in the Bazaar (open
 *       orders and unsold items) than the profit it has made. Your own coins are never used.</li>
 *   <li>Otherwise a fixed cap ({@code maxInvestedMillions}) when the whole-purse setting is off.</li>
 *   <li>Always at most the purse minus the reserve, read live from the scoreboard.</li>
 * </ul>
 */
public final class Bankroll {
    private Bankroll() {}

    /** The most the bot may have in the Bazaar at once, or -1 for no cap. */
    public static double cap(FlipperConfig cfg, double profitMade) {
        if (cfg.tradeWithProfitOnly) return Math.max(0, profitMade);
        if (!cfg.investFromPurse && cfg.maxInvestedMillions > 0) return cfg.maxInvestedMillions * 1_000_000.0;
        return -1;
    }

    /** Bankroll for a purse reading (-1 = unknown), or -1 when the purse is unknown. */
    public static double of(FlipperConfig cfg, long purse, double profitMade) {
        if (purse < 0) return -1;
        double bankroll = Math.max(0, purse - cfg.reserveCoins);
        double cap = cap(cfg, profitMade);
        return cap >= 0 ? Math.min(bankroll, cap) : bankroll;
    }

    /** True when some cap applies (profit only, or a fixed cap). */
    public static boolean hasCap(FlipperConfig cfg) {
        return cfg.tradeWithProfitOnly || (!cfg.investFromPurse && cfg.maxInvestedMillions > 0);
    }

    /** Going live needs a limit: profit only, the purse itself, or a fixed cap. */
    public static boolean hasLimit(FlipperConfig cfg) {
        return cfg.tradeWithProfitOnly || cfg.investFromPurse || cfg.maxInvestedMillions > 0;
    }
}

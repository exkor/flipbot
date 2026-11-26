package com.exkor.flipbot.craft;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.exkor.flipbot.craft.Collections.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class CollectionsTest {
    @Test
    void romanNumerals() {
        assertEquals(4, Collections.roman("IV"));
        assertEquals(9, Collections.roman("IX"));
        assertEquals(14, Collections.roman("XIV"));
        assertEquals(25, Collections.roman("XXV"));
        assertEquals(0, Collections.roman("IVa"));
    }

    @Test
    void requirementsFromTheRecipeRepo() {
        assertEquals(new Collections.Requirement("Gold Ingot", 4), Collections.requirement("Requires: Gold Ingot IV"));
        assertEquals(new Collections.Requirement("Raw Porkchop", 3), Collections.requirement("Requires Raw Porkchop III"));
        assertEquals(new Collections.Requirement("Gemstone", 9), Collections.requirement("Requires: Gemstone IX"));
        assertEquals(0, Collections.requirement("").tier(), "no requirement");
        assertNull(Collections.requirement("Requires: Spider Slayer 6"), "slayer is not a collection");
        assertNull(Collections.requirement("Requires: HotM 5"));
        assertNull(Collections.requirement("Requires: 20 Museum Donations"));
    }

    @Test
    void collectionMenuNames() {
        assertEquals(Map.entry("Gold Ingot", 7), Collections.menuEntry("Gold Ingot VII"));
        assertEquals(Map.entry("Raw Porkchop", 0), Collections.menuEntry("Raw Porkchop"), "nothing reached yet");
        assertEquals(Map.entry("Ice", 11), Collections.menuEntry("Ice XI"));
    }

    @Test
    void levelUpChatLines() {
        assertEquals(Map.entry("Gold Ingot", 7), Collections.levelUp("  COLLECTION LEVEL UP Gold Ingot VII"));
        assertEquals(Map.entry("Gold Ingot", 7), Collections.levelUp("COLLECTION LEVEL UP Gold Ingot VI ➜ VII"));
        assertEquals(Map.entry("Raw Porkchop", 2), Collections.levelUp("COLLECTION LEVEL UP Raw Porkchop II"));
        assertNull(Collections.levelUp("SKILL LEVEL UP Farming XXI"));
        assertNull(Collections.levelUp("[Bazaar] Buy Order Setup! 5x Stone for 500 coins."));
    }

    private static Recipe needs(String text) {
        return new Recipe("X", 1, Map.of("Y", 1), text);
    }

    @Test
    void recipeStatusFromTiers() {
        Map<String, Integer> tiers = Map.of("gold ingot", 7, "raw porkchop", 0);
        assertEquals(UNLOCKED, Collections.status(needs("Requires: Gold Ingot IV"), tiers));
        assertEquals(UNLOCKED, Collections.status(needs("Requires: Gold Ingot VII"), tiers));
        assertEquals(LOCKED, Collections.status(needs("Requires: Gold Ingot VIII"), tiers));
        assertEquals(LOCKED, Collections.status(needs("Requires Raw Porkchop III"), tiers));
        assertEquals(UNKNOWN, Collections.status(needs(""), tiers), "none listed is not proof of no requirement");
        assertEquals(UNKNOWN, Collections.status(needs("Requires: Mithril IV"), tiers), "not in the scan");
        assertEquals(UNKNOWN, Collections.status(needs("Requires: Spider Slayer 6"), tiers), "not a collection");
        assertEquals(UNKNOWN, Collections.status(needs("Requires: Gold Ingot IV"), null), "never scanned");
    }
}

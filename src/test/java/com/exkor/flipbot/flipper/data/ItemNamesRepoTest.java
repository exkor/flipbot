package com.exkor.flipbot.flipper.data;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ItemNamesRepoTest {
    @Test
    void guessedNamesFollowHypixelsPatterns() {
        assertEquals("Ant Shard", ItemNames.guessName("SHARD_ANT"));
        assertEquals("Black Widow Shard", ItemNames.guessName("SHARD_BLACK_WIDOW"));
        assertEquals("Bug Blender III", ItemNames.guessName("ENCHANTMENT_BUG_BLENDER_3"));
        assertEquals("Arcane VI", ItemNames.guessName("ENCHANTMENT_ARCANE_6"));
        assertEquals("Wise V", ItemNames.guessName("ENCHANTMENT_ULTIMATE_WISE_5"), "no 'Ultimate', like the bundled list");
        assertEquals("Sharpness X", ItemNames.guessName("ENCHANTMENT_SHARPNESS_10"));
        assertEquals("Null Ovoid", ItemNames.guessName("NULL_OVOID"));
    }

    @Test
    void bundledShardNamesGetShardAndGuessesResolveBack() {
        ItemNames.load();
        try {
            assertEquals("Salmon Shard", ItemNames.displayName("SHARD_SALMON"));
            assertEquals(List.of("Salmon Shard", "Salmon"), ItemNames.aliases("SHARD_SALMON"));
            assertEquals("SHARD_SALMON", ItemNames.productId("Salmon Shard"));
            assertEquals("SHARD_CARROT_KING", ItemNames.productId("Carrot King Shard"));
            ItemNames.registerGuesses(List.of("SHARD_ANT", "ENCHANTMENT_BUG_BLENDER_3"));
            assertEquals("SHARD_ANT", ItemNames.productId("Ant Shard"));
            assertEquals("ENCHANTMENT_BUG_BLENDER_3", ItemNames.productId("Bug Blender III"));
            assertEquals("Protection VII", ItemNames.displayName("ENCHANTMENT_PROTECTION_7"), "bundled enchant names stay");
        } finally {
            ItemNames.load();
        }
    }

    @Test
    void currentNamesWinAndOldNamesStayAliases() {
        ItemNames.load();
        assertEquals("Enchanted Gold", ItemNames.displayName("ENCHANTED_GOLD"), "bundled name before the repo loads");
        ItemNames.setRepoNames(Map.of(
                "ENCHANTED_GOLD", "§aEnchanted Gold Ingot",
                "FINE_JADE_GEM", "§9 Fine Jade Gemstone",     // symbol-only difference: keep ours
                "BACHELOR_ROSE", "§aBachelor's Rose",               // not in the bundled list at all
                "ENCHANTED_MELON_BLOCK", "§9Enchanted Melon",
                "ENCHANTED_MELON", "§aEnchanted Melon Slice"));
        try {
            assertEquals("Enchanted Gold Ingot", ItemNames.displayName("ENCHANTED_GOLD"));
            assertEquals(List.of("Enchanted Gold Ingot", "Enchanted Gold"), ItemNames.aliases("ENCHANTED_GOLD"));
            assertEquals("ENCHANTED_GOLD", ItemNames.productId("Enchanted Gold Ingot"));
            assertEquals("ENCHANTED_GOLD", ItemNames.productId("Enchanted Gold"), "old name still resolves");
            assertTrue(ItemNames.displayName("FINE_JADE_GEM").contains("Fine Jade Gemstone"));
            assertNotEquals('', ItemNames.displayName("FINE_JADE_GEM").charAt(0));
            assertEquals("Bachelor's Rose", ItemNames.displayName("BACHELOR_ROSE"));
            assertEquals("ENCHANTED_MELON_BLOCK", ItemNames.productId("Enchanted Melon"), "a reused name means the current item");
            assertEquals("ENCHANTED_MELON", ItemNames.productId("Enchanted Melon Slice"));
        } finally {
            ItemNames.load(); // leave the bundled names for other tests
        }
    }
}

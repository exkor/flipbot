package com.exkor.flipbot.flipper.data;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ItemNamesRepoTest {
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

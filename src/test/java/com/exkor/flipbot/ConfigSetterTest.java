package com.exkor.flipbot;

import com.exkor.flipbot.flipper.FlipperConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigSetterTest {
    @Test
    void setsEachSupportedType() {
        FlipperConfig c = new FlipperConfig();
        assertEquals("14.0", ConfigSetter.set(c, "maxMarginPercent", "14"));
        assertEquals(14.0, c.maxMarginPercent);
        assertEquals("5", ConfigSetter.set(c, "MAXORDERS", "5"));
        assertEquals("2000000", ConfigSetter.set(c, "reserveCoins", "2_000_000"));
        assertEquals("false", ConfigSetter.set(c, "requireHistoricalPrice", "off"));
        assertEquals("MARGIN", ConfigSetter.set(c, "flipsSort", "MARGIN"));
    }

    @Test
    void rejectsBadInputAndLeavesValueAlone() {
        FlipperConfig c = new FlipperConfig();
        assertThrows(IllegalArgumentException.class, () -> ConfigSetter.set(c, "noSuchKey", "1"));
        assertThrows(IllegalArgumentException.class, () -> ConfigSetter.set(c, "maxOrders", "lots"));
        assertThrows(IllegalArgumentException.class, () -> ConfigSetter.set(c, "onlyItemsMode", "maybe"));
        assertThrows(IllegalArgumentException.class, () -> ConfigSetter.set(c, "configVersion", "9"));
        assertEquals(14, c.maxOrders);
        assertFalse(c.onlyItemsMode);
    }

    @Test
    void listsOnlySimpleFields() {
        List<String> keys = ConfigSetter.keys(new FlipperConfig());
        assertTrue(keys.contains("actionDelayMs"));
        assertFalse(keys.contains("blacklist"));
        assertFalse(keys.contains("configVersion"));
    }
}

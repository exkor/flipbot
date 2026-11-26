package com.exkor.flipbot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmokeTest {
    @Test
    void junitRuns() {
        assertEquals("flipbot", FlipBotClient.MOD_ID);
    }
}

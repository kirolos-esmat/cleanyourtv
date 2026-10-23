package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class WeatherTest {

    @Test
    public void testWeatherIcons() {
        assertEquals("☀️", Weather.icon(0, true));
        assertEquals("🌙", Weather.icon(0, false));
        assertEquals("⛅", Weather.icon(1, true));
        assertEquals("⛅", Weather.icon(2, true));
        assertEquals("☁️", Weather.icon(2, false));
        assertEquals("☁️", Weather.icon(3, true));
        assertEquals("☁️", Weather.icon(45, true));
        assertEquals("🌦️", Weather.icon(51, true));
        assertEquals("🌧️", Weather.icon(61, true));
        assertEquals("🌧️", Weather.icon(80, true));
        assertEquals("❄️", Weather.icon(71, true));
        assertEquals("⛈️", Weather.icon(95, true));
    }

    @Test
    public void testIconNotNull() {
        for (int i = 0; i <= 100; i++) {
            assertNotNull(Weather.icon(i, true));
            assertNotNull(Weather.icon(i, false));
        }
    }
}

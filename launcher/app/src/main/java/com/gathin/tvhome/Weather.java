package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Current weather for the home header (config.xml show_weather).
 * Place comes from config or is auto-detected via public IP (ipwho.is, falling back to ipapi.co)
 * once a day; weather comes from Open-Meteo every 30 minutes.
 * No API keys or accounts required. Text is cached in SharedPreferences so Home displays the last reading immediately.
 */
final class Weather {
    private static final String TAG = "TvHome.Weather";

    interface Listener {
        void onWeather(String text);
    }

    private static final String PREFS = "weather";
    private static final long WEATHER_MS = 30 * 60_000L;
    private static final long LOCATION_MS = 24 * 60 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile boolean running;

    private Weather() { }

    /** Shows the cached text right away if present, then fetches a fresh reading if older than 30 minutes. */
    static void refresh(Context ctx, Config cfg, Handler mainHandler, Listener listener) {
        if (cfg == null || !cfg.showWeather) {
            Log.d(TAG, "refresh skipped: cfg=" + cfg + " showWeather=" + (cfg != null && cfg.showWeather));
            return;
        }
        Context app = ctx.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        boolean hasConfigCoords = cfg.weatherLatitude != null && !cfg.weatherLatitude.isEmpty()
                && cfg.weatherLongitude != null && !cfg.weatherLongitude.isEmpty();
        boolean placeMismatch = hasConfigCoords && (!cfg.weatherPlace.trim().equals(p.getString("city", ""))
                || !cfg.weatherLatitude.trim().equals(p.getString("cfg_lat", "")));
        if (placeMismatch) {
            p.edit().remove("text").remove("time").putString("cfg_lat", cfg.weatherLatitude.trim()).apply();
        }

        String cached = p.getString("text", null);
        if (cached != null) {
            Log.d(TAG, "using cached weather: " + cached);
            listener.onWeather(cached);
        }

        long now = System.currentTimeMillis();
        long lastTime = p.getLong("time", 0);
        if ((cached != null && now - lastTime < WEATHER_MS) || running) {
            Log.d(TAG, "refresh not needed yet: now=" + now + " lastTime=" + lastTime + " running=" + running);
            return;
        }

        running = true;
        Log.d(TAG, "starting background weather fetch");
        IO.execute(() -> {
            try {
                String text = fetch(cfg, p);
                Log.d(TAG, "fetch returned: " + text);
                if (text != null) {
                    p.edit()
                            .putString("text", text)
                            .putLong("time", System.currentTimeMillis())
                            .apply();
                    if (mainHandler != null) {
                        mainHandler.post(() -> listener.onWeather(text));
                    }
                } else {
                    p.edit().putLong("time", System.currentTimeMillis() - WEATHER_MS + RETRY_MS).apply();
                }
            } catch (Exception e) {
                Log.w(TAG, "Weather fetch failed: " + e, e);
            } finally {
                running = false;
            }
        });
    }

    private static String fetch(Config cfg, SharedPreferences p) {
        try {
            double lat;
            double lon;
            String city;

            boolean hasConfigCoords = cfg.weatherLatitude != null && !cfg.weatherLatitude.isEmpty()
                    && cfg.weatherLongitude != null && !cfg.weatherLongitude.isEmpty();

            if (hasConfigCoords) {
                lat = Double.parseDouble(cfg.weatherLatitude);
                lon = Double.parseDouble(cfg.weatherLongitude);
                city = cfg.weatherPlace != null ? cfg.weatherPlace.trim() : "";
                p.edit().putString("city", city).putString("cfg_lat", cfg.weatherLatitude.trim()).apply();
                Log.d(TAG, "using config coords: lat=" + lat + " lon=" + lon + " city=" + city);
            } else {
                long now = System.currentTimeMillis();
                boolean staleLoc = now - p.getLong("locTime", 0) > LOCATION_MS || !p.contains("lat");
                Log.d(TAG, "resolving location: stale=" + staleLoc);
                if (staleLoc) {
                    boolean found = locate(p, "https://ipwho.is/") || locate(p, "https://ipapi.co/json/");
                    Log.d(TAG, "locate result: " + found);
                    if (!found && !p.contains("lat")) return null;
                }
                lat = Double.longBitsToDouble(p.getLong("lat", 0));
                lon = Double.longBitsToDouble(p.getLong("lon", 0));
                city = p.getString("city", "");
                Log.d(TAG, "resolved coords: lat=" + lat + " lon=" + lon + " city=" + city);
            }

            String unit = "fahrenheit".equalsIgnoreCase(cfg.weatherUnit) ? "fahrenheit" : "celsius";
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,weather_code,is_day&temperature_unit=%s",
                    lat, lon, unit);
            Log.d(TAG, "requesting weather url: " + url);

            String jsonStr = get(url);
            JSONObject root = new JSONObject(jsonStr);
            JSONObject cur = root.getJSONObject("current");
            double temp = cur.getDouble("temperature_2m");
            int code = cur.getInt("weather_code");
            boolean day = cur.optInt("is_day", 1) == 1;

            String icon = icon(code, day);
            long rounded = Math.round(temp);
            return icon + "  " + rounded + "°" + (city.isEmpty() ? "" : "  ·  " + city);
        } catch (Exception e) {
            Log.w(TAG, "fetch error: " + e, e);
            return null;
        }
    }

    private static boolean locate(SharedPreferences p, String url) {
        try {
            Log.d(TAG, "locate querying " + url);
            String jsonStr = get(url);
            JSONObject json = new JSONObject(jsonStr);
            if (!json.has("latitude") || !json.has("longitude")) {
                Log.w(TAG, "locate response missing coords: " + jsonStr);
                return false;
            }
            double lat = json.getDouble("latitude");
            double lon = json.getDouble("longitude");
            String city = json.optString("city", "");
            p.edit()
                    .putLong("lat", Double.doubleToLongBits(lat))
                    .putLong("lon", Double.doubleToLongBits(lon))
                    .putString("city", city)
                    .putLong("locTime", System.currentTimeMillis())
                    .apply();
            Log.d(TAG, "locate success: lat=" + lat + " lon=" + lon + " city=" + city);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "locate error querying " + url + ": " + e);
            return false;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "CleanYourTV-Launcher");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** WMO weather code to an emoji symbol. */
    static String icon(int code, boolean day) {
        if (code == 0) return day ? "\u2600\uFE0F" : "\uD83C\uDF19";                  // ☀️ / 🌙 (sun / moon)
        if (code <= 2) return day ? "\u26C5" : "\u2601\uFE0F";                          // ⛅ / ☁️ (partly cloudy)
        if (code == 3) return "\u2601\uFE0F";                                           // ☁️ (overcast)
        if (code == 45 || code == 48) return "\u2601\uFE0F";                           // ☁️ (fog: older TV emoji fonts show fog as a box, so cloud is safer)
        if (code >= 51 && code <= 57) return "\uD83C\uDF26\uFE0F";                      // 🌦️ (drizzle)
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "\uD83C\uDF27\uFE0F"; // 🌧️ (rain)
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "\u2744\uFE0F";         // ❄️ (snow)
        if (code >= 95) return "\u26C8\uFE0F";                                          // ⛈️ (thunderstorm)
        return "\uD83C\uDF27\uFE0F";
    }
}

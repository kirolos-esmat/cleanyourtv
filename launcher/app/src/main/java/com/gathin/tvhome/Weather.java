package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Current weather for the home header (config.xml show_weather).
 * Place is auto-detected from Android's location service (Wi-Fi/network provider), falling back to public IP
 * (ipwho.is / ipapi.co) once a day; weather comes from Open-Meteo every 30 minutes.
 * Can also be set manually in config.xml or config.json.
 * Text is cached in SharedPreferences so Home displays the last reading immediately.
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
    private static final ExecutorService IO_CALLBACKS = Executors.newSingleThreadExecutor();
    private static volatile boolean running;

    private Weather() { }

    /** Shows the cached text right away if present, then fetches a fresh reading if older than 30 minutes. */
    static void refresh(Context ctx, Config cfg, Handler mainHandler, Listener listener) {
        if (cfg == null || !cfg.showWeather) {
            Log.d(TAG, "refresh skipped: showWeather is false");
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
        long lastUpgradeAttempt = p.getLong("upgrade_attempt", 0);
        // If location was previously from IP, try upgrading to device location if available, but at most once per day
        boolean upgrade = !hasConfigCoords && !"device".equals(p.getString("src", "")) && locationOn(app)
                && (now - lastUpgradeAttempt > LOCATION_MS);

        if ((cached != null && now - lastTime < WEATHER_MS && !upgrade) || running) {
            Log.d(TAG, "refresh not needed yet: running=" + running);
            return;
        }

        running = true;
        Log.d(TAG, "starting background weather fetch");
        IO.execute(() -> {
            try {
                String text = fetch(app, cfg, p);
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

    private static String fetch(Context ctx, Config cfg, SharedPreferences p) {
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
                long lastUpgrade = p.getLong("upgrade_attempt", 0);
                boolean upgrade = !"device".equals(p.getString("src", "")) && locationOn(ctx)
                        && (now - lastUpgrade > LOCATION_MS);
                Log.d(TAG, "resolving location: stale=" + staleLoc + " upgrade=" + upgrade);
                if (staleLoc || upgrade) {
                    if (upgrade) p.edit().putLong("upgrade_attempt", now).apply();
                    boolean found = locateDevice(ctx, p);
                    if (!found && staleLoc) found = locate(p, "https://ipwho.is/") || locate(p, "https://ipapi.co/json/");
                    if (!found && !p.contains("lat")) return null;
                }
                lat = Double.longBitsToDouble(p.getLong("lat", 0));
                lon = Double.longBitsToDouble(p.getLong("lon", 0));
                city = p.getString("city", "");
                Log.d(TAG, "resolved coords: lat=" + lat + " lon=" + lon + " city=" + city + " src=" + p.getString("src", ""));
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

    private static boolean locationOn(Context ctx) {
        boolean fine = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) return false;
        LocationManager lm = ctx.getSystemService(LocationManager.class);
        return lm != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || lm.isLocationEnabled());
    }

    @SuppressWarnings("MissingPermission")
    private static boolean locateDevice(Context ctx, SharedPreferences p) {
        try {
            if (!locationOn(ctx)) {
                Log.d(TAG, "locateDevice skipped: location permissions or service off");
                return false;
            }
            LocationManager lm = ctx.getSystemService(LocationManager.class);
            if (lm == null) return false;
            Location loc = null;
            for (String provider : new String[]{"fused", LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                try {
                    Location last = lm.getLastKnownLocation(provider);
                    if (last != null) {
                        loc = last;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }

            if (loc == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                CountDownLatch done = new CountDownLatch(1);
                Location[] got = new Location[1];
                for (String provider : new String[]{"fused", LocationManager.NETWORK_PROVIDER}) {
                    try {
                        lm.getCurrentLocation(provider, null, IO_CALLBACKS, l -> {
                            if (l != null) got[0] = l;
                            done.countDown();
                        });
                        done.await(4, TimeUnit.SECONDS);
                        if (got[0] != null) {
                            loc = got[0];
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            if (loc == null) {
                Log.d(TAG, "locateDevice: no location found from providers");
                return false;
            }

            double lat = loc.getLatitude();
            double lon = loc.getLongitude();
            String city = "";

            try {
                if (Geocoder.isPresent()) {
                    List<Address> list = new Geocoder(ctx, Locale.ENGLISH).getFromLocation(lat, lon, 1);
                    if (list != null && !list.isEmpty()) {
                        Address a = list.get(0);
                        String admin = a.getAdminArea() != null ? a.getAdminArea().replace(" Governorate", "").replace(" governorate", "").trim() : "";
                        String subAdmin = a.getSubAdminArea() != null ? a.getSubAdminArea() : "";
                        String locality = a.getLocality() != null ? a.getLocality() : "";

                        if (!admin.isEmpty() && (admin.equalsIgnoreCase("Alexandria") || admin.equalsIgnoreCase("Cairo") || admin.equalsIgnoreCase("Giza"))) {
                            city = admin;
                        } else if (!subAdmin.isEmpty() && (subAdmin.equalsIgnoreCase("Alexandria") || subAdmin.equalsIgnoreCase("Cairo") || subAdmin.equalsIgnoreCase("Giza"))) {
                            city = subAdmin;
                        } else if (!locality.isEmpty()) {
                            city = locality;
                        } else if (!subAdmin.isEmpty()) {
                            city = subAdmin;
                        } else if (!admin.isEmpty()) {
                            city = admin;
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            if (city.isEmpty()) {
                try {
                    String geoUrl = String.format(Locale.US,
                            "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.4f&longitude=%.4f&localityLanguage=en",
                            lat, lon);
                    JSONObject json = new JSONObject(get(geoUrl));
                    city = json.optString("city", json.optString("locality", ""));
                } catch (Exception ignored) {
                }
            }

            p.edit()
                    .putLong("lat", Double.doubleToLongBits(lat))
                    .putLong("lon", Double.doubleToLongBits(lon))
                    .putString("city", city)
                    .putLong("locTime", System.currentTimeMillis())
                    .putString("src", "device")
                    .apply();
            Log.d(TAG, "locateDevice success: lat=" + lat + " lon=" + lon + " city=" + city);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "locateDevice error: " + e);
            return false;
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
                    .putString("src", "ip")
                    .apply();
            Log.d(TAG, "locate IP success: lat=" + lat + " lon=" + lon + " city=" + city);
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
        if (code == 45 || code == 48) return "\u2601\uFE0F";                           // ☁️ (fog)
        if (code >= 51 && code <= 57) return "\uD83C\uDF26\uFE0F";                      // 🌦️ (drizzle)
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "\uD83C\uDF27\uFE0F"; // 🌧️ (rain)
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "\u2744\uFE0F";         // ❄️ (snow)
        if (code >= 95) return "\u26C8\uFE0F";                                          // ⛈️ (thunderstorm)
        return "\uD83C\uDF27\uFE0F";
    }
}

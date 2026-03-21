package com.gargon.smarthome.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.gargon.smarthome.heatfloor.HeatfloorBridgeClient;
import com.gargon.smarthome.heatfloor.HeatfloorSnapshot;

import okhttp3.HttpUrl;

public final class BridgeSettings {

    private static final String PREFS_NAME = "smarthome_settings";
    private static final String KEY_BRIDGE_HOST = "bridge_host";
    private static final String LEGACY_KEY_EVENTS_URL = "events_url";
    private static final String DEFAULT_BRIDGE_HOST = "192.168.1.120";

    private static final String KEY_CHANNEL_MODE_PREFIX = "channel_mode_";
    private static final String KEY_CHANNEL_MANUAL_PREFIX = "channel_manual_";
    private static final String KEY_CHANNEL_WEEKDAY_PREFIX = "channel_weekday_";
    private static final String KEY_CHANNEL_SATURDAY_PREFIX = "channel_saturday_";
    private static final String KEY_CHANNEL_SUNDAY_PREFIX = "channel_sunday_";

    private static final int PRESET_NONE = 0;
    private static final int PRESET_MANUAL = 1;
    private static final int PRESET_WEEK = 2;

    private BridgeSettings() {
    }

    public static String getBridgeHost(Context context) {
        SharedPreferences prefs = prefs(context);
        String value = prefs.getString(KEY_BRIDGE_HOST, null);
        if (TextUtils.isEmpty(value)) {
            value = prefs.getString(LEGACY_KEY_EVENTS_URL, DEFAULT_BRIDGE_HOST);
        }

        String normalized = normalizeBridgeHost(value);
        if (isValidBridgeHost(normalized)) {
            return normalized;
        }
        return DEFAULT_BRIDGE_HOST;
    }

    public static void setBridgeHost(Context context, String host) {
        prefs(context).edit()
                .putString(KEY_BRIDGE_HOST, normalizeBridgeHost(host))
                .remove(LEGACY_KEY_EVENTS_URL)
                .apply();
    }

    public static boolean isValidBridgeHost(String host) {
        return buildBaseUrl(host) != null;
    }

    public static String getEventsUrl(Context context) {
        return getBaseUrl(context) + "events";
    }

    public static void setEventsUrl(Context context, String url) {
        setBridgeHost(context, url);
    }

    public static boolean isValidEventsUrl(String url) {
        return isValidBridgeHost(url);
    }

    public static String getBaseUrl(Context context) {
        HttpUrl url = buildBaseUrl(getBridgeHost(context));
        if (url == null) {
            url = buildBaseUrl(DEFAULT_BRIDGE_HOST);
        }
        return url == null ? "http://" + DEFAULT_BRIDGE_HOST + "/" : url.toString();
    }

    public static void rememberManualPreset(Context context, int channelId, int temperature) {
        prefs(context).edit()
                .putInt(KEY_CHANNEL_MODE_PREFIX + channelId, PRESET_MANUAL)
                .putInt(KEY_CHANNEL_MANUAL_PREFIX + channelId, temperature)
                .apply();
    }

    public static void rememberWeekPreset(Context context, int channelId, int weekdaysProgram, int saturdayProgram, int sundayProgram) {
        prefs(context).edit()
                .putInt(KEY_CHANNEL_MODE_PREFIX + channelId, PRESET_WEEK)
                .putInt(KEY_CHANNEL_WEEKDAY_PREFIX + channelId, weekdaysProgram)
                .putInt(KEY_CHANNEL_SATURDAY_PREFIX + channelId, saturdayProgram)
                .putInt(KEY_CHANNEL_SUNDAY_PREFIX + channelId, sundayProgram)
                .apply();
    }

    public static void rememberFromState(Context context, HeatfloorSnapshot.ChannelState channel) {
        if (channel == null) {
            return;
        }

        switch (channel.modeId) {
            case 1:
                if (channel.manualTemperature >= HeatfloorBridgeClient.MIN_TEMPERATURE
                        && channel.manualTemperature <= HeatfloorBridgeClient.MAX_TEMPERATURE) {
                    rememberManualPreset(context, channel.channelId, channel.manualTemperature);
                }
                break;
            case 2:
            case 5:
                if (channel.dayProgram >= 0) {
                    rememberWeekPreset(context, channel.channelId, channel.dayProgram, channel.dayProgram, channel.dayProgram);
                }
                break;
            case 3:
                if (channel.weekWeekdaysProgram >= 0
                        && channel.weekSaturdayProgram >= 0
                        && channel.weekSundayProgram >= 0) {
                    rememberWeekPreset(context, channel.channelId,
                            channel.weekWeekdaysProgram,
                            channel.weekSaturdayProgram,
                            channel.weekSundayProgram);
                }
                break;
            default:
                break;
        }
    }

    public static ChannelPreset getChannelPreset(Context context, int channelId) {
        SharedPreferences prefs = prefs(context);
        ChannelPreset preset = new ChannelPreset();
        preset.mode = prefs.getInt(KEY_CHANNEL_MODE_PREFIX + channelId, PRESET_NONE);
        preset.manualTemperature = prefs.getInt(KEY_CHANNEL_MANUAL_PREFIX + channelId, 28);
        preset.weekdaysProgram = prefs.getInt(KEY_CHANNEL_WEEKDAY_PREFIX + channelId, 0);
        preset.saturdayProgram = prefs.getInt(KEY_CHANNEL_SATURDAY_PREFIX + channelId, 0);
        preset.sundayProgram = prefs.getInt(KEY_CHANNEL_SUNDAY_PREFIX + channelId, 0);
        return preset;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String normalizeBridgeHost(String url) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) {
            return DEFAULT_BRIDGE_HOST;
        }

        if (value.contains("://")) {
            HttpUrl parsed = HttpUrl.parse(value);
            String extracted = extractHostPort(parsed);
            return TextUtils.isEmpty(extracted) ? value : extracted;
        }

        if (value.contains("/")) {
            HttpUrl parsed = HttpUrl.parse("http://" + value);
            String extracted = extractHostPort(parsed);
            if (!TextUtils.isEmpty(extracted)) {
                return extracted;
            }
            return value.substring(0, value.indexOf('/'));
        }

        return value;
    }

    private static HttpUrl buildBaseUrl(String host) {
        String normalized = normalizeBridgeHost(host);
        if (TextUtils.isEmpty(normalized)) {
            return null;
        }

        HttpUrl parsed = HttpUrl.parse("http://" + normalized);
        if (parsed == null || TextUtils.isEmpty(parsed.host())) {
            return null;
        }

        HttpUrl.Builder builder = new HttpUrl.Builder()
                .scheme("http")
                .host(parsed.host());

        if (parsed.port() != HttpUrl.defaultPort("http")) {
            builder.port(parsed.port());
        }

        return builder.build();
    }

    private static String extractHostPort(HttpUrl parsed) {
        if (parsed == null || TextUtils.isEmpty(parsed.host())) {
            return null;
        }

        if (parsed.port() != HttpUrl.defaultPort(parsed.scheme())) {
            return parsed.host() + ":" + parsed.port();
        }
        return parsed.host();
    }

    public static final class ChannelPreset {
        public int mode;
        public int manualTemperature;
        public int weekdaysProgram;
        public int saturdayProgram;
        public int sundayProgram;

        public boolean isManual() {
            return mode == PRESET_MANUAL;
        }

        public boolean isWeek() {
            return mode == PRESET_WEEK;
        }
    }
}

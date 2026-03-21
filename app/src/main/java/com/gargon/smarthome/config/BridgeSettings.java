package com.gargon.smarthome.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.gargon.smarthome.heatfloor.HeatfloorBridgeClient;
import com.gargon.smarthome.heatfloor.HeatfloorSnapshot;

import okhttp3.HttpUrl;

public final class BridgeSettings {

    private static final String PREFS_NAME = "smarthome_settings";
    private static final String KEY_EVENTS_URL = "events_url";
    private static final String DEFAULT_EVENTS_URL = "http://192.168.1.120/events";

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

    public static String getEventsUrl(Context context) {
        SharedPreferences prefs = prefs(context);
        String value = prefs.getString(KEY_EVENTS_URL, DEFAULT_EVENTS_URL);
        if (isValidEventsUrl(value)) {
            return normalizeEventsUrl(value);
        }
        return DEFAULT_EVENTS_URL;
    }

    public static void setEventsUrl(Context context, String url) {
        prefs(context).edit()
                .putString(KEY_EVENTS_URL, normalizeEventsUrl(url))
                .apply();
    }

    public static boolean isValidEventsUrl(String url) {
        HttpUrl parsed = HttpUrl.parse(normalizeEventsUrl(url));
        return parsed != null
                && !TextUtils.isEmpty(parsed.scheme())
                && !TextUtils.isEmpty(parsed.host())
                && ("http".equals(parsed.scheme()) || "https".equals(parsed.scheme()));
    }

    public static String getBaseUrl(Context context) {
        HttpUrl url = HttpUrl.parse(getEventsUrl(context));
        if (url == null) {
            url = HttpUrl.parse(DEFAULT_EVENTS_URL);
        }

        HttpUrl.Builder builder = new HttpUrl.Builder()
                .scheme(url.scheme())
                .host(url.host());

        if (url.port() != HttpUrl.defaultPort(url.scheme())) {
            builder.port(url.port());
        }

        return builder.build().toString();
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

    private static String normalizeEventsUrl(String url) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) {
            return DEFAULT_EVENTS_URL;
        }

        HttpUrl parsed = HttpUrl.parse(value);
        if (parsed == null) {
            return value;
        }

        if ("/".equals(parsed.encodedPath()) || parsed.encodedPath().isEmpty()) {
            parsed = parsed.newBuilder().encodedPath("/events").build();
        }
        return parsed.toString();
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

package com.gargon.smarthome.heatfloor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HeatfloorBridgeClient {

    public static final int CHANNEL_COUNT = 2;
    public static final int PROGRAM_COUNT = 10;
    public static final int PROGRAM_MAX_POINTS = 9;
    public static final int MIN_TEMPERATURE = 10;
    public static final int MAX_TEMPERATURE = 45;

    private static final String BASE_URL = "http://192.168.1.120";
    private static final int DEVICE_RELAY_1 = 0x14;
    private static final int COMMAND_HEATFLOOR = 0x60;
    private static final int COMMAND_HEATFLOOR_INFO = 0x61;

    private static final int MODE_OFF = 0;
    private static final int MODE_MANUAL = 1;
    private static final int MODE_DAY = 2;
    private static final int MODE_WEEK = 3;
    private static final int MODE_PARTY = 4;
    private static final int MODE_DAY_FOR_TODAY = 5;

    private final OkHttpClient httpClient;

    public HeatfloorBridgeClient() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    public HeatfloorSnapshot loadSnapshot() throws Exception {
        HeatfloorSnapshot snapshot = createEmptySnapshot();
        snapshot = applyPacket(snapshot, parseRequiredPayload(requestHeatfloor("FF", 1200)));
        snapshot = applyPacket(snapshot, parseRequiredPayload(requestHeatfloor("FE", 1200)));

        for (int i = 0; i < PROGRAM_COUNT; i++) {
            snapshot = applyPacket(snapshot, parseRequiredPayload(requestHeatfloor(toHex(0xF0 + i), 1200)));
        }

        return snapshot;
    }

    public void setSystemEnabled(boolean enabled) throws Exception {
        sendHeatfloorCommand(toHex(enabled ? 1 : 0));
    }

    public void setChannelOff(int channelId) throws Exception {
        sendHeatfloorCommand(buildModePayload(channelId, MODE_OFF));
    }

    public void setChannelManual(int channelId, int temperature) throws Exception {
        if (temperature < MIN_TEMPERATURE || temperature > MAX_TEMPERATURE) {
            throw new IllegalArgumentException("Температура вне диапазона");
        }
        sendHeatfloorCommand(buildModePayload(channelId, MODE_MANUAL, temperature));
    }

    public void setChannelWeek(int channelId, int weekdaysProgram, int saturdayProgram, int sundayProgram) throws Exception {
        validateProgramId(weekdaysProgram);
        validateProgramId(saturdayProgram);
        validateProgramId(sundayProgram);
        sendHeatfloorCommand(buildModePayload(channelId, MODE_WEEK, weekdaysProgram, saturdayProgram, sundayProgram));
    }

    public void saveProgram(int programId, List<HeatfloorSnapshot.SchedulePoint> points) throws Exception {
        validateProgramId(programId);
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("Добавьте хотя бы одну точку графика");
        }
        if (points.size() > PROGRAM_MAX_POINTS) {
            throw new IllegalArgumentException("У программы может быть максимум 9 точек");
        }

        List<HeatfloorSnapshot.SchedulePoint> sorted = new ArrayList<HeatfloorSnapshot.SchedulePoint>(points);
        Collections.sort(sorted, new Comparator<HeatfloorSnapshot.SchedulePoint>() {
            @Override
            public int compare(HeatfloorSnapshot.SchedulePoint left, HeatfloorSnapshot.SchedulePoint right) {
                return left.hour - right.hour;
            }
        });

        int previousHour = -1;
        StringBuilder builder = new StringBuilder(toHex(0xF0 + programId));
        for (HeatfloorSnapshot.SchedulePoint point : sorted) {
            if (point.hour < 0 || point.hour > 23) {
                throw new IllegalArgumentException("Час должен быть в диапазоне 0-23");
            }
            if (point.temperature < 0 || point.temperature > MAX_TEMPERATURE) {
                throw new IllegalArgumentException("Температура программы должна быть в диапазоне 0-45");
            }
            if (point.hour <= previousHour) {
                throw new IllegalArgumentException("Точки графика должны идти по возрастанию часов");
            }
            previousHour = point.hour;
            builder.append(toHex(point.hour));
            builder.append(toHex(point.temperature));
        }

        sendHeatfloorCommand(builder.toString());
    }

    private JSONObject requestHeatfloor(String hexData, int timeoutMs) throws Exception {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL + "/request").newBuilder()
                .addQueryParameter("c", String.valueOf(COMMAND_HEATFLOOR))
                .addQueryParameter("a", String.valueOf(DEVICE_RELAY_1))
                .addQueryParameter("r", String.valueOf(COMMAND_HEATFLOOR_INFO))
                .addQueryParameter("t", String.valueOf(timeoutMs));
        if (hexData != null && hexData.length() > 0) {
            urlBuilder.addQueryParameter("d", hexData);
        }

        Request request = new Request.Builder().url(urlBuilder.build()).get().build();
        String body = executeTextRequest(request);
        return new JSONObject(body);
    }

    private void sendHeatfloorCommand(String hexData) throws Exception {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL + "/command").newBuilder()
                .addQueryParameter("c", String.valueOf(COMMAND_HEATFLOOR))
                .addQueryParameter("a", String.valueOf(DEVICE_RELAY_1));
        if (hexData != null && hexData.length() > 0) {
            urlBuilder.addQueryParameter("d", hexData);
        }

        Request request = new Request.Builder().url(urlBuilder.build()).get().build();
        executeTextRequest(request);
    }

    private String executeTextRequest(Request request) throws Exception {
        Response response = httpClient.newCall(request).execute();
        try {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            if (response.body() == null) {
                throw new IOException("Пустой ответ bridge");
            }
            return response.body().string();
        } finally {
            response.close();
        }
    }

    private byte[] parseRequiredPayload(JSONObject root) throws Exception {
        JSONArray responses = root.optJSONArray("responses");
        if (responses == null || responses.length() == 0) {
            throw new IOException("Bridge не вернул данные теплого пола");
        }

        for (int i = 0; i < responses.length(); i++) {
            JSONObject message = responses.optJSONObject(i);
            if (message == null) {
                continue;
            }
            JSONObject payload = message.optJSONObject("m");
            if (payload == null) {
                continue;
            }
            String hex = payload.optString("hex", null);
            if (hex != null && hex.length() > 0) {
                return fromHex(hex);
            }
        }

        throw new IOException("Bridge вернул ответ без payload");
    }

    public static HeatfloorSnapshot createEmptySnapshot() {
        List<HeatfloorSnapshot.ChannelState> channels = new ArrayList<HeatfloorSnapshot.ChannelState>();
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            channels.add(new HeatfloorSnapshot.ChannelState(i, HeatfloorCatalog.getChannelName(i)));
        }

        List<HeatfloorSnapshot.Program> programs = new ArrayList<HeatfloorSnapshot.Program>();
        for (int i = 0; i < PROGRAM_COUNT; i++) {
            programs.add(new HeatfloorSnapshot.Program(i, HeatfloorCatalog.getProgramLabel(i)));
        }

        return new HeatfloorSnapshot(false, channels, programs);
    }

    public static HeatfloorSnapshot applyPacket(HeatfloorSnapshot snapshot, byte[] data) throws Exception {
        if (snapshot == null) {
            snapshot = createEmptySnapshot();
        }
        if (data == null || data.length == 0) {
            throw new IOException("Пустой пакет теплого пола");
        }

        int type = data[0] & 0xFF;
        if (type <= CHANNEL_COUNT) {
            snapshot = applyState(snapshot, data);
        } else if (type == 0xFE) {
            applyModes(snapshot.channels, data);
        } else if (type >= 0xF0 && type < 0xF0 + PROGRAM_COUNT) {
            HeatfloorSnapshot.Program program = parseProgram(data);
            snapshot.programs.set(program.index, program);
        } else {
            throw new IOException("Неизвестный пакет теплого пола");
        }
        return snapshot;
    }

    private static HeatfloorSnapshot applyState(HeatfloorSnapshot snapshot, byte[] data) throws Exception {
        List<HeatfloorSnapshot.ChannelState> channels = snapshot.channels;
        if (data.length == 0) {
            throw new IOException("Пустое состояние теплого пола");
        }

        int count = data[0] & 0xFF;
        if (count == 0) {
            for (int i = 0; i < channels.size(); i++) {
                HeatfloorSnapshot.ChannelState channel = channels.get(i);
                channel.hasLiveState = false;
                channel.relayOn = false;
                channel.solution = -1;
                channel.currentTemperature = null;
                channel.targetTemperature = null;
            }
            return new HeatfloorSnapshot(false, channels, snapshot.programs);
        }
        if (data.length != count * 6 + 1) {
            throw new IOException("Некорректный пакет состояния теплого пола");
        }

        for (int i = 0; i < count && i < channels.size(); i++) {
            int offset = 1 + i * 6;
            HeatfloorSnapshot.ChannelState channel = channels.get(i);
            channel.hasLiveState = true;
            channel.modeId = data[offset] & 0xFF;
            channel.solution = data[offset + 1];
            channel.relayOn = channel.solution == 1;
            channel.currentTemperature = decodeTemperature10(data, offset + 2);
            channel.targetTemperature = decodeTemperature10(data, offset + 4);
        }
        return new HeatfloorSnapshot(true, channels, snapshot.programs);
    }

    private static void applyModes(List<HeatfloorSnapshot.ChannelState> channels, byte[] data) throws Exception {
        if (data.length != 1 + CHANNEL_COUNT * 4 || (data[0] & 0xFF) != 0xFE) {
            throw new IOException("Некорректный пакет режимов теплого пола");
        }

        for (int i = 0; i < channels.size(); i++) {
            int offset = 1 + i * 4;
            HeatfloorSnapshot.ChannelState channel = channels.get(i);
            channel.modeId = data[offset] & 0xFF;
            switch (channel.modeId) {
                case MODE_MANUAL:
                case MODE_PARTY:
                    channel.manualTemperature = data[offset + 1] & 0xFF;
                    break;
                case MODE_DAY:
                case MODE_DAY_FOR_TODAY:
                    channel.dayProgram = data[offset + 1] & 0xFF;
                    channel.weekWeekdaysProgram = channel.dayProgram;
                    channel.weekSaturdayProgram = channel.dayProgram;
                    channel.weekSundayProgram = channel.dayProgram;
                    break;
                case MODE_WEEK:
                    channel.weekWeekdaysProgram = data[offset + 1] & 0xFF;
                    channel.weekSaturdayProgram = data[offset + 2] & 0xFF;
                    channel.weekSundayProgram = data[offset + 3] & 0xFF;
                    break;
                default:
                    break;
            }
        }
    }

    private static HeatfloorSnapshot.Program parseProgram(byte[] data) throws Exception {
        if (data.length < 2) {
            throw new IOException("Пустая программа теплого пола");
        }

        int programId = data[0] & 0x0F;
        int count = data[1] & 0xFF;
        HeatfloorSnapshot.Program program = new HeatfloorSnapshot.Program(programId, HeatfloorCatalog.getProgramLabel(programId));

        if (count > 0 && count < PROGRAM_COUNT && data.length >= 2 + count * 2) {
            for (int i = 0; i < count; i++) {
                int offset = 2 + i * 2;
                program.points.add(new HeatfloorSnapshot.SchedulePoint(data[offset] & 0xFF, data[offset + 1] & 0xFF));
            }
        }

        return program;
    }

    private static Float decodeTemperature10(byte[] data, int offset) {
        int value = ((data[offset + 1] & 0xFF) << 8) | (data[offset] & 0xFF);
        if (value == 0xFFFF) {
            return null;
        }
        return value / 10f;
    }

    private static String buildModePayload(int channelId, int mode, int... params) {
        StringBuilder builder = new StringBuilder();
        builder.append("FE");
        builder.append(toHex(1 << channelId));
        builder.append(toHex(mode));
        if (params != null) {
            for (int param : params) {
                builder.append(toHex(param));
            }
        }
        return builder.toString();
    }

    private static void validateProgramId(int programId) {
        if (programId < 0 || programId >= PROGRAM_COUNT) {
            throw new IllegalArgumentException("Некорректный идентификатор программы");
        }
    }

    private static String toHex(int value) {
        return String.format(Locale.US, "%02X", value & 0xFF);
    }

    private static byte[] fromHex(String hex) {
        int length = hex.length();
        byte[] data = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            data[i / 2] = (byte) ((hi << 4) + lo);
        }
        return data;
    }
}

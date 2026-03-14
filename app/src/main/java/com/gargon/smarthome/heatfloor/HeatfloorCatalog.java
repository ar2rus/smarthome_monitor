package com.gargon.smarthome.heatfloor;

public final class HeatfloorCatalog {

    private static final String[] CHANNEL_NAMES = {
            "Ванная комната",
            "Кухня"
    };

    private static final String[] PROGRAM_TITLES = {
            "Комфорт 26*",
            "Комфорт 27*",
            "Комфорт 28*",
            "Комфорт 30*",
            "Утро-вечер 26*",
            "Утро-вечер 28*",
            "Утро-вечер 28*, день 26",
            "Утро-вечер 30*, день 28",
            "Утро 28*",
            "Утро 30*"
    };

    private HeatfloorCatalog() {
    }

    public static String getChannelName(int channelId) {
        if (channelId >= 0 && channelId < CHANNEL_NAMES.length) {
            return CHANNEL_NAMES[channelId];
        }
        return "Канал " + channelId;
    }

    public static String getProgramTitle(int programId) {
        if (programId >= 0 && programId < PROGRAM_TITLES.length) {
            return PROGRAM_TITLES[programId];
        }
        return "Программа " + programId;
    }

    public static String getProgramLabel(int programId) {
        return "П" + programId + "  " + getProgramTitle(programId);
    }
}

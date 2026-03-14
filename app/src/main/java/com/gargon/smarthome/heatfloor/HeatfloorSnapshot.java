package com.gargon.smarthome.heatfloor;

import java.util.ArrayList;
import java.util.List;

public class HeatfloorSnapshot {

    public final boolean systemEnabled;
    public final List<ChannelState> channels;
    public final List<Program> programs;
    public final long loadedAtMillis;

    public HeatfloorSnapshot(boolean systemEnabled, List<ChannelState> channels, List<Program> programs) {
        this.systemEnabled = systemEnabled;
        this.channels = channels;
        this.programs = programs;
        this.loadedAtMillis = System.currentTimeMillis();
    }

    public static class ChannelState {

        public final int channelId;
        public final String title;
        public boolean hasLiveState;
        public boolean relayOn;
        public int solution;
        public Float currentTemperature;
        public Float targetTemperature;
        public int modeId;
        public int manualTemperature = -1;
        public int dayProgram = -1;
        public int weekWeekdaysProgram = -1;
        public int weekSaturdayProgram = -1;
        public int weekSundayProgram = -1;

        public ChannelState(int channelId, String title) {
            this.channelId = channelId;
            this.title = title;
        }
    }

    public static class Program {

        public final int index;
        public final String title;
        public final List<SchedulePoint> points = new ArrayList<SchedulePoint>();

        public Program(int index, String title) {
            this.index = index;
            this.title = title;
        }
    }

    public static class SchedulePoint {

        public final int hour;
        public final int temperature;

        public SchedulePoint(int hour, int temperature) {
            this.hour = hour;
            this.temperature = temperature;
        }
    }
}

package com.gargon.smarthome.admin;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.gargon.smarthome.MainService;
import com.gargon.smarthome.R;
import com.gargon.smarthome.heatfloor.HeatfloorBridgeClient;
import com.gargon.smarthome.heatfloor.HeatfloorCatalog;
import com.gargon.smarthome.heatfloor.HeatfloorSnapshot;
import com.gargon.smarthome.model.SmarthomeMessage;
import com.gargon.smarthome.sse.SSESmarthomeClient;
import com.gargon.smarthome.sse.SSESmarthomeMessageListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeviceAdminRequestActivity extends AppCompatActivity {

    public static final int REQUEST_ENABLED = 1;
    private static final long REFRESH_INTERVAL_MS = 30000L;
    private static final int COMMAND_HEATFLOOR_INFO = 0x61;
    private static final int DEVICE_RELAY_1 = 0x14;

    private ComponentName adminComponent;
    private DevicePolicyManager devicePolicyManager;

    private TextView adminStatusView;
    private TextView bridgeStatusView;
    private TextView systemStatusView;
    private TextView lastUpdateView;
    private ProgressBar refreshProgress;
    private Button refreshButton;
    private Button systemOnButton;
    private Button systemOffButton;
    private LinearLayout channelsContainer;
    private LinearLayout programsContainer;

    private final Handler handler = new Handler();
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final LayoutInflaterHolder inflaterHolder = new LayoutInflaterHolder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final List<ChannelCard> channelCards = new ArrayList<ChannelCard>();
    private final List<ProgramCard> programCards = new ArrayList<ProgramCard>();

    private HeatfloorBridgeClient bridgeClient;
    private HeatfloorSnapshot currentSnapshot;
    private SSESmarthomeClient liveClient;
    private boolean activityStarted;
    private boolean adminRequestPending;
    private boolean refreshInProgress;

    private final Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
            if (hasEditableFocus()) {
                scheduleNextRefresh();
                return;
            }
            refreshDashboard(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_admin);

        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, DeviceAdminReceiver_.class);
        bridgeClient = new HeatfloorBridgeClient();

        bindViews();
        buildChannelCards();
        buildProgramCards();
        bindActions();
        updateAdminStatus();
        updateBridgeStatus("Ожидание первого опроса bridge", false);
        systemStatusView.setText("Система: неизвестно");
        lastUpdateView.setText("Обновление еще не выполнялось");
        currentSnapshot = HeatfloorBridgeClient.createEmptySnapshot();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        if (isAdminActive()) {
            startMainService();
        } else {
            requestAdminAccess();
        }
        startLiveUpdates();
        refreshDashboard(true);
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        handler.removeCallbacks(periodicRefresh);
        stopLiveUpdates();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLED) {
            adminRequestPending = false;
            updateAdminStatus();
            if (isAdminActive()) {
                startMainService();
            }
            refreshDashboard(true);
        }
    }

    private void bindViews() {
        adminStatusView = findViewById(R.id.adminStatus);
        bridgeStatusView = findViewById(R.id.bridgeStatus);
        systemStatusView = findViewById(R.id.systemStatus);
        lastUpdateView = findViewById(R.id.lastUpdate);
        refreshProgress = findViewById(R.id.refreshProgress);
        refreshButton = findViewById(R.id.refreshButton);
        systemOnButton = findViewById(R.id.systemOnButton);
        systemOffButton = findViewById(R.id.systemOffButton);
        channelsContainer = findViewById(R.id.channelsContainer);
        programsContainer = findViewById(R.id.programsContainer);
    }

    private void bindActions() {
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshDashboard(true);
            }
        });

        systemOnButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                executeBridgeAction("Включаю систему теплого пола...", "Система включена", new BridgeAction() {
                    @Override
                    public void run() throws Exception {
                        bridgeClient.setSystemEnabled(true);
                    }
                });
            }
        });

        systemOffButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                executeBridgeAction("Отключаю систему теплого пола...", "Система выключена", new BridgeAction() {
                    @Override
                    public void run() throws Exception {
                        bridgeClient.setSystemEnabled(false);
                    }
                });
            }
        });
    }

    private void buildChannelCards() {
        for (int channelId = 0; channelId < HeatfloorBridgeClient.CHANNEL_COUNT; channelId++) {
            final ChannelCard card = new ChannelCard();
            card.channelId = channelId;
            card.root = inflaterHolder.get().inflate(R.layout.item_heatfloor_channel, channelsContainer, false);
            card.titleView = card.root.findViewById(R.id.channelTitle);
            card.modeView = card.root.findViewById(R.id.channelMode);
            card.currentTempView = card.root.findViewById(R.id.channelCurrentTemp);
            card.targetTempView = card.root.findViewById(R.id.channelTargetTemp);
            card.relayStateView = card.root.findViewById(R.id.channelRelayState);
            card.offButton = card.root.findViewById(R.id.channelOffButton);
            card.manualTempSpinner = card.root.findViewById(R.id.channelManualTempSpinner);
            card.manualApplyButton = card.root.findViewById(R.id.channelManualApplyButton);
            card.weekdaysProgramSpinner = card.root.findViewById(R.id.channelWeekdaysProgramSpinner);
            card.saturdayProgramSpinner = card.root.findViewById(R.id.channelSaturdayProgramSpinner);
            card.sundayProgramSpinner = card.root.findViewById(R.id.channelSundayProgramSpinner);
            card.weekApplyButton = card.root.findViewById(R.id.channelWeekApplyButton);

            card.titleView.setText(HeatfloorCatalog.getChannelName(channelId));
            card.manualTempSpinner.setAdapter(buildManualTemperatureAdapter());
            card.weekdaysProgramSpinner.setAdapter(buildProgramsAdapter());
            card.saturdayProgramSpinner.setAdapter(buildProgramsAdapter());
            card.sundayProgramSpinner.setAdapter(buildProgramsAdapter());

            card.offButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    executeBridgeAction("Отключаю канал " + card.titleView.getText() + "...", "Канал отключен", new BridgeAction() {
                        @Override
                        public void run() throws Exception {
                            bridgeClient.setChannelOff(card.channelId);
                        }
                    });
                }
            });

            card.manualApplyButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final int temperature = HeatfloorBridgeClient.MIN_TEMPERATURE + card.manualTempSpinner.getSelectedItemPosition();
                    executeBridgeAction("Включаю ручной режим для " + card.titleView.getText() + "...", "Ручной режим применен", new BridgeAction() {
                        @Override
                        public void run() throws Exception {
                            bridgeClient.setChannelManual(card.channelId, temperature);
                        }
                    });
                }
            });

            card.weekApplyButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final int weekdaysProgram = card.weekdaysProgramSpinner.getSelectedItemPosition();
                    final int saturdayProgram = card.saturdayProgramSpinner.getSelectedItemPosition();
                    final int sundayProgram = card.sundayProgramSpinner.getSelectedItemPosition();

                    executeBridgeAction("Применяю недельный график для " + card.titleView.getText() + "...", "Недельный график применен", new BridgeAction() {
                        @Override
                        public void run() throws Exception {
                            bridgeClient.setChannelWeek(card.channelId, weekdaysProgram, saturdayProgram, sundayProgram);
                        }
                    });
                }
            });

            channelsContainer.addView(card.root);
            channelCards.add(card);
        }
    }

    private void buildProgramCards() {
        for (int programId = 0; programId < HeatfloorBridgeClient.PROGRAM_COUNT; programId++) {
            final ProgramCard card = new ProgramCard();
            card.programId = programId;
            card.root = inflaterHolder.get().inflate(R.layout.item_heatfloor_program, programsContainer, false);
            card.titleView = card.root.findViewById(R.id.programTitle);
            card.summaryView = card.root.findViewById(R.id.programSummary);
            card.rowsContainer = card.root.findViewById(R.id.programRowsContainer);
            card.addRowButton = card.root.findViewById(R.id.programAddRowButton);
            card.saveButton = card.root.findViewById(R.id.programSaveButton);

            card.titleView.setText(HeatfloorCatalog.getProgramLabel(programId));

            card.addRowButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    addProgramRow(card, null);
                }
            });

            card.saveButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final List<HeatfloorSnapshot.SchedulePoint> points;
                    try {
                        points = collectProgramPoints(card);
                    } catch (IllegalArgumentException e) {
                        showToast(describeError(e));
                        return;
                    }

                    executeBridgeAction("Сохраняю программу " + card.programId + "...", "Программа сохранена", new BridgeAction() {
                        @Override
                        public void run() throws Exception {
                            bridgeClient.saveProgram(card.programId, points);
                        }
                    });
                }
            });

            programsContainer.addView(card.root);
            programCards.add(card);
        }
    }

    private ArrayAdapter<String> buildManualTemperatureAdapter() {
        String[] values = new String[HeatfloorBridgeClient.MAX_TEMPERATURE - HeatfloorBridgeClient.MIN_TEMPERATURE + 1];
        for (int i = 0; i < values.length; i++) {
            values[i] = String.valueOf(HeatfloorBridgeClient.MIN_TEMPERATURE + i) + " °C";
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private ArrayAdapter<String> buildProgramsAdapter() {
        String[] values = new String[HeatfloorBridgeClient.PROGRAM_COUNT];
        for (int i = 0; i < values.length; i++) {
            values[i] = HeatfloorCatalog.getProgramLabel(i);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void addProgramRow(final ProgramCard card, HeatfloorSnapshot.SchedulePoint point) {
        View row = inflaterHolder.get().inflate(R.layout.item_heatfloor_program_row, card.rowsContainer, false);
        final ProgramRow rowHolder = new ProgramRow();
        rowHolder.root = row;
        rowHolder.hourInput = row.findViewById(R.id.programRowHourInput);
        rowHolder.tempInput = row.findViewById(R.id.programRowTempInput);
        rowHolder.removeButton = row.findViewById(R.id.programRowRemoveButton);
        row.setTag(rowHolder);

        if (point != null) {
            rowHolder.hourInput.setText(String.valueOf(point.hour));
            rowHolder.tempInput.setText(String.valueOf(point.temperature));
        }

        rowHolder.removeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                card.rowsContainer.removeView(rowHolder.root);
            }
        });

        card.rowsContainer.addView(row);
    }

    private List<HeatfloorSnapshot.SchedulePoint> collectProgramPoints(ProgramCard card) {
        List<HeatfloorSnapshot.SchedulePoint> points = new ArrayList<HeatfloorSnapshot.SchedulePoint>();
        for (int i = 0; i < card.rowsContainer.getChildCount(); i++) {
            View rowView = card.rowsContainer.getChildAt(i);
            ProgramRow row = (ProgramRow) rowView.getTag();
            String hourString = row.hourInput.getText().toString().trim();
            String tempString = row.tempInput.getText().toString().trim();

            if (TextUtils.isEmpty(hourString) && TextUtils.isEmpty(tempString)) {
                continue;
            }
            if (TextUtils.isEmpty(hourString) || TextUtils.isEmpty(tempString)) {
                throw new IllegalArgumentException("Заполните и час, и температуру в каждой строке");
            }

            int hour;
            int temperature;
            try {
                hour = Integer.parseInt(hourString);
                temperature = Integer.parseInt(tempString);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("В графике нужны только целые числа");
            }

            points.add(new HeatfloorSnapshot.SchedulePoint(hour, temperature));
        }

        if (points.isEmpty()) {
            throw new IllegalArgumentException("Добавьте хотя бы одну точку графика");
        }
        return points;
    }

    private void refreshDashboard(final boolean userInitiated) {
        if (refreshInProgress) {
            return;
        }

        refreshInProgress = true;
        setBusyState(true, userInitiated ? "Обновляю состояние теплого пола..." : "Фоновое обновление...");

        networkExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final HeatfloorSnapshot snapshot = bridgeClient.loadSnapshot();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            currentSnapshot = snapshot;
                            applySnapshot(snapshot);
                            setBusyState(false, "Bridge доступен");
                            refreshInProgress = false;
                            scheduleNextRefresh();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateBridgeStatus("Ошибка bridge: " + describeError(e), true);
                            setBusyState(false, null);
                            refreshInProgress = false;
                            scheduleNextRefresh();
                        }
                    });
                }
            }
        });
    }

    private void executeBridgeAction(String busyMessage, final String successMessage, final BridgeAction action) {
        setBusyState(true, busyMessage);
        networkExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    action.run();
                    final HeatfloorSnapshot snapshot = bridgeClient.loadSnapshot();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            currentSnapshot = snapshot;
                            applySnapshot(snapshot);
                            setBusyState(false, "Bridge доступен");
                            scheduleNextRefresh();
                            showToast(successMessage);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setBusyState(false, null);
                            updateBridgeStatus("Ошибка bridge: " + describeError(e), true);
                            scheduleNextRefresh();
                            showToast(describeError(e));
                        }
                    });
                }
            }
        });
    }

    private void applySnapshot(HeatfloorSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        updateAdminStatus();
        systemStatusView.setText(snapshot.systemEnabled ? "Система: включена" : "Система: отключена");
        lastUpdateView.setText("Обновлено в " + timeFormat.format(new Date(snapshot.loadedAtMillis)));

        for (int i = 0; i < channelCards.size() && i < snapshot.channels.size(); i++) {
            bindChannelState(channelCards.get(i), snapshot.channels.get(i));
        }

        for (int i = 0; i < programCards.size() && i < snapshot.programs.size(); i++) {
            bindProgram(programCards.get(i), snapshot.programs.get(i));
        }
    }

    private void bindChannelState(ChannelCard card, HeatfloorSnapshot.ChannelState channel) {
        card.titleView.setText(channel.title);
        card.modeView.setText("Режим: " + modeToText(channel.modeId, channel));
        card.currentTempView.setText(channel.currentTemperature == null ? "нет данных" : formatTemperature(channel.currentTemperature));
        card.targetTempView.setText(channel.targetTemperature == null ? "нет данных" : formatTemperature(channel.targetTemperature));
        card.relayStateView.setText(solutionToText(channel.solution, channel.relayOn, channel.hasLiveState));

        card.manualTempSpinner.setSelection(normalizeManualSelection(channel.manualTemperature));
        card.weekdaysProgramSpinner.setSelection(normalizeProgramSelection(channel.weekWeekdaysProgram));
        card.saturdayProgramSpinner.setSelection(normalizeProgramSelection(channel.weekSaturdayProgram));
        card.sundayProgramSpinner.setSelection(normalizeProgramSelection(channel.weekSundayProgram));
    }

    private void bindProgram(ProgramCard card, HeatfloorSnapshot.Program program) {
        card.summaryView.setText(buildProgramSummary(program));
        card.rowsContainer.removeAllViews();
        for (HeatfloorSnapshot.SchedulePoint point : program.points) {
            addProgramRow(card, point);
        }
        if (program.points.isEmpty()) {
            addProgramRow(card, null);
        }
    }

    private String buildProgramSummary(HeatfloorSnapshot.Program program) {
        if (program.points.isEmpty()) {
            return "Точек нет";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < program.points.size(); i++) {
            HeatfloorSnapshot.SchedulePoint point = program.points.get(i);
            if (i > 0) {
                builder.append("  ·  ");
            }
            builder.append(String.format(Locale.getDefault(), "%02d:00 -> %d°C", point.hour, point.temperature));
        }
        return builder.toString();
    }

    private String modeToText(int modeId, HeatfloorSnapshot.ChannelState channel) {
        switch (modeId) {
            case 0:
                return "выключен";
            case 1:
                return channel.manualTemperature > 0 ? "ручной " + channel.manualTemperature + "°C" : "ручной";
            case 2:
                return channel.dayProgram >= 0 ? "дневной, " + HeatfloorCatalog.getProgramLabel(channel.dayProgram) : "дневной";
            case 3:
                return "недельный: "
                        + HeatfloorCatalog.getProgramLabel(normalizeProgramSelection(channel.weekWeekdaysProgram))
                        + " / "
                        + HeatfloorCatalog.getProgramLabel(normalizeProgramSelection(channel.weekSaturdayProgram))
                        + " / "
                        + HeatfloorCatalog.getProgramLabel(normalizeProgramSelection(channel.weekSundayProgram));
            case 4:
                return channel.manualTemperature > 0 ? "вечеринка " + channel.manualTemperature + "°C" : "вечеринка";
            case 5:
                return channel.dayProgram >= 0 ? "дневной до конца дня, " + HeatfloorCatalog.getProgramLabel(channel.dayProgram) : "дневной до конца дня";
            default:
                return "неизвестно";
        }
    }

    private String solutionToText(int solution, boolean relayOn, boolean hasLiveState) {
        if (!hasLiveState) {
            return "Состояние реле: нет данных";
        }
        switch (solution) {
            case 1:
                return "Состояние реле: нагрев включен";
            case 0:
                return "Состояние реле: ожидание";
            case -1:
                return relayOn ? "Состояние реле: охлаждение" : "Состояние реле: отключено";
            case -2:
                return "Состояние реле: ошибка датчика";
            case -3:
                return "Состояние реле: ошибка диапазона датчика";
            case -4:
                return "Состояние реле: ошибка диспетчера";
            default:
                return "Состояние реле: неизвестно";
        }
    }

    private String formatTemperature(Float temperature) {
        return String.format(Locale.getDefault(), "%.1f°C", temperature);
    }

    private int normalizeManualSelection(int manualTemperature) {
        int safeTemperature = manualTemperature;
        if (safeTemperature < HeatfloorBridgeClient.MIN_TEMPERATURE || safeTemperature > HeatfloorBridgeClient.MAX_TEMPERATURE) {
            safeTemperature = 28;
        }
        return safeTemperature - HeatfloorBridgeClient.MIN_TEMPERATURE;
    }

    private int normalizeProgramSelection(int programId) {
        if (programId < 0 || programId >= HeatfloorBridgeClient.PROGRAM_COUNT) {
            return 0;
        }
        return programId;
    }

    private void requestAdminAccess() {
        if (adminRequestPending || isAdminActive()) {
            return;
        }
        adminRequestPending = true;
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        startActivityForResult(intent, REQUEST_ENABLED);
    }

    private boolean isAdminActive() {
        return devicePolicyManager.isAdminActive(adminComponent);
    }

    private void updateAdminStatus() {
        boolean adminActive = isAdminActive();
        adminStatusView.setText(adminActive ? "Device admin: активен" : "Device admin: не выдан");
        adminStatusView.setTextColor(ContextCompat.getColor(this, adminActive ? R.color.status_ok : R.color.status_warn));
    }

    private void updateBridgeStatus(String text, boolean error) {
        bridgeStatusView.setText(text);
        bridgeStatusView.setTextColor(ContextCompat.getColor(this, error ? R.color.status_error : R.color.status_ok));
    }

    private void setBusyState(boolean busy, String message) {
        refreshProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        refreshButton.setEnabled(!busy);
        systemOnButton.setEnabled(!busy);
        systemOffButton.setEnabled(!busy);
        if (message != null) {
            updateBridgeStatus(message, false);
        }
    }

    private void scheduleNextRefresh() {
        handler.removeCallbacks(periodicRefresh);
        if (activityStarted) {
            handler.postDelayed(periodicRefresh, REFRESH_INTERVAL_MS);
        }
    }

    private void startLiveUpdates() {
        if (liveClient != null) {
            return;
        }

        liveClient = new SSESmarthomeClient("http://192.168.1.120/events", 30);
        liveClient.addListener(new SSESmarthomeMessageListener() {
            @Override
            public void onMessage(final SmarthomeMessage message) {
                if (message == null
                        || message.getCommand() != COMMAND_HEATFLOOR_INFO
                        || message.getSource() != DEVICE_RELAY_1
                        || !message.hasPayload()) {
                    return;
                }

                final byte[] payload = message.getPayload().getBytes();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        applyLivePacket(payload);
                    }
                });
            }
        });
    }

    private void stopLiveUpdates() {
        if (liveClient != null) {
            liveClient.close();
            liveClient = null;
        }
    }

    private void applyLivePacket(byte[] payload) {
        try {
            currentSnapshot = HeatfloorBridgeClient.applyPacket(currentSnapshot, payload);
            applySnapshot(currentSnapshot);
            updateBridgeStatus("Bridge доступен, live-обновление активно", false);
        } catch (Exception ignored) {
        }
    }

    private boolean hasEditableFocus() {
        View focus = getCurrentFocus();
        return focus instanceof EditText;
    }

    private void startMainService() {
        Intent serviceLauncher = new Intent(getApplicationContext(), MainService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplicationContext().startForegroundService(serviceLauncher);
        } else {
            getApplicationContext().startService(serviceLauncher);
        }
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String describeError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "Не удалось выполнить команду";
        }
        return message;
    }

    private interface BridgeAction {
        void run() throws Exception;
    }

    private static class ChannelCard {
        int channelId;
        View root;
        TextView titleView;
        TextView modeView;
        TextView currentTempView;
        TextView targetTempView;
        TextView relayStateView;
        Button offButton;
        Spinner manualTempSpinner;
        Button manualApplyButton;
        Spinner weekdaysProgramSpinner;
        Spinner saturdayProgramSpinner;
        Spinner sundayProgramSpinner;
        Button weekApplyButton;
    }

    private static class ProgramCard {
        int programId;
        View root;
        TextView titleView;
        TextView summaryView;
        LinearLayout rowsContainer;
        Button addRowButton;
        Button saveButton;
    }

    private static class ProgramRow {
        View root;
        EditText hourInput;
        EditText tempInput;
        Button removeButton;
    }

    private class LayoutInflaterHolder {
        private LayoutInflater inflater;

        LayoutInflater get() {
            if (inflater == null) {
                inflater = LayoutInflater.from(DeviceAdminRequestActivity.this);
            }
            return inflater;
        }
    }
}

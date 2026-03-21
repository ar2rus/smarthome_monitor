package com.gargon.smarthome.admin;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SwitchCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.gargon.smarthome.MainService;
import com.gargon.smarthome.R;
import com.gargon.smarthome.config.BridgeSettings;
import com.gargon.smarthome.heatfloor.HeatfloorBridgeClient;
import com.gargon.smarthome.heatfloor.HeatfloorCatalog;
import com.gargon.smarthome.heatfloor.HeatfloorSnapshot;
import com.gargon.smarthome.model.SmarthomeMessage;
import com.gargon.smarthome.sse.SSESmarthomeClient;
import com.gargon.smarthome.sse.SSESmarthomeMessageListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChannelSettingsActivity extends AppCompatActivity {

    private static final long REFRESH_INTERVAL_MS = 30000L;
    private static final int COMMAND_HEATFLOOR_INFO = 0x61;
    private static final int DEVICE_RELAY_1 = 0x14;

    private final Handler handler = new Handler();
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private int channelId;

    private TextView backButton;
    private TextView channelTitleView;
    private SwitchCompat channelSwitch;
    private TextView channelModeView;
    private TextView channelTempsView;
    private TextView channelRelayStateView;
    private TextView bridgeStatusView;
    private ProgressBar statusProgress;
    private Spinner manualTempSpinner;
    private Spinner weekdaysProgramSpinner;
    private Spinner saturdayProgramSpinner;
    private Spinner sundayProgramSpinner;
    private TextView manualApplyButton;
    private TextView weekApplyButton;
    private ViewGroup programsContainer;

    private HeatfloorBridgeClient bridgeClient;
    private HeatfloorSnapshot currentSnapshot;
    private SSESmarthomeClient liveClient;

    private boolean activityStarted;
    private boolean refreshInProgress;
    private boolean bindingUi;

    private final Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
            refreshScreen(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_settings);

        channelId = getIntent().getIntExtra(DeviceAdminRequestActivity.EXTRA_CHANNEL_ID, -1);
        if (channelId < 0 || channelId >= HeatfloorBridgeClient.CHANNEL_COUNT) {
            showToast("Неизвестный канал");
            finish();
            return;
        }

        bridgeClient = new HeatfloorBridgeClient(getApplicationContext());
        currentSnapshot = HeatfloorBridgeClient.createEmptySnapshot();

        bindViews();
        bindStaticContent();
        bindActions();
        updateBridgeStatus("Ожидание подключения", false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        startLiveUpdates();
        refreshScreen(true);
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

    private void bindViews() {
        backButton = findViewById(R.id.backButton);
        channelTitleView = findViewById(R.id.channelTitle);
        channelSwitch = findViewById(R.id.channelSwitch);
        channelModeView = findViewById(R.id.channelMode);
        channelTempsView = findViewById(R.id.channelTemps);
        channelRelayStateView = findViewById(R.id.channelRelayState);
        bridgeStatusView = findViewById(R.id.bridgeStatus);
        statusProgress = findViewById(R.id.statusProgress);
        manualTempSpinner = findViewById(R.id.manualTempSpinner);
        weekdaysProgramSpinner = findViewById(R.id.weekdaysProgramSpinner);
        saturdayProgramSpinner = findViewById(R.id.saturdayProgramSpinner);
        sundayProgramSpinner = findViewById(R.id.sundayProgramSpinner);
        manualApplyButton = findViewById(R.id.manualApplyButton);
        weekApplyButton = findViewById(R.id.weekApplyButton);
        programsContainer = findViewById(R.id.programsContainer);
    }

    private void bindStaticContent() {
        channelTitleView.setText(HeatfloorCatalog.getChannelName(channelId));
        channelModeView.setText("Ожидание состояния");
        channelTempsView.setText("Текущая --.-°  •  Заданная --.-°");
        channelRelayStateView.setText("Нет live-данных");
        channelSwitch.setChecked(false);

        List<String> temperatureLabels = new ArrayList<String>();
        for (int temperature = HeatfloorBridgeClient.MIN_TEMPERATURE;
             temperature <= HeatfloorBridgeClient.MAX_TEMPERATURE;
             temperature++) {
            temperatureLabels.add(temperature + " °C");
        }
        ArrayAdapter<String> manualAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_item,
                temperatureLabels
        );
        manualAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        manualTempSpinner.setAdapter(manualAdapter);

        List<String> programLabels = new ArrayList<String>();
        for (int i = 0; i < HeatfloorBridgeClient.PROGRAM_COUNT; i++) {
            programLabels.add(HeatfloorCatalog.getProgramLabel(i));
        }
        ArrayAdapter<String> programAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_item,
                programLabels
        );
        programAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        weekdaysProgramSpinner.setAdapter(programAdapter);
        saturdayProgramSpinner.setAdapter(programAdapter);
        sundayProgramSpinner.setAdapter(programAdapter);

        BridgeSettings.ChannelPreset preset = BridgeSettings.getChannelPreset(this, channelId);
        int manualTemperature = preset.manualTemperature;
        if (manualTemperature < HeatfloorBridgeClient.MIN_TEMPERATURE
                || manualTemperature > HeatfloorBridgeClient.MAX_TEMPERATURE) {
            manualTemperature = 28;
        }
        manualTempSpinner.setSelection(manualTemperature - HeatfloorBridgeClient.MIN_TEMPERATURE);
        setProgramSelection(weekdaysProgramSpinner, preset.weekdaysProgram);
        setProgramSelection(saturdayProgramSpinner, preset.saturdayProgram);
        setProgramSelection(sundayProgramSpinner, preset.sundayProgram);
    }

    private void bindActions() {
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        bridgeStatusView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBridgeSettingsDialog();
            }
        });

        channelSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (bindingUi) {
                    return;
                }
                toggleChannel(isChecked);
            }
        });

        manualApplyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyManualMode();
            }
        });

        weekApplyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyWeekMode();
            }
        });
    }

    private void openBridgeSettingsDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(BridgeSettings.getEventsUrl(this));
        input.setSelection(input.getText().length());
        input.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        input.setBackgroundResource(R.drawable.bg_input);

        new AlertDialog.Builder(this)
                .setTitle("URL событий bridge")
                .setMessage("Меняем полную ссылку на SSE /events. request и command будут идти на тот же host.")
                .setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String url = input.getText().toString().trim();
                        if (!BridgeSettings.isValidEventsUrl(url)) {
                            showToast("Нужен корректный http/https URL");
                            return;
                        }

                        BridgeSettings.setEventsUrl(getApplicationContext(), url);
                        bridgeClient = new HeatfloorBridgeClient(getApplicationContext());
                        stopLiveUpdates();
                        startLiveUpdates();
                        startMainService();
                        refreshScreen(true);
                    }
                })
                .show();
    }

    private void startMainService() {
        Intent serviceLauncher = new Intent(getApplicationContext(), MainService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplicationContext().startForegroundService(serviceLauncher);
        } else {
            getApplicationContext().startService(serviceLauncher);
        }
    }

    private void refreshScreen(final boolean userInitiated) {
        if (refreshInProgress) {
            return;
        }

        refreshInProgress = true;
        setBusyState(true, userInitiated ? "Синхронизация..." : "Фоновое обновление...");

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
                            setBusyState(false, "Подключено");
                            refreshInProgress = false;
                            scheduleNextRefresh();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateBridgeStatus("Ошибка: " + describeError(e), true);
                            setBusyState(false, null);
                            refreshInProgress = false;
                            scheduleNextRefresh();
                        }
                    });
                }
            }
        });
    }

    private void startLiveUpdates() {
        if (liveClient != null) {
            return;
        }

        liveClient = new SSESmarthomeClient(BridgeSettings.getEventsUrl(this), 30);
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
            updateBridgeStatus("Live", false);
        } catch (Exception ignored) {
        }
    }

    private void applySnapshot(HeatfloorSnapshot snapshot) {
        if (snapshot == null || snapshot.channels == null || channelId >= snapshot.channels.size()) {
            return;
        }

        bindingUi = true;
        try {
            for (int i = 0; i < snapshot.channels.size(); i++) {
                BridgeSettings.rememberFromState(this, snapshot.channels.get(i));
            }

            HeatfloorSnapshot.ChannelState channel = snapshot.channels.get(channelId);
            channelTitleView.setText(channel.title);
            channelModeView.setText(modeText(channel));
            channelTempsView.setText(formatTemperatures(channel));
            channelRelayStateView.setText(relayText(channel));
            channelSwitch.setChecked(snapshot.systemEnabled && channel.modeId != 0);
            applySpinnerSelections(channel);

            if (shouldReloadPrograms()) {
                bindPrograms(snapshot.programs);
            }
        } finally {
            bindingUi = false;
        }
    }

    private void applySpinnerSelections(HeatfloorSnapshot.ChannelState channel) {
        BridgeSettings.ChannelPreset preset = BridgeSettings.getChannelPreset(this, channelId);

        int manualTemperature = channel.manualTemperature;
        if (manualTemperature < HeatfloorBridgeClient.MIN_TEMPERATURE
                || manualTemperature > HeatfloorBridgeClient.MAX_TEMPERATURE) {
            manualTemperature = preset.manualTemperature;
        }
        if (manualTemperature < HeatfloorBridgeClient.MIN_TEMPERATURE
                || manualTemperature > HeatfloorBridgeClient.MAX_TEMPERATURE) {
            manualTemperature = 28;
        }
        manualTempSpinner.setSelection(manualTemperature - HeatfloorBridgeClient.MIN_TEMPERATURE);

        int weekdaysProgram = preset.weekdaysProgram;
        int saturdayProgram = preset.saturdayProgram;
        int sundayProgram = preset.sundayProgram;

        if (channel.modeId == 2 || channel.modeId == 5) {
            weekdaysProgram = channel.dayProgram;
            saturdayProgram = channel.dayProgram;
            sundayProgram = channel.dayProgram;
        } else if (channel.modeId == 3) {
            weekdaysProgram = channel.weekWeekdaysProgram;
            saturdayProgram = channel.weekSaturdayProgram;
            sundayProgram = channel.weekSundayProgram;
        }

        setProgramSelection(weekdaysProgramSpinner, weekdaysProgram);
        setProgramSelection(saturdayProgramSpinner, saturdayProgram);
        setProgramSelection(sundayProgramSpinner, sundayProgram);
    }

    private void setProgramSelection(Spinner spinner, int programId) {
        if (programId < 0 || programId >= HeatfloorBridgeClient.PROGRAM_COUNT) {
            programId = 0;
        }
        spinner.setSelection(programId);
    }

    private boolean shouldReloadPrograms() {
        if (programsContainer.getChildCount() == 0) {
            return true;
        }
        View focusedView = getCurrentFocus();
        return focusedView == null || !isChildOf(focusedView, programsContainer);
    }

    private boolean isChildOf(View child, ViewGroup parent) {
        ViewParent current = child.getParent();
        while (current instanceof View) {
            if (current == parent) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private void bindPrograms(List<HeatfloorSnapshot.Program> programs) {
        programsContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < programs.size(); i++) {
            final HeatfloorSnapshot.Program program = programs.get(i);
            View card = inflater.inflate(R.layout.item_heatfloor_program, programsContainer, false);

            TextView titleView = card.findViewById(R.id.programTitle);
            final TextView summaryView = card.findViewById(R.id.programSummary);
            final ViewGroup rowsContainer = card.findViewById(R.id.programRowsContainer);
            Button addRowButton = card.findViewById(R.id.programAddRowButton);
            Button saveButton = card.findViewById(R.id.programSaveButton);

            titleView.setText(program.title);

            if (program.points.isEmpty()) {
                addProgramRow(rowsContainer, null);
            } else {
                for (int j = 0; j < program.points.size(); j++) {
                    addProgramRow(rowsContainer, program.points.get(j));
                }
            }
            updateProgramSummary(summaryView, rowsContainer);

            addRowButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (rowsContainer.getChildCount() >= HeatfloorBridgeClient.PROGRAM_MAX_POINTS) {
                        showToast("В программе максимум 9 точек");
                        return;
                    }
                    View row = addProgramRow(rowsContainer, null);
                    updateProgramSummary(summaryView, rowsContainer);
                    EditText hourInput = row.findViewById(R.id.programRowHourInput);
                    hourInput.requestFocus();
                }
            });

            saveButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveProgram(program.index, rowsContainer);
                }
            });

            programsContainer.addView(card);
        }
    }

    private View addProgramRow(final ViewGroup rowsContainer, HeatfloorSnapshot.SchedulePoint point) {
        LayoutInflater inflater = LayoutInflater.from(this);
        final View row = inflater.inflate(R.layout.item_heatfloor_program_row, rowsContainer, false);
        final EditText hourInput = row.findViewById(R.id.programRowHourInput);
        final EditText tempInput = row.findViewById(R.id.programRowTempInput);
        Button removeButton = row.findViewById(R.id.programRowRemoveButton);

        if (point != null) {
            hourInput.setText(String.valueOf(point.hour));
            tempInput.setText(String.valueOf(point.temperature));
        }

        removeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rowsContainer.removeView(row);
                View root = (View) rowsContainer.getParent();
                TextView summaryView = root.findViewById(R.id.programSummary);
                updateProgramSummary(summaryView, rowsContainer);
            }
        });

        rowsContainer.addView(row);
        return row;
    }

    private void saveProgram(final int programId, final ViewGroup rowsContainer) {
        final List<HeatfloorSnapshot.SchedulePoint> points;
        try {
            points = readProgramPoints(rowsContainer);
        } catch (IllegalArgumentException e) {
            showToast(describeError(e));
            return;
        }

        executeBridgeAction("Сохраняю " + HeatfloorCatalog.getProgramLabel(programId) + "...",
                "График сохранен",
                new BridgeAction() {
                    @Override
                    public void run() throws Exception {
                        bridgeClient.saveProgram(programId, points);
                    }
                });
    }

    private List<HeatfloorSnapshot.SchedulePoint> readProgramPoints(ViewGroup rowsContainer) {
        if (rowsContainer.getChildCount() == 0) {
            throw new IllegalArgumentException("Добавьте хотя бы одну точку");
        }

        List<HeatfloorSnapshot.SchedulePoint> points = new ArrayList<HeatfloorSnapshot.SchedulePoint>();
        for (int i = 0; i < rowsContainer.getChildCount(); i++) {
            View row = rowsContainer.getChildAt(i);
            EditText hourInput = row.findViewById(R.id.programRowHourInput);
            EditText tempInput = row.findViewById(R.id.programRowTempInput);

            String hourText = hourInput.getText().toString().trim();
            String tempText = tempInput.getText().toString().trim();
            if (hourText.length() == 0 || tempText.length() == 0) {
                throw new IllegalArgumentException("Заполните час и температуру в строке " + (i + 1));
            }

            int hour;
            int temperature;
            try {
                hour = Integer.parseInt(hourText);
                temperature = Integer.parseInt(tempText);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Час и температура должны быть числами");
            }

            if (hour < 0 || hour > 23) {
                throw new IllegalArgumentException("Час должен быть в диапазоне 0-23");
            }
            if (temperature < 0 || temperature > HeatfloorBridgeClient.MAX_TEMPERATURE) {
                throw new IllegalArgumentException("Температура программы должна быть в диапазоне 0-45");
            }

            points.add(new HeatfloorSnapshot.SchedulePoint(hour, temperature));
        }

        Collections.sort(points, new Comparator<HeatfloorSnapshot.SchedulePoint>() {
            @Override
            public int compare(HeatfloorSnapshot.SchedulePoint left, HeatfloorSnapshot.SchedulePoint right) {
                return left.hour - right.hour;
            }
        });

        int previousHour = -1;
        for (int i = 0; i < points.size(); i++) {
            HeatfloorSnapshot.SchedulePoint point = points.get(i);
            if (point.hour == previousHour) {
                throw new IllegalArgumentException("Точки графика должны иметь разные часы");
            }
            previousHour = point.hour;
        }

        return points;
    }

    private void updateProgramSummary(TextView summaryView, ViewGroup rowsContainer) {
        List<HeatfloorSnapshot.SchedulePoint> points = new ArrayList<HeatfloorSnapshot.SchedulePoint>();
        for (int i = 0; i < rowsContainer.getChildCount(); i++) {
            View row = rowsContainer.getChildAt(i);
            EditText hourInput = row.findViewById(R.id.programRowHourInput);
            EditText tempInput = row.findViewById(R.id.programRowTempInput);
            String hourText = hourInput.getText().toString().trim();
            String tempText = tempInput.getText().toString().trim();
            if (hourText.length() == 0 || tempText.length() == 0) {
                continue;
            }
            try {
                points.add(new HeatfloorSnapshot.SchedulePoint(
                        Integer.parseInt(hourText),
                        Integer.parseInt(tempText)
                ));
            } catch (NumberFormatException ignored) {
            }
        }
        summaryView.setText(formatProgramSummary(points));
    }

    private String formatProgramSummary(List<HeatfloorSnapshot.SchedulePoint> points) {
        if (points == null || points.isEmpty()) {
            return "Нет точек графика";
        }

        List<HeatfloorSnapshot.SchedulePoint> sorted = new ArrayList<HeatfloorSnapshot.SchedulePoint>(points);
        Collections.sort(sorted, new Comparator<HeatfloorSnapshot.SchedulePoint>() {
            @Override
            public int compare(HeatfloorSnapshot.SchedulePoint left, HeatfloorSnapshot.SchedulePoint right) {
                return left.hour - right.hour;
            }
        });

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            HeatfloorSnapshot.SchedulePoint point = sorted.get(i);
            if (i > 0) {
                builder.append("  •  ");
            }
            builder.append(String.format(Locale.getDefault(), "%02d:00 %d°", point.hour, point.temperature));
        }
        return builder.toString();
    }

    private void toggleChannel(final boolean enabled) {
        executeBridgeAction(enabled ? "Включаю канал..." : "Отключаю канал...",
                enabled ? "Канал включен" : "Канал отключен",
                new BridgeAction() {
                    @Override
                    public void run() throws Exception {
                        if (enabled) {
                            ensureSystemEnabled();
                            restoreChannelPreset();
                        } else {
                            bridgeClient.setChannelOff(channelId);
                        }
                    }
                });
    }

    private void applyManualMode() {
        final int temperature = manualTempSpinner.getSelectedItemPosition() + HeatfloorBridgeClient.MIN_TEMPERATURE;
        executeBridgeAction("Включаю ручной режим...",
                "Ручной режим применен",
                new BridgeAction() {
                    @Override
                    public void run() throws Exception {
                        ensureSystemEnabled();
                        bridgeClient.setChannelManual(channelId, temperature);
                        BridgeSettings.rememberManualPreset(getApplicationContext(), channelId, temperature);
                    }
                });
    }

    private void applyWeekMode() {
        final int weekdaysProgram = weekdaysProgramSpinner.getSelectedItemPosition();
        final int saturdayProgram = saturdayProgramSpinner.getSelectedItemPosition();
        final int sundayProgram = sundayProgramSpinner.getSelectedItemPosition();

        executeBridgeAction("Применяю недельный график...",
                "Недельный график применен",
                new BridgeAction() {
                    @Override
                    public void run() throws Exception {
                        ensureSystemEnabled();
                        bridgeClient.setChannelWeek(channelId, weekdaysProgram, saturdayProgram, sundayProgram);
                        BridgeSettings.rememberWeekPreset(
                                getApplicationContext(),
                                channelId,
                                weekdaysProgram,
                                saturdayProgram,
                                sundayProgram
                        );
                    }
                });
    }

    private void ensureSystemEnabled() throws Exception {
        if (currentSnapshot != null && !currentSnapshot.systemEnabled) {
            bridgeClient.setSystemEnabled(true);
        }
    }

    private void restoreChannelPreset() throws Exception {
        BridgeSettings.ChannelPreset preset = BridgeSettings.getChannelPreset(this, channelId);
        if (preset.isWeek()) {
            bridgeClient.setChannelWeek(channelId, preset.weekdaysProgram, preset.saturdayProgram, preset.sundayProgram);
        } else {
            int temperature = preset.manualTemperature;
            if (temperature < HeatfloorBridgeClient.MIN_TEMPERATURE
                    || temperature > HeatfloorBridgeClient.MAX_TEMPERATURE) {
                temperature = 28;
            }
            bridgeClient.setChannelManual(channelId, temperature);
        }
    }

    private void executeBridgeAction(String busyText, final String successText, final BridgeAction action) {
        handler.removeCallbacks(periodicRefresh);
        refreshInProgress = true;
        setBusyState(true, busyText);
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
                            setBusyState(false, "Подключено");
                            refreshInProgress = false;
                            scheduleNextRefresh();
                            showToast(successText);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setBusyState(false, null);
                            updateBridgeStatus("Ошибка: " + describeError(e), true);
                            refreshInProgress = false;
                            scheduleNextRefresh();
                            showToast(describeError(e));
                        }
                    });
                }
            }
        });
    }

    private void setBusyState(boolean busy, String bridgeText) {
        statusProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        bridgeStatusView.setEnabled(!busy);
        channelSwitch.setEnabled(!busy);
        manualTempSpinner.setEnabled(!busy);
        weekdaysProgramSpinner.setEnabled(!busy);
        saturdayProgramSpinner.setEnabled(!busy);
        sundayProgramSpinner.setEnabled(!busy);
        manualApplyButton.setEnabled(!busy);
        weekApplyButton.setEnabled(!busy);

        for (int i = 0; i < programsContainer.getChildCount(); i++) {
            View card = programsContainer.getChildAt(i);
            card.setEnabled(!busy);
            Button addRowButton = card.findViewById(R.id.programAddRowButton);
            Button saveButton = card.findViewById(R.id.programSaveButton);
            addRowButton.setEnabled(!busy);
            saveButton.setEnabled(!busy);

            ViewGroup rowsContainer = card.findViewById(R.id.programRowsContainer);
            for (int rowIndex = 0; rowIndex < rowsContainer.getChildCount(); rowIndex++) {
                View row = rowsContainer.getChildAt(rowIndex);
                EditText hourInput = row.findViewById(R.id.programRowHourInput);
                EditText tempInput = row.findViewById(R.id.programRowTempInput);
                Button removeButton = row.findViewById(R.id.programRowRemoveButton);
                hourInput.setEnabled(!busy);
                tempInput.setEnabled(!busy);
                removeButton.setEnabled(!busy);
            }
        }

        if (bridgeText != null) {
            updateBridgeStatus(bridgeText, false);
        }
    }

    private void updateBridgeStatus(String state, boolean error) {
        String url = BridgeSettings.getEventsUrl(this);
        bridgeStatusView.setText(state + " • " + timeFormat.format(new Date()) + "\n" + url);
        bridgeStatusView.setTextColor(ContextCompat.getColor(this, error ? R.color.status_error : R.color.textPrimary));
    }

    private void scheduleNextRefresh() {
        handler.removeCallbacks(periodicRefresh);
        if (activityStarted) {
            handler.postDelayed(periodicRefresh, REFRESH_INTERVAL_MS);
        }
    }

    private String formatTemperatures(HeatfloorSnapshot.ChannelState channel) {
        String current = channel.currentTemperature == null
                ? "--.-°"
                : String.format(Locale.getDefault(), "%.1f°", channel.currentTemperature);
        String target = channel.targetTemperature == null
                ? "--.-°"
                : String.format(Locale.getDefault(), "%.1f°", channel.targetTemperature);
        return "Текущая " + current + "  •  Заданная " + target;
    }

    private String modeText(HeatfloorSnapshot.ChannelState channel) {
        switch (channel.modeId) {
            case 0:
                return "Канал выключен";
            case 1:
                return channel.manualTemperature > 0 ? "Ручной режим " + channel.manualTemperature + "°C" : "Ручной режим";
            case 2:
                return channel.dayProgram >= 0 ? "Дневной: " + HeatfloorCatalog.getProgramLabel(channel.dayProgram) : "Дневной";
            case 3:
                return "Недельный график";
            case 4:
                return channel.manualTemperature > 0 ? "Вечеринка " + channel.manualTemperature + "°C" : "Вечеринка";
            case 5:
                return channel.dayProgram >= 0 ? "До конца дня: " + HeatfloorCatalog.getProgramLabel(channel.dayProgram) : "До конца дня";
            default:
                return "Режим неизвестен";
        }
    }

    private String relayText(HeatfloorSnapshot.ChannelState channel) {
        if (!channel.hasLiveState) {
            return "Нет live-данных";
        }
        switch (channel.solution) {
            case 1:
                return "Нагрев";
            case 0:
                return "Ожидание";
            case -2:
                return "Ошибка датчика";
            case -3:
                return "Ошибка диапазона";
            case -4:
                return "Ошибка диспетчера";
            default:
                return "Выключено";
        }
    }

    private String describeError(Exception e) {
        return e.getMessage() == null || e.getMessage().trim().isEmpty()
                ? "Не удалось выполнить команду"
                : e.getMessage();
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private interface BridgeAction {
        void run() throws Exception;
    }
}

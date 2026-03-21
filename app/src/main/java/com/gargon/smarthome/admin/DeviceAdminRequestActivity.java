package com.gargon.smarthome.admin;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
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
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ProgressBar;
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
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeviceAdminRequestActivity extends AppCompatActivity {

    public static final int REQUEST_ENABLED = 1;
    public static final String EXTRA_CHANNEL_ID = "channel_id";

    private static final long REFRESH_INTERVAL_MS = 30000L;
    private static final int COMMAND_HEATFLOOR_INFO = 0x61;
    private static final int DEVICE_RELAY_1 = 0x14;

    private ComponentName adminComponent;
    private DevicePolicyManager devicePolicyManager;

    private TextView bridgeStatusView;
    private TextView adminStatusView;
    private ProgressBar statusProgress;
    private SwitchCompat systemSwitch;

    private final Handler handler = new Handler();
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final ChannelCard[] channelCards = new ChannelCard[HeatfloorBridgeClient.CHANNEL_COUNT];

    private HeatfloorBridgeClient bridgeClient;
    private HeatfloorSnapshot currentSnapshot;
    private SSESmarthomeClient liveClient;

    private boolean activityStarted;
    private boolean adminRequestPending;
    private boolean refreshInProgress;
    private boolean bindingUi;

    private final Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
            refreshDashboard(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_admin);

        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, DeviceAdminReceiver_.class);
        bridgeClient = new HeatfloorBridgeClient(getApplicationContext());
        currentSnapshot = HeatfloorBridgeClient.createEmptySnapshot();

        bindViews();
        buildChannelCards();
        bindActions();
        updateAdminStatus();
        updateBridgeStatus("Ожидание подключения", false);
        applySnapshot(currentSnapshot);
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        ensureAdminAndService();
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
            ensureAdminAndService();
            refreshDashboard(true);
        }
    }

    private void bindViews() {
        bridgeStatusView = findViewById(R.id.bridgeStatus);
        adminStatusView = findViewById(R.id.adminStatus);
        statusProgress = findViewById(R.id.statusProgress);
        systemSwitch = findViewById(R.id.systemSwitch);
    }

    private void buildChannelCards() {
        ViewGroup leftContainer = findViewById(R.id.leftChannelContainer);
        ViewGroup rightContainer = findViewById(R.id.rightChannelContainer);
        channelCards[0] = createChannelCard(leftContainer, 0);
        channelCards[1] = createChannelCard(rightContainer, 1);
    }

    private ChannelCard createChannelCard(ViewGroup container, final int channelId) {
        LayoutInflater inflater = LayoutInflater.from(this);
        View root = inflater.inflate(R.layout.item_dashboard_channel, container, false);

        final ChannelCard card = new ChannelCard();
        card.channelId = channelId;
        card.root = root;
        card.titleView = root.findViewById(R.id.channelTitle);
        card.modeView = root.findViewById(R.id.channelMode);
        card.currentTempView = root.findViewById(R.id.channelCurrentTemp);
        card.targetTempView = root.findViewById(R.id.channelTargetTemp);
        card.relayStateView = root.findViewById(R.id.channelRelayState);
        card.channelSwitch = root.findViewById(R.id.channelSwitch);
        card.settingsButton = root.findViewById(R.id.channelSettingsButton);

        card.titleView.setText(HeatfloorCatalog.getChannelName(channelId));
        card.channelSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (bindingUi) {
                    return;
                }
                toggleChannel(card, isChecked);
            }
        });
        card.settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openChannelSettings(channelId);
            }
        });

        container.addView(root);
        return card;
    }

    private void bindActions() {
        bridgeStatusView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBridgeSettingsDialog();
            }
        });

        systemSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (bindingUi) {
                    return;
                }
                toggleSystem(isChecked);
            }
        });
    }

    private void ensureAdminAndService() {
        updateAdminStatus();
        if (isAdminActive()) {
            startMainService();
        } else if (!adminRequestPending) {
            requestAdminAccess();
        }
    }

    private void requestAdminAccess() {
        adminRequestPending = true;
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        startActivityForResult(intent, REQUEST_ENABLED);
    }

    private boolean isAdminActive() {
        return devicePolicyManager.isAdminActive(adminComponent);
    }

    private void startMainService() {
        Intent serviceLauncher = new Intent(getApplicationContext(), MainService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplicationContext().startForegroundService(serviceLauncher);
        } else {
            getApplicationContext().startService(serviceLauncher);
        }
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
                .setMessage("Меняем полную ссылку на SSE `/events`. `request` и `command` будут идти на тот же host.")
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
                        refreshDashboard(true);
                    }
                })
                .show();
    }

    private void openChannelSettings(int channelId) {
        Intent intent = new Intent(this, ChannelSettingsActivity.class);
        intent.putExtra(EXTRA_CHANNEL_ID, channelId);
        startActivity(intent);
    }

    private void refreshDashboard(final boolean userInitiated) {
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
        bindingUi = true;
        try {
            updateAdminStatus();
            systemSwitch.setChecked(snapshot.systemEnabled);

            for (int i = 0; i < channelCards.length && i < snapshot.channels.size(); i++) {
                HeatfloorSnapshot.ChannelState channel = snapshot.channels.get(i);
                BridgeSettings.rememberFromState(this, channel);
                bindChannel(channelCards[i], snapshot.systemEnabled, channel);
            }
        } finally {
            bindingUi = false;
        }
    }

    private void bindChannel(ChannelCard card, boolean systemEnabled, HeatfloorSnapshot.ChannelState channel) {
        card.modeView.setText(modeText(channel));
        card.currentTempView.setText(channel.currentTemperature == null ? "--.-" : String.format(Locale.getDefault(), "%.1f°", channel.currentTemperature));
        card.targetTempView.setText(channel.targetTemperature == null ? "--.-" : String.format(Locale.getDefault(), "%.1f°", channel.targetTemperature));
        card.relayStateView.setText(relayText(channel));
        card.channelSwitch.setChecked(systemEnabled && channel.modeId != 0);
    }

    private void toggleSystem(final boolean enabled) {
        executeBridgeAction(enabled ? "Включаю систему..." : "Отключаю систему...",
                enabled ? "Система включена" : "Система отключена",
                new BridgeAction() {
                    @Override
                    public void run() throws Exception {
                        bridgeClient.setSystemEnabled(enabled);
                    }
                });
    }

    private void toggleChannel(final ChannelCard card, final boolean enabled) {
        executeBridgeAction(enabled ? "Включаю " + card.titleView.getText() + "..." : "Отключаю " + card.titleView.getText() + "...",
                enabled ? "Канал включен" : "Канал отключен",
                new BridgeAction() {
                    @Override
                    public void run() throws Exception {
                        if (enabled) {
                            if (currentSnapshot != null && !currentSnapshot.systemEnabled) {
                                bridgeClient.setSystemEnabled(true);
                            }
                            restoreChannelPreset(card.channelId);
                        } else {
                            bridgeClient.setChannelOff(card.channelId);
                        }
                    }
                });
    }

    private void restoreChannelPreset(int channelId) throws Exception {
        BridgeSettings.ChannelPreset preset = BridgeSettings.getChannelPreset(this, channelId);
        if (preset.isWeek()) {
            bridgeClient.setChannelWeek(channelId, preset.weekdaysProgram, preset.saturdayProgram, preset.sundayProgram);
        } else {
            int temperature = preset.manualTemperature;
            if (temperature < HeatfloorBridgeClient.MIN_TEMPERATURE || temperature > HeatfloorBridgeClient.MAX_TEMPERATURE) {
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
        systemSwitch.setEnabled(!busy);
        bridgeStatusView.setEnabled(!busy);
        for (ChannelCard card : channelCards) {
            if (card != null) {
                card.channelSwitch.setEnabled(!busy);
                card.settingsButton.setEnabled(!busy);
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

    private void updateAdminStatus() {
        boolean active = isAdminActive();
        if (active) {
            adminStatusView.setText("");
            adminStatusView.setVisibility(View.GONE);
        } else {
            adminStatusView.setText("Нужен device admin");
            adminStatusView.setTextColor(ContextCompat.getColor(this, R.color.status_warn));
            adminStatusView.setVisibility(View.VISIBLE);
        }
    }

    private void scheduleNextRefresh() {
        handler.removeCallbacks(periodicRefresh);
        if (activityStarted) {
            handler.postDelayed(periodicRefresh, REFRESH_INTERVAL_MS);
        }
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

    private static class ChannelCard {
        int channelId;
        View root;
        TextView titleView;
        TextView modeView;
        TextView currentTempView;
        TextView targetTempView;
        TextView relayStateView;
        SwitchCompat channelSwitch;
        TextView settingsButton;
    }
}

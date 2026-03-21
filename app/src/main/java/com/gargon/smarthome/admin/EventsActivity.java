package com.gargon.smarthome.admin;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.gargon.smarthome.MainService;
import com.gargon.smarthome.R;
import com.gargon.smarthome.config.BridgeSettings;
import com.gargon.smarthome.model.SmarthomeMessage;
import com.gargon.smarthome.sse.SSESmarthomeClient;
import com.gargon.smarthome.sse.SSESmarthomeMessageListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class EventsActivity extends AppCompatActivity {

    private static final int MAX_EVENT_ROWS = 80;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private TextView backButton;
    private TextView clearButton;
    private TextView bridgeStatusView;
    private TextView eventsSummaryView;
    private TextView emptyView;
    private ProgressBar statusProgress;
    private ViewGroup eventsContainer;

    private SSESmarthomeClient liveClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_events);

        bindViews();
        bindActions();
        updateBridgeStatus("Ожидание подключения", false);
        updateSummary();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startLiveUpdates();
    }

    @Override
    protected void onStop() {
        stopLiveUpdates();
        super.onStop();
    }

    private void bindViews() {
        backButton = findViewById(R.id.backButton);
        clearButton = findViewById(R.id.clearButton);
        bridgeStatusView = findViewById(R.id.bridgeStatus);
        eventsSummaryView = findViewById(R.id.eventsSummary);
        emptyView = findViewById(R.id.emptyView);
        statusProgress = findViewById(R.id.statusProgress);
        eventsContainer = findViewById(R.id.eventsContainer);
    }

    private void bindActions() {
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearEvents();
            }
        });
        bridgeStatusView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBridgeSettingsDialog();
            }
        });
    }

    private void openBridgeSettingsDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("192.168.1.120");
        input.setText(BridgeSettings.getBridgeHost(this));
        input.setSelection(input.getText().length());
        input.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        input.setBackgroundResource(R.drawable.bg_input);

        new AlertDialog.Builder(this)
                .setTitle("IP bridge")
                .setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String host = input.getText().toString().trim();
                        if (!BridgeSettings.isValidBridgeHost(host)) {
                            showTemporaryError("Нужен корректный IP или host");
                            return;
                        }

                        BridgeSettings.setBridgeHost(getApplicationContext(), host);
                        stopLiveUpdates();
                        clearEvents();
                        startMainService();
                        startLiveUpdates();
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

    private void startLiveUpdates() {
        if (liveClient != null) {
            return;
        }

        statusProgress.setVisibility(View.VISIBLE);
        updateBridgeStatus("Ожидание событий", false);
        liveClient = new SSESmarthomeClient(BridgeSettings.getEventsUrl(this), 30);
        liveClient.addListener(new SSESmarthomeMessageListener() {
            @Override
            public void onMessage(final SmarthomeMessage message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        appendEvent(message);
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
        statusProgress.setVisibility(View.GONE);
    }

    private void appendEvent(SmarthomeMessage message) {
        if (message == null) {
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        View row = inflater.inflate(R.layout.item_event_row, eventsContainer, false);

        TextView timeView = row.findViewById(R.id.eventTime);
        TextView sourceView = row.findViewById(R.id.eventSource);
        TextView destinationView = row.findViewById(R.id.eventDestination);
        TextView commandView = row.findViewById(R.id.eventCommand);
        TextView payloadView = row.findViewById(R.id.eventPayload);

        timeView.setText(timeFormat.format(resolveEventDate(message)));
        sourceView.setText(toHex(message.getSource()));
        destinationView.setText(toHex(message.getDestination()));
        commandView.setText(toHex(message.getCommand()));
        payloadView.setText(message.hasPayload() ? message.getPayload().getHex() : "-");

        eventsContainer.addView(row, 0);
        while (eventsContainer.getChildCount() > MAX_EVENT_ROWS) {
            eventsContainer.removeViewAt(eventsContainer.getChildCount() - 1);
        }

        emptyView.setVisibility(eventsContainer.getChildCount() == 0 ? View.VISIBLE : View.GONE);
        statusProgress.setVisibility(View.GONE);
        updateBridgeStatus("Live", false);
        updateSummary();
    }

    private Date resolveEventDate(SmarthomeMessage message) {
        long timestamp = message.getTimestamp();
        if (timestamp <= 0) {
            return new Date();
        }
        if (timestamp < 100000000000L) {
            timestamp *= 1000L;
        }
        return new Date(timestamp);
    }

    private void clearEvents() {
        eventsContainer.removeAllViews();
        emptyView.setVisibility(View.VISIBLE);
        updateSummary();
    }

    private void updateSummary() {
        int count = eventsContainer.getChildCount();
        if (count == 0) {
            eventsSummaryView.setText("Live-таблица ждет события");
        } else {
            eventsSummaryView.setText("Показано " + count + " последних событий");
        }
    }

    private void updateBridgeStatus(String state, boolean error) {
        String host = BridgeSettings.getBridgeHost(this);
        bridgeStatusView.setText(state + " • " + timeFormat.format(new Date()) + "\n" + host);
        bridgeStatusView.setTextColor(ContextCompat.getColor(this, error ? R.color.status_error : R.color.textPrimary));
    }

    private void showTemporaryError(String text) {
        updateBridgeStatus(text, true);
    }

    private String toHex(int value) {
        return String.format(Locale.US, "0x%02X", value & 0xFF);
    }
}

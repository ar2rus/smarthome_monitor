package com.gargon.smarthome;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.KeyEvent;

import com.gargon.smarthome.admin.DeviceAdminReceiver_;
import com.gargon.smarthome.admin.DeviceAdminRequestActivity;
import com.gargon.smarthome.events.Event;
import com.gargon.smarthome.events.EventListener;
import com.gargon.smarthome.model.SmarthomeMessage;
import com.gargon.smarthome.model.SmarthomeMessageFilter;
import com.gargon.smarthome.sse.SSESmarthomeClient;
import com.gargon.smarthome.sse.SSESmarthomeMessageListener;

public class MainService extends Service {

    private Object clientLock = new Object();

    private SSESmarthomeClient client;


    private final IBinder binder = new LocalBinder();

    private EventListener listener;

    private boolean audioPowerState = true;

    private int activeAudioChannelId = AUDIO_CHANNEL_PAD_ID;

    private String activeVideoEventId = null;


    public class LocalBinder extends Binder {

        void addEventListener(EventListener listener) {
            MainService.this.listener = listener;
        }
    }

    private void sendEvent(Event event, String... params) {
        if (listener != null) {
            listener.onEvent(event, params);
        }
    }

    private boolean getAdminDev() {
        final ComponentName adminComponent = new ComponentName(MainService.this, DeviceAdminReceiver_.class);
        final DevicePolicyManager devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);

        if (devicePolicyManager.isAdminActive(adminComponent)) {
            return true;
        } else {
            Intent activityIntent = new Intent(this, DeviceAdminRequestActivity.class);
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(activityIntent);
            return devicePolicyManager.isAdminActive(adminComponent);
        }
    }

    private void unlockDevice() {
        Log.d(MainService.class.getName(), "device unlocking");
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        final KeyguardManager.KeyguardLock kl = km.newKeyguardLock("myapp:MyKeyguardLock");
        kl.disableKeyguard();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "myapp:MyKeyguardLock");
        wakeLock.acquire();
        Log.d(MainService.class.getName(), "device unlocked");
    }

    private boolean lockDevice() {
        Log.d(MainService.class.getName(), "device locking");
        if (getAdminDev()) {
            final DevicePolicyManager devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            devicePolicyManager.lockNow();
            Log.d(MainService.class.getName(), "device locked");
            return true;
        }
        return false;
    }

    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm.isScreenOn();
    }

    private void toggleLockDevice() {
        if (isScreenOn()) {
            lockDevice();
        } else {
            unlockDevice();
        }
    }

    private void switchAudioManager(int keyCode) {
        Log.d(MainService.class.getName(), "switching audioManager: " + keyCode);
        if (audioPowerState && activeAudioChannelId == AUDIO_CHANNEL_PAD_ID) {
            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        }
    }

    private void showHallVideo(String videoId) {
        if (isHallVideoActive()) {
            return;
        }
        boolean force = videoId == null;
        Log.d(MainService.class.getName(), "show hall video: id= " + videoId + "; force=" + force);

        if (force) {
            unlockDevice();
        }
        if (isScreenOn()) {
            if (force || spyMode) {
                activeVideoEventId = videoId;
                Log.d(MainService.class.getName(), "open intent");

                Intent intent = new Intent(this, HallVideoActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        }
    }

    private void hideHallVideo(String videoId) {
        if (!isHallVideoActive()) {
            return;
        }
        boolean force = videoId == null;
        Log.d(MainService.class.getName(), "hide hall video: id=" + videoId + "; force=" + force);

        if (force || videoId.equals(activeVideoEventId)) {
            activeVideoEventId = null;
            sendEvent(Event.MOTION_END);
        }
    }

    private boolean isHallVideoActive() {
        return listener != null;
    }

    private void toggleHallVideo() {
        if (isHallVideoActive()) {
            hideHallVideo(null);
        } else {
            showHallVideo(null);
        }
    }

    public void onCreate() {
        Log.d(MainService.class.getName(), "service onCreate");
        super.onCreate();

        createNotificationChannel();
    }

    private static final String CHANNEL_ID = MainService.class.getName();

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "SmARThome Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            serviceChannel.setSound(null, null);
            serviceChannel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(serviceChannel);
        }
    }

    private final static int COMMAND_MOTION_INFO = 0x41;
    private final static int COMMAND_LIGHT_INFO = 0x46;
    private final static int COMMAND_RC_INFO = 0x75;
    private final static int COMMAND_CHANNEL_INFO = 0x11;
    private final static int COMMAND_POWER_INFO = 0x1F;

    private final static int DEVICE_BATH_SENSORS = 0x1E;
    private final static int DEVICE_AUDIOBATH = 0x0B;

    private final static int AUDIO_CHANNEL_PAD_ID = 0x01;

    private void createSSEClient() {
        synchronized (clientLock) {
            if (client == null) {
                client = new SSESmarthomeClient("http://192.168.1.120/events", 30);
                client.addListener(new SSESmarthomeMessageListener() {
                    @Override
                    public void onMessage(SmarthomeMessage message) {
                        Log.d(MainService.class.getName(), message.toString());

                        if (SmarthomeMessageFilter.builder().withCommand(COMMAND_LIGHT_INFO).withSource(DEVICE_BATH_SENSORS).withPayloadLength(2).build()
                                .check(message)) {
                            if (message.getPayload().getBytes()[0] == 1) {
                                unlockDevice();
                            } else {
                                lockDevice();
                            }
                        } else if (SmarthomeMessageFilter.builder().withCommand(COMMAND_RC_INFO).withSource(DEVICE_BATH_SENSORS).build()
                                .check(message)) {
                            if (SmarthomeMessageFilter.builder().withPayloadRegexp("000042").build()
                                    .check(message)) {
                                toggleLockDevice();
                            } else if (SmarthomeMessageFilter.builder().withPayloadRegexp("000098").build()
                                    .check(message)) {
                                switchAudioManager(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                            } else if (SmarthomeMessageFilter.builder().withPayloadRegexp("0000A8").build()
                                    .check(message)) {
                                switchAudioManager(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                            } else if (SmarthomeMessageFilter.builder().withPayloadRegexp("000002").build()
                                    .check(message)) {
                                switchAudioManager(KeyEvent.KEYCODE_MEDIA_NEXT);
                            } else if (SmarthomeMessageFilter.builder().withPayloadRegexp("000052").build()
                                    .check(message)) {
                                toggleHallVideo();
                            }
                        } else if (SmarthomeMessageFilter.builder().withCommand(COMMAND_MOTION_INFO).withPayloadRegexp("0100[0-9A-F]{4}").build()
                                .check(message)) {
                            showHallVideo(message.getPayload().getHex().substring(4));
                        } else if (SmarthomeMessageFilter.builder().withCommand(COMMAND_MOTION_INFO).withPayloadRegexp("0200[0-9A-F]{4}").build()
                                .check(message)) {
                            hideHallVideo(message.getPayload().getHex().substring(4));
                        } else if (SmarthomeMessageFilter.builder().withSource(DEVICE_AUDIOBATH).withCommand(COMMAND_CHANNEL_INFO).withPayloadLength(1).build()
                                .check(message)) {
                            int channelId = message.getPayload().getBytes()[0];
                            if (channelId != activeAudioChannelId) {
                                switchAudioManager(KeyEvent.KEYCODE_MEDIA_PAUSE);
                                activeAudioChannelId = channelId;
                            }
                        } else if (SmarthomeMessageFilter.builder().withSource(DEVICE_AUDIOBATH).withCommand(COMMAND_POWER_INFO).withPayloadLength(1).build()
                                .check(message)) {
                            boolean powerState = message.getPayload().getBytes()[0] == 1;
                            if (powerState != audioPowerState) {
                                switchAudioManager(KeyEvent.KEYCODE_MEDIA_PAUSE);
                                audioPowerState = powerState;
                            }
                        }
                    }
                });
            }
        }
    }

    private void closeSSEClient() {
        synchronized (clientLock) {
            if (client != null) {
                client.close();
                client = null;
            }
        }
    }

    private boolean spyMode = Boolean.TRUE;

    private Notification createNotification() {
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, HallVideoActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);

        String actionTitle = spyMode ? "не следить" : "следить";
        Intent actionIntent = new Intent(this, MainService.class);
        actionIntent.putExtra("spyMode", !spyMode);
        PendingIntent pendingActionIntent = PendingIntent.getService(this, 0,
                actionIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .addAction(0, actionTitle, pendingActionIntent)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(MainService.class.getName(), "service handle intent");

        spyMode = intent.getBooleanExtra("spyMode", spyMode);

        if (getAdminDev()) {
            createSSEClient();
            startForeground(1, createNotification());
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(MainService.class.getName(), "service onUnbind");
        hideHallVideo(null);
        listener = null;

        super.onUnbind(intent);
        return true;
    }

    @Override
    public void onDestroy() {
        Log.d(MainService.class.getName(), "service onDestroy");
        closeSSEClient();

        super.onDestroy();
    }

}

package com.gargon.smarthome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import com.gargon.smarthome.events.Event;
import com.gargon.smarthome.events.EventListener;
import com.github.niqdev.mjpeg.DisplayMode;
import com.github.niqdev.mjpeg.Mjpeg;
import com.github.niqdev.mjpeg.MjpegInputStream;
import com.github.niqdev.mjpeg.MjpegView;

import rx.functions.Action1;

public class HallVideoActivity extends AppCompatActivity {

    private MjpegView mjpegView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(HallVideoActivity.class.getName(), "onCreate activity");
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_hall_video);

        Window w = getWindow();
        w.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        w.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
    }

    private ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            final MainService.LocalBinder binder = (MainService.LocalBinder) service;
            binder.addEventListener(new EventListener() {
                @Override
                public void onEvent(Event event, String... params) {
                    Log.d(HallVideoActivity.class.getName(), "event received: " + event + "; params" + params);
                    switch (event) {
                        case MOTION_END:
                            finish();
                            break;
                    }
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
        }
    };


    @Override
    protected void onStart() {
        Log.d(HallVideoActivity.class.getName(), "onStart activity");
        super.onStart();
    }

    @Override
    protected void onResume() {
        Log.d(HallVideoActivity.class.getName(), "onResume activity");
        super.onResume();

        mjpegView = (MjpegView) findViewById(R.id.mjpegViewDefault);
        Log.d(HallVideoActivity.class.getName(), "mjpegView=" + mjpegView);
        startStream();

        Intent intent = new Intent(this, MainService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onPause() {
        Log.d(HallVideoActivity.class.getName(), "onPause activity");
        super.onPause();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    mjpegView.stopPlayback();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();


        unbindService(connection);
    }

    @Override
    protected void onStop() {
        Log.d(HallVideoActivity.class.getName(), "onStop activity");
        super.onStop();
    }

    private void startStream() {
        Log.d(HallVideoActivity.class.getName(), "Stream starting");

        Mjpeg.newInstance()
                .open("http://192.168.1.21:8081", 5)
                .subscribe(new Action1<MjpegInputStream>() {
                    @Override
                    public void call(MjpegInputStream mjpegInputStream) {
                        mjpegView.setSource(mjpegInputStream);
                        mjpegView.setDisplayMode(DisplayMode.BEST_FIT);
//                        mjpegView.showFps(true);

                        Log.d(HallVideoActivity.class.getName(), "streaming=" + mjpegView.isStreaming());

                    }
                }, new Action1<Throwable>() {
                    @Override
                    public void call(Throwable throwable) {
                        Log.e(HallVideoActivity.class.getName(), "stream error", throwable);
                    }
                });
    }

}
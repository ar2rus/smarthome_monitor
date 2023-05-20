package com.gargon.smarthome.sse;

import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gargon.smarthome.MainService;
import com.gargon.smarthome.model.SmarthomeMessage;
import com.here.oksse.OkSse;
import com.here.oksse.ServerSentEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;

public class SSESmarthomeClient {

    private final ServerSentEvent sse;

    private final List<SSESmarthomeMessageListener> listeners = new CopyOnWriteArrayList<>();

    private final ObjectMapper mapper = new ObjectMapper();

    public SSESmarthomeClient(String url, long timeout) {
        sse = new OkSse().newServerSentEvent(new Request.Builder().url(url).build(),
                new ServerSentEvent.Listener() {
                    @Override
                    public void onOpen(ServerSentEvent sse, Response response) {
                        Log.d(MainService.class.getName(), "SSE connection opened");
                    }

                    @Override
                    public void onMessage(ServerSentEvent sse, String id, String event, String message) {
                        if ("DATA".equals(event)) {
                            Log.d(MainService.class.getName(), id + ": " + message);

                            try {
                                SmarthomeMessage[] messages = mapper.readValue(message, SmarthomeMessage[].class);
                                for (SmarthomeMessage m : messages) {
                                    for (SSESmarthomeMessageListener listener : listeners) {
                                        listener.onMessage(m);
                                    }
                                }
                            } catch (Exception e) {
                                Log.w(MainService.class.getName(), "error message parsing: " + e.getMessage());
                                e.printStackTrace();
                            }
                        }
                    }

                    @Override
                    public void onComment(ServerSentEvent sse, String comment) {
                    }

                    @Override
                    public boolean onRetryTime(ServerSentEvent sse, long milliseconds) {
                        return true;
                    }

                    @Override
                    public boolean onRetryError(ServerSentEvent sse, Throwable throwable, Response response) {
                        return true;
                    }

                    @Override
                    public void onClosed(ServerSentEvent sse) {
                    }

                    @Override
                    public Request onPreRetry(ServerSentEvent sse, Request originalRequest) {
                        return originalRequest;
                    }
                });
        sse.setTimeout(timeout, TimeUnit.SECONDS);
    }

    public void addListener(SSESmarthomeMessageListener listener) {
        listeners.add(listener);
    }

    public void close() {
        sse.close();
    }
}

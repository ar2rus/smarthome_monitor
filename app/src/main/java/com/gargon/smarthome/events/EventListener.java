package com.gargon.smarthome.events;

public interface EventListener {

    void onEvent(Event event, String... params);

}

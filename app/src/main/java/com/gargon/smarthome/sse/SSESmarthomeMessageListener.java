package com.gargon.smarthome.sse;

import com.gargon.smarthome.model.SmarthomeMessage;

public interface SSESmarthomeMessageListener {

    void onMessage(SmarthomeMessage message);
}

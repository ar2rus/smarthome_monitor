package com.gargon.smarthome.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SmarthomeMessage {

    @JsonProperty("t")
    private long timestamp;

    @JsonProperty("c")
    private int command;

    @JsonProperty("s")
    private int source;

    @JsonProperty("d")
    private int destination;

    @JsonProperty("m")
    private SmarthomeMessagePayload payload;

    public long getTimestamp() {
        return timestamp;
    }

    public int getCommand() {
        return command;
    }

    public int getSource() {
        return source;
    }

    public int getDestination() {
        return destination;
    }

    public void setCommand(int command) {
        this.command = command;
    }

    public void setSource(int source) {
        this.source = source;
    }

    public void setDestination(int destination) {
        this.destination = destination;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public SmarthomeMessagePayload getPayload() {
        return payload;
    }

    public void setPayload(SmarthomeMessagePayload payload) {
        this.payload = payload;
    }

    public boolean hasPayload() {
        return this.payload != null && this.getPayload().getBytes() != null;
    }

    @Override
    public String toString() {
        return "SmarthomeMessage{" +
                "timestamp=" + timestamp +
                ", command=" + command +
                ", source=" + source +
                ", destination=" + destination +
                ", payload=" + payload +
                '}';
    }
}

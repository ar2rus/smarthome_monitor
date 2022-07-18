package com.gargon.smarthome.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import okio.ByteString;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SmarthomeMessagePayload {

    @JsonProperty("hex")
    private String hex;

    public String getHex() {
        return hex;
    }

    public void setHex(String hex) {
        this.hex = hex;
    }

    public byte[] getBytes() {
        if (hex != null) {
            return ByteString.decodeHex(hex).toByteArray();
        }
        return null;
    }

    @Override
    public String toString() {
        return "SmarthomeMessage{" +
                "hex='" + hex + '\'' +
                '}';
    }
}

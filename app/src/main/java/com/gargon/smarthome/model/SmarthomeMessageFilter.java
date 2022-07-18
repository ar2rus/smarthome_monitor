package com.gargon.smarthome.model;

import java.util.regex.Pattern;

public class SmarthomeMessageFilter {

    private final int command;

    private final int source;

    private final int destination;

    private final int payloadLength;

    private final Pattern payloadPattern;

    private SmarthomeMessageFilter(int command, int source, int destination,
                                   int payloadLength, String payloadRegexp) {
        this.command = command;
        this.source = source;
        this.destination = destination;
        this.payloadLength = payloadLength;
        if (payloadRegexp != null) {
            payloadPattern = Pattern.compile(payloadRegexp);
        } else {
            payloadPattern = null;
        }
    }

    public static SmarthomeMessageFilterBuilder builder() {
        return new SmarthomeMessageFilterBuilder();
    }

    public boolean check(SmarthomeMessage message) {
        if (message == null) {
            return false;
        }
        if (command >= 0) {
            if (message.getCommand() != command) {
                return false;
            }
        }
        if (source >= 0) {
            if (message.getSource() != source) {
                return false;
            }
        }
        if (destination >= 0) {
            if (message.getDestination() != destination) {
                return false;
            }
        }
        if (payloadLength >= 0) {
            if (!message.hasPayload() || message.getPayload().getBytes().length != payloadLength) {
                return false;
            }
        }
        if (payloadPattern != null) {
            if (!message.hasPayload() || !payloadPattern.matcher(message.getPayload().getHex()).matches()) {
                return false;
            }
        }
        return true;
    }

    public static class SmarthomeMessageFilterBuilder {

        private int command = -1;

        private int source = -1;

        private int destination = -1;

        private int payloadLength = -1;

        private String payloadRegexp = null;

        public SmarthomeMessageFilterBuilder withCommand(int command) {
            this.command = command;
            return this;
        }

        public SmarthomeMessageFilterBuilder withSource(int source) {
            this.source = source;
            return this;
        }

        public SmarthomeMessageFilterBuilder withDestination(int destination) {
            this.destination = destination;
            return this;
        }

        public SmarthomeMessageFilterBuilder withPayloadLength(int payloadLength) {
            this.payloadLength = payloadLength;
            return this;
        }

        public SmarthomeMessageFilterBuilder withPayloadRegexp(String payloadRegexp) {
            this.payloadRegexp = payloadRegexp;
            return this;
        }

        public SmarthomeMessageFilter build() {
            return new SmarthomeMessageFilter(command, source, destination, payloadLength, payloadRegexp);
        }
    }
}

package com.runninggu.server.auth.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** 문자열·숫자의 Boolean 강제 변환을 막고 JSON Boolean만 허용한다. (SPEC §4.2, 결정-58) */
public class StrictBooleanDeserializer extends JsonDeserializer<Boolean> {

    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_TRUE) {
            return true;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return false;
        }
        return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
    }
}

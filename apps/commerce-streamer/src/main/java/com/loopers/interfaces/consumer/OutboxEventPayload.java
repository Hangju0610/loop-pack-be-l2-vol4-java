package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OutboxEventPayload(
        String eventId,
        String eventType,
        String occurredAt,
        JsonNode data
) {
    public static OutboxEventPayload from(Object value, ObjectMapper objectMapper) throws IOException {
        if (value instanceof JsonNode node) {
            return node.isTextual()
                    ? objectMapper.readValue(node.asText(), OutboxEventPayload.class)
                    : objectMapper.treeToValue(node, OutboxEventPayload.class);
        }
        if (value instanceof byte[] bytes) {
            return fromString(new String(bytes, StandardCharsets.UTF_8), objectMapper);
        }
        return fromString(String.valueOf(value), objectMapper);
    }

    private static OutboxEventPayload fromString(String value, ObjectMapper objectMapper) throws IOException {
        JsonNode node = objectMapper.readTree(value);
        return node.isTextual()
                ? objectMapper.readValue(node.asText(), OutboxEventPayload.class)
                : objectMapper.treeToValue(node, OutboxEventPayload.class);
    }
}

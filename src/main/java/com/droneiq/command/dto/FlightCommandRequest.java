package com.droneiq.command.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record FlightCommandRequest(

        @NotBlank(message = "Drone ID is required")
        String droneId,

        @NotBlank(message = "Command type is required")
        @JsonAlias("command")
        String commandType,

        String payloadJson,

        Map<String, Object> parameters
) {

    @JsonIgnore
    public String getEffectivePayloadJson() {
        try {
            if (payloadJson != null && !payloadJson.isBlank()) {
                return payloadJson;
            }

            if (parameters != null) {
                return new ObjectMapper().writeValueAsString(parameters);
            }

            return "{}";

        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Unable to serialize command parameters", e);
        }
    }
}
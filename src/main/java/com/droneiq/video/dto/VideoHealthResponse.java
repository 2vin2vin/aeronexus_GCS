package com.droneiq.video.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VideoHealthResponse(
        boolean enabled,
        String mediaServer,
        String status,
        String mediaServerUrl,
        int activeStreamsCount
) {
}

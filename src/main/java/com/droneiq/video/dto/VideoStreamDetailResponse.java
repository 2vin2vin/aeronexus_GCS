package com.droneiq.video.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VideoStreamDetailResponse(
        String streamId,
        String droneId,
        String streamName,
        String protocol,
        String status,
        String webrtcUrl,
        String rtmpUrl
) {
}

package com.droneiq.video.service;

import com.droneiq.common.exception.ResourceNotFoundException;
import com.droneiq.video.config.VideoStreamingConfig;
import com.droneiq.video.dto.VideoHealthResponse;
import com.droneiq.video.dto.VideoStreamDetailResponse;
import com.droneiq.video.dto.VideoStreamResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoStreamServiceTest {

    private VideoStreamingConfig config;
    private VideoStreamService service;

    @BeforeEach
    void setUp() {
        config = new VideoStreamingConfig();
        config.setEnabled(true);
        config.setMediaServerUrl("http://localhost:8889");
        config.setRtmpBaseUrl("rtmp://localhost:1935");
        config.setDefaultStreamId("drone-001-camera");

        VideoStreamingConfig.StreamDefinition stream1 = new VideoStreamingConfig.StreamDefinition();
        stream1.setStreamId("drone-001-camera");
        stream1.setDroneId("DRONE-001");
        stream1.setStreamName("Drone 001 Camera");
        stream1.setProtocol("WEBRTC");
        stream1.setPath("drone-001-camera");
        stream1.setStatus("AVAILABLE");

        VideoStreamingConfig.StreamDefinition stream2 = new VideoStreamingConfig.StreamDefinition();
        stream2.setStreamId("drone-002-camera");
        stream2.setDroneId("DRONE-002");
        stream2.setStreamName("Drone 002 Camera");
        stream2.setProtocol("WEBRTC");
        stream2.setPath("drone-002-camera");
        stream2.setStatus("AVAILABLE");

        config.setStreams(List.of(stream1, stream2));

        service = new VideoStreamService(config);
        service.init();
    }

    @Test
    @DisplayName("Should return all configured video streams")
    void testGetAllStreams_ReturnsConfiguredStreams() {
        List<VideoStreamResponse> streams = service.getAllStreams();
        assertNotNull(streams);
        assertEquals(2, streams.size());
    }

    @Test
    @DisplayName("Should return stream details with correct WebRTC WHEP URL when stream exists")
    void testGetStreamDetails_Found_ReturnsDetailsWithWebrtcUrl() {
        VideoStreamDetailResponse details = service.getStreamDetails("drone-001-camera");
        assertNotNull(details);
        assertEquals("drone-001-camera", details.streamId());
        assertEquals("DRONE-001", details.droneId());
        assertEquals("Drone 001 Camera", details.streamName());
        assertEquals("WEBRTC", details.protocol());
        assertEquals("AVAILABLE", details.status());
        assertEquals("http://localhost:8889/drone-001-camera/whep", details.webrtcUrl());
        assertEquals("rtmp://localhost:1935/drone-001-camera", details.rtmpUrl());
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when stream does not exist")
    void testGetStreamDetails_NotFound_ThrowsResourceNotFoundException() {
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () ->
                service.getStreamDetails("non-existent-stream"));
        assertTrue(ex.getMessage().contains("non-existent-stream"));
    }

    @Test
    @DisplayName("Should return disabled health response when video streaming is disabled")
    void testHealthCheck_Disabled() {
        config.setEnabled(false);
        VideoStreamService disabledService = new VideoStreamService(config);
        disabledService.init();

        VideoHealthResponse health = disabledService.checkHealth();
        assertNotNull(health);
        assertFalse(health.enabled());
        assertEquals("DISABLED", health.status());
    }

    @Test
    @DisplayName("Should return health response reporting MediaMTX status")
    void testHealthCheck_Active() {
        VideoHealthResponse health = service.checkHealth();
        assertNotNull(health);
        assertTrue(health.enabled());
        assertEquals("MediaMTX", health.mediaServer());
        assertEquals("http://localhost:8889", health.mediaServerUrl());
        assertEquals(2, health.activeStreamsCount());
    }
}

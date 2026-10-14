package com.droneiq.video.controller;

import com.droneiq.common.dto.ApiResponse;
import com.droneiq.video.dto.VideoHealthResponse;
import com.droneiq.video.dto.VideoStreamDetailResponse;
import com.droneiq.video.dto.VideoStreamListResponse;
import com.droneiq.video.service.VideoStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/video")
@Tag(name = "Video Streaming & Egress", description = "Live Video Streaming (MediaMTX / WebRTC WHEP) and HUD Egress")
@SecurityRequirement(name = "BearerAuth")
public class VideoController {

    private final VideoStreamService videoStreamService;

    public VideoController(VideoStreamService videoStreamService) {
        this.videoStreamService = videoStreamService;
    }

    @GetMapping("/streams")
    @Operation(summary = "List all active video streams (All Authenticated Users)")
    public ResponseEntity<ApiResponse<VideoStreamListResponse>> getVideoStreams() {
        VideoStreamListResponse response = new VideoStreamListResponse(videoStreamService.getAllStreams());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/streams/{streamId}")
    @Operation(summary = "Get WebRTC connection details for a specific video stream (All Authenticated Users)")
    public ResponseEntity<ApiResponse<VideoStreamDetailResponse>> getStreamDetails(@PathVariable String streamId) {
        VideoStreamDetailResponse response = videoStreamService.getStreamDetails(streamId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/health")
    @Operation(summary = "Check health and connectivity of the video streaming subsystem (All Authenticated Users)")
    public ResponseEntity<ApiResponse<VideoHealthResponse>> getVideoHealth() {
        VideoHealthResponse response = videoStreamService.checkHealth();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/streams/{droneId}/hud")
    @Operation(summary = "Get Heads-Up Display (HUD) overlay parameters for video egress (All Authenticated Users)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getHudOverlay(@PathVariable String droneId) {
        Map<String, Object> hudConfig = Map.of(
                "droneId", droneId,
                "artificialHorizon", true,
                "compassRose", true,
                "altitudeTape", true,
                "speedTape", true,
                "batteryGauge", true,
                "fps", 30,
                "resolution", "1920x1080"
        );
        return ResponseEntity.ok(ApiResponse.success(hudConfig));
    }

    @PostMapping("/streams/{droneId}/control")
    @Operation(summary = "Modify video egress bitrate/stream parameters (All Authenticated Users)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> controlVideoStream(
            @PathVariable String droneId,
            @RequestBody Map<String, Object> controlParams) {
        return ResponseEntity.ok(ApiResponse.success("Video egress configuration applied", Map.of(
                "droneId", droneId,
                "appliedSettings", controlParams,
                "status", "UPDATED"
        )));
    }
}

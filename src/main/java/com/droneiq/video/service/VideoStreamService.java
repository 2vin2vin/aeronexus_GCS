package com.droneiq.video.service;

import com.droneiq.common.exception.ResourceNotFoundException;
import com.droneiq.video.config.VideoStreamingConfig;
import com.droneiq.video.dto.VideoHealthResponse;
import com.droneiq.video.dto.VideoStreamDetailResponse;
import com.droneiq.video.dto.VideoStreamResponse;
import com.droneiq.video.model.VideoStream;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VideoStreamService {

    private static final Logger log =
            LoggerFactory.getLogger(VideoStreamService.class);

    private static final String DEFAULT_SIGNALING_URL =
            "ws://35.183.17.145:8080/";

    private final VideoStreamingConfig config;

    private final Map<String, VideoStream> streamCatalog =
            new ConcurrentHashMap<>();

    private String signalingServerUrl;

    public VideoStreamService(VideoStreamingConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {

        if (!config.isEnabled()) {
            log.info("WebSocket WebRTC streaming is disabled");
            return;
        }

        signalingServerUrl = normalizeSignalingUrl(
                config.getSignalingServerUrl()
        );

        if (config.getStreams() != null
                && !config.getStreams().isEmpty()) {

            for (VideoStreamingConfig.StreamDefinition def
                    : config.getStreams()) {

                String streamId = def.getStreamId();

                String path = def.getPath() != null
                        && !def.getPath().isBlank()
                        ? def.getPath()
                        : streamId;

                VideoStream stream = new VideoStream(
                        streamId,
                        def.getDroneId(),
                        def.getStreamName(),
                        "WEBRTC",
                        path,

                        // Legacy constructor argument.
                        // This is NOT a MediaMTX HTTP URL.
                        signalingServerUrl,

                        // Reuse the existing DTO field to return
                        // the WebSocket URL to the frontend.
                        signalingServerUrl,

                        // RTMP is not used by this implementation.
                        null,

                        def.getStatus() != null
                                ? def.getStatus()
                                : "AVAILABLE"
                );

                streamCatalog.put(streamId, stream);

                log.info(
                        "Registered WebSocket WebRTC stream: "
                                + "streamId={}, room={}, signalingUrl={}",
                        streamId,
                        path,
                        signalingServerUrl
                );
            }

        } else {

            String defaultStreamId =
                    config.getDefaultStreamId() != null
                            && !config.getDefaultStreamId().isBlank()
                            ? config.getDefaultStreamId()
                            : "GCS";

            VideoStream stream = new VideoStream(
                    defaultStreamId,
                    "DRONE-001",
                    "Drone 001 Camera",
                    "WEBRTC",
                    defaultStreamId,
                    signalingServerUrl,
                    signalingServerUrl,
                    null,
                    "AVAILABLE"
            );

            streamCatalog.put(defaultStreamId, stream);

            log.info(
                    "Registered default WebSocket WebRTC stream: "
                            + "streamId={}, signalingUrl={}",
                    defaultStreamId,
                    signalingServerUrl
            );
        }

        log.info(
                "WebSocket WebRTC initialized. Streams: {}",
                streamCatalog.size()
        );
    }

    private String normalizeSignalingUrl(String url) {

        if (url == null || url.isBlank()) {
            url = DEFAULT_SIGNALING_URL;
        }

        String normalized = url.trim();

        if (!normalized.startsWith("ws://")
                && !normalized.startsWith("wss://")) {

            throw new IllegalArgumentException(
                    "Signaling URL must start with ws:// or wss://"
            );
        }

        return normalized.replaceAll("/+$", "") + "/";
    }

    public String getSignalingServerUrl() {
        return signalingServerUrl;
    }

    public List<VideoStreamResponse> getAllStreams() {

        List<VideoStreamResponse> responses = new ArrayList<>();

        for (VideoStream stream : streamCatalog.values()) {

            responses.add(
                    new VideoStreamResponse(
                            stream.getStreamId(),
                            stream.getDroneId(),
                            stream.getStreamName(),
                            stream.getProtocol(),
                            stream.getStatus()
                    )
            );
        }

        return responses;
    }

    public VideoStreamDetailResponse getStreamDetails(
            String streamId) {

        log.debug("Video stream requested: {}", streamId);

        VideoStream stream = streamCatalog.get(streamId);

        if (stream == null) {

            throw new ResourceNotFoundException(
                    "Video stream not found: " + streamId
            );
        }

        return new VideoStreamDetailResponse(
                stream.getStreamId(),
                stream.getDroneId(),
                stream.getStreamName(),
                stream.getProtocol(),
                stream.getStatus(),
                stream.getWebrtcUrl(),
                stream.getRtmpUrl()
        );
    }

    public VideoHealthResponse checkHealth() {

        if (!config.isEnabled()) {

            return new VideoHealthResponse(
                    false,
                    "WebSocket",
                    "DISABLED",
                    signalingServerUrl,
                    0
            );
        }

        boolean reachable =
                probeSignalingServer(signalingServerUrl);

        return new VideoHealthResponse(
                true,
                "WebSocket",
                reachable ? "UP" : "SIGNALING_SERVER_UNREACHABLE",
                signalingServerUrl,
                streamCatalog.size()
        );
    }

    private boolean probeSignalingServer(String serverUrl) {

        if (serverUrl == null || serverUrl.isBlank()) {
            return false;
        }

        try {

            URI uri = URI.create(serverUrl);

            String host = uri.getHost();

            if (host == null) {
                return false;
            }

            int port = uri.getPort();

            if (port == -1) {
                port = "wss".equalsIgnoreCase(uri.getScheme())
                        ? 443
                        : 80;
            }

            try (Socket socket = new Socket()) {

                socket.connect(
                        new InetSocketAddress(host, port),
                        800
                );

                return true;
            }

        } catch (IOException | IllegalArgumentException e) {

            log.debug(
                    "Signaling server probe failed for {}: {}",
                    serverUrl,
                    e.getMessage()
            );

            return false;
        }
    }
}
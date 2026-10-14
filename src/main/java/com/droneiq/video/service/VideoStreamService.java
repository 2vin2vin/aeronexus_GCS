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

    private static final Logger log = LoggerFactory.getLogger(VideoStreamService.class);

    private final VideoStreamingConfig config;
    private final Map<String, VideoStream> streamCatalog = new ConcurrentHashMap<>();

    public VideoStreamService(VideoStreamingConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        if (!config.isEnabled()) {
            log.info("Video streaming module is DISABLED via configuration");
            return;
        }

        String mediaServerUrl = config.getMediaServerUrl() != null ? config.getMediaServerUrl() : "http://localhost:8889";
        String rtmpBase = config.getRtmpBaseUrl() != null ? config.getRtmpBaseUrl() : "rtmp://localhost:1935";

        if (config.getStreams() != null && !config.getStreams().isEmpty()) {
            for (VideoStreamingConfig.StreamDefinition def : config.getStreams()) {
                String path = def.getPath() != null && !def.getPath().isBlank() ? def.getPath() : def.getStreamId();
                String webrtcUrl = buildWebrtcUrl(mediaServerUrl, path);
                String rtmpUrl = buildRtmpUrl(rtmpBase, path);

                VideoStream stream = new VideoStream(
                        def.getStreamId(),
                        def.getDroneId(),
                        def.getStreamName(),
                        def.getProtocol() != null ? def.getProtocol() : "WEBRTC",
                        path,
                        mediaServerUrl,
                        webrtcUrl,
                        rtmpUrl,
                        def.getStatus() != null ? def.getStatus() : "AVAILABLE"
                );
                streamCatalog.put(stream.getStreamId(), stream);
                log.info("Configured video stream: streamId={}, droneId={}, webrtcUrl={}",
                        stream.getStreamId(), stream.getDroneId(), webrtcUrl);
            }
        } else {
            String defaultStreamId = config.getDefaultStreamId() != null ? config.getDefaultStreamId() : "drone-001-camera";
            String path = defaultStreamId;
            String webrtcUrl = buildWebrtcUrl(mediaServerUrl, path);
            String rtmpUrl = buildRtmpUrl(rtmpBase, path);

            VideoStream defaultStream = new VideoStream(
                    defaultStreamId,
                    "DRONE-001",
                    "Drone 001 Camera",
                    "WEBRTC",
                    path,
                    mediaServerUrl,
                    webrtcUrl,
                    rtmpUrl,
                    "AVAILABLE"
            );
            streamCatalog.put(defaultStreamId, defaultStream);
            log.info("Initialized default video stream: streamId={}, webrtcUrl={}",
                    defaultStreamId, webrtcUrl);
        }

        log.info("Video streaming module initialized. Media server URL: {}, active streams: {}",
                mediaServerUrl, streamCatalog.size());
    }

    public List<VideoStreamResponse> getAllStreams() {
        List<VideoStreamResponse> responses = new ArrayList<>();
        for (VideoStream stream : streamCatalog.values()) {
            responses.add(new VideoStreamResponse(
                    stream.getStreamId(),
                    stream.getDroneId(),
                    stream.getStreamName(),
                    stream.getProtocol(),
                    stream.getStatus()
            ));
        }
        return responses;
    }

    public VideoStreamDetailResponse getStreamDetails(String streamId) {
        log.debug("Video stream requested: {}", streamId);
        VideoStream stream = streamCatalog.get(streamId);
        if (stream == null) {
            log.warn("Video stream not found: {}", streamId);
            throw new ResourceNotFoundException("Video stream not found: " + streamId);
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
            return new VideoHealthResponse(false, "MediaMTX", "DISABLED", config.getMediaServerUrl(), 0);
        }

        boolean isReachable = probeMediaServer(config.getMediaServerUrl());
        String status = isReachable ? "UP" : "MEDIA_SERVER_UNREACHABLE";

        return new VideoHealthResponse(
                true,
                "MediaMTX",
                status,
                config.getMediaServerUrl(),
                streamCatalog.size()
        );
    }

    private boolean probeMediaServer(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(serverUrl);
            String host = uri.getHost() != null ? uri.getHost() : "localhost";
            int port = uri.getPort() != -1 ? uri.getPort() : 8889;

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 800);
                return true;
            }
        } catch (IOException e) {
            log.debug("Media server probe failed for {}: {}", serverUrl, e.getMessage());
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String buildWebrtcUrl(String base, String path) {
        String cleanBase = base.replaceAll("/+$", "");
        return cleanBase + "/" + path + "/whep";
    }

    private String buildRtmpUrl(String base, String path) {
        String cleanBase = base.replaceAll("/+$", "");
        return cleanBase + "/" + path;
    }
}

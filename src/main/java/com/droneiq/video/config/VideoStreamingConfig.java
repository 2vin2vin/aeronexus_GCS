package com.droneiq.video.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "droneiq.video")
public class VideoStreamingConfig {

    private boolean enabled = true;
    private String mediaServerUrl = "http://localhost:8889";
    private String rtmpBaseUrl = "rtmp://localhost:1935";
    private String defaultStreamId = "drone-001-camera";
    private List<StreamDefinition> streams = new ArrayList<>();

    private String signalingServerUrl =
        "ws://35.183.17.145:8080/";

    public String getSignalingServerUrl() {
        return signalingServerUrl;
    }

    public void setSignalingServerUrl(String signalingServerUrl) {
        this.signalingServerUrl = signalingServerUrl;
    }

    public static class StreamDefinition {
        private String streamId;
        private String droneId;
        private String streamName;
        private String protocol = "WEBRTC";
        private String path;
        private String status = "AVAILABLE";

        public String getStreamId() {
            return streamId;
        }

        public void setStreamId(String streamId) {
            this.streamId = streamId;
        }

        public String getDroneId() {
            return droneId;
        }

        public void setDroneId(String droneId) {
            this.droneId = droneId;
        }

        public String getStreamName() {
            return streamName;
        }

        public void setStreamName(String streamName) {
            this.streamName = streamName;
        }

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMediaServerUrl() {
        return mediaServerUrl;
    }

    public void setMediaServerUrl(String mediaServerUrl) {
        this.mediaServerUrl = mediaServerUrl;
    }

    public String getRtmpBaseUrl() {
        return rtmpBaseUrl;
    }

    public void setRtmpBaseUrl(String rtmpBaseUrl) {
        this.rtmpBaseUrl = rtmpBaseUrl;
    }

    public String getDefaultStreamId() {
        return defaultStreamId;
    }

    public void setDefaultStreamId(String defaultStreamId) {
        this.defaultStreamId = defaultStreamId;
    }

    public List<StreamDefinition> getStreams() {
        return streams;
    }

    public void setStreams(List<StreamDefinition> streams) {
        this.streams = streams;
    }
}

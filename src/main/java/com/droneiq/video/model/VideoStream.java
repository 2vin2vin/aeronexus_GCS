package com.droneiq.video.model;

public class VideoStream {
    private String streamId;
    private String droneId;
    private String streamName;
    private String protocol;
    private String path;
    private String mediaServerUrl;
    private String webrtcUrl;
    private String rtmpUrl;
    private String status;

    public VideoStream() {
    }

    public VideoStream(String streamId, String droneId, String streamName, String protocol,
                       String path, String mediaServerUrl, String webrtcUrl, String rtmpUrl, String status) {
        this.streamId = streamId;
        this.droneId = droneId;
        this.streamName = streamName;
        this.protocol = protocol;
        this.path = path;
        this.mediaServerUrl = mediaServerUrl;
        this.webrtcUrl = webrtcUrl;
        this.rtmpUrl = rtmpUrl;
        this.status = status;
    }

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

    public String getMediaServerUrl() {
        return mediaServerUrl;
    }

    public void setMediaServerUrl(String mediaServerUrl) {
        this.mediaServerUrl = mediaServerUrl;
    }

    public String getWebrtcUrl() {
        return webrtcUrl;
    }

    public void setWebrtcUrl(String webrtcUrl) {
        this.webrtcUrl = webrtcUrl;
    }

    public String getRtmpUrl() {
        return rtmpUrl;
    }

    public void setRtmpUrl(String rtmpUrl) {
        this.rtmpUrl = rtmpUrl;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

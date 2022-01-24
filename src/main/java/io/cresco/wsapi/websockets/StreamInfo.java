package io.cresco.wsapi.websockets;

public class StreamInfo {

    private String sessionId;
    private String identKey;
    private String identId;
    private String stream_query;
    private String listenerId;

    public StreamInfo(String sessionId, String identKy, String identId, String stream_query) {
        this.sessionId = sessionId;
        this.identKey = identKy;
        this.identId = identId;
        this.stream_query = stream_query;
    }

    public StreamInfo(String sessionId, String stream_query) {
        this.sessionId = sessionId;
        this.stream_query = stream_query;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getIdentKey() {
        return identKey;
    }

    public void setIdentId(String identKey) {
        this.identKey = identKey;
    }

    public String getIdentId() {
        return identId;
    }

    public String getStream_query() {
        return stream_query;
    }

    public void setStream_query(String stream_query) {
        this.stream_query = stream_query;
    }

    public String getListenerId() {
        return listenerId;
    }

    public void setListenerId(String listenerId) {
        this.listenerId = listenerId;
    }

}

package io.cresco.wsapi.websockets;

public class SessionInfo {

    public String webSessionId;
    public String regionId;
    public String agentId;
    public String logSessionId;

    public SessionInfo(String logSessionId, String webSessionId, String regionId, String agentId) {
        this.webSessionId = webSessionId;
        this.regionId = regionId;
        this.agentId = agentId;
        this.logSessionId = logSessionId;
    }

}

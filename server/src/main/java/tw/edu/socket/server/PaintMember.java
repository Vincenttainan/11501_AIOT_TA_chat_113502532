package tw.edu.socket.server;

/** Paint room member details used by the Server management panel. */
public final class PaintMember {
    public final String clientId;
    public final String name;
    public final String ipAddress;
    public final String color;

    public PaintMember(String clientId, String name, String ipAddress, String color) {
        this.clientId = clientId;
        this.name = name;
        this.ipAddress = ipAddress;
        this.color = color;
    }
}

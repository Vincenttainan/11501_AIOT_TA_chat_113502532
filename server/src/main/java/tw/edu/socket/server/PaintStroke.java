package tw.edu.socket.server;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable, normalized stroke retained by the authoritative server board. */
public final class PaintStroke {
    public static final int MAX_POINTS = 512;
    public final String strokeId;
    public final String requestId;
    public final String clientId;
    public final String name;
    public final int color;
    public final float width;
    public final long revision;
    public final List<Point> points;

    public PaintStroke(String strokeId, String requestId, String clientId, String name,
                       int color, float width, long revision, List<Point> points) {
        this.strokeId = strokeId; this.requestId = requestId; this.clientId = clientId;
        this.name = name; this.color = color; this.width = width; this.revision = revision;
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
    }

    public JSONObject toJson() throws JSONException {
        JSONObject value = new JSONObject();
        value.put("strokeId", strokeId); value.put("requestId", requestId);
        value.put("clientId", clientId); value.put("ownerId", clientId); value.put("name", name);
        value.put("color", String.format("#%08X", color)); value.put("width", width);
        value.put("revision", revision);
        JSONArray array = new JSONArray();
        for (Point point : points) {
            JSONObject p = new JSONObject(); p.put("x", point.x); p.put("y", point.y); array.put(p);
        }
        value.put("points", array); return value;
    }

    public static final class Point {
        public final float x, y;
        public Point(float x, float y) { this.x = x; this.y = y; }
    }
}

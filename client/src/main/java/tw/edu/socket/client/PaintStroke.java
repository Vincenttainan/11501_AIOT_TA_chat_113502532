package tw.edu.socket.client;

import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable normalized stroke shared by the canvas and paint activity. */
public final class PaintStroke {
    public static final int MAX_POINTS = 512;
    public final String strokeId, requestId, clientId, ownerId, name;
    public final int color;
    public final float width;
    public final long revision;
    public final List<Point> points;
    public PaintStroke(String strokeId, String requestId, String clientId, String name,
                       int color, float width, long revision, List<Point> points) {
        this.strokeId=strokeId; this.requestId=requestId; this.clientId=clientId; this.ownerId=clientId; this.name=name;
        this.color=color; this.width=width; this.revision=revision;
        this.points=Collections.unmodifiableList(new ArrayList<>(points));
    }
    public static PaintStroke fromJson(JSONObject value) throws JSONException {
        String rawColor=value.optString("color", "#FF000000");
        int color;
        try { color=Color.parseColor(rawColor); } catch (IllegalArgumentException e) { throw new JSONException("invalid color"); }
        JSONArray array=value.getJSONArray("points");
        if (array.length()<1 || array.length()>512) throw new JSONException("invalid points");
        List<Point> points=new ArrayList<>();
        for(int i=0;i<array.length();i++) {
            JSONObject p=array.getJSONObject(i); float x=(float)p.getDouble("x"), y=(float)p.getDouble("y");
            if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||x>1||y<0||y>1) throw new JSONException("invalid coordinate");
            points.add(new Point(x,y));
        }
        float width=(float)value.getDouble("width");
        if(!Float.isFinite(width)||width<=0||width>0.2f) throw new JSONException("invalid width");
        return new PaintStroke(value.optString("strokeId"), value.optString("requestId"),
                value.optString("ownerId", value.optString("clientId")), value.optString("name"), color,width,
                value.optLong("revision",0),points);
    }
    public static final class Point { public final float x,y; public Point(float x,float y){this.x=x;this.y=y;} }
}

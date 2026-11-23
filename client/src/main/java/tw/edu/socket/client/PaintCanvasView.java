package tw.edu.socket.client;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Canvas and touch input only; networking is provided by its owning Activity. */
public class PaintCanvasView extends View {
    public interface StrokeListener { void onStrokeCompleted(String requestId, List<PaintStroke.Point> points, float width); }
    public interface EraseListener { void onEraseRequested(String strokeId); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<PaintStroke> confirmed = new ArrayList<>();
    private final Map<String, PaintStroke> optimistic = new LinkedHashMap<>();
    private final List<PaintStroke.Point> activePoints = new ArrayList<>();
    private StrokeListener strokeListener;
    private EraseListener eraseListener;
    private PaintStroke activePreview;
    private int userColor = Color.BLACK;
    private float normalizedWidth = 0.012f;
    private boolean drawingEnabled;
    private boolean eraserMode;
    private String ownerId;

    public PaintCanvasView(Context context) { super(context); init(); }
    public PaintCanvasView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    private void init() { setBackgroundColor(Color.WHITE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); }
    public void setStrokeListener(StrokeListener listener) { strokeListener=listener; }
    public void setEraseListener(EraseListener listener) { eraseListener=listener; }
    public void setUserColor(int color) { userColor=color; }
    public void setOwnerId(String id) { ownerId=id; }
    public void setDrawingEnabled(boolean enabled) { drawingEnabled=enabled; }
    public void setEraserMode(boolean enabled) { eraserMode=enabled; }
    public void setBoard(List<PaintStroke> strokes) { confirmed.clear(); optimistic.clear(); confirmed.addAll(strokes); invalidate(); }
    public void confirmStroke(String requestId, PaintStroke stroke) { optimistic.remove(requestId); if(!contains(stroke.strokeId)) confirmed.add(stroke); invalidate(); }
    public void addRemoteStroke(PaintStroke stroke) { if(!contains(stroke.strokeId)) confirmed.add(stroke); invalidate(); }
    public void removeStrokes(List<String> strokeIds) {
        for(int i=confirmed.size()-1;i>=0;i--) if(strokeIds.contains(confirmed.get(i).strokeId)) confirmed.remove(i);
        invalidate();
    }
    private boolean contains(String id) { for(PaintStroke s:confirmed) if(s.strokeId.equals(id)) return true; return false; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.WHITE);
        for(PaintStroke stroke:confirmed) drawStroke(canvas,stroke);
        for(PaintStroke stroke:optimistic.values()) drawStroke(canvas,stroke);
        if(activePreview!=null) drawStroke(canvas,activePreview);
    }
    private void drawStroke(Canvas canvas, PaintStroke stroke) {
        float w=Math.max(1f,stroke.width*Math.max(1,Math.min(getWidth(),getHeight()))); paint.setColor(stroke.color); paint.setStrokeWidth(w);
        if(stroke.points.size()==1) { Paint p=new Paint(paint); p.setStyle(Paint.Style.FILL); PaintStroke.Point point=stroke.points.get(0); canvas.drawCircle(point.x*getWidth(),point.y*getHeight(),w/2,p); return; }
        for(int i=1;i<stroke.points.size();i++) { PaintStroke.Point a=stroke.points.get(i-1), b=stroke.points.get(i); canvas.drawLine(a.x*getWidth(),a.y*getHeight(),b.x*getWidth(),b.y*getHeight(),paint); }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(!drawingEnabled)return false;
        if(getWidth()<=0||getHeight()<=0) return false;
        if(eraserMode) {
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN) {
                String strokeId=findOwnedStroke(event.getX(),event.getY());
                if(strokeId!=null&&eraseListener!=null)eraseListener.onEraseRequested(strokeId);
            }
            return true;
        }
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePoints.clear(); addPoint(event.getX(),event.getY()); updatePreview(); invalidate(); return true;
            case MotionEvent.ACTION_MOVE:
                for(int i=0;i<event.getHistorySize();i++) addPoint(event.getHistoricalX(i),event.getHistoricalY(i));
                addPoint(event.getX(),event.getY()); updatePreview(); invalidate(); return true;
            case MotionEvent.ACTION_UP:
                addPoint(event.getX(),event.getY()); completeStroke(); performClick(); return true;
            case MotionEvent.ACTION_CANCEL:
                // Cancelled gestures are discarded so an incomplete local preview is never submitted.
                activePoints.clear(); activePreview=null; invalidate(); return true;
            default: return true;
        }
    }
    private String findOwnedStroke(float x,float y) {
        float tolerance=24f*getResources().getDisplayMetrics().density;
        for(int s=confirmed.size()-1;s>=0;s--) {
            PaintStroke stroke=confirmed.get(s);
            if(ownerId==null||!ownerId.equals(stroke.ownerId))continue;
            float limit=tolerance+stroke.width*Math.min(getWidth(),getHeight())/2f;
            if(stroke.points.size()==1) {
                PaintStroke.Point p=stroke.points.get(0);
                if(distance(x,y,p.x*getWidth(),p.y*getHeight())<=limit)return stroke.strokeId;
            } else for(int i=1;i<stroke.points.size();i++) {
                PaintStroke.Point a=stroke.points.get(i-1),b=stroke.points.get(i);
                if(distanceToSegment(x,y,a.x*getWidth(),a.y*getHeight(),b.x*getWidth(),b.y*getHeight())<=limit)return stroke.strokeId;
            }
        }
        return null;
    }
    private float distance(float x1,float y1,float x2,float y2){return (float)Math.hypot(x1-x2,y1-y2);}
    private float distanceToSegment(float x,float y,float ax,float ay,float bx,float by){
        float dx=bx-ax,dy=by-ay;
        if(dx==0&&dy==0)return distance(x,y,ax,ay);
        float t=Math.max(0,Math.min(1,((x-ax)*dx+(y-ay)*dy)/(dx*dx+dy*dy)));
        return distance(x,y,ax+t*dx,ay+t*dy);
    }
    private void addPoint(float x,float y) {
        if(activePoints.size()>=PaintStroke.MAX_POINTS) return;
        float nx=Math.max(0f,Math.min(1f,x/getWidth())), ny=Math.max(0f,Math.min(1f,y/getHeight()));
        PaintStroke.Point point=new PaintStroke.Point(nx,ny);
        if(activePoints.isEmpty() || Math.abs(activePoints.get(activePoints.size()-1).x-nx)+Math.abs(activePoints.get(activePoints.size()-1).y-ny)>0.001f) activePoints.add(point);
    }
    private void updatePreview() { if(activePoints.isEmpty()) return; activePreview=new PaintStroke("", "", "", "", userColor, normalizedWidth, 0, activePoints); }
    private void completeStroke() {
        if(activePoints.isEmpty()) return;
        String requestId=UUID.randomUUID().toString();
        PaintStroke local=new PaintStroke("",requestId,"","",userColor,normalizedWidth,0,activePoints);
        optimistic.put(requestId,local);
        List<PaintStroke.Point> points=new ArrayList<>(activePoints);
        activePoints.clear(); activePreview=null; invalidate();
        if(strokeListener!=null) strokeListener.onStrokeCompleted(requestId,points,normalizedWidth);
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}

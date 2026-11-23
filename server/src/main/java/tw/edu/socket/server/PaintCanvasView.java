package tw.edu.socket.server;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Read-only rendering of the server's authoritative normalized board. */
public class PaintCanvasView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<PaintStroke> strokes=new ArrayList<>();
    public PaintCanvasView(Context context){super(context);setBackgroundColor(Color.WHITE);}
    public PaintCanvasView(Context context,AttributeSet attrs){super(context,attrs);setBackgroundColor(Color.WHITE);}
    public void setBoard(List<PaintStroke> board){strokes.clear();strokes.addAll(board);invalidate();}
    public void addStroke(PaintStroke stroke){for(PaintStroke s:strokes)if(s.strokeId.equals(stroke.strokeId))return;strokes.add(stroke);invalidate();}
    public void removeStrokes(List<String> strokeIds){for(int i=strokes.size()-1;i>=0;i--)if(strokeIds.contains(strokes.get(i).strokeId))strokes.remove(i);invalidate();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.drawColor(Color.WHITE);for(PaintStroke s:strokes)drawStroke(canvas,s);}
    private void drawStroke(Canvas canvas,PaintStroke stroke){
        float w=Math.max(1f,stroke.width*Math.max(1,Math.min(getWidth(),getHeight())));paint.setColor(stroke.color);paint.setStrokeWidth(w);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStyle(Paint.Style.STROKE);
        if(stroke.points.size()==1){Paint p=new Paint(paint);p.setStyle(Paint.Style.FILL);PaintStroke.Point a=stroke.points.get(0);canvas.drawCircle(a.x*getWidth(),a.y*getHeight(),w/2,p);return;}
        for(int i=1;i<stroke.points.size();i++){PaintStroke.Point a=stroke.points.get(i-1),b=stroke.points.get(i);canvas.drawLine(a.x*getWidth(),a.y*getHeight(),b.x*getWidth(),b.y*getHeight(),paint);}
    }
}

package tw.edu.socket.server;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.View;

/** 空白畫布外框；後續階段會在此加入權威筆跡的繪製。 */
public class PaintCanvasView extends View {
    public PaintCanvasView(Context context) {
        super(context);
        setBackgroundColor(Color.WHITE);
    }

    public PaintCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(Color.WHITE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.WHITE);
    }
}

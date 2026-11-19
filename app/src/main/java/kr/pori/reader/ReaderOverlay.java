package kr.pori.reader;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;

final class ReaderOverlay extends View {
    interface Pick { void at(float x, float y); }
    final Pick pick;
    FrameReader.Line line;
    PointF tip;
    final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    ReaderOverlay(Context context, Pick pick) {
        super(context); this.pick = pick;
        setContentDescription("카메라 화면. 원하는 글줄을 눌러 선택할 수 있어요.");
    }
    protected void onDraw(Canvas c) {
        if (line != null) {
            RectF r = new RectF(line.box);
            r.set(r.left*getWidth(), r.top*getHeight(), r.right*getWidth(), r.bottom*getHeight());
            r.inset(-3,-3);
            pen.setStyle(Paint.Style.FILL); pen.setColor(0x55C9F973); c.drawRoundRect(r,8,8,pen);
            pen.setColor(0xffC9F973); pen.setStyle(Paint.Style.STROKE); pen.setStrokeWidth(4);
            c.drawRoundRect(r,8,8,pen); pen.setStyle(Paint.Style.FILL);
        }
        if (tip != null) {
            float x=tip.x*getWidth(), y=tip.y*getHeight();
            pen.setColor(0xff164B3E); c.drawCircle(x,y,10,pen);
            pen.setColor(0xffE0FFA0); c.drawCircle(x,y,6,pen);
        }
    }
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction()==MotionEvent.ACTION_UP) {
            performClick(); pick.at(e.getX()/getWidth(), e.getY()/getHeight());
        }
        return true;
    }
    public boolean performClick() { super.performClick(); return true; }
}

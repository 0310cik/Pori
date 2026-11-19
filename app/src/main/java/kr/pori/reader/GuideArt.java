package kr.pori.reader;

import android.content.Context;
import android.graphics.*;
import android.view.View;

// A page, the selected line, and a pointing fingertip illustrate the actual gesture.
final class GuideArt extends View {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    GuideArt(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth()/320f,getHeight()/180f);
        c.save(); c.translate((getWidth()-320*s)/2,(getHeight()-180*s)/2); c.scale(s,s);
        p.setColor(0xffC4D4C8); c.drawRoundRect(58,22,264,167,12,12,p);
        p.setColor(0xffFFFEF7); c.drawRoundRect(53,16,259,160,12,12,p);
        p.setColor(0xff486858); c.drawRoundRect(73,34,143,41,4,4,p);
        p.setColor(0xffCED7C9); c.drawRoundRect(73,54,222,59,3,3,p);
        c.drawRoundRect(73,69,204,74,3,3,p);
        p.setColor(0xffD4F399); c.drawRoundRect(65,83,237,109,6,6,p);
        p.setColor(0xff204B3B); p.setTextSize(15); p.setTypeface(Typeface.DEFAULT_BOLD);
        c.drawText("읽고 싶은 한 줄",74,101,p);
        p.setColor(0xffCED7C9); c.drawRoundRect(73,121,188,126,3,3,p);
        Path hand=new Path(); hand.moveTo(221,169); hand.lineTo(200,142); hand.lineTo(181,116);
        hand.cubicTo(174,105,183,100,190,107); hand.lineTo(206,123); hand.lineTo(200,103);
        hand.cubicTo(197,91,207,89,211,100); hand.lineTo(221,124); hand.cubicTo(242,118,249,133,252,146);
        hand.lineTo(259,166); hand.close();
        p.setColor(0xffE5B793); c.drawPath(hand,p); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2);
        p.setColor(0xff355846); c.drawPath(hand,p); p.setStyle(Paint.Style.FILL);
        c.restore();
    }
}

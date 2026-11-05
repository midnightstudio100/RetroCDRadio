package com.aistudio.retroradio.kzvpwx;
import android.content.Context; import android.graphics.*; import android.os.Handler; import android.util.AttributeSet; import android.view.View;
public class CdView extends View {
    private String label = "WGTS 91.9"; private boolean playing = false; private float angle = 0;
    private final Handler h = new Handler(android.os.Looper.getMainLooper()); private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable tick = new Runnable(){ public void run(){ if(playing){ angle += 20; if(angle>=360) angle-=360; invalidate(); } h.postDelayed(this, 50); } };
    public CdView(Context c, AttributeSet a){ super(c,a); h.post(tick); }
    public void setLabel(String s){ label = s==null?"":s; invalidate(); }
    public void setPlaying(boolean b){ playing=b; invalidate(); }
    @Override protected void onDraw(Canvas cv){
        super.onDraw(cv); float cx=getWidth()/2f, cy=getHeight()/2f, r=Math.min(cx,cy)-8;
        // tray
        p.setColor(0xFF1E2228); RectF tray=new RectF(4,4,getWidth()-4,getHeight()-4); cv.drawRoundRect(tray,28,28,p);
        // disc
        p.setColor(0xFF2A2E35); cv.drawCircle(cx,cy,r,p);
        p.setColor(0xFF3A3F47); cv.drawCircle(cx,cy,r*0.96f,p);
        // reflective grooves, rotating
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2);
        for(int i=0;i<12;i++){
            float rr=r*(0.55f+0.035f*i);
            int shade = 0xFF666666 + (i%3)*0x111111;
            p.setColor(shade);
            RectF arc=new RectF(cx-rr, cy-rr, cx+rr, cy+rr);
            cv.drawArc(arc, angle+i*30, 60, false, p);
        }
        p.setStyle(Paint.Style.FILL);
        // rainbow shine
        p.setColor(0x44FFFFFF);
        cv.save(); cv.rotate(angle, cx, cy);
        RectF shine=new RectF(cx-r*0.9f, cy-r*0.18f, cx+r*0.9f, cy+r*0.18f);
        cv.drawOval(shine, p); cv.restore();
        // centre label
        p.setColor(0xFFF2EAD8); cv.drawCircle(cx,cy,r*0.42f,p);
        p.setColor(0xFF111111); p.setTextSize(30); p.setTextAlign(Paint.Align.CENTER);
        String t = label.length()>16 ? label.substring(0,16) : label;
        cv.drawText(t, cx, cy+6, p);
        p.setTextSize(16); cv.drawText("CD", cx, cy+32, p);
        // centre hole
        p.setColor(0xFF111111); cv.drawCircle(cx,cy,r*0.12f,p);
        p.setColor(0xFF888888); cv.drawCircle(cx,cy,r*0.12f,p);
        p.setColor(0xFF111111); cv.drawCircle(cx,cy,r*0.08f,p);
    }
}

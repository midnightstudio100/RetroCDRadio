package com.aistudio.retroradio.kzvpwx;
import android.content.Context; import android.graphics.*; import android.util.AttributeSet; import android.view.*;
public class DialView extends View {
    public interface Listener{ void onVolume(int v); }
    private Listener listener; private int vol=10, max=15; private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    public DialView(Context c, AttributeSet a){ super(c,a); }
    public void setListener(Listener l){ listener=l; }
    public void setVolume(int v, int m){ vol=v; max=m; invalidate(); }
    @Override protected void onDraw(Canvas cv){ super.onDraw(cv); float cx=getWidth()/2f, cy=getHeight()/2f, r=Math.min(cx,cy)-4;
        p.setColor(0xFFE8E0D0); cv.drawCircle(cx,cy,r,p); p.setColor(0xFF333333); cv.drawCircle(cx,cy,r*0.85f,p);
        double a=Math.toRadians(-135 + 270.0*vol/Math.max(1,max));
        p.setColor(0xFFFFFFFF); p.setStrokeWidth(5); cv.drawLine(cx,cy,(float)(cx+Math.cos(a)*r*0.7),(float)(cy+Math.sin(a)*r*0.7),p); }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getAction()==MotionEvent.ACTION_MOVE || e.getAction()==MotionEvent.ACTION_DOWN){
            int v = (int)Math.round(max * (1 - e.getY()/Math.max(1,getHeight()))); if(v<0)v=0; if(v>max)v=max;
            if(v!=vol){ vol=v; if(listener!=null) listener.onVolume(v); invalidate(); }
            return true; }
        return super.onTouchEvent(e); }
}

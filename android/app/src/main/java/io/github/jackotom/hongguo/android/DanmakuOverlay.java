// SPDX-License-Identifier: GPL-3.0-only
package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import java.util.List;

/** Non-interactive display between VideoView and HUD. All movement follows the decoder's clock. */
final class DanmakuOverlay extends View {
    interface Clock { long position(); boolean playing(); }
    private final Clock clock;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DanmakuTimeline timeline=new DanmakuTimeline();
    private boolean enabled;
    /** 0 = normal horizontal scroll; 90/270 = portrait projection, where the travel axis is
     *  this view's height and glyphs rotate so text stays readable on the sideways wall. */
    private int verticalScroll;
    DanmakuOverlay(Context context,Clock clock){super(context);this.clock=clock;setFocusable(false);setClickable(false);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);paint.setTextSize(18*getResources().getDisplayMetrics().scaledDensity);paint.setShadowLayer(2,1,1,0xff000000);paint.setAlpha(220);}
    void items(List<DanmakuClient.Item> items,long position){timeline.replace(items,position);invalidate();}
    void clear(){timeline.clear();invalidate();}
    void enabled(boolean value){enabled=value;if(!value)timeline.clear();invalidate();}
    void pulse(){if(enabled)invalidate();}
    void seek(long position){timeline.seek(position);invalidate();}
    void setVerticalScroll(int degrees){verticalScroll=degrees==90||degrees==270?degrees:0;invalidate();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(!enabled)return;long position;boolean playing;try{position=clock.position();playing=clock.playing();}catch(RuntimeException notReady){return;}
        if(verticalScroll!=0)drawVertical(canvas,position,playing);else drawHorizontal(canvas,position,playing);
        if(playing&&getWindowVisibility()==View.VISIBLE)postInvalidateDelayed(33);
    }
    private void drawHorizontal(Canvas canvas,long position,boolean playing){
        float size=paint.getTextSize(),top=Math.max(size*1.3f,getHeight()*.05f),step=size*1.5f;
        for(DanmakuTimeline.Visible item:timeline.advance(position,playing,getWidth(),paint::measureText)){paint.setColor(item.item.color);paint.setAlpha(220);canvas.drawText(item.item.text,item.x(position,getWidth()),top+item.lane*step,paint);}
    }
    /** The parent layer renders rotated 90/270, so canvas down maps to wall-left (90) or
     *  wall-right (270). Danmaku must still fly right-to-left on the wall: the timeline keeps
     *  its math on an abstract travel axis and this view maps that axis onto canvas Y. */
    private void drawVertical(Canvas canvas,long position,boolean playing){
        float size=paint.getTextSize(),left=Math.max(size*1.3f,getWidth()*.05f),step=size*1.5f;
        float travel=getHeight();
        for(DanmakuTimeline.Visible item:timeline.advance(position,playing,travel,paint::measureText)){
            paint.setColor(item.item.color);paint.setAlpha(220);
            float along=item.x(position,travel);
            float y=verticalScroll==90?travel-along:along;
            float x=left+item.lane*step;
            canvas.save();
            canvas.rotate(verticalScroll==90?-90:90,x,y);
            canvas.drawText(item.item.text,x,y,paint);
            canvas.restore();
        }
    }
}

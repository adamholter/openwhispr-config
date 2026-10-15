package com.voicerewriter;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** One inset owner for both keyboard modes. Swipes cancel the child tap first. */
public final class VoiceKeyboardContainer extends FrameLayout {
    public interface SwipeMode { boolean canStart(float y); void switchMode(); }
    private final SwipeMode mode;
    private float downX, downY;
    private boolean eligible, swiping;
    public VoiceKeyboardContainer(Context context, SwipeMode mode) {
        super(context); this.mode=mode;
        setOnApplyWindowInsetsListener((v,insets)->{ requestLayout(); return insets; });
    }
    private float threshold() { return 32 * getResources().getDisplayMetrics().density; }
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        final int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = e.getX(); downY = e.getY();
            eligible = mode.canStart(downY); swiping = false;
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            eligible = false;
        }
        if (eligible && !swiping && e.getPointerCount() == 1
                && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)) {
            float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
            if (dx >= threshold() && dx > dy * 1.25f) {
                // Observe before child dispatch, even if a key requested exclusive touch.
                // Cancel the key/button before consuming the rest of this gesture.
                MotionEvent cancel = MotionEvent.obtain(e);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                swiping = true;
            }
        }
        if (swiping) {
            if (action == MotionEvent.ACTION_UP) {
                boolean switchNow = eligible && mode.canStart(downY);
                eligible = false; swiping = false;
                if (switchNow) mode.switchMode();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                eligible = false; swiping = false;
            }
            return true;
        }
        boolean handled = super.dispatchTouchEvent(e);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
            eligible = false;
        return handled;
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        // Keep empty panel space in the gesture stream as well as buttons and keys.
        return eligible || super.onTouchEvent(e);
    }
    private int imeNavigationHeight(View view) {
        if (view.getVisibility() != VISIBLE) return 0;
        if (view.getClass().getName().equals("android.inputmethodservice.navigationbar.NavigationBarFrame"))
            return view.getMeasuredHeight();
        int height = 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                height = Math.max(height, imeNavigationHeight(group.getChildAt(i)));
        }
        return height;
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        WindowInsets insets=getRootWindowInsets();
        if(insets!=null && Build.VERSION.SDK_INT>=30) {
            android.graphics.Insets nav=insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars());
            Rect screen=getContext().getSystemService(WindowManager.class).getCurrentWindowMetrics().getBounds();
            int[] pos=new int[2];getLocationOnScreen(pos);
            int left=Math.max(0,screen.left+nav.left-pos[0]);
            int right=Math.max(0,pos[0]+getWidth()-(screen.right-nav.right));
            // Gesture navigation can add an IME-owned navigation frame taller than
            // the ordinary gesture inset. Reserve its measured height as well.
            int navBottom = Math.max(nav.bottom, imeNavigationHeight(getRootView()));
            int bottom=Math.max(0,pos[1]+getHeight()-(screen.bottom-navBottom));
            if(getPaddingBottom()!=bottom || getPaddingLeft()!=left || getPaddingRight()!=right)
                setPadding(left,0,right,bottom);
        }
        super.onLayout(changed,l,t,r,b);
    }
}

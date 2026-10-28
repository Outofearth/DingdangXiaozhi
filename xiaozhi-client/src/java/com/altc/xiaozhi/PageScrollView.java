package com.altc.xiaozhi;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

/**
 * 设置页的滚动容器：可以被「临时锁住」，把这段时间的手势完整交给里面的子控件。
 *
 * <h3>为什么需要这个</h3>
 * 设置页里嵌了一个 <b>独立滚动</b> 的目录树框（同方向嵌套）。按常规做法在子控件里调
 * {@code requestDisallowInterceptTouchEvent(true)}，在**这台设备上并不生效** ——
 * 实测手指在框里滑，外层页面照样把事件抢走（子控件收到的是 ACTION_CANCEL，
 * 滚动位置一直是 0），表现就是「树一格都不动，页面反而跳了一屏」。
 *
 * <p>{@code disallowIntercept} 走的是 ViewGroup 的组标志位，猜不透这个定制 ROM 在哪一层丢了；
 * 但 {@link #onInterceptTouchEvent} 是我们自己实现的 —— 锁住时直接返回 false，
 * 父容器连「要不要抢」都不会问，效果是确定的。
 *
 * <p>只在手指落在树框里的那一次手势期间上锁（见 {@code SettingsActivity#bindTreeScroll}），
 * 抬手立刻解锁，页面照常能滑。
 */
public class PageScrollView extends ScrollView {

    private boolean locked;

    public PageScrollView(Context c) { super(c); }

    public PageScrollView(Context c, AttributeSet a) { super(c, a); }

    public PageScrollView(Context c, AttributeSet a, int defStyle) { super(c, a, defStyle); }

    /** true = 本次手势不拦截，全部让给里面的滚动框。 */
    public void setScrollLocked(boolean lock) { this.locked = lock; }

    public boolean isScrollLocked() { return locked; }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (locked) return false;
        return super.onInterceptTouchEvent(ev);
    }
}

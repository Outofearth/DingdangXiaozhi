package com.altc.xiaozhi;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * 全局 Activity 生命周期跟踪：用来告诉 FloatEntryService「小智自己是不是在前台」。
 *
 * 小智自己在前台时悬浮按钮没意义、还会挡住 UI，所以自动隐藏；
 * 退到叮当待机屏 / 其他应用时再浮出来。
 */
public class App extends Application {

    private int resumed = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity a) {
                resumed++;
                if (resumed == 1) FloatEntryService.setOwnAppVisible(true);
            }
            @Override public void onActivityPaused(Activity a) {
                resumed--;
                if (resumed <= 0) {
                    resumed = 0;
                    FloatEntryService.setOwnAppVisible(false);
                }
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
}

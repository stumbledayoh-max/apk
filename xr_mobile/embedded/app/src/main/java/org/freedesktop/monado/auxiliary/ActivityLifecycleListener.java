package org.freedesktop.monado.auxiliary;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** JNI-compatible activity callbacks expected by the pinned Monado runtime. */
public final class ActivityLifecycleListener implements Application.ActivityLifecycleCallbacks {
    private final long nativePtr;
    public ActivityLifecycleListener(long nativePtr) { this.nativePtr = nativePtr; }
    public void registerCallback(Activity activity) { activity.getApplication().registerActivityLifecycleCallbacks(this); }
    public void unregisterCallback(Activity activity) { activity.getApplication().unregisterActivityLifecycleCallbacks(this); }
    private native void nativeOnActivityCreated(long ptr, Activity activity);
    private native void nativeOnActivityStarted(long ptr, Activity activity);
    private native void nativeOnActivityResumed(long ptr, Activity activity);
    private native void nativeOnActivityPaused(long ptr, Activity activity);
    private native void nativeOnActivityStopped(long ptr, Activity activity);
    private native void nativeOnActivitySaveInstanceState(long ptr, Activity activity);
    private native void nativeOnActivityDestroyed(long ptr, Activity activity);
    @Override public void onActivityCreated(Activity activity, Bundle state) { nativeOnActivityCreated(nativePtr, activity); }
    @Override public void onActivityStarted(Activity activity) { nativeOnActivityStarted(nativePtr, activity); }
    @Override public void onActivityResumed(Activity activity) { nativeOnActivityResumed(nativePtr, activity); }
    @Override public void onActivityPaused(Activity activity) { nativeOnActivityPaused(nativePtr, activity); }
    @Override public void onActivityStopped(Activity activity) { nativeOnActivityStopped(nativePtr, activity); }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { nativeOnActivitySaveInstanceState(nativePtr, activity); }
    @Override public void onActivityDestroyed(Activity activity) { nativeOnActivityDestroyed(nativePtr, activity); }
}

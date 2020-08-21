package com.snail.collie.fps;

import android.app.Activity;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Choreographer;

import androidx.annotation.NonNull;

import com.snail.collie.BuildConfig;
import com.snail.collie.Collie;
import com.snail.collie.core.ActivityStack;
import com.snail.collie.core.CollieHandlerThread;
import com.snail.collie.core.ITracker;
import com.snail.collie.core.LooperMonitor;
import com.snail.collie.core.SimpleActivityLifecycleCallbacks;
import com.snail.collie.debug.DebugHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class FpsTracker extends LooperMonitor.LooperDispatchListener implements ITracker {

    private HashSet<ITrackFpsListener> mITrackListeners = new HashSet<>();
    private Handler mHandler;
    private HashMap<Activity, CollectItem> mActivityCollectItemHashMap = new HashMap<>();
    private long mStartTime;
    private static volatile FpsTracker sInstance = null;

    public void addTrackerListener(ITrackFpsListener listener) {
        mITrackListeners.add(listener);
    }

    public void removeTrackerListener(ITrackFpsListener listener) {

        mITrackListeners.remove(listener);
    }


    private FpsTracker() {
        mHandler = new Handler(CollieHandlerThread.getInstance().getHandlerThread().getLooper());
    }

    public static FpsTracker getInstance() {
        if (sInstance == null) {
            synchronized (FpsTracker.class) {
                if (sInstance == null) {
                    sInstance = new FpsTracker();
                }
            }
        }
        return sInstance;
    }

    private Object callbackQueueLock;
    private Object[] callbackQueues;
    private Method addTraversalQueue;
    private Method addInputQueue;
    private Method addAnimationQueue;
    private Choreographer choreographer;
    private long frameIntervalNanos = 16666666;
    public static final int CALLBACK_INPUT = 0;
    public static final int CALLBACK_ANIMATION = 1;
    public static final int CALLBACK_TRAVERSAL = 2;
    private static final String ADD_CALLBACK = "addCallbackLocked";
    private boolean mInDoFrame = false;
    private SimpleActivityLifecycleCallbacks mSimpleActivityLifecycleCallbacks = new SimpleActivityLifecycleCallbacks() {

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
            super.onActivityPaused(activity);
            pauseTrack();
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            super.onActivityResumed(activity);
            resumeTrack();
        }
    };


    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public void dispatchStart() {
        super.dispatchStart();
        mStartTime = SystemClock.uptimeMillis();
//        Log.v("Collie", "start");
    }


    /**
     * Message 内部移除后 ，looper找不到mStartTime归零防止误判
     */

    @Override
    public void dispatchEnd() {
        super.dispatchEnd();
        if (mStartTime > 0) {
            long cost = SystemClock.uptimeMillis() - mStartTime;
//            Log.v("Collie", "end " + cost);
            collectInfoAndDispatch(ActivityStack.getInstance().getTopActivity(), cost, mInDoFrame);
            if (mInDoFrame) {
                addFrameCallBack();
                mInDoFrame = false;
            }
        }
    }

    private void addFrameCallBack() {
        addFrameCallback(CALLBACK_INPUT, new Runnable() {
            @Override
            public void run() {
                mInDoFrame = true;
            }
        }, true);

    }

    private void collectInfoAndDispatch(final Activity activity, final long cost, final boolean inDoFrame) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (activity != null) {
                    CollectItem collectItem = mActivityCollectItemHashMap.get(activity);
                    if (collectItem == null) {
                        collectItem = new CollectItem();
                        collectItem.activity = activity;
                        mActivityCollectItemHashMap.put(activity, collectItem);
                    }
                    collectItem.sumCost += Math.max(16, cost);
                    collectItem.sumFrame++;

                    for (ITrackFpsListener item : mITrackListeners) {
                        if (collectItem.sumFrame > 10) {
                            long averageFps = Math.min(60, collectItem.sumCost > 0 ? collectItem.sumFrame * 1000 / collectItem.sumCost : 60);
                            item.onHandlerMessageCost(cost, cost <= 16 ? 0 : Math.max(1, cost / 16 - 1), inDoFrame, averageFps);
                        }
                    }
                    //   不过度累积
                    if (collectItem.sumFrame > 60) {
                        mActivityCollectItemHashMap.remove(activity);
                    }
                }
            }
        });
    }


    public long getFrameIntervalNanos() {
        return frameIntervalNanos;
    }


    private Method reflectChoreographerMethod(Object instance, String name, Class<?>... argTypes) {
        try {
            Method method = instance.getClass().getDeclaredMethod(name, argTypes);
            method.setAccessible(true);
            return method;
        } catch (Exception e) {

        }
        return null;
    }

    private <T> T reflectObject(Object instance, String name) {
        try {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(instance);
        } catch (Exception e) {
            e.printStackTrace();

        }
        return null;
    }


    /**
     * 监测不同的阶段  Input 、动画、布局
     * 简化处理FPS的时候， 没必要区分的这么细
     **/
    private synchronized void addFrameCallback(int type, Runnable callback, boolean isAddHeader) {

        try {
            synchronized (callbackQueueLock) {
                Method method = null;
                switch (type) {
                    case CALLBACK_INPUT:
                        method = addInputQueue;
                        break;
                    case CALLBACK_ANIMATION:
                        method = addAnimationQueue;
                        break;
                    case CALLBACK_TRAVERSAL:
                        method = addTraversalQueue;
                        break;
                }
                if (null != method) {
                    method.invoke(callbackQueues[type], !isAddHeader ? SystemClock.uptimeMillis() : -1, callback, null);
                }
            }
        } catch (Exception ignored) {

        }
    }

    @Override
    public void destroy() {
        Collie.getInstance().removeActivityLifecycleCallbacks(mSimpleActivityLifecycleCallbacks);
        sInstance = null;
    }

    @Override
    public void startTrack() {
        Collie.getInstance().addActivityLifecycleCallbacks(mSimpleActivityLifecycleCallbacks);
    }

    private void resumeTrack() {
        if (choreographer == null) {
            choreographer = Choreographer.getInstance();
            callbackQueueLock = reflectObject(choreographer, "mLock");
            callbackQueues = reflectObject(choreographer, "mCallbackQueues");
            frameIntervalNanos = reflectObject(choreographer, "mFrameIntervalNanos");
            addInputQueue = reflectChoreographerMethod(callbackQueues[CALLBACK_INPUT], ADD_CALLBACK, long.class, Object.class, Object.class);
            addAnimationQueue = reflectChoreographerMethod(callbackQueues[CALLBACK_ANIMATION], ADD_CALLBACK, long.class, Object.class, Object.class);
            addTraversalQueue = reflectChoreographerMethod(callbackQueues[CALLBACK_TRAVERSAL], ADD_CALLBACK, long.class, Object.class, Object.class);
        }
        LooperMonitor.register(this);
        addFrameCallBack();
    }

    @Override
    public void pauseTrack() {
        LooperMonitor.unregister(this);
        mITrackListeners.clear();
        mStartTime = 0;
    }
}

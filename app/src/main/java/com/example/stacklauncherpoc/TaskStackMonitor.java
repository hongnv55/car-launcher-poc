package com.example.stacklauncherpoc;

import android.app.TaskStackListener;
import android.content.ComponentName;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

/**
 * Event-driven replacement for polling ActivityManager.getTasks().
 *
 * A hosted app can start a follow-up activity on its own, without our ActivityOptions,
 * and ActivityStarter then re-resolves the destination stack and drops the task back on
 * the default display. Reacting to that on a timer means the rogue frame is already on
 * screen; these callbacks fire as AMS makes the change, which is the earliest signal a
 * userspace app can get.
 *
 * TaskStackListener is @hide, hence the compileOnly stub in framework-stubs/.
 */
final class TaskStackMonitor {
    private static final String TAG = "TaskStackMonitor";

    /** onTaskStackChanged() is very chatty; collapse bursts into one reconcile. */
    private static final long STACK_CHANGED_DEBOUNCE_MS = 30;

    interface Callback {
        void onTaskCreated(int taskId, ComponentName component);

        void onTaskMovedToFront(int taskId);

        void onTaskRemoved(int taskId);

        /** Coarse signal: re-check state, don't assume which task moved. */
        void onTaskStackChanged();
    }

    private final Callback callback;
    private final HiddenApiBridge hiddenApis;

    /**
     * Relocation does several blocking Binder calls; doing that on the incoming Binder
     * thread would stall AMS's callback dispatch to every other listener.
     */
    private final HandlerThread workerThread =
            new HandlerThread("pip-task-watchdog", Process.THREAD_PRIORITY_DISPLAY);

    private Handler worker;
    private boolean registered;

    private final Runnable stackChangedRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                callback.onTaskStackChanged();
            } catch (RuntimeException e) {
                Log.e(TAG, "onTaskStackChanged handler failed", e);
            }
        }
    };

    private final TaskStackListener listener = new TaskStackListener() {
        @Override
        public void onTaskCreated(final int taskId, final ComponentName componentName)
                throws RemoteException {
            post(new Runnable() {
                @Override
                public void run() {
                    callback.onTaskCreated(taskId, componentName);
                }
            }, "onTaskCreated");
        }

        @Override
        public void onTaskMovedToFront(final int taskId) throws RemoteException {
            post(new Runnable() {
                @Override
                public void run() {
                    callback.onTaskMovedToFront(taskId);
                }
            }, "onTaskMovedToFront");
        }

        @Override
        public void onTaskRemoved(final int taskId) throws RemoteException {
            post(new Runnable() {
                @Override
                public void run() {
                    callback.onTaskRemoved(taskId);
                }
            }, "onTaskRemoved");
        }

        @Override
        public void onTaskStackChanged() throws RemoteException {
            Handler h = worker;
            if (h == null) {
                return;
            }
            h.removeCallbacks(stackChangedRunnable);
            h.postDelayed(stackChangedRunnable, STACK_CHANGED_DEBOUNCE_MS);
        }
    };

    TaskStackMonitor(HiddenApiBridge hiddenApis, Callback callback) {
        this.hiddenApis = hiddenApis;
        this.callback = callback;
    }

    private void post(final Runnable work, final String what) {
        Handler h = worker;
        if (h == null) {
            return;
        }
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    Log.e(TAG, what + " handler failed", e);
                }
            }
        });
    }

    synchronized void start() {
        if (registered) {
            return;
        }

        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        try {
            hiddenApis.registerTaskStackListener(listener);
            registered = true;
            Log.i(TAG, "TaskStackListener registered");
        } catch (RuntimeException e) {
            worker = null;
            workerThread.quitSafely();
            throw e;
        }
    }

    synchronized void stop() {
        if (!registered) {
            return;
        }
        registered = false;

        try {
            hiddenApis.unregisterTaskStackListener(listener);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to unregister listener", e);
        }

        Handler h = worker;
        if (h != null) {
            h.removeCallbacksAndMessages(null);
        }
        worker = null;
        workerThread.quitSafely();

        Log.i(TAG, "TaskStackListener stopped");
    }
}

package com.example.stacklauncherpoc;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.util.Log;
import android.view.Display;

import java.util.List;

/** Owns exactly one dedicated stack at a time. */
final class PlatformStackController {
    private static final String TAG = "PlatformStackCtl";
    private static final int TASK_QUERY_LIMIT = 100;

    private final HiddenApiBridge hiddenApis;

    PlatformStackController(HiddenApiBridge hiddenApis) {
        this.hiddenApis = hiddenApis;
    }

    int createDedicatedStackOnDefaultDisplay() {
        return hiddenApis.createStackOnDisplay(Display.DEFAULT_DISPLAY);
    }

    void moveTaskToStack(int taskId, int stackId) {
        // false: with ActivityOptions.setAvoidMoveToFront() used at launch time, the
        // launcher stays focused/front, so TaskRecord.reparent() has no reason to
        // route the destination stack through the moveToFront() path that NPEs on a
        // freshly created, still-empty stack.
        hiddenApis.moveTaskToStack(taskId, stackId, false);
    }

    void moveStackToDisplay(int stackId, int displayId) {
        hiddenApis.moveStackToDisplay(stackId, displayId);
    }

    /**
     * Running tasks are returned front-most first. After startActivity(), the newly launched
     * launcher task is therefore normally the first package match.
     */
    int findTopTaskForComponent(ComponentName target) {
        List<ActivityManager.RunningTaskInfo> tasks = hiddenApis.getTasks(TASK_QUERY_LIMIT);
        if (tasks == null) {
            return -1;
        }

        // Prefer an exact component match first.
        for (ActivityManager.RunningTaskInfo task : tasks) {
            if (target.equals(task.topActivity) || target.equals(task.baseActivity)) {
                return task.id;
            }
        }

        // Some apps redirect their launcher Activity immediately. Fall back to package match.
        String packageName = target.getPackageName();
        for (ActivityManager.RunningTaskInfo task : tasks) {
            if ((task.topActivity != null
                    && packageName.equals(task.topActivity.getPackageName()))
                    || (task.baseActivity != null
                    && packageName.equals(task.baseActivity.getPackageName()))) {
                return task.id;
            }
        }

        return -1;
    }

    /**
     * One getTasks() round-trip, shareable across panels: onTaskStackChanged() is chatty,
     * and querying once per panel per event means N Binder calls for the same answer.
     */
    List<ActivityManager.RunningTaskInfo> snapshotTasks() {
        return hiddenApis.getTasks(TASK_QUERY_LIMIT);
    }

    /** Returns the stack the given task currently lives in, or -1 if it's gone. */
    int findStackIdForTask(int taskId) {
        return findStackIdForTask(snapshotTasks(), taskId);
    }

    int findStackIdForTask(List<ActivityManager.RunningTaskInfo> tasks, int taskId) {
        if (tasks == null) {
            return -1;
        }
        for (ActivityManager.RunningTaskInfo task : tasks) {
            if (task.id == taskId) {
                return hiddenApis.getStackId(task);
            }
        }
        return -1;
    }

    void removeStackQuietly(int stackId) {
        if (stackId < 0) {
            return;
        }
        try {
            hiddenApis.removeStack(stackId);
        } catch (RuntimeException e) {
            Log.w(TAG, "removeStack(" + stackId + ") failed", e);
        }
    }
}

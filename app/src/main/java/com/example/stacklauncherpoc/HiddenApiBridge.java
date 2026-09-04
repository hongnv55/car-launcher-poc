package com.example.stacklauncherpoc;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.os.IBinder;
import android.view.InputEvent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, cached reflection bridge for Android 9 hidden APIs.
 *
 * Why reflection? It keeps this project buildable with the stock API-28 android.jar.
 * At runtime the APK MUST be signed with the same platform certificate as the ROM.
 *
 * Pie no longer has ActivityOptions.setLaunchStackId() or InputEvent.setDisplayId():
 * both were added in later releases. The primitives actually present on Pie are
 * IActivityManager.{createStackOnDisplay,moveTaskToStack,moveStackToDisplay,removeStack}
 * for window placement, and IInputManager.createInputForwarder(displayId) ->
 * IInputForwarder.forwardEvent(event) for routing input to a specific display.
 */
final class HiddenApiBridge {
    private final Object activityManagerService;
    private final Method createStackOnDisplay;
    private final Method moveTaskToStack;
    private final Method moveStackToDisplay;
    private final Method removeStack;
    private final Method getTasks;
    private final Method setAvoidMoveToFront;
    private final Field runningTaskInfoStackId;
    private final Method moveTaskToFront;
    private final Method registerTaskStackListener;
    private final Method unregisterTaskStackListener;

    private final Object inputManagerService;
    private final Method createInputForwarder;
    private final Method forwardEvent;
    private final Map<Integer, Object> forwarders = new HashMap<>();

    HiddenApiBridge() {
        try {
            Method getService = ActivityManager.class.getDeclaredMethod("getService");
            getService.setAccessible(true);
            activityManagerService = getService.invoke(null);

            Class<?> iActivityManager = Class.forName("android.app.IActivityManager");
            createStackOnDisplay = iActivityManager.getMethod("createStackOnDisplay", int.class);
            moveTaskToStack = iActivityManager.getMethod(
                    "moveTaskToStack", int.class, int.class, boolean.class);
            moveStackToDisplay = iActivityManager.getMethod(
                    "moveStackToDisplay", int.class, int.class);
            removeStack = iActivityManager.getMethod("removeStack", int.class);
            getTasks = iActivityManager.getMethod("getTasks", int.class);

            setAvoidMoveToFront = ActivityOptions.class.getDeclaredMethod("setAvoidMoveToFront");
            setAvoidMoveToFront.setAccessible(true);

            runningTaskInfoStackId = ActivityManager.RunningTaskInfo.class.getDeclaredField("stackId");
            runningTaskInfoStackId.setAccessible(true);

            moveTaskToFront = iActivityManager.getMethod(
                    "moveTaskToFront", int.class, int.class, android.os.Bundle.class);

            // Reflection rather than a direct call: IActivityManager itself is not in
            // android.jar, so registerTaskStackListener(ITaskStackListener) cannot be
            // named at compile time even with the TaskStackListener stub available.
            Class<?> iTaskStackListener = Class.forName("android.app.ITaskStackListener");
            registerTaskStackListener = iActivityManager.getMethod(
                    "registerTaskStackListener", iTaskStackListener);
            unregisterTaskStackListener = iActivityManager.getMethod(
                    "unregisterTaskStackListener", iTaskStackListener);

            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getServiceByName = serviceManagerClass.getDeclaredMethod(
                    "getService", String.class);
            getServiceByName.setAccessible(true);
            IBinder inputBinder = (IBinder) getServiceByName.invoke(null, "input");

            Class<?> iInputManagerClass = Class.forName("android.hardware.input.IInputManager");
            Class<?> stubClass = Class.forName("android.hardware.input.IInputManager$Stub");
            Method asInterface = stubClass.getDeclaredMethod("asInterface", IBinder.class);
            asInterface.setAccessible(true);
            inputManagerService = asInterface.invoke(null, inputBinder);

            createInputForwarder = iInputManagerClass.getDeclaredMethod(
                    "createInputForwarder", int.class);
            createInputForwarder.setAccessible(true);

            Class<?> inputForwarderClass = Class.forName("android.app.IInputForwarder");
            forwardEvent = inputForwarderClass.getDeclaredMethod("forwardEvent", InputEvent.class);
            forwardEvent.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Android 9 hidden API bootstrap failed. Verify platform signing and API level.", e);
        }
    }

    int createStackOnDisplay(int displayId) {
        return (Integer) invoke(createStackOnDisplay, activityManagerService, displayId);
    }

    void moveTaskToStack(int taskId, int stackId, boolean toTop) {
        invoke(moveTaskToStack, activityManagerService, taskId, stackId, toTop);
    }

    void moveStackToDisplay(int stackId, int displayId) {
        invoke(moveStackToDisplay, activityManagerService, stackId, displayId);
    }

    void removeStack(int stackId) {
        invoke(removeStack, activityManagerService, stackId);
    }

    @SuppressWarnings("unchecked")
    List<ActivityManager.RunningTaskInfo> getTasks(int maxNum) {
        return (List<ActivityManager.RunningTaskInfo>) invoke(
                getTasks, activityManagerService, maxNum);
    }

    /** RunningTaskInfo.stackId is @hide on this SDK, so it isn't visible at compile time. */
    int getStackId(ActivityManager.RunningTaskInfo task) {
        try {
            return runningTaskInfoStackId.getInt(task);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read RunningTaskInfo.stackId", e);
        }
    }

    /**
     * Brings a task to front/focus. Used to re-focus our own launcher task before
     * re-reparenting a drifted hosted task: TaskRecord.reparent() routes the destination
     * stack through the NPE-prone moveToFront() path whenever the task being moved is
     * currently focused/front, which it will be right after it drifted onto the default
     * display and took over the screen.
     */
    void moveTaskToFront(int taskId) {
        invoke(moveTaskToFront, activityManagerService, taskId, 0, null);
    }

    /** listener must be an android.app.TaskStackListener (an ITaskStackListener Binder). */
    void registerTaskStackListener(Object listener) {
        invoke(registerTaskStackListener, activityManagerService, listener);
    }

    void unregisterTaskStackListener(Object listener) {
        invoke(unregisterTaskStackListener, activityManagerService, listener);
    }

    /**
     * Keeps the caller (our launcher) focused/front while the target task is created.
     * Avoids TaskRecord.reparent() later treating the new task as "was focused/front"
     * and routing the destination stack through ActivityStack.moveToFront(), which on
     * this build NPEs when the destination is a freshly created, still-empty stack
     * (splitScreenPrimaryStack is null on that code path).
     */
    void setAvoidMoveToFront(ActivityOptions options) {
        invoke(setAvoidMoveToFront, options);
    }

    /** Forwards an input event to the given display via IInputManager.createInputForwarder(). */
    boolean forward(InputEvent event, int displayId) {
        Object forwarder = forwarders.get(displayId);
        if (forwarder == null) {
            forwarder = invoke(createInputForwarder, inputManagerService, displayId);
            forwarders.put(displayId, forwarder);
        }
        return (Boolean) invoke(forwardEvent, forwarder, event);
    }

    private static Object invoke(Method method, Object receiver, Object... args) {
        try {
            return method.invoke(receiver, args);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access hidden API " + method, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("Hidden API failed: " + method, cause);
        }
    }
}

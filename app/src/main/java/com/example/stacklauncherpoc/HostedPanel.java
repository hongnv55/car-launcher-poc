package com.example.stacklauncherpoc;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;

import java.util.List;

/**
 * One PIP panel: a SurfaceView, the VirtualDisplay behind it, and the third-party task
 * currently reparented onto that display.
 *
 * All state here used to live as scalar fields on MainActivity, which only worked for a
 * single panel. Everything is main-thread-confined.
 */
final class HostedPanel implements VirtualDisplayHost.Listener {
    private static final int INVALID_STACK_ID = -1;
    private static final int INVALID_TASK_ID = -1;
    private static final int TASK_FIND_RETRY_MS = 50;
    private static final int TASK_FIND_MAX_ATTEMPTS = 20;
    private static final int HOST_SLOT_RETRY_MS = 100;
    private static final int HOST_SLOT_MAX_ATTEMPTS = 40;

    /**
     * Launching and attaching must not interleave between panels: findTopTaskForComponent()
     * resolves by component and takes the front-most match, so two overlapping launches can
     * hand a panel the other panel's task.
     */
    interface Coordinator {
        boolean acquireHostSlot(HostedPanel panel);

        void releaseHostSlot(HostedPanel panel);

        void onPickerRequested(HostedPanel panel);
    }

    private final int slot;
    private final String tag;
    private final Activity activity;
    private final HiddenApiBridge hiddenApis;
    private final PlatformStackController stackController;
    private final AppSelectionStore selectionStore;
    private final Handler handler;
    private final Coordinator coordinator;

    private final SurfaceView hostSurface;
    private final TextView appTitle;
    private final View emptyState;

    private final VirtualDisplayHost displayHost;
    private final VirtualDisplayInputForwarder inputForwarder;

    private ComponentName selectedComponent;

    private int activeStackId = INVALID_STACK_ID;
    private int hostedTaskId = INVALID_TASK_ID;
    private int hostedDisplayId = INVALID_STACK_ID;
    private ComponentName hostedTarget;

    private ComponentName hostRequestTarget;
    private int hostRequestDisplayId = INVALID_STACK_ID;
    private boolean hostingInFlight;
    private boolean holdsHostSlot;
    /** Task id handed to us by onTaskCreated() for the launch currently in flight. */
    private int pendingTaskId = INVALID_TASK_ID;

    private int generation;
    private boolean destroyed;

    HostedPanel(int slot,
            Activity activity,
            HiddenApiBridge hiddenApis,
            PlatformStackController stackController,
            AppSelectionStore selectionStore,
            Handler handler,
            Coordinator coordinator,
            SurfaceView hostSurface,
            TextView appTitle,
            View emptyState,
            View addButton,
            View emptyAddButton,
            View backButton) {
        this.slot = slot;
        this.tag = "PipPanel" + slot;
        this.activity = activity;
        this.hiddenApis = hiddenApis;
        this.stackController = stackController;
        this.selectionStore = selectionStore;
        this.handler = handler;
        this.coordinator = coordinator;
        this.hostSurface = hostSurface;
        this.appTitle = appTitle;
        this.emptyState = emptyState;

        displayHost = new VirtualDisplayHost(activity, hostSurface, this, "Panel" + slot);
        inputForwarder = new VirtualDisplayInputForwarder(displayHost, hiddenApis);

        hostSurface.setClickable(true);
        hostSurface.setOnTouchListener(inputForwarder);

        View.OnClickListener pick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                coordinator.onPickerRequested(HostedPanel.this);
            }
        };
        addButton.setOnClickListener(pick);
        emptyAddButton.setOnClickListener(pick);

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    inputForwarder.sendBack();
                } catch (RuntimeException e) {
                    Log.e(tag, "Back injection failed", e);
                }
            }
        });
    }

    int getSlot() {
        return slot;
    }

    /** Restores the remembered selection, dropping it if the app was uninstalled. */
    void loadSelection() {
        selectedComponent = selectionStore.load(slot);
        if (selectedComponent != null && !isComponentInstalled(selectedComponent)) {
            selectionStore.clear(slot);
            selectedComponent = null;
        }
        refreshUi();
    }

    void select(ComponentName componentName) {
        selectedComponent = componentName;
        selectionStore.save(slot, componentName);
        refreshUi();
        if (displayHost.isReady()) {
            hostSelectedApp();
        }
    }

    @Override
    public void onVirtualDisplayReady(int displayId, int width, int height) {
        if (!destroyed && selectedComponent != null) {
            hostSelectedApp();
        }
    }

    boolean ownsTask(int taskId) {
        return taskId != INVALID_TASK_ID && taskId == hostedTaskId;
    }

    /**
     * Attributes a freshly created task to this panel's in-flight launch.
     *
     * Without this the attach step has to guess: findTopTaskForComponent() resolves by
     * component and takes the front-most match, which is wrong the moment the app opens its
     * task under a different activity than the one we launched (we launch Email's
     * .activity.Welcome, the task is created as email2.ui.MailActivityEmail) and ambiguous
     * if both panels ever host the same package. onTaskCreated() states the task id
     * outright. Matching is per-package for that same reason.
     *
     * Hosting is serialized through the coordinator's host slot, so at most one panel has a
     * launch in flight and the attribution cannot be stolen by the other panel.
     */
    void claimCreatedTask(int taskId, ComponentName component) {
        if (destroyed || !hostingInFlight || hostRequestTarget == null || component == null) {
            return;
        }
        if (!hostRequestTarget.getPackageName().equals(component.getPackageName())) {
            return;
        }
        if (pendingTaskId == taskId) {
            return;
        }
        pendingTaskId = taskId;
        Log.d(tag, "claimed created task " + taskId + " for " + component.flattenToShortString());
    }

    private void hostSelectedApp() {
        hostSelectedApp(0);
    }

    private void hostSelectedApp(int slotAttempt) {
        if (destroyed || selectedComponent == null || !displayHost.isReady()) {
            return;
        }

        // Picking an app makes hostSurface visible, which creates the VirtualDisplay and
        // fires onVirtualDisplayReady -- so both that callback and the picker callback ask
        // to host the same selection, milliseconds apart, and the app would be launched
        // twice (two tasks, two stacks). Reject the duplicate. The displayId is part of the
        // check so a genuinely recreated VirtualDisplay still re-hosts.
        if (selectedComponent.equals(hostRequestTarget)
                && hostRequestDisplayId == displayHost.getDisplayId()
                && (hostingInFlight || hostedTaskId != INVALID_TASK_ID)) {
            return;
        }

        if (!holdsHostSlot) {
            if (!coordinator.acquireHostSlot(this)) {
                if (slotAttempt >= HOST_SLOT_MAX_ATTEMPTS) {
                    Log.w(tag, "Gave up waiting for the host slot");
                    return;
                }
                final int next = slotAttempt + 1;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        hostSelectedApp(next);
                    }
                }, HOST_SLOT_RETRY_MS);
                return;
            }
            holdsHostSlot = true;
        }

        // Cancel any in-flight task discovery from a previous selection.
        generation++;
        cleanupHostedStack();

        final int currentGeneration = generation;
        final ComponentName target = selectedComponent;
        final int targetDisplayId = displayHost.getDisplayId();

        hostRequestTarget = target;
        hostRequestDisplayId = targetDisplayId;
        hostingInFlight = true;

        try {
            // Pie no longer has ActivityOptions.setLaunchStackId(). Launch the app as a task
            // first, then reparent that task into a dedicated stack. avoidMoveToFront keeps
            // this launcher focused/front so the later reparent doesn't route the destination
            // stack through ActivityStack.moveToFront() (NPEs on a freshly created stack).
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(target);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            ActivityOptions options = ActivityOptions.makeBasic();
            hiddenApis.setAvoidMoveToFront(options);

            activity.startActivity(intent, options.toBundle());
            attachLaunchedTask(target, targetDisplayId, currentGeneration, 0);
        } catch (RuntimeException e) {
            finishHostAttempt();
            Log.e(tag, "Unable to launch " + target.getPackageName(), e);
        }
    }

    private void attachLaunchedTask(
            final ComponentName target, final int targetDisplayId, final int gen, int attempt) {
        if (destroyed || gen != generation) {
            return;
        }

        int taskId = pendingTaskId;
        if (taskId == INVALID_TASK_ID) {
            // onTaskCreated() hasn't landed (or the app reused an existing task, which
            // fires no creation callback): fall back to resolving by component.
            try {
                taskId = stackController.findTopTaskForComponent(target);
            } catch (RuntimeException e) {
                finishHostAttempt();
                Log.e(tag, "Unable to query task for " + target.getPackageName(), e);
                return;
            }
        }

        if (taskId < 0) {
            if (attempt >= TASK_FIND_MAX_ATTEMPTS) {
                finishHostAttempt();
                Log.e(tag, "Unable to find launched task for " + target.getPackageName());
                return;
            }

            final int next = attempt + 1;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    attachLaunchedTask(target, targetDisplayId, gen, next);
                }
            }, TASK_FIND_RETRY_MS);
            return;
        }

        int newStackId = INVALID_STACK_ID;
        try {
            // 1) Create a dedicated standard stack on display 0.
            newStackId = stackController.createDedicatedStackOnDefaultDisplay();
            if (newStackId < 0) {
                throw new IllegalStateException("createStackOnDisplay returned " + newStackId);
            }

            // Track it immediately so lifecycle cleanup can always remove it.
            activeStackId = newStackId;

            // A freshly created stack can land at the front of display 0's stack order
            // regardless of setAvoidMoveToFront on the launch options, which still makes
            // TaskRecord.reparent() treat the move as wasFront=true and NPE in
            // ActivityStack.moveToFront(). Force our own task back to front immediately
            // before reparenting so that check reliably reads false.
            hiddenApis.moveTaskToFront(activity.getTaskId());

            // 2) Reparent the selected app task into our dedicated stack WHILE the
            //    stack is still on the default display. getReparentTargetStack()
            //    only enforces mSupportsMultiDisplay when the target stack already
            //    lives on a non-default display, so doing this first bypasses that
            //    gate entirely (which we cannot rely on the real hardware to pass).
            stackController.moveTaskToStack(taskId, newStackId);

            // 3) Only now move the populated stack onto the Surface-backed VirtualDisplay.
            stackController.moveStackToDisplay(newStackId, targetDisplayId);

            hostedTaskId = taskId;
            hostedDisplayId = targetDisplayId;
            hostedTarget = target;

            Log.i(tag, "Hosted " + target.flattenToShortString()
                    + " task=" + taskId
                    + " stack=" + activeStackId
                    + " display=" + targetDisplayId);
        } catch (RuntimeException e) {
            if (newStackId >= 0) {
                stackController.removeStackQuietly(newStackId);
            }
            activeStackId = INVALID_STACK_ID;
            // On a device without FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS,
            // ActivityStackSupervisor.getReparentTargetStack() throws here. The
            // target hardware already declares that feature, so this path never
            // triggers there; log only, no user-facing toast for this test rig.
            Log.e(tag, "Unable to host " + target.getPackageName(), e);
        } finally {
            finishHostAttempt();
        }
    }

    /** Ends the launch phase: clears the in-flight flag and hands the slot back. */
    private void finishHostAttempt() {
        hostingInFlight = false;
        pendingTaskId = INVALID_TASK_ID;
        if (holdsHostSlot) {
            holdsHostSlot = false;
            coordinator.releaseHostSlot(this);
        }
    }

    /**
     * Checks whether the hosted task still sits in the stack we put on the VirtualDisplay,
     * and drags it back if not.
     *
     * Any app the launcher hosts can, on its own, start a follow-up activity in the same
     * task (Music's artist browser, Email's account setup wizard, ...) without our
     * ActivityOptions. ActivityStarter then re-resolves the destination stack and the whole
     * task is silently reparented back to the default display.
     */
    void checkDrift() {
        checkDrift(null);
    }

    /** @param tasks a shared getTasks() snapshot, or null to fetch one. */
    void checkDrift(List<ActivityManager.RunningTaskInfo> tasks) {
        if (destroyed || hostedTaskId == INVALID_TASK_ID || hostedTarget == null) {
            return;
        }

        try {
            int currentStackId = tasks == null
                    ? stackController.findStackIdForTask(hostedTaskId)
                    : stackController.findStackIdForTask(tasks, hostedTaskId);
            if (currentStackId < 0) {
                // Task is gone (user backed out of it, or it was killed).
                cleanupHostedStack();
                return;
            }

            if (currentStackId != activeStackId) {
                Log.w(tag, hostedTarget.flattenToShortString()
                        + " drifted off the hosted display (stack " + currentStackId
                        + " != " + activeStackId + "); re-attaching");
                reattachDriftedTask();
            }
        } catch (RuntimeException e) {
            Log.e(tag, "Drift check failed for " + hostedTarget.getPackageName(), e);
        }
    }

    private void reattachDriftedTask() {
        final ComponentName target = hostedTarget;
        int oldStackId = activeStackId;
        int newStackId = INVALID_STACK_ID;
        try {
            // The drifted task just took over the default display, so it is now
            // focused/front. Reclaim focus first so the upcoming moveTaskToStack()
            // doesn't see wasFocused/wasFront=true and route through the NPE-prone
            // ActivityStack.moveToFront() path again.
            hiddenApis.moveTaskToFront(activity.getTaskId());

            newStackId = stackController.createDedicatedStackOnDefaultDisplay();
            if (newStackId < 0) {
                throw new IllegalStateException("createStackOnDisplay returned " + newStackId);
            }
            activeStackId = newStackId;

            stackController.moveTaskToStack(hostedTaskId, newStackId);
            stackController.moveStackToDisplay(newStackId, hostedDisplayId);

            Log.i(tag, "Re-attached " + target.flattenToShortString()
                    + " task=" + hostedTaskId
                    + " stack=" + activeStackId
                    + " display=" + hostedDisplayId);
        } catch (RuntimeException e) {
            if (newStackId >= 0) {
                stackController.removeStackQuietly(newStackId);
            }
            activeStackId = oldStackId;
            Log.e(tag, "Unable to re-attach " + target.getPackageName(), e);
        }
    }

    void cleanupHostedStack() {
        hostedTaskId = INVALID_TASK_ID;
        hostedTarget = null;
        // Drop the request markers too, so selecting the same app again after the task
        // went away is not mistaken for a duplicate trigger.
        hostRequestTarget = null;
        hostRequestDisplayId = INVALID_STACK_ID;
        hostingInFlight = false;
        pendingTaskId = INVALID_TASK_ID;

        if (activeStackId != INVALID_STACK_ID) {
            stackController.removeStackQuietly(activeStackId);
            activeStackId = INVALID_STACK_ID;
        }
    }

    private boolean isComponentInstalled(ComponentName componentName) {
        try {
            activity.getPackageManager().getActivityInfo(componentName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private CharSequence getSelectedLabel() {
        if (selectedComponent == null) {
            return "No app selected";
        }
        try {
            PackageManager pm = activity.getPackageManager();
            ActivityInfo info = pm.getActivityInfo(selectedComponent, 0);
            CharSequence label = info.loadLabel(pm);
            return label != null ? label : selectedComponent.getPackageName();
        } catch (PackageManager.NameNotFoundException e) {
            return selectedComponent.getPackageName();
        }
    }

    private void refreshUi() {
        boolean hasApp = selectedComponent != null;
        appTitle.setText(getSelectedLabel());
        emptyState.setVisibility(hasApp ? View.GONE : View.VISIBLE);
        hostSurface.setVisibility(hasApp ? View.VISIBLE : View.INVISIBLE);
    }

    void destroy() {
        destroyed = true;
        generation++;

        // Destroy WindowManager content first; then destroy the display target.
        cleanupHostedStack();
        finishHostAttempt();

        hostSurface.setOnTouchListener(null);
        displayHost.close();
    }
}

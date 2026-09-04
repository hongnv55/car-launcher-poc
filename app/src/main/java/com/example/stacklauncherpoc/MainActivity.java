package com.example.stacklauncherpoc;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity implements HostedPanel.Coordinator {
    private static final String TAG = "StackLauncherPoc";
    private static final int SAFETY_POLL_INTERVAL_MS = 3000;

    private HiddenApiBridge hiddenApis;
    private PlatformStackController stackController;
    private AppSelectionStore selectionStore;
    private TaskStackMonitor taskStackMonitor;

    private final List<HostedPanel> panels = new ArrayList<>();
    private final Handler hostHandler = new Handler(Looper.getMainLooper());

    /** Only one panel may be in its launch/attach phase at a time. */
    private HostedPanel hostSlotOwner;

    private AlertDialog pickerDialog;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            hiddenApis = new HiddenApiBridge();
        } catch (RuntimeException e) {
            showFatalPlatformError(e);
            return;
        }

        stackController = new PlatformStackController(hiddenApis);
        selectionStore = new AppSelectionStore(this);

        panels.add(createPanel(0,
                R.id.hostSurface0, R.id.appTitle0, R.id.emptyState0,
                R.id.addButton0, R.id.emptyAddButton0, R.id.backButton0));
        panels.add(createPanel(1,
                R.id.hostSurface1, R.id.appTitle1, R.id.emptyState1,
                R.id.addButton1, R.id.emptyAddButton1, R.id.backButton1));

        startTaskStackMonitor();

        for (HostedPanel panel : panels) {
            panel.loadSelection();
        }

        scheduleSafetyNetPoll();
    }

    private HostedPanel createPanel(int slot, int surfaceId, int titleId, int emptyStateId,
            int addButtonId, int emptyAddButtonId, int backButtonId) {
        return new HostedPanel(
                slot,
                this,
                hiddenApis,
                stackController,
                selectionStore,
                hostHandler,
                this,
                (SurfaceView) findViewById(surfaceId),
                (TextView) findViewById(titleId),
                findViewById(emptyStateId),
                findViewById(addButtonId),
                findViewById(emptyAddButtonId),
                findViewById(backButtonId));
    }

    // ---------------------------------------------------------------- Coordinator

    @Override
    public boolean acquireHostSlot(HostedPanel panel) {
        if (hostSlotOwner == null || hostSlotOwner == panel) {
            hostSlotOwner = panel;
            return true;
        }
        return false;
    }

    @Override
    public void releaseHostSlot(HostedPanel panel) {
        if (hostSlotOwner == panel) {
            hostSlotOwner = null;
        }
    }

    @Override
    public void onPickerRequested(final HostedPanel panel) {
        if (destroyed || hiddenApis == null) {
            return;
        }
        if (pickerDialog != null) {
            pickerDialog.dismiss();
        }
        pickerDialog = AppPickerDialog.show(this, new AppPickerDialog.Callback() {
            @Override
            public void onAppSelected(ComponentName componentName, CharSequence label) {
                panel.select(componentName);
            }
        });
        pickerDialog.setOnDismissListener(dialog -> pickerDialog = null);
    }

    // ------------------------------------------------------------- Task tracking

    /**
     * Callbacks arrive on a worker thread; every handler hops to the main thread because
     * all panel state is main-thread-confined.
     */
    private void startTaskStackMonitor() {
        taskStackMonitor = new TaskStackMonitor(hiddenApis, new TaskStackMonitor.Callback() {
            @Override
            public void onTaskCreated(final int taskId, final ComponentName component) {
                hostHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // Offer the new task to whichever panel is mid-launch for that
                        // package. A task created while no panel is launching belongs to
                        // the DocumentsUI-style case (the app abandons the task we hosted
                        // and spawns another one), which no panel claims yet.
                        for (HostedPanel panel : panels) {
                            panel.claimCreatedTask(taskId, component);
                        }
                    }
                });
            }

            @Override
            public void onTaskMovedToFront(final int taskId) {
                hostHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        HostedPanel owner = findPanelForTask(taskId);
                        if (owner != null) {
                            owner.checkDrift();
                        }
                    }
                });
            }

            @Override
            public void onTaskRemoved(final int taskId) {
                hostHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        HostedPanel owner = findPanelForTask(taskId);
                        if (owner != null) {
                            owner.cleanupHostedStack();
                        }
                    }
                });
            }

            @Override
            public void onTaskStackChanged() {
                hostHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        checkAllPanelsForDrift();
                    }
                });
            }
        });

        try {
            taskStackMonitor.start();
        } catch (RuntimeException e) {
            // Losing the listener degrades to the safety-net poll rather than breaking
            // hosting outright, so this is not fatal.
            Log.e(TAG, "TaskStackListener unavailable; relying on the safety-net poll", e);
            taskStackMonitor = null;
        }
    }

    private HostedPanel findPanelForTask(int taskId) {
        for (HostedPanel panel : panels) {
            if (panel.ownsTask(taskId)) {
                return panel;
            }
        }
        return null;
    }

    private void checkAllPanelsForDrift() {
        if (destroyed || stackController == null) {
            return;
        }
        // One getTasks() call for all panels: onTaskStackChanged() is chatty, and a query
        // per panel would issue N Binder round-trips for the same answer.
        List<ActivityManager.RunningTaskInfo> snapshot;
        try {
            snapshot = stackController.snapshotTasks();
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to snapshot running tasks", e);
            return;
        }
        for (HostedPanel panel : panels) {
            panel.checkDrift(snapshot);
        }
    }

    /**
     * Backstop for drift the listener callbacks don't surface. Deliberately slow: the
     * callbacks are the primary signal, so this only needs to catch the pathological case
     * without keeping the CPU busy on an always-on head unit.
     */
    private void scheduleSafetyNetPoll() {
        hostHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                checkAllPanelsForDrift();
                scheduleSafetyNetPoll();
            }
        }, SAFETY_POLL_INTERVAL_MS);
    }

    // ------------------------------------------------------------------ Lifecycle

    private void showFatalPlatformError(Throwable error) {
        Log.e(TAG, "Platform API bootstrap failed", error);
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        String detail = cause.getMessage();
        Toast.makeText(this,
                "Platform API unavailable: " + cause.getClass().getSimpleName()
                        + (detail == null ? "" : " - " + detail),
                Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        hostHandler.removeCallbacksAndMessages(null);

        if (taskStackMonitor != null) {
            taskStackMonitor.stop();
            taskStackMonitor = null;
        }

        if (pickerDialog != null) {
            pickerDialog.dismiss();
            pickerDialog = null;
        }

        for (HostedPanel panel : panels) {
            panel.destroy();
        }
        panels.clear();

        hostSlotOwner = null;
        stackController = null;
        hiddenApis = null;
        selectionStore = null;
        super.onDestroy();
    }
}

package com.example.stacklauncherpoc;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/** Owns one SurfaceHolder callback and one Surface-backed VirtualDisplay. */
final class VirtualDisplayHost implements SurfaceHolder.Callback, AutoCloseable {
    interface Listener {
        void onVirtualDisplayReady(int displayId, int width, int height);
    }

    private static final String TAG = "VirtualDisplayHost";
    private static final String DISPLAY_NAME = "StackLauncherPanel";

    private final SurfaceView surfaceView;
    private final DisplayManager displayManager;
    private final Listener listener;
    private final int densityDpi;
    /** Distinguishes each panel's display in dumpsys/logs. */
    private final String displayName;

    private VirtualDisplay virtualDisplay;
    private int width;
    private int height;
    private boolean closed;

    VirtualDisplayHost(Context context, SurfaceView surfaceView, Listener listener, String label) {
        Context appContext = context.getApplicationContext();
        this.surfaceView = surfaceView;
        this.listener = listener;
        this.displayName = DISPLAY_NAME + "-" + label;
        this.displayManager = (DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);

        DisplayMetrics metrics = appContext.getResources().getDisplayMetrics();
        densityDpi = metrics.densityDpi;

        surfaceView.getHolder().addCallback(this);
    }

    boolean isReady() {
        return !closed && virtualDisplay != null && virtualDisplay.getDisplay() != null;
    }

    int getDisplayId() {
        return isReady() ? virtualDisplay.getDisplay().getDisplayId() : -1;
    }

    int getWidth() {
        return width;
    }

    int getHeight() {
        return height;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // surfaceChanged() supplies the usable dimensions.
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int newWidth, int newHeight) {
        if (closed || newWidth <= 0 || newHeight <= 0) {
            return;
        }

        Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) {
            return;
        }

        width = newWidth;
        height = newHeight;

        if (virtualDisplay == null) {
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

            virtualDisplay = displayManager.createVirtualDisplay(
                    displayName,
                    width,
                    height,
                    densityDpi,
                    surface,
                    flags);

            if (virtualDisplay == null) {
                throw new IllegalStateException("createVirtualDisplay returned null");
            }
            Log.i(TAG, "Created " + displayName + " display=" + getDisplayId()
                    + " size=" + width + "x" + height + " dpi=" + densityDpi);
        } else {
            // Avoid recreating the logical display on ordinary SurfaceView churn.
            virtualDisplay.setSurface(surface);
            virtualDisplay.resize(width, height, densityDpi);
        }

        listener.onVirtualDisplayReady(getDisplayId(), width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (virtualDisplay != null) {
            // Keep logical display alive; just detach the destroyed BufferQueue surface.
            virtualDisplay.setSurface(null);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        surfaceView.getHolder().removeCallback(this);
        if (virtualDisplay != null) {
            virtualDisplay.setSurface(null);
            virtualDisplay.release();
            virtualDisplay = null;
        }
    }
}

package com.example.stacklauncherpoc;

import android.graphics.Matrix;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/** Forwards panel touch/back input to the hosted window on the VirtualDisplay. */
final class VirtualDisplayInputForwarder implements View.OnTouchListener {
    private final VirtualDisplayHost displayHost;
    private final HiddenApiBridge hiddenApis;
    private final Matrix transform = new Matrix();

    VirtualDisplayInputForwarder(VirtualDisplayHost displayHost, HiddenApiBridge hiddenApis) {
        this.displayHost = displayHost;
        this.hiddenApis = hiddenApis;
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (!displayHost.isReady() || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return true;
        }

        MotionEvent copy = MotionEvent.obtain(event);
        try {
            float sx = displayHost.getWidth() / (float) view.getWidth();
            float sy = displayHost.getHeight() / (float) view.getHeight();

            transform.reset();
            transform.setScale(sx, sy);
            copy.transform(transform);
            copy.setSource(InputDevice.SOURCE_TOUCHSCREEN);

            hiddenApis.forward(copy, displayHost.getDisplayId());
        } finally {
            copy.recycle();
        }
        return true;
    }

    void sendBack() {
        if (!displayHost.isReady()) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(
                now, now,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_BACK,
                0,
                0,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_FROM_SYSTEM,
                InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);

        hiddenApis.forward(down, displayHost.getDisplayId());
        hiddenApis.forward(up, displayHost.getDisplayId());
    }
}

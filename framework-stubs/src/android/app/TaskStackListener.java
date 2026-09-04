package android.app;

import android.content.ComponentName;
import android.os.RemoteException;

/**
 * COMPILE-TIME STUB ONLY -- never packaged into the APK.
 *
 * The real android.app.TaskStackListener is @hide, so it is absent from the public
 * API-28 android.jar. It does exist at runtime (it ships inside boot-framework.vdex,
 * which is why /system/framework/framework.jar is only a 183-byte placeholder and
 * cannot be pulled for compileOnly use).
 *
 * This stub exists purely so javac can type-check `new TaskStackListener() { ... }`.
 * At runtime the class is resolved from the boot image, and our anonymous subclass
 * links against the real one -- overriding works because Java dispatch matches on
 * method name + descriptor, which the signatures below reproduce exactly.
 *
 * The signatures were taken from a live reflection dump of android.app.ITaskStackListener
 * on this exact build (sdk_phone_x86 / 9 / PSR1.180720.012), so they are authoritative
 * for the target image rather than guessed from an AOSP tag. Only the callbacks this
 * project overrides are declared; the real class supplies no-op bodies for the rest.
 *
 * Do NOT add fields, constructors with arguments, or a superclass here: the real class
 * extends ITaskStackListener.Stub (a Binder), and declaring a conflicting hierarchy
 * would only mislead readers -- nothing here reaches the device.
 */
public class TaskStackListener {

    public void onTaskStackChanged() throws RemoteException {
    }

    public void onTaskCreated(int taskId, ComponentName componentName) throws RemoteException {
    }

    public void onTaskMovedToFront(int taskId) throws RemoteException {
    }

    public void onTaskRemoved(int taskId) throws RemoteException {
    }
}

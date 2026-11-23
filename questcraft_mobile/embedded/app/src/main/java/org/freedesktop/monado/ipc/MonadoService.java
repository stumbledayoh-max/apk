package org.freedesktop.monado.ipc;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/** Minimal same-package host for the pinned Monado IPC implementation. */
public final class MonadoService extends Service {
    private Object implementation;

    @Override public void onCreate() {
        super.onCreate();
        try {
            Class<?> type = Class.forName("org.freedesktop.monado.ipc.MonadoImpl");
            implementation = type.getConstructor(android.content.Context.class).newInstance(this);
        } catch (ReflectiveOperationException | LinkageError error) {
            Log.e("QuestCraftRuntime", "Could not start embedded Monado", error);
            stopSelf();
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return implementation instanceof IBinder ? (IBinder) implementation : null;
    }

    @Override public void onDestroy() {
        if (implementation != null) {
            try {
                implementation.getClass().getMethod("shutdown").invoke(implementation);
            } catch (ReflectiveOperationException | LinkageError error) {
                Log.e("QuestCraftRuntime", "Could not stop embedded Monado", error);
            }
        }
        implementation = null;
        super.onDestroy();
    }
}

package dev.a2dprouter.system;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** Owns the Modern Xposed service connection for the configuration UI. */
public final class ModuleApplication extends Application
        implements XposedServiceHelper.OnServiceListener {
    interface ServiceStateListener {
        void onServiceStateChanged(XposedService service);
    }

    private static final CopyOnWriteArraySet<ServiceStateListener> LISTENERS =
            new CopyOnWriteArraySet<>();
    private static volatile XposedService service;

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        XposedServiceHelper.registerListener(this);
    }

    static void addServiceStateListener(ServiceStateListener listener) {
        LISTENERS.add(listener);
        listener.onServiceStateChanged(service);
    }

    static void removeServiceStateListener(ServiceStateListener listener) {
        LISTENERS.remove(listener);
    }

    private static void dispatchServiceState(XposedService current) {
        for (ServiceStateListener listener : LISTENERS) {
            listener.onServiceStateChanged(current);
        }
    }

    @Override
    public void onServiceBind(XposedService boundService) {
        service = boundService;
        dispatchServiceState(boundService);
    }

    @Override
    public void onServiceDied(XposedService deadService) {
        if (service == deadService) {
            service = null;
            dispatchServiceState(null);
        }
    }
}

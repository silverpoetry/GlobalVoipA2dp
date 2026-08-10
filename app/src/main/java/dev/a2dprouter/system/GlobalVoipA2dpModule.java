package dev.a2dprouter.system;

import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** Modern LSPosed entry point. This module is scoped only to system_server. */
public final class GlobalVoipA2dpModule extends XposedModule {
    private static final String TAG = "GlobalVoipA2dp";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        if (!param.isSystemServer()) {
            detach();
            return;
        }
        log(Log.INFO, TAG, "Loaded in system_server with Modern API " + getApiVersion());
    }

    @Override
    public void onSystemServerStarting(
            XposedModuleInterface.SystemServerStartingParam param) {
        try {
            SharedPreferences preferences = getRemotePreferences(ModulePreferences.GROUP_ROUTING);
            SystemAudioHooks hooks = new SystemAudioHooks(
                    this,
                    param.getClassLoader(),
                    preferences);
            hooks.install();
            log(Log.INFO, TAG, "All system audio hooks installed; fail-open policy active");
        } catch (Throwable error) {
            // Do not leave a partially installed route policy in system_server.
            log(Log.ERROR, TAG, "Hook installation failed; no partial hook set retained", error);
        }
    }
}

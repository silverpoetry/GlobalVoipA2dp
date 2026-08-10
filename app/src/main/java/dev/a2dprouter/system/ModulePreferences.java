package dev.a2dprouter.system;

/** Shared remote-preference contract used by the module app and system_server instance. */
final class ModulePreferences {
    static final String GROUP_ROUTING = "routing";
    static final String KEY_ENABLED = "enabled";

    private ModulePreferences() {
    }
}

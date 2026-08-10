package dev.a2dprouter.system;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.AttributionSource;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Android 16 system_server audio policy bridge.
 *
 * <p>The implementation deliberately fails open: any lookup or runtime policy error delegates to
 * the untouched framework implementation. Core hooks are installed atomically so a partial route
 * policy can never survive installation failure.</p>
 */
final class SystemAudioHooks {
    private static final String TAG = "GlobalVoipA2dp";
    private static final long LOG_INTERVAL_MS = 10_000L;

    private final XposedModule module;
    private final ClassLoader loader;
    private final SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private final ConcurrentHashMap<String, Long> lastLogTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> virtualScoClients =
            new ConcurrentHashMap<>();
    private final AudioAttributes mediaAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build();

    private Class<?> audioSystemAdapterClass;
    private Class<?> audioDeviceBrokerClass;
    private Class<?> communicationDeviceInfoClass;
    private Class<?> communicationRouteClientClass;
    private Class<?> audioDeviceAttributesClass;
    private Class<?> audioServiceClass;

    private Method setPhoneState;
    private Method onUpdateAudioMode;
    private Method onSetCommunicationDeviceForClient;
    private Method updateStreamVolumeAlias;
    private Method getDevicesStatic;
    private Method getFirstConnectedA2dp;
    private Method getCommunicationRouteClientForUid;
    private Method communicationClientGetDevice;
    private Method audioDeviceAttributesGetType;
    private Method audioServiceGetMode;
    private Method audioServiceGetModeOwnerUid;
    private Method audioServiceGetDevicesForAttributes;
    private Method checkAllAliasStreamVolumes;
    private Method dispatchStreamAliasingUpdate;
    private Method volumeStateUpdateVolumeGroupIndex;
    private Method volumeStateMute;
    private Method volumeStateApplyAllVolumes;
    private Method broadcastMuteSetting;
    private Method currentActivityThread;
    private Method getSystemContext;

    private Field brokerDeviceInventory;
    private Field brokerAudioService;
    private Field brokerContext;
    private Field brokerHandler;
    private Field audioServiceAudioSystem;
    private Field audioServiceHandler;
    private Field streamVolumeAlias;
    private Field streamStates;
    private Field settingsLock;
    private Field volumeStateLock;
    private Field volumeStateIndexMap;
    private Field volumeStateIsMuted;
    private Field allA2dpOutputTypes;

    private Field infoAttributionSource;
    private Field infoBinder;
    private Field infoDevice;
    private Field infoEventSource;
    private Field infoIsPrivileged;
    private Field infoOn;
    private Field infoPid;
    private Field infoScoAudioMode;
    private Constructor<?> communicationDeviceInfoConstructor;

    private volatile Context cachedSystemContext;
    private SparseIntArray savedVoiceCallIndexes;
    private int savedVoiceCallAlias = AudioManager.STREAM_VOICE_CALL;
    private boolean savedVoiceCallMuted;
    private boolean mediaVolumeAliasApplied;

    SystemAudioHooks(
            XposedModule module,
            ClassLoader loader,
            SharedPreferences preferences) {
        this.module = module;
        this.loader = loader;
        this.preferences = preferences;
        this.preferenceListener = (sharedPreferences, key) -> {
            if (key == null || ModulePreferences.KEY_ENABLED.equals(key)) {
                logInfo(
                        "preference",
                        "interception " + (isDisabled() ? "disabled" : "enabled"));
            }
        };
    }

    void install() throws Throwable {
        resolveFrameworkMembers();
        List<XposedInterface.HookHandle> installed = new ArrayList<>(3);
        boolean preferenceListenerRegistered = false;
        try {
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
            preferenceListenerRegistered = true;

            // AudioSystemAdapter#setPhoneState is tiny and may have been inlined into this caller.
            // Deoptimizing the large caller is safe and makes interception deterministic.
            boolean deoptimized = module.deoptimize(onUpdateAudioMode);
            logInfo("install", "AudioService#onUpdateAudioMode deoptimized=" + deoptimized);

            installed.add(module.hook(setPhoneState)
                    .setId("global-voip-a2dp.phone-state")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(this::interceptPhoneState));

            installed.add(module.hook(onSetCommunicationDeviceForClient)
                    .setId("global-voip-a2dp.communication-route")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(this::interceptCommunicationRoute));

            installed.add(module.hook(updateStreamVolumeAlias)
                    .setId("global-voip-a2dp.volume-alias")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(this::interceptVolumeAlias));
        } catch (Throwable installError) {
            for (int index = installed.size() - 1; index >= 0; index--) {
                try {
                    installed.get(index).unhook();
                } catch (Throwable ignored) {
                    // Best-effort rollback during system_server startup.
                }
            }
            if (preferenceListenerRegistered) {
                try {
                    preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
                } catch (Throwable ignored) {
                    // Best-effort rollback during system_server startup.
                }
            }
            throw installError;
        }
    }

    private Object interceptPhoneState(XposedInterface.Chain chain) throws Throwable {
        Object[] replacementArgs = null;
        try {
            int state = ((Number) chain.getArg(0)).intValue();
            int uid = ((Number) chain.getArg(1)).intValue();
            boolean disabled = isDisabled();
            boolean hasPermission = hasModifyPhoneState(uid);
            boolean hasA2dp = hasClassicA2dp();
            boolean hasRedirectedBluetoothRoute = virtualScoClients.containsKey(uid);

            if (RuntimePolicy.shouldRewritePhoneState(
                    state,
                    uid,
                    hasPermission,
                    hasA2dp,
                    hasRedirectedBluetoothRoute,
                    disabled)) {
                replacementArgs = chain.getArgs().toArray();
                replacementArgs[0] = AudioManager.MODE_NORMAL;
                replacementArgs[1] = 0;
                logInfoRateLimited(
                        "phone-state-" + uid,
                        "native phone state rewritten IN_COMMUNICATION->NORMAL uid=" + uid);
            }
        } catch (Throwable hookError) {
            logWarningRateLimited("phone-state", "Phone-state policy failed open", hookError);
        }

        // Never catch exceptions thrown by the original framework method and never call it twice.
        return replacementArgs == null ? chain.proceed() : chain.proceed(replacementArgs);
    }

    private Object interceptCommunicationRoute(XposedInterface.Chain chain) throws Throwable {
        RouteRewrite rewrite;
        try {
            rewrite = prepareRouteRewrite(chain.getThisObject(), chain.getArg(0));
        } catch (Throwable hookError) {
            logWarningRateLimited("route-prepare", "Communication route policy failed open", hookError);
            return chain.proceed();
        }

        if (rewrite == null) {
            return chain.proceed();
        }

        Object result = rewrite.replaceArgument
                ? chain.proceed(new Object[]{rewrite.replacementInfo})
                : chain.proceed();

        // The route is already committed. Compatibility and volume work must never change its
        // return value or turn a successful framework call into a system_server exception.
        try {
            updateVirtualScoState(rewrite);
        } catch (Throwable postError) {
            logWarningRateLimited(
                    "route-sco-post", "Virtual SCO compatibility step skipped", postError);
        }
        try {
            schedulePostRouteAudioWork(rewrite);
        } catch (Throwable postError) {
            logWarningRateLimited(
                    "route-audio-post", "Post-route audio work was not queued", postError);
        }
        try {
            logInfoRateLimited(
                    "route-" + rewrite.uid,
                    (rewrite.redirectsBluetoothHeadset
                            ? "Bluetooth headset route rewritten type="
                            : rewrite.on
                                    ? "non-Bluetooth-headset route passed through type="
                                    : "route cleared type=")
                            + rewrite.originalDeviceType
                            + (rewrite.redirectsBluetoothHeadset ? " -> A2DP" : "")
                            + " uid=" + rewrite.uid
                            + " package=" + rewrite.attributionSource.getPackageName()
                            + " on=" + rewrite.on);
        } catch (Throwable logError) {
            // The original route already succeeded; diagnostics must never reverse its result.
        }
        return result;
    }

    private Object interceptVolumeAlias(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        try {
            applyMediaVolumeAlias(chain.getThisObject(), true);
        } catch (Throwable hookError) {
            logWarningRateLimited("volume-alias", "Volume alias policy failed open", hookError);
        }
        return result;
    }

    private RouteRewrite prepareRouteRewrite(Object broker, Object info) throws Throwable {
        if (info == null) {
            return null;
        }

        AttributionSource attributionSource = (AttributionSource) infoAttributionSource.get(info);
        if (attributionSource == null) {
            return null;
        }
        int uid = attributionSource.getUid();
        boolean privileged = infoIsPrivileged.getBoolean(info);
        if (privileged || !isEligibleApplicationUid(uid)) {
            return null;
        }

        Object audioService = brokerAudioService.get(broker);
        if (audioService == null) {
            return null;
        }
        boolean on = infoOn.getBoolean(info);
        int mode = ((Number) audioServiceGetMode.invoke(audioService)).intValue();
        if (mode == AudioManager.MODE_IN_CALL && on) {
            return null;
        }

        Object requestedDevice = infoDevice.get(info);
        String eventSource = (String) infoEventSource.get(info);
        int originalDeviceType = requestedDevice == null
                ? AudioDeviceInfo.TYPE_UNKNOWN
                : deviceType(requestedDevice);

        Object replacementDevice;
        boolean replaceArgument = true;
        boolean hadVirtualSco = virtualScoClients.containsKey(uid);
        boolean redirectsBluetoothHeadset = false;
        if (on) {
            boolean shouldRedirect = !isDisabled()
                    && RuntimePolicy.isScoLikeDevice(originalDeviceType);
            if (!shouldRedirect) {
                // Speaker, earpiece, wired, USB and every other communication endpoint must remain
                // fully native. If this UID previously held our virtual A2DP route, observe the
                // successful framework switch only so our compatibility state can be released.
                if (!hadVirtualSco) {
                    return null;
                }
                replacementDevice = requestedDevice;
                replaceArgument = false;
            } else if (requestedDevice == null || !hasClassicA2dp()) {
                return null;
            } else {
                replacementDevice = findActiveA2dpAttributes(broker, audioService);
                if (replacementDevice == null) {
                    return null;
                }
                redirectsBluetoothHeadset = true;
            }
        } else {
            if (requestedDevice == null) {
                // clearCommunicationDevice(): preserve the exact framework argument, but retain
                // after-work so an earlier package-targeted virtual SCO state can be closed.
                Object currentClient = getCommunicationRouteClientForUid.invoke(broker, uid);
                Object currentDevice = currentClient == null
                        ? null
                        : communicationClientGetDevice.invoke(currentClient);
                if (!hadVirtualSco
                        || currentDevice == null
                        || deviceType(currentDevice) != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                    return null;
                }
                if (eventSource != null && eventSource.startsWith("stopBluetoothSco")) {
                    // On this ROM the LE-connected legacy stop path carries a null device, but
                    // onSetCommunicationDeviceForClient rejects that null when the stored client
                    // is not BLE_HEADSET. Match the A2DP client explicitly so cleanup reaches the
                    // original removeCommunicationRouteClient path.
                    replacementDevice = currentDevice;
                } else {
                    // clearCommunicationDevice(): preserve its canonical null argument.
                    replacementDevice = null;
                    replaceArgument = false;
                }
            } else {
                Object currentClient = getCommunicationRouteClientForUid.invoke(broker, uid);
                Object currentDevice = currentClient == null
                        ? null
                        : communicationClientGetDevice.invoke(currentClient);
                if (currentDevice == null
                        || deviceType(currentDevice) != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                    return null;
                }
                // A legacy stopBluetoothSco request must match the A2DP object stored by the start
                // rewrite, even when that headset has already disconnected.
                replacementDevice = currentDevice;
            }
        }

        Object replacementInfo = replaceArgument
                ? communicationDeviceInfoConstructor.newInstance(
                        (IBinder) infoBinder.get(info),
                        infoPid.getInt(info),
                        attributionSource,
                        replacementDevice,
                        on,
                        infoScoAudioMode.getInt(info),
                        eventSource,
                        privileged)
                : info;

        return new RouteRewrite(
                broker,
                audioService,
                replacementInfo,
                attributionSource,
                uid,
                originalDeviceType,
                on,
                replaceArgument,
                hadVirtualSco,
                redirectsBluetoothHeadset);
    }

    private Object findActiveA2dpAttributes(Object broker, Object audioService) throws Throwable {
        Object routed = audioServiceGetDevicesForAttributes.invoke(
                audioService, mediaAttributes, false);
        if (routed instanceof Iterable<?>) {
            for (Object device : (Iterable<?>) routed) {
                if (device != null
                        && deviceType(device) == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                    return device;
                }
            }
        }

        Object inventory = brokerDeviceInventory.get(broker);
        @SuppressWarnings("unchecked")
        Set<Integer> types = (Set<Integer>) allA2dpOutputTypes.get(null);
        Object fallback = getFirstConnectedA2dp.invoke(inventory, types);
        return fallback != null
                && deviceType(fallback) == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                ? fallback
                : null;
    }

    private void refreshNativePolicyState(Object broker, int uid) throws Throwable {
        Object audioService = brokerAudioService.get(broker);
        int mode = ((Number) audioServiceGetMode.invoke(audioService)).intValue();
        int currentOwnerUid = ((Number) audioServiceGetModeOwnerUid.invoke(audioService)).intValue();
        if (mode != AudioManager.MODE_IN_COMMUNICATION
                || currentOwnerUid != uid
                || !isEligibleApplicationUid(currentOwnerUid)) {
            return;
        }
        boolean redirectedRouteActive = !isDisabled()
                && hasClassicA2dp()
                && virtualScoClients.containsKey(uid)
                && communicationClientUsesA2dp(broker, uid);
        Object adapter = audioServiceAudioSystem.get(audioService);
        setPhoneState.invoke(
                adapter,
                redirectedRouteActive ? AudioManager.MODE_NORMAL : mode,
                redirectedRouteActive ? 0 : uid);
    }

    private void schedulePostRouteAudioWork(RouteRewrite rewrite) throws IllegalAccessException {
        Handler handler = (Handler) audioServiceHandler.get(rewrite.audioService);
        if (handler == null || !handler.post(() -> {
            try {
                refreshNativePolicyState(rewrite.broker, rewrite.uid);
            } catch (Throwable error) {
                logWarningRateLimited(
                        "post-route-mode", "Native phone-state synchronization failed open", error);
            }
            try {
                applyMediaVolumeAlias(rewrite.audioService, false);
            } catch (Throwable error) {
                logWarningRateLimited(
                        "post-route-volume", "Post-route volume alias failed open", error);
            }
        })) {
            throw new IllegalStateException("AudioService handler unavailable");
        }
    }

    private void applyMediaVolumeAlias(
            Object audioService, boolean frameworkAlreadyRecomputedAlias) throws Throwable {
        if (audioService == null) {
            return;
        }

        int mode = ((Number) audioServiceGetMode.invoke(audioService)).intValue();
        int ownerUid = ((Number) audioServiceGetModeOwnerUid.invoke(audioService)).intValue();
        boolean shouldApply = !isDisabled()
                && hasClassicA2dp()
                && mode == AudioManager.MODE_IN_COMMUNICATION
                && isEligibleApplicationUid(ownerUid)
                && virtualScoClients.containsKey(ownerUid);

        SparseIntArray aliases = (SparseIntArray) streamVolumeAlias.get(null);
        if (aliases == null) {
            return;
        }
        SparseArray<?> states = (SparseArray<?>) streamStates.get(audioService);
        if (states == null) {
            return;
        }
        Object voiceState = states.get(AudioManager.STREAM_VOICE_CALL);
        Object musicState = states.get(AudioManager.STREAM_MUSIC);
        if (voiceState == null || musicState == null) {
            return;
        }

        Object settings = settingsLock.get(audioService);
        Object volume = volumeStateLock.get(audioService);
        if (settings == null || volume == null) {
            return;
        }

        boolean changed = false;
        boolean voiceMuteChanged = false;
        synchronized (settings) {
            synchronized (volume) {
                @SuppressWarnings("unchecked")
                SparseIntArray voiceIndexes =
                        (SparseIntArray) volumeStateIndexMap.get(voiceState);
                if (voiceIndexes == null) {
                    return;
                }

                if (shouldApply) {
                    if (!mediaVolumeAliasApplied) {
                        savedVoiceCallAlias = aliases.get(
                                AudioManager.STREAM_VOICE_CALL,
                                AudioManager.STREAM_VOICE_CALL);
                        savedVoiceCallIndexes = voiceIndexes.clone();
                        savedVoiceCallMuted = volumeStateIsMuted.getBoolean(voiceState);
                        mediaVolumeAliasApplied = true;
                    }
                    aliases.put(AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_MUSIC);
                    checkAllAliasStreamVolumes.invoke(audioService);
                    changed = true;
                } else if (mediaVolumeAliasApplied) {
                    // updateStreamVolumeAlias() is authoritative when it just ran. For an
                    // Preference or route cleanup can happen without an alias recomputation;
                    // restore the pre-VoIP mapping here.
                    if (!frameworkAlreadyRecomputedAlias) {
                        aliases.put(AudioManager.STREAM_VOICE_CALL, savedVoiceCallAlias);
                    }
                    if (savedVoiceCallIndexes != null) {
                        voiceIndexes.clear();
                        for (int index = 0; index < savedVoiceCallIndexes.size(); index++) {
                            voiceIndexes.put(
                                    savedVoiceCallIndexes.keyAt(index),
                                    savedVoiceCallIndexes.valueAt(index));
                        }
                        // Restore only the user mute bit through the framework method. Internal
                        // safety/policy mute state is intentionally preserved as current.
                        voiceMuteChanged = (Boolean) volumeStateMute.invoke(
                                voiceState,
                                savedVoiceCallMuted,
                                true,
                                "GlobalVoipA2dp restore");
                        for (int index = 0; index < savedVoiceCallIndexes.size(); index++) {
                            int device = savedVoiceCallIndexes.keyAt(index);
                            volumeStateUpdateVolumeGroupIndex.invoke(
                                    voiceState, device, false);
                        }
                        // Re-apply VOICE_CALL and every stream (for example DTMF) that aliases it.
                        checkAllAliasStreamVolumes.invoke(audioService);
                        // checkAllAlias skips a user-muted stream; force the restored/current mute
                        // and internal-mute combination into native AudioSystem unconditionally.
                        volumeStateApplyAllVolumes.invoke(voiceState);
                    }
                    savedVoiceCallIndexes = null;
                    mediaVolumeAliasApplied = false;
                    changed = true;
                }
            }
        }
        if (changed) {
            if (voiceMuteChanged) {
                broadcastMuteSetting.invoke(
                        audioService,
                        AudioManager.STREAM_VOICE_CALL,
                        savedVoiceCallMuted);
            }
            dispatchStreamAliasingUpdate.invoke(audioService);
            logInfoRateLimited(
                    "volume-" + ownerUid,
                    shouldApply
                            ? "STREAM_VOICE_CALL aliased to STREAM_MUSIC ownerUid=" + ownerUid
                            : "STREAM_VOICE_CALL alias and pre-VoIP indices restored");
        }
    }

    private void updateVirtualScoState(RouteRewrite rewrite) throws Throwable {
        if (rewrite.redirectsBluetoothHeadset) {
            if (!communicationClientUsesA2dp(rewrite.broker, rewrite.uid)) {
                return;
            }
            String packageName = rewrite.attributionSource.getPackageName();
            if (packageName == null || packageName.isBlank()) {
                return;
            }
            virtualScoClients.put(rewrite.uid, packageName);
            scheduleVirtualScoState(rewrite, packageName, true);
            return;
        }

        // A native speaker/earpiece/wired/USB request replaces the old A2DP client by itself. We
        // only retire the compatibility bookkeeping after observing that the framework really did
        // move away from our route.
        if (communicationClientUsesA2dp(rewrite.broker, rewrite.uid)) {
            return;
        }
        String packageName = virtualScoClients.remove(rewrite.uid);
        if (packageName != null && rewrite.hadVirtualSco) {
            // When interception was disabled and the app has just requested a real SCO/LE route,
            // Android will emit the physical Bluetooth state itself. A synthetic DISCONNECTED at
            // that point would race the real CONNECTED broadcast.
            boolean physicalBluetoothRequest = rewrite.on
                    && RuntimePolicy.isScoLikeDevice(rewrite.originalDeviceType);
            if (!physicalBluetoothRequest) {
                scheduleVirtualScoState(rewrite, packageName, false);
            }
        }
    }

    private boolean communicationClientUsesA2dp(Object broker, int uid) throws Throwable {
        Object client = getCommunicationRouteClientForUid.invoke(broker, uid);
        Object device = client == null ? null : communicationClientGetDevice.invoke(client);
        return device != null && deviceType(device) == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
    }

    private void scheduleVirtualScoState(
            RouteRewrite rewrite, String packageName, boolean connected) {
        try {
            Handler handler = (Handler) brokerHandler.get(rewrite.broker);
            if (handler == null
                    || !handler.post(() -> sendVirtualScoStateNow(
                            rewrite, packageName, connected))) {
                logWarningRateLimited(
                        "virtual-sco-post",
                        "Targeted virtual SCO broadcast could not be queued",
                        new IllegalStateException("Audio broker handler unavailable"));
            }
        } catch (Throwable postError) {
            logWarningRateLimited(
                    "virtual-sco-post", "Targeted virtual SCO broadcast was not queued", postError);
        }
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void sendVirtualScoStateNow(
            RouteRewrite rewrite, String packageName, boolean connected) {
        try {
            Context context = (Context) brokerContext.get(rewrite.broker);
            if (context == null || !packageBelongsToUid(context, packageName, rewrite.uid)) {
                return;
            }
            Intent intent = new Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                    .setPackage(packageName)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(
                            AudioManager.EXTRA_SCO_AUDIO_STATE,
                            connected
                                    ? AudioManager.SCO_AUDIO_STATE_CONNECTED
                                    : AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
                    .putExtra(
                            AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE,
                            connected
                                    ? AudioManager.SCO_AUDIO_STATE_CONNECTING
                                    : AudioManager.SCO_AUDIO_STATE_CONNECTED);
            long identity = Binder.clearCallingIdentity();
            try {
                context.sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(rewrite.uid));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } catch (Throwable broadcastError) {
            logWarningRateLimited(
                    "virtual-sco", "Targeted virtual SCO broadcast skipped", broadcastError);
        }
    }

    private boolean packageBelongsToUid(Context context, String packageName, int uid) {
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) {
            return false;
        }
        for (String candidate : packages) {
            if (packageName.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEligibleApplicationUid(int uid) {
        return RuntimePolicy.isApplicationUid(uid) && !hasModifyPhoneState(uid);
    }

    private boolean hasModifyPhoneState(int uid) {
        if (!RuntimePolicy.isApplicationUid(uid)) {
            return true;
        }
        Context context = systemContext();
        if (context == null) {
            // Conservative: unknown privilege state must never rewrite a possibly privileged UID.
            return true;
        }
        return context.checkPermission(Manifest.permission.MODIFY_PHONE_STATE, -1, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    private Context systemContext() {
        Context existing = cachedSystemContext;
        if (existing != null) {
            return existing;
        }
        try {
            Object activityThread = currentActivityThread.invoke(null);
            Context resolved = activityThread == null
                    ? null
                    : (Context) getSystemContext.invoke(activityThread);
            if (resolved != null) {
                cachedSystemContext = resolved;
            }
            return resolved;
        } catch (Throwable error) {
            logWarningRateLimited("context", "Unable to resolve system context", error);
            return null;
        }
    }

    private boolean hasClassicA2dp() {
        try {
            Object devices = getDevicesStatic.invoke(null, AudioManager.GET_DEVICES_OUTPUTS);
            if (devices == null || !devices.getClass().isArray()) {
                return false;
            }
            int length = Array.getLength(devices);
            for (int index = 0; index < length; index++) {
                Object device = Array.get(devices, index);
                if (device instanceof AudioDeviceInfo
                        && ((AudioDeviceInfo) device).getType()
                        == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                    return true;
                }
            }
            return false;
        } catch (Throwable error) {
            logWarningRateLimited("a2dp-query", "A2DP query failed open", error);
            return false;
        }
    }

    private int deviceType(Object audioDeviceAttributes) throws Throwable {
        return ((Number) audioDeviceAttributesGetType.invoke(audioDeviceAttributes)).intValue();
    }

    private boolean isDisabled() {
        try {
            return !preferences.getBoolean(ModulePreferences.KEY_ENABLED, true);
        } catch (Throwable error) {
            logWarningRateLimited("preferences", "Remote preferences unavailable", error);
            return true;
        }
    }

    @SuppressLint({"PrivateApi", "SoonBlockedPrivateApi"})
    private void resolveFrameworkMembers() throws ReflectiveOperationException {
        audioSystemAdapterClass = Class.forName(
                "com.android.server.audio.AudioSystemAdapter", false, loader);
        audioDeviceBrokerClass = Class.forName(
                "com.android.server.audio.AudioDeviceBroker", false, loader);
        communicationDeviceInfoClass = Class.forName(
                "com.android.server.audio.AudioDeviceBroker$CommunicationDeviceInfo",
                false,
                loader);
        communicationRouteClientClass = Class.forName(
                "com.android.server.audio.AudioDeviceBroker$CommunicationRouteClient",
                false,
                loader);
        audioDeviceAttributesClass = Class.forName(
                "android.media.AudioDeviceAttributes", false, loader);
        audioServiceClass = Class.forName(
                "com.android.server.audio.AudioService", false, loader);
        setPhoneState = ReflectionAccess.method(
                audioSystemAdapterClass, "setPhoneState", int.class, int.class);
        onUpdateAudioMode = ReflectionAccess.method(
                audioServiceClass,
                "onUpdateAudioMode",
                int.class,
                int.class,
                String.class,
                boolean.class,
                boolean.class);
        onSetCommunicationDeviceForClient = ReflectionAccess.method(
                audioDeviceBrokerClass,
                "onSetCommunicationDeviceForClient",
                communicationDeviceInfoClass);
        updateStreamVolumeAlias = ReflectionAccess.method(
                audioServiceClass,
                "updateStreamVolumeAlias",
                boolean.class,
                String.class);

        Class<?> audioManagerClass = Class.forName("android.media.AudioManager", false, loader);
        getDevicesStatic = ReflectionAccess.method(audioManagerClass, "getDevicesStatic", int.class);
        Class<?> audioDeviceInventoryClass = Class.forName(
                "com.android.server.audio.AudioDeviceInventory", false, loader);
        getFirstConnectedA2dp = ReflectionAccess.method(
                audioDeviceInventoryClass,
                "getFirstConnectedDeviceAttributesOfTypes",
                Set.class);
        getCommunicationRouteClientForUid = ReflectionAccess.method(
                audioDeviceBrokerClass,
                "getCommunicationRouteClientForUid",
                int.class);
        communicationClientGetDevice = ReflectionAccess.method(
                communicationRouteClientClass, "getDevice");
        audioDeviceAttributesGetType = ReflectionAccess.method(
                audioDeviceAttributesClass, "getType");

        audioServiceGetMode = ReflectionAccess.method(audioServiceClass, "getMode");
        audioServiceGetModeOwnerUid = ReflectionAccess.method(
                audioServiceClass, "getModeOwnerUid");
        audioServiceGetDevicesForAttributes = ReflectionAccess.method(
                audioServiceClass,
                "getDevicesForAttributesInt",
                AudioAttributes.class,
                boolean.class);
        checkAllAliasStreamVolumes = ReflectionAccess.method(
                audioServiceClass, "checkAllAliasStreamVolumes");
        dispatchStreamAliasingUpdate = ReflectionAccess.method(
                audioServiceClass, "dispatchStreamAliasingUpdate");
        broadcastMuteSetting = ReflectionAccess.method(
                audioServiceClass,
                "broadcastMuteSetting",
                int.class,
                boolean.class);
        Class<?> volumeStreamStateClass = Class.forName(
                "com.android.server.audio.AudioService$VolumeStreamState", false, loader);
        volumeStateUpdateVolumeGroupIndex = ReflectionAccess.method(
                volumeStreamStateClass,
                "updateVolumeGroupIndex",
                int.class,
                boolean.class);
        volumeStateMute = ReflectionAccess.method(
                volumeStreamStateClass,
                "mute",
                boolean.class,
                boolean.class,
                String.class);
        volumeStateApplyAllVolumes = ReflectionAccess.method(
                volumeStreamStateClass, "applyAllVolumes");
        brokerDeviceInventory = ReflectionAccess.field(audioDeviceBrokerClass, "mDeviceInventory");
        brokerAudioService = ReflectionAccess.field(audioDeviceBrokerClass, "mAudioService");
        brokerContext = ReflectionAccess.field(audioDeviceBrokerClass, "mContext");
        brokerHandler = ReflectionAccess.field(audioDeviceBrokerClass, "mBrokerHandler");
        audioServiceAudioSystem = ReflectionAccess.field(audioServiceClass, "mAudioSystem");
        audioServiceHandler = ReflectionAccess.field(audioServiceClass, "mAudioHandler");
        streamVolumeAlias = ReflectionAccess.field(audioServiceClass, "sStreamVolumeAlias");
        streamStates = ReflectionAccess.field(audioServiceClass, "mStreamStates");
        settingsLock = ReflectionAccess.field(audioServiceClass, "mSettingsLock");
        volumeStateLock = ReflectionAccess.field(audioServiceClass, "mVolumeStateLock");
        volumeStateIndexMap = ReflectionAccess.field(volumeStreamStateClass, "mIndexMap");
        volumeStateIsMuted = ReflectionAccess.field(volumeStreamStateClass, "mIsMuted");

        Class<?> audioSystemClass = Class.forName("android.media.AudioSystem", false, loader);
        allA2dpOutputTypes = ReflectionAccess.field(
                audioSystemClass, "DEVICE_OUT_ALL_A2DP_SET");

        infoAttributionSource = ReflectionAccess.field(
                communicationDeviceInfoClass, "mAttributionSource");
        infoBinder = ReflectionAccess.field(communicationDeviceInfoClass, "mCb");
        infoDevice = ReflectionAccess.field(communicationDeviceInfoClass, "mDevice");
        infoEventSource = ReflectionAccess.field(
                communicationDeviceInfoClass, "mEventSource");
        infoIsPrivileged = ReflectionAccess.field(
                communicationDeviceInfoClass, "mIsPrivileged");
        infoOn = ReflectionAccess.field(communicationDeviceInfoClass, "mOn");
        infoPid = ReflectionAccess.field(communicationDeviceInfoClass, "mPid");
        infoScoAudioMode = ReflectionAccess.field(
                communicationDeviceInfoClass, "mScoAudioMode");

        communicationDeviceInfoConstructor = ReflectionAccess.constructor(
                communicationDeviceInfoClass,
                IBinder.class,
                int.class,
                AttributionSource.class,
                audioDeviceAttributesClass,
                boolean.class,
                int.class,
                String.class,
                boolean.class);

        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread", false, loader);
        currentActivityThread = ReflectionAccess.method(
                activityThreadClass, "currentActivityThread");
        getSystemContext = ReflectionAccess.method(activityThreadClass, "getSystemContext");

    }

    private void logInfo(String key, String message) {
        try {
            module.log(Log.INFO, TAG + "/" + key, message);
        } catch (Throwable ignored) {
            // Diagnostics must never escape onto a system_server Looper.
        }
    }

    private void logInfoRateLimited(String key, String message) {
        try {
            long now = android.os.SystemClock.elapsedRealtime();
            Long previous = lastLogTimes.put(key, now);
            if (previous == null || now - previous >= LOG_INTERVAL_MS) {
                module.log(Log.INFO, TAG, message);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never escape onto a system_server Looper.
        }
    }

    private void logWarningRateLimited(String key, String message, Throwable error) {
        try {
            long now = android.os.SystemClock.elapsedRealtime();
            Long previous = lastLogTimes.put("error-" + key, now);
            if (previous == null || now - previous >= LOG_INTERVAL_MS) {
                module.log(Log.WARN, TAG, message, error);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never escape onto a system_server Looper.
        }
    }

    private static final class RouteRewrite {
        final Object broker;
        final Object audioService;
        final Object replacementInfo;
        final AttributionSource attributionSource;
        final int uid;
        final int originalDeviceType;
        final boolean on;
        final boolean replaceArgument;
        final boolean hadVirtualSco;
        final boolean redirectsBluetoothHeadset;

        RouteRewrite(
                Object broker,
                Object audioService,
                Object replacementInfo,
                AttributionSource attributionSource,
                int uid,
                int originalDeviceType,
                boolean on,
                boolean replaceArgument,
                boolean hadVirtualSco,
                boolean redirectsBluetoothHeadset) {
            this.broker = broker;
            this.audioService = audioService;
            this.replacementInfo = replacementInfo;
            this.attributionSource = attributionSource;
            this.uid = uid;
            this.originalDeviceType = originalDeviceType;
            this.on = on;
            this.replaceArgument = replaceArgument;
            this.hadVirtualSco = hadVirtualSco;
            this.redirectsBluetoothHeadset = redirectsBluetoothHeadset;
        }
    }
}

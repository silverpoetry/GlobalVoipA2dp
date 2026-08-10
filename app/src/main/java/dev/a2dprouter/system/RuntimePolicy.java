package dev.a2dprouter.system;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Process;

/** Pure routing decisions kept separate from reflection-heavy framework integration. */
final class RuntimePolicy {
    static final int PER_USER_RANGE = 100_000;

    private RuntimePolicy() {
    }

    static int appIdForUid(int uid) {
        return Math.floorMod(uid, PER_USER_RANGE);
    }

    static boolean isApplicationUid(int uid) {
        return appIdForUid(uid) >= Process.FIRST_APPLICATION_UID;
    }

    static boolean shouldRewritePhoneState(
            int state,
            int uid,
            boolean hasModifyPhoneState,
            boolean hasClassicA2dp,
            boolean hasRedirectedBluetoothRoute,
            boolean disabled) {
        return !disabled
                && state == AudioManager.MODE_IN_COMMUNICATION
                && isApplicationUid(uid)
                && !hasModifyPhoneState
                && hasClassicA2dp
                && hasRedirectedBluetoothRoute;
    }

    static boolean isScoLikeDevice(int publicDeviceType) {
        return publicDeviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || publicDeviceType == AudioDeviceInfo.TYPE_BLE_HEADSET;
    }
}

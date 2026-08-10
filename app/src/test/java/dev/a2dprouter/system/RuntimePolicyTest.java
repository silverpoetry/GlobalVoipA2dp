package dev.a2dprouter.system;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import org.junit.Test;

public final class RuntimePolicyTest {
    @Test
    public void rewritesThirdPartyVoipOnlyWhenA2dpIsPresent() {
        assertTrue(RuntimePolicy.shouldRewritePhoneState(
                AudioManager.MODE_IN_COMMUNICATION,
                10_407,
                false,
                true,
                true,
                false));
        assertFalse(RuntimePolicy.shouldRewritePhoneState(
                AudioManager.MODE_IN_COMMUNICATION,
                10_407,
                false,
                false,
                true,
                false));
        assertFalse(RuntimePolicy.shouldRewritePhoneState(
                AudioManager.MODE_IN_COMMUNICATION,
                10_407,
                false,
                true,
                false,
                false));
    }

    @Test
    public void neverRewritesRealCallsOrPrivilegedUids() {
        assertFalse(RuntimePolicy.shouldRewritePhoneState(
                AudioManager.MODE_IN_CALL,
                10_407,
                false,
                true,
                true,
                false));
        assertFalse(RuntimePolicy.shouldRewritePhoneState(
                AudioManager.MODE_IN_COMMUNICATION,
                1_001,
                false,
                true,
                true,
                false));
        assertFalse(RuntimePolicy.shouldRewritePhoneState(
                AudioManager.MODE_IN_COMMUNICATION,
                10_407,
                true,
                true,
                true,
                false));
    }

    @Test
    public void handlesSecondaryUserUidsByAppId() {
        assertTrue(RuntimePolicy.shouldRewritePhoneState(
                AudioManager.MODE_IN_COMMUNICATION,
                210_407,
                false,
                true,
                true,
                false));
        assertFalse(RuntimePolicy.shouldRewritePhoneState(
                AudioManager.MODE_IN_COMMUNICATION,
                201_001,
                false,
                true,
                true,
                false));
    }

    @Test
    public void killSwitchAlwaysWins() {
        assertFalse(RuntimePolicy.shouldRewritePhoneState(
                AudioManager.MODE_IN_COMMUNICATION,
                10_407,
                false,
                true,
                true,
                true));
    }

    @Test
    public void onlyBidirectionalBluetoothCommunicationEndpointsNeedCompatibilityState() {
        assertTrue(RuntimePolicy.isScoLikeDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO));
        assertTrue(RuntimePolicy.isScoLikeDevice(AudioDeviceInfo.TYPE_BLE_HEADSET));
        assertFalse(RuntimePolicy.isScoLikeDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP));
        assertFalse(RuntimePolicy.isScoLikeDevice(AudioDeviceInfo.TYPE_BLE_SPEAKER));
    }
}

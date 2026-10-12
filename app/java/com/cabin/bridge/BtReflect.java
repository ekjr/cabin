package com.cabin.bridge;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.app.PendingIntent;
import android.net.Uri;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/** Hidden car-kit Bluetooth APIs. The public SDK does not expose the hands-free client. */
final class BtReflect {
    static final String TAG = "Cabin";
    static final int HEADSET_CLIENT = 16;
    static final int PBAP_CLIENT = 17;
    static final int MAP_CLIENT = 18;

    static final String ACTION_CALL_CHANGED =
            "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED";
    static final String ACTION_HFP_CONNECTION =
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED";
    static final String ACTION_HFP_AUDIO =
            "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED";
    static final String EXTRA_AUDIO_WBS = "android.bluetooth.headsetclient.extra.AUDIO_WBS";
    static final int AUDIO_CONNECTED = 2;
    static final String ACTION_AG_EVENT =
            "android.bluetooth.headsetclient.profile.action.AG_EVENT";
    static final String EXTRA_CALL = "android.bluetooth.headsetclient.extra.CALL";

    static final String ACTION_MAP_MESSAGE =
            "android.bluetooth.mapmce.profile.action.MESSAGE_RECEIVED";
    static final String ACTION_MAP_CONNECTION =
            "android.bluetooth.mapmce.profile.action.CONNECTION_STATE_CHANGED";
    static final String EXTRA_MESSAGE_HANDLE =
            "android.bluetooth.mapmce.profile.extra.MESSAGE_HANDLE";
    static final String EXTRA_SENDER_NAME =
            "android.bluetooth.mapmce.profile.extra.SENDER_CONTACT_NAME";
    static final String EXTRA_SENDER_URI =
            "android.bluetooth.mapmce.profile.extra.SENDER_CONTACT_URI";

    /** AOSP and the Google (APEX) build of the Bluetooth app. */
    static final String[] BT_PACKAGES = {"com.android.bluetooth", "com.google.android.bluetooth"};
    /** Android 13+ and Android 12-and-older class names of the system hands-free ConnectionService. */
    static final String[] HFP_CONNSERV = {
            "com.android.bluetooth.hfpclient.HfpClientConnectionService",
            "com.android.bluetooth.hfpclient.connserv.HfpClientConnectionService",
    };

    static boolean isBluetoothPackage(String pkg) {
        for (String p : BT_PACKAGES) if (p.equals(pkg)) return true;
        return false;
    }

    static final int CALL_ACTIVE = 0;
    static final int CALL_HELD = 1;
    static final int CALL_DIALING = 2;
    static final int CALL_ALERTING = 3;
    static final int CALL_INCOMING = 4;
    static final int CALL_WAITING = 5;
    static final int CALL_TERMINATED = 7;

    private BtReflect() {}

    static boolean connect(Object profile, BluetoothDevice device) {
        // Allow the profile first: the stack refuses connect() while the policy is "forbidden".
        // 100 = CONNECTION_POLICY_ALLOWED / PRIORITY_ON
        if (!invokeBool(profile, "setConnectionPolicy",
                new Class[]{BluetoothDevice.class, int.class}, new Object[]{device, 100})) {
            invokeBool(profile, "setPriority",
                    new Class[]{BluetoothDevice.class, int.class}, new Object[]{device, 100});
        }
        return invokeBool(profile, "connect", new Class[]{BluetoothDevice.class}, new Object[]{device});
    }

    static boolean acceptCall(Object profile, BluetoothDevice device) {
        // 0 = CALL_ACCEPT_NONE
        return invokeBool(profile, "acceptCall",
                new Class[]{BluetoothDevice.class, int.class}, new Object[]{device, 0});
    }

    static boolean rejectCall(Object profile, BluetoothDevice device) {
        return invokeBool(profile, "rejectCall",
                new Class[]{BluetoothDevice.class}, new Object[]{device});
    }

    static boolean terminateCall(Object profile, BluetoothDevice device, Object call) {
        if (call == null) return false;
        return invokeBool(profile, "terminateCall",
                new Class[]{BluetoothDevice.class, call.getClass()}, new Object[]{device, call});
    }

    /** BluetoothHeadsetClient audio state: 0 disconnected, 1 connecting, 2 connected; -1 unknown. */
    static int audioState(Object profile, BluetoothDevice device) {
        try {
            Object v = profile.getClass().getMethod("getAudioState", BluetoothDevice.class)
                    .invoke(profile, device);
            return v instanceof Integer ? (Integer) v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    static boolean connectAudio(Object profile, BluetoothDevice device) {
        return invokeBool(profile, "connectAudio",
                new Class[]{BluetoothDevice.class}, new Object[]{device});
    }

    static boolean disconnectAudio(Object profile, BluetoothDevice device) {
        return invokeBool(profile, "disconnectAudio",
                new Class[]{BluetoothDevice.class}, new Object[]{device});
    }

    /** false keeps call audio on the phone (or the car it is also connected to). */
    static boolean setAudioRouteAllowed(Object profile, BluetoothDevice device, boolean allowed) {
        return invokeBool(profile, "setAudioRouteAllowed",
                new Class[]{BluetoothDevice.class, boolean.class}, new Object[]{device, allowed});
    }

    static boolean dial(Object profile, BluetoothDevice device, String number) {
        try {
            Object result = profile.getClass()
                    .getMethod("dial", BluetoothDevice.class, String.class)
                    .invoke(profile, device, number);
            return result != null;
        } catch (Throwable t) {
            Log.w(TAG, "dial failed", t);
            return false;
        }
    }

    static boolean sendDtmf(Object profile, BluetoothDevice device, char tone) {
        if (invokeBool(profile, "sendDTMF",
                new Class[]{BluetoothDevice.class, byte.class}, new Object[]{device, (byte) tone})) {
            return true;
        }
        return invokeBool(profile, "sendDtmf",
                new Class[]{BluetoothDevice.class, byte.class}, new Object[]{device, (byte) tone});
    }

    static boolean getUnreadMessages(Object profile, BluetoothDevice device) {
        return invokeBool(profile, "getUnreadMessages",
                new Class[]{BluetoothDevice.class}, new Object[]{device});
    }

    static boolean sendMessage(Object profile, BluetoothDevice device, String address, String body) {
        Uri[] contacts = new Uri[]{Uri.fromParts("tel", address, null)};
        try {
            Object result = profile.getClass().getMethod(
                    "sendMessage",
                    BluetoothDevice.class,
                    Uri[].class,
                    String.class,
                    PendingIntent.class,
                    PendingIntent.class).invoke(profile, device, contacts, body, null, null);
            return result instanceof Boolean && (Boolean) result;
        } catch (NoSuchMethodException ignored) {
            try {
                Object result = profile.getClass().getMethod(
                        "sendMessage",
                        BluetoothDevice.class,
                        String[].class,
                        String.class,
                        PendingIntent.class,
                        PendingIntent.class).invoke(
                        profile, device, new String[]{address}, body, null, null);
                return result instanceof Boolean && (Boolean) result;
            } catch (Throwable t) {
                Log.w(TAG, "sendMessage failed", t);
                return false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "sendMessage failed", t);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    static List<BluetoothDevice> connected(Object profile) {
        if (profile instanceof BluetoothProfile) {
            try {
                List<BluetoothDevice> list = ((BluetoothProfile) profile).getConnectedDevices();
                return list == null ? Collections.<BluetoothDevice>emptyList() : list;
            } catch (Throwable t) {
                Log.w(TAG, "getConnectedDevices failed", t);
            }
        }
        return Collections.emptyList();
    }

    static int callState(Object call) {
        Object v = invoke(call, "getState");
        return v instanceof Integer ? (Integer) v : -1;
    }

    static String callNumber(Object call) {
        Object v = invoke(call, "getNumber");
        return v instanceof String ? (String) v : "";
    }

    static int callId(Object call) {
        Object v = invoke(call, "getId");
        return v instanceof Integer ? (Integer) v : -1;
    }

    private static Object invoke(Object target, String name) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (Throwable t) {
            Log.w(TAG, name + " failed", t);
            return null;
        }
    }

    private static boolean invokeBool(Object target, String name, Class<?>[] types, Object[] args) {
        if (target == null) return false;
        try {
            Object result = target.getClass().getMethod(name, types).invoke(target, args);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable t) {
            Log.w(TAG, name + " failed", t);
            return false;
        }
    }
}

package com.cabin.bridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Experimental ways to get two-way call audio through a headset connected to this device
 * (see README, "Relay experiments"). Each experiment is a name in the EXPERIMENTS setting and
 * only runs while RELAY is not off. They are independent; combine them freely.
 *
 *   cvsd          ask the headset's HFP link for narrowband (CVSD) so both links match
 *   pin8k         force 8 kHz on the phone link's audio path (app parameter + mixer)
 *   pin16k        force 16 kHz instead
 *   hs_connect    open the headset's call link ourselves (BluetoothHeadset.connectAudio)
 *   hs_vvc        ... or with startScoUsingVirtualVoiceCall
 *   hs_audiomgr   ... or with AudioManager.startBluetoothSco
 *   late          wait 5 s instead of 1.5 s after the phone's link before the headset's
 *   a2dp_keep     keep un-pausing headset media during the call
 *   sw_up         record the headset mic and inject it into the phone link from root
 *   sw_down       copy the phone link's audio from root and play it to the headset's call link
 *   early         open the headset's link after 0.3 s instead of 1.5 s
 *   mute_local    silence this device's own mic and earpiece loopback (use with sw_up/sw_down)
 *   hs_commdev    pick the headset as the communication device (Android 12+)
 *   mode_comm     put Android's audio in communication mode for the call
 *   nudge         re-run the route nudge once the headset's link is open
 *   hfp_reassert  re-send the phone link's HAL parameters once the headset's link is open
 *   hfp_cycle     turn the phone link's HAL loopback off and on again at that point
 *   mixer_reapply root re-applies the hfp-sco mixer settings at that point
 *   route_bt      select Bluetooth as the call's audio route once the headset's link is open
 *   voip_mode     mark Cabin's calls as VoIP so Telecom uses MODE_IN_COMMUNICATION, not IN_CALL
 *                 (IN_CALL suspends the headset's music output; try with RELAY=media)
 */
final class Experiments {
    static final String[] NAMES = {"cvsd", "pin8k", "pin16k", "hs_connect", "hs_vvc",
            "hs_audiomgr", "late", "a2dp_keep", "sw_up", "sw_down", "early", "mute_local",
            "hs_commdev", "mode_comm", "nudge", "hfp_reassert", "hfp_cycle", "mixer_reapply", "route_bt",
            "voip_mode"};
    private static final int RATE = 8000;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private static Object ag;  // BluetoothHeadset proxy (this device as phone to a headset)
    private static BroadcastReceiver agReceiver;
    private static volatile Thread uplink;
    private static volatile Thread keeper;
    private static boolean scoByAudioManager;
    private static boolean lastWbs;
    private static boolean modeSet;

    private Experiments() {}

    static boolean has(Context context, String name) {
        return ("," + Prefs.experiments(context) + ",").contains("," + name + ",");
    }

    static boolean active(Context context) {
        return !"off".equals(Prefs.relay(context)) && Prefs.experiments(context).length() > 0;
    }

    /** Sample rate for the phone link's audio path; pinned by pin8k / pin16k. */
    static int rate(Context context, boolean wbs) {
        lastWbs = wbs;
        if (active(context)) {
            if (has(context, "pin8k")) return 8000;
            if (has(context, "pin16k")) return 16000;
        }
        return wbs ? 16000 : 8000;
    }

    static synchronized void refresh(Context context) {
        Context app = context.getApplicationContext();
        stop(app);
        if (LinkService.running()) start(app);
    }

    static synchronized void start(final Context context) {
        if (!active(context)) return;
        Log.i(BtReflect.TAG, "relay experiments: " + Prefs.experiments(context));
        boolean needHeadset = has(context, "cvsd") || has(context, "hs_connect") || has(context, "hs_vvc");
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (!needHeadset || adapter == null) return;
        final Context app = context.getApplicationContext();
        adapter.getProfileProxy(app, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                ag = proxy;
                if (has(app, "cvsd")) HANDLER.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        applyCvsd(app);
                    }
                }, 700);
            }

            @Override
            public void onServiceDisconnected(int profile) {
                ag = null;
            }
        }, BluetoothProfile.HEADSET);
        agReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                if (state == BluetoothProfile.STATE_CONNECTED && has(app, "cvsd")) {
                    HANDLER.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            applyCvsd(app);
                        }
                    }, 700);
                }
            }
        };
        app.registerReceiver(agReceiver, new IntentFilter(BtReflect.ACTION_HFP_AG_CONNECTION));
    }

    static synchronized void stop(Context context) {
        Context app = context.getApplicationContext();
        HANDLER.removeCallbacksAndMessages(null);
        stopThreads();
        if (agReceiver != null) {
            try {
                app.unregisterReceiver(agReceiver);
            } catch (Throwable ignored) {
                // Already gone.
            }
            agReceiver = null;
        }
        Object proxy = ag;
        ag = null;
        if (proxy instanceof BluetoothProfile) {
            try {
                BluetoothAdapter.getDefaultAdapter().closeProfileProxy(BluetoothProfile.HEADSET, (BluetoothProfile) proxy);
            } catch (Throwable ignored) {
                // Adapter may be off.
            }
        }
    }

    /** The phone's call link opened (up) or closed. */
    static void phoneAudio(final Context context, boolean up) {
        if (!active(context)) return;
        HANDLER.removeCallbacksAndMessages(null);
        stopThreads();
        RootQueue.enqueue(context, "op=relay\nvalue=" + (up ? "up" : "down") + "\n");
        if (!up) {
            closeHeadsetAudio(context);
            return;
        }
        final long delay = has(context, "late") ? 5000 : has(context, "early") ? 300 : 1500;
        if (has(context, "a2dp_keep")) startKeeper(context);
        if (has(context, "mode_comm")) {
            AudioManager am = context.getSystemService(AudioManager.class);
            if (am != null) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                modeSet = true;
            }
        }
        // Once the headset's link has had a moment, poke the phone link's side again.
        HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                afterHeadset(context);
            }
        }, delay + 2500);
        if (has(context, "mute_local")) {
            HANDLER.postDelayed(new Runnable() {
                @Override
                public void run() {
                    AudioManager am = context.getSystemService(AudioManager.class);
                    if (am == null) return;
                    am.setMicrophoneMute(true);
                    am.setParameters("hfp_volume=0");
                    Log.i(BtReflect.TAG, "relay mute_local: mic muted, earpiece loopback volume 0");
                }
            }, delay + 1000);
        }
        HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                openHeadsetAudio(context);
            }
        }, delay);
        if (has(context, "route_bt")) {
            HANDLER.postDelayed(new Runnable() {
                @Override
                public void run() {
                    CabinConnectionService.routeToBluetooth();
                }
            }, delay + 1500);
        }
        if (has(context, "sw_up")) {
            HANDLER.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startUplink(context);
                }
            }, delay + 2000);
        }
        // Mixer and Bluetooth state once both links have had time to settle.
        HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                RootQueue.enqueue(context, "op=relay\nvalue=snap\n");
            }
        }, delay + 4000);
    }

    private static void afterHeadset(Context context) {
        if (has(context, "nudge")) LinkService.nudgeAudioRoute();
        if (has(context, "hfp_reassert") || has(context, "hfp_cycle")) {
            final AudioManager am = context.getSystemService(AudioManager.class);
            if (am == null) return;
            if (has(context, "hfp_cycle")) {
                am.setParameters("hfp_enable=false");
                Log.i(BtReflect.TAG, "relay hfp_cycle: loopback off");
            }
            final Context app = context.getApplicationContext();
            HANDLER.postDelayed(new Runnable() {
                @Override
                public void run() {
                    LinkService.applyHfpPcmDevice(app);
                    am.setParameters("hfp_set_sampling_rate=" + rate(app, lastWbs));
                    am.setParameters("hfp_enable=true");
                    Log.i(BtReflect.TAG, "relay hfp_reassert: hfp parameters sent again");
                }
            }, has(context, "hfp_cycle") ? 300 : 0);
        }
        if (has(context, "mixer_reapply")) RootQueue.enqueue(context, "op=relay\nvalue=reapply\n");
    }

    private static void applyCvsd(Context context) {
        Object profile = ag;
        if (profile == null || !has(context, "cvsd")) return;
        if (BtReflect.connected(profile).isEmpty()) {
            Log.i(BtReflect.TAG, "relay cvsd: no headset connected yet");
            return;
        }
        Log.i(BtReflect.TAG, "relay cvsd: disableWBS accepted=" + BtReflect.setWidebandEnabled(profile, false));
    }

    private static void openHeadsetAudio(Context context) {
        if (has(context, "hs_connect")) {
            Object profile = ag;
            if (profile == null) {
                Log.i(BtReflect.TAG, "relay hs_connect: headset profile not bound");
            } else if (BtReflect.connected(profile).isEmpty()) {
                Log.i(BtReflect.TAG, "relay hs_connect: no headset connected");
            } else {
                Log.i(BtReflect.TAG, "relay hs_connect: connectAudio accepted="
                        + BtReflect.invokeBool(profile, "connectAudio", new Class[0], new Object[0]));
            }
        }
        if (has(context, "hs_vvc")) {
            Object profile = ag;
            Log.i(BtReflect.TAG, "relay hs_vvc: startScoUsingVirtualVoiceCall accepted="
                    + (profile != null && BtReflect.invokeBool(profile, "startScoUsingVirtualVoiceCall",
                    new Class[0], new Object[0])));
        }
        if (has(context, "hs_commdev") && Build.VERSION.SDK_INT >= 31) {
            AudioManager am = context.getSystemService(AudioManager.class);
            boolean set = false;
            if (am != null) {
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) set = am.setCommunicationDevice(d);
                }
            }
            Log.i(BtReflect.TAG, "relay hs_commdev: setCommunicationDevice accepted=" + set);
        }
        if (has(context, "hs_audiomgr")) {
            AudioManager am = context.getSystemService(AudioManager.class);
            if (am != null) {
                scoByAudioManager = true;
                am.setBluetoothScoOn(true);
                am.startBluetoothSco();
                am.setParameters("BT_SCO=on");
                Log.i(BtReflect.TAG, "relay hs_audiomgr: startBluetoothSco requested");
            }
        }
    }

    private static void closeHeadsetAudio(Context context) {
        Object profile = ag;
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio != null) {
            if (has(context, "mute_local")) audio.setMicrophoneMute(false);
            // Telecom normally resets the mode itself when the call ends; only undo ours if it is
            // still in place. (Restoring a mode saved earlier could leave the policy in IN_CALL.)
            if (modeSet && audio.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
                audio.setMode(AudioManager.MODE_NORMAL);
            }
            if (has(context, "hs_commdev") && Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice();
        }
        modeSet = false;
        if (profile != null && has(context, "hs_vvc")) {
            BtReflect.invokeBool(profile, "stopScoUsingVirtualVoiceCall", new Class[0], new Object[0]);
        }
        if (profile != null && has(context, "hs_connect")) {
            BtReflect.invokeBool(profile, "disconnectAudio", new Class[0], new Object[0]);
        }
        if (scoByAudioManager) {
            scoByAudioManager = false;
            AudioManager am = context.getSystemService(AudioManager.class);
            if (am != null) {
                am.stopBluetoothSco();
                am.setBluetoothScoOn(false);
                am.setParameters("BT_SCO=off");
            }
        }
    }

    private static void startKeeper(final Context context) {
        final AudioManager am = context.getSystemService(AudioManager.class);
        if (am == null) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (keeper == Thread.currentThread()) {
                        am.setParameters("A2dpSuspended=false");
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException ignored) {
                    // Call ended.
                }
            }
        }, "cabin-a2dp-keep");
        keeper = t;
        t.start();
    }

    /**
     * sw_up: records the headset's mic (or this device's, if the headset has no call link) as a
     * WAV stream in files/relay-up.pcm. The root script follows the file and plays it into the
     * phone link's transmit side.
     */
    private static void startUplink(final Context context) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runUplink(context);
            }
        }, "cabin-relay-up");
        uplink = t;
        t.start();
    }

    private static void runUplink(Context context) {
        AudioManager am = context.getSystemService(AudioManager.class);
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = null;
        File file = new File(context.getFilesDir(), "relay-up.pcm");
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE / 5 * 2));
            AudioDeviceInfo mic = null;
            if (am != null) {
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) mic = d;
                }
            }
            if (mic != null) rec.setPreferredDevice(mic);
            Log.i(BtReflect.TAG, "relay sw_up: recording from "
                    + (mic != null ? "headset " + mic.getProductName() : "the default mic (no headset call link)"));
            rec.startRecording();
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(wavHeader());
                byte[] buf = new byte[RATE / 50 * 2];  // 20 ms
                while (uplink == Thread.currentThread()) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n > 0) out.write(buf, 0, n);
                    else if (n < 0) break;
                }
            } finally {
                out.close();
            }
        } catch (IOException | RuntimeException e) {
            Log.w(BtReflect.TAG, "relay sw_up: " + e);
        } finally {
            if (rec != null) rec.release();
            Log.i(BtReflect.TAG, "relay sw_up: stopped");
        }
    }

    /** 8 kHz mono 16-bit WAV header with an "endless" length, so a player keeps reading. */
    private static byte[] wavHeader() {
        int byteRate = RATE * 2;
        long size = 0x7fffffffL;
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        le32(h, 4, size + 36);
        put(h, 8, "WAVEfmt ");
        le32(h, 16, 16);
        h[20] = 1;  // PCM
        h[22] = 1;  // mono
        le32(h, 24, RATE);
        le32(h, 28, byteRate);
        h[32] = 2;  // block align
        h[34] = 16; // bits
        put(h, 36, "data");
        le32(h, 40, size);
        return h;
    }

    private static void put(byte[] b, int at, String s) {
        for (int i = 0; i < s.length(); i++) b[at + i] = (byte) s.charAt(i);
    }

    private static void le32(byte[] b, int at, long v) {
        for (int i = 0; i < 4; i++) b[at + i] = (byte) (v >> (8 * i));
    }

    private static void stopThreads() {
        Thread t = uplink;
        uplink = null;
        if (t != null) t.interrupt();
        Thread k = keeper;
        keeper = null;
        if (k != null) k.interrupt();
    }
}

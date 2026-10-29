package com.cabin.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Experimental (RELAY=media): plays the caller's voice to a Bluetooth headset as media (A2DP)
 * while the call itself stays on this device. The root script captures the call audio from the
 * sound chip and writes it, as an 8 kHz mono 16-bit WAV stream, into files/relay.pcm; this
 * follows the file as it grows (SELinux keeps the app from reading a pipe root created).
 * Some Bluetooth stacks drop all headset media while a call link is open; then nothing plays.
 */
public class HalfRelay extends BroadcastReceiver {
    static final String ACTION = "com.cabin.bridge.RELAY";
    private static final int RATE = 8000;
    /** The tapped call audio is much quieter than normal speech; boost it before playing. */
    private static final int GAIN = 8;
    private static volatile Thread worker;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if ("start".equals(intent.getStringExtra("state"))) start(context.getApplicationContext());
        else stop();
    }

    /** Called when the call's audio link to the phone opens or closes. */
    static void onCallAudio(Context context, boolean up) {
        if ("media".equals(Prefs.relay(context)) && !Experiments.has(context, "sw_down")) {
            RootQueue.enqueue(context, "op=relay\nvalue=" + (up ? "start" : "stop") + "\n");
        }
    }

    static synchronized void start(Context context) {
        stop();
        File pcm = new File(context.getFilesDir(), "relay.pcm");
        Thread t = new Thread(() -> run(context, pcm), "cabin-relay");
        worker = t;
        t.start();
    }

    static synchronized void stop() {
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
    }

    private static void run(Context context, File pcm) {
        AudioManager am = context.getSystemService(AudioManager.class);
        // sw_down: play into the headset's call link instead of its media link.
        boolean sco = Experiments.has(context, "sw_down");
        // Android pauses headset media during calls; this relay is media, so un-pause it.
        if (!sco) am.setParameters("A2dpSuspended=false");
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(sco ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(Math.max(min, RATE / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        AudioDeviceInfo headset = null;
        int wanted = sco ? AudioDeviceInfo.TYPE_BLUETOOTH_SCO : AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
        // The headset's call link opens a moment after the phone's; wait for it.
        for (int tries = 0; headset == null && tries < (sco ? 40 : 1) && worker == Thread.currentThread(); tries++) {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (d.getType() == wanted) headset = d;
            }
            if (headset == null && sco) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        if (headset == null) {
            Log.i(BtReflect.TAG, "relay: no Bluetooth headset " + (sco ? "call link" : "for media") + "; not playing");
            track.release();
            return;
        }
        track.setPreferredDevice(headset);
        Log.i(BtReflect.TAG, "relay: playing to " + headset.getProductName());
        try (InputStream in = new FileInputStream(pcm)) {
            byte[] buf = new byte[RATE / 50 * 2];  // 20 ms
            long skip = 44;  // WAV header
            while (skip > 0) skip -= Math.max(0, in.skip(skip));
            track.play();
            long unpaused = 0;
            while (worker == Thread.currentThread()) {
                // Android pauses headset media when a call starts (and may again later).
                long now = android.os.SystemClock.elapsedRealtime();
                if (!sco && now - unpaused > 1000) {
                    am.setParameters("A2dpSuspended=false");
                    unpaused = now;
                }
                int n = in.read(buf);
                if (n > 0) {
                    for (int i = 0; i + 1 < n; i += 2) {
                        int sample = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
                        sample = Math.max(-32768, Math.min(32767, sample * GAIN));
                        buf[i] = (byte) sample;
                        buf[i + 1] = (byte) (sample >> 8);
                    }
                    track.write(buf, 0, n);
                }
                else Thread.sleep(10);
            }
        } catch (IOException | InterruptedException e) {
            Log.w(BtReflect.TAG, "relay: " + e);
        } finally {
            track.release();
            Log.i(BtReflect.TAG, "relay: stopped");
        }
    }
}

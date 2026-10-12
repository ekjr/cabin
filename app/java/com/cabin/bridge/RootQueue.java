package com.cabin.bridge;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Requests for the module's root helper (cabind.sh). The helper polls this
 * directory, parses each file as key=value lines, and deletes it.
 */
final class RootQueue {
    private static int seq;

    private RootQueue() {}

    /** Ask the cabin script to change a setting: MODE, MEDIA_SINK, HFP_AG, MIC_BOOST, ... */
    static boolean set(Context context, String key, String value) {
        Prefs.get(context).edit()
                .putString("pending", key + "=" + value)
                .putLong("pending_at", System.currentTimeMillis())
                .apply();
        return enqueue(context, "op=set\nkey=" + key + "\nvalue=" + value + "\n");
    }

    static boolean enqueue(Context context, String payload) {
        try {
            File dir = new File(context.getFilesDir(), "cabin-queue");
            if (!dir.exists() && !dir.mkdirs()) return false;
            int n;
            synchronized (RootQueue.class) {
                n = ++seq;
            }
            File tmp = new File(dir, System.currentTimeMillis() + "-" + n + ".tmp");
            File dst = new File(dir, tmp.getName().replace(".tmp", ".req"));
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(payload.getBytes(StandardCharsets.UTF_8));
            } finally {
                fos.close();
            }
            return tmp.renameTo(dst);
        } catch (Throwable t) {
            Log.e(BtReflect.TAG, "enqueue failed", t);
            return false;
        }
    }
}

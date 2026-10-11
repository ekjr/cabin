package com.cabin.bridge;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;

/**
 * Writes texts into the system SMS database so the tablet's Messages app can show them.
 * Privileged apps are still blocked from that database unless they are the default SMS app,
 * so a root helper started by the Magisk module performs the insert.
 */
final class SmsBridge {
    private static final Uri SMS = Uri.parse("content://sms");
    private static int seq;

    private SmsBridge() {}

    static boolean insertInbox(Context context, String address, String body, long when) {
        if (body == null || body.length() == 0) return false;
        if (address == null || address.length() == 0) address = "Unknown";
        ContentValues cv = values(address, body, when, 1);
        if (insert(context.getContentResolver(), inboxUri(), cv)) return true;
        return enqueue(context, "op=insert\n"
                + "ab=" + b64(address) + "\n"
                + "bb=" + b64(body) + "\n"
                + "date=" + when + "\n"
                + "type=1\n");
    }

    static boolean insertSent(Context context, String address, String body, long when) {
        if (body == null || body.length() == 0) return false;
        if (address == null || address.length() == 0) address = "Unknown";
        ContentValues cv = values(address, body, when, 2);
        cv.put("read", 1);
        if (insert(context.getContentResolver(), Uri.parse("content://sms/sent"), cv)) return true;
        return enqueue(context, "op=insert\n"
                + "ab=" + b64(address) + "\n"
                + "bb=" + b64(body) + "\n"
                + "date=" + when + "\n"
                + "type=2\n");
    }

    static boolean markSent(Context context, long id) {
        ContentValues cv = new ContentValues();
        cv.put("type", 2);
        cv.put("status", 0);
        cv.put("read", 1);
        try {
            int n = context.getContentResolver().update(Uri.parse("content://sms/" + id), cv, null, null);
            if (n > 0) return true;
        } catch (Throwable t) {
            Log.w(BtReflect.TAG, "markSent direct failed", t);
        }
        return enqueue(context, "op=update\nid=" + id + "\ntype=2\n");
    }

    static Cursor outbox(Context context) {
        return context.getContentResolver().query(
                SMS,
                new String[]{"_id", "address", "body", "type", "date"},
                "type IN (4,5,6)",
                null,
                "date DESC LIMIT 15");
    }

    private static Uri inboxUri() {
        return Uri.parse("content://sms/inbox");
    }

    private static ContentValues values(String address, String body, long when, int type) {
        ContentValues cv = new ContentValues();
        cv.put("address", address);
        cv.put("body", body);
        cv.put("date", when);
        cv.put("date_sent", when);
        cv.put("read", 0);
        cv.put("seen", 0);
        cv.put("type", type);
        cv.put("status", -1);
        return cv;
    }

    private static boolean insert(ContentResolver resolver, Uri uri, ContentValues cv) {
        try {
            Uri result = resolver.insert(uri, cv);
            return result != null;
        } catch (Throwable t) {
            Log.w(BtReflect.TAG, "sms insert needs root helper", t);
            return false;
        }
    }

    private static boolean enqueue(Context context, String payload) {
        try {
            File dir = new File(context.getFilesDir(), "cabin-queue");
            if (!dir.exists() && !dir.mkdirs()) return false;
            int n;
            synchronized (SmsBridge.class) {
                n = ++seq;
            }
            File tmp = new File(dir, System.currentTimeMillis() + "-" + n + ".tmp");
            File dst = new File(dir, tmp.getName().replace(".tmp", ".req"));
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(payload.getBytes(Charset.forName("UTF-8")));
            fos.flush();
            fos.close();
            return tmp.renameTo(dst);
        } catch (Throwable t) {
            Log.e(BtReflect.TAG, "enqueue sms failed", t);
            return false;
        }
    }

    private static String b64(String value) {
        return Base64.encodeToString(value.getBytes(Charset.forName("UTF-8")), Base64.NO_WRAP);
    }
}

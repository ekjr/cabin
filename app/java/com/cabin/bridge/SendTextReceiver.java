package com.cabin.bridge;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.util.Log;

import java.util.ArrayList;

/**
 * Texts handed over by Cabin's LSPosed hook in an SMS app (no SIM on this device). Sends
 * them through the paired phone and reports the result through the app's own "sent"
 * PendingIntents, so the app marks the message sent or failed as usual.
 */
public class SendTextReceiver extends BroadcastReceiver {
    static final String ACTION = "com.cabin.bridge.SEND_TEXT";
    static final String EXTRA_ADDRESS = "address";
    static final String EXTRA_TEXT = "text";
    static final String EXTRA_SENT = "sent";

    @Override
    public void onReceive(Context context, Intent intent) {
        String address = intent.getStringExtra(EXTRA_ADDRESS);
        String text = intent.getStringExtra(EXTRA_TEXT);
        ArrayList<PendingIntent> sent = intent.getParcelableArrayListExtra(EXTRA_SENT);
        boolean ok = LinkService.sendText(address, text);
        Log.i(BtReflect.TAG, "text to " + address + (ok ? " sent through your phone" : " not sent"));
        if (sent == null) return;
        for (PendingIntent pi : sent) {
            if (pi == null) continue;
            try {
                pi.send(context, ok ? Activity.RESULT_OK : SmsManager.RESULT_ERROR_GENERIC_FAILURE, null);
            } catch (PendingIntent.CanceledException ignored) {
                // The app no longer waits for this result.
            }
        }
    }
}

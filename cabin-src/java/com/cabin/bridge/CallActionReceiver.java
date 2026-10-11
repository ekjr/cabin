package com.cabin.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class CallActionReceiver extends BroadcastReceiver {
    public static final String ACTION_ANSWER = "com.cabin.bridge.ANSWER";
    public static final String ACTION_REJECT = "com.cabin.bridge.REJECT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        if (ACTION_ANSWER.equals(intent.getAction())) {
            LinkService.answer();
        } else if (ACTION_REJECT.equals(intent.getAction())) {
            LinkService.reject();
        }
    }
}

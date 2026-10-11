package com.cabin.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Prefs.MODE_HFP.equals(Prefs.mode(context))) {
            context.startForegroundService(new Intent(context, LinkService.class));
        }
    }
}

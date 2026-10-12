package com.cabin.bridge;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.service.quicksettings.TileService;

/** Receives the confirmed settings from the root cabin script and starts or stops Cabin. */
public class ConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String priorHfpCodec = Prefs.hfpCodec(context);
        SharedPreferences.Editor e = Prefs.get(context).edit();
        copy(intent, e, "mode");
        copy(intent, e, "last_on");
        copy(intent, e, "hfp_ag");
        copy(intent, e, "relay");
        copy(intent, e, "modem");
        copy(intent, e, "hfp_pcm_dev_id");
        copy(intent, e, "hfp_codec");
        if (intent.hasExtra("media_sink")) e.putBoolean("media_sink", intent.getBooleanExtra("media_sink", false));
        e.remove("pending");
        e.commit();
        LinkService.applyHfpPcmDevice(context);
        if (!priorHfpCodec.equals(Prefs.hfpCodec(context))) {
            LinkService.applyHfpCodecPreference(context);
        }
        apply(context);
    }

    static void apply(Context context) {
        String mode = Prefs.mode(context);
        Intent service = new Intent(context, LinkService.class);
        if (Prefs.MODE_HFP.equals(mode)) {
            context.startForegroundService(service);
        } else {
            context.stopService(service);
            LinkService.forgetAccount(context);
        }
        try {
            TileService.requestListeningState(context, new ComponentName(context, CabinTile.class));
        } catch (Throwable ignored) {
            // No tile added; nothing to refresh.
        }
    }

    private static void copy(Intent intent, SharedPreferences.Editor e, String key) {
        String v = intent.getStringExtra(key);
        if (v != null) e.putString(key, v);
    }
}

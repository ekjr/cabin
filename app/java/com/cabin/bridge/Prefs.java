package com.cabin.bridge;

import android.content.Context;
import android.content.SharedPreferences;

/** Cabin settings as last confirmed by the root cabin script. */
final class Prefs {
    static final String FILE = "cabin";
    static final String MODE_HFP = "hfp";
    static final String MODE_SAP = "sap";
    static final String MODE_OFF = "off";

    private Prefs() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String mode(Context context) {
        return get(context).getString("mode", MODE_HFP);
    }

    static String lastOn(Context context) {
        return get(context).getString("last_on", MODE_HFP);
    }

    static boolean isOn(Context context) {
        return !MODE_OFF.equals(mode(context));
    }
}

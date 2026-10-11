package com.cabin.bridge;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module, scoped to the Bluetooth app. Android 11's MAP client sends each text it
 * receives from the phone only to the default SMS app, which ignores it. This also sends a
 * copy to Cabin, which puts the text into the message database for the stock Messages app.
 *
 * It hooks ContextWrapper.sendBroadcast(Intent, String) instead of a MAP client class, so it
 * does not depend on the Bluetooth app's internal class names.
 */
public class XposedHook implements IXposedHookLoadPackage {
    private static final String CABIN = "com.cabin.bridge";
    private static final String ACTION_MESSAGE_RECEIVED =
            "android.bluetooth.mapmce.profile.action.MESSAGE_RECEIVED";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!BtReflect.isBluetoothPackage(lpparam.packageName)) return;
        XposedHelpers.findAndHookMethod(ContextWrapper.class, "sendBroadcast",
                Intent.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Intent intent = (Intent) param.args[0];
                        if (intent == null || !ACTION_MESSAGE_RECEIVED.equals(intent.getAction())) return;
                        // Our own copy comes back through this hook; don't copy it again.
                        if (CABIN.equals(intent.getPackage())) return;
                        Intent copy = new Intent(intent).setPackage(CABIN);
                        ((Context) param.thisObject).sendBroadcast(copy, (String) param.args[1]);
                    }
                });
        XposedBridge.log("Cabin: relaying Bluetooth texts to Cabin in " + lpparam.packageName);
    }
}

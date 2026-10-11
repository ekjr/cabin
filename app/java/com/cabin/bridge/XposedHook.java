package com.cabin.bridge;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;

import java.util.ArrayList;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module that lets the stock Messages app text through the paired phone.
 *
 * Bluetooth app: Android 11's MAP client sends each text it receives from the phone only to
 * the default SMS app, which ignores it. Also send a copy to Cabin, which puts the text into
 * the message database. Hooks ContextWrapper.sendBroadcast(Intent, String) rather than a MAP
 * client class, so it doesn't depend on the Bluetooth app's internal class names.
 *
 * SMS apps: with no SIM, sending fails and the app retries forever. When there is no ready
 * SIM, hand the text to Cabin instead (which sends it through the phone) and let Cabin
 * report the result through the app's own "sent" PendingIntents, so it shows as sent.
 */
public class XposedHook implements IXposedHookLoadPackage {
    private static final String CABIN = "com.cabin.bridge";
    private static final String ACTION_MESSAGE_RECEIVED =
            "android.bluetooth.mapmce.profile.action.MESSAGE_RECEIVED";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (CABIN.equals(lpparam.packageName)) return;
        if (!BtReflect.isBluetoothPackage(lpparam.packageName)) {
            hookSmsSending(lpparam.packageName);
            return;
        }
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

    private static void hookSmsSending(String pkg) {
        XposedHelpers.findAndHookMethod(SmsManager.class, "sendMultipartTextMessage",
                String.class, String.class, ArrayList.class, ArrayList.class, ArrayList.class,
                new XC_MethodHook() {
                    @Override
                    @SuppressWarnings("unchecked")
                    protected void beforeHookedMethod(MethodHookParam param) {
                        ArrayList<String> parts = (ArrayList<String>) param.args[2];
                        StringBuilder text = new StringBuilder();
                        if (parts != null) for (String part : parts) text.append(part);
                        if (handOff((String) param.args[0], text.toString(),
                                (ArrayList<PendingIntent>) param.args[3])) {
                            param.setResult(null);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(SmsManager.class, "sendTextMessage",
                String.class, String.class, String.class, PendingIntent.class, PendingIntent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        ArrayList<PendingIntent> sent = new ArrayList<PendingIntent>();
                        if (param.args[3] != null) sent.add((PendingIntent) param.args[3]);
                        if (handOff((String) param.args[0], (String) param.args[2], sent)) {
                            param.setResult(null);
                        }
                    }
                });
        XposedBridge.log("Cabin: SIM-less texts in " + pkg + " go through Cabin");
    }

    /** Returns true when Cabin took the text (no ready SIM and Cabin is installed). */
    private static boolean handOff(String address, String text, ArrayList<PendingIntent> sent) {
        try {
            Context context = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (context == null || address == null) return false;
            TelephonyManager tm = context.getSystemService(TelephonyManager.class);
            if (tm != null && tm.getSimState() == TelephonyManager.SIM_STATE_READY) return false;
            Intent intent = new Intent(SendTextReceiver.ACTION)
                    .setComponent(new ComponentName(CABIN, SendTextReceiver.class.getName()))
                    .putExtra(SendTextReceiver.EXTRA_ADDRESS, address)
                    .putExtra(SendTextReceiver.EXTRA_TEXT, text);
            if (sent != null) intent.putParcelableArrayListExtra(SendTextReceiver.EXTRA_SENT, sent);
            context.sendBroadcast(intent);
            return true;
        } catch (Throwable t) {
            XposedBridge.log("Cabin: hand-off failed: " + t);
            return false;
        }
    }
}

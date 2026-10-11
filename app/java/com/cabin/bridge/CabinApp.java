package com.cabin.bridge;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

public class CabinApp extends Application {
    public static final String CHANNEL_LINK = "cabin_link";
    public static final String CHANNEL_CALLS = "cabin_calls";
    public static final String CHANNEL_TEXTS = "cabin_texts";

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel link = new NotificationChannel(
                    CHANNEL_LINK, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            link.setShowBadge(false);
            nm.createNotificationChannel(link);
            NotificationChannel calls = new NotificationChannel(
                    CHANNEL_CALLS, getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH);
            calls.setShowBadge(false);
            nm.createNotificationChannel(calls);
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_TEXTS, getString(R.string.channel_texts), NotificationManager.IMPORTANCE_HIGH));
        }
    }
}

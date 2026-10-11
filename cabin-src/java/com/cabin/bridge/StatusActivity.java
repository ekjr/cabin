package com.cabin.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;

public class StatusActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int REQ = 7;
    private SharedPreferences prefs;
    private boolean bindingSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_status);
        prefs = getSharedPreferences("cabin", MODE_PRIVATE);
        final Switch power = findViewById(R.id.switch_power);
        power.setChecked(prefs.getBoolean("enabled", true));
        power.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (bindingSwitch) return;
                prefs.edit().putBoolean("enabled", checked).apply();
                Intent service = new Intent(StatusActivity.this, LinkService.class).setAction(checked ? "on" : "off");
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                else startService(service);
            }
        });
        findViewById(R.id.btn_reply).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!prefs.getBoolean("enabled", true)) return;
                EditText field = findViewById(R.id.reply_body);
                String body = field.getText().toString().trim();
                if (body.length() == 0) return;
                String to = prefs.getString("reply_to", "");
                field.setText("");
                LinkService.reply(to, body);
            }
        });
        findViewById(R.id.btn_pair).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
            }
        });
        findViewById(R.id.btn_sync).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent service = new Intent(StatusActivity.this, LinkService.class).setAction("pull");
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                else startService(service);
                LinkService.pullUnread();
            }
        });
        findViewById(R.id.btn_phone).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Intent.ACTION_DIAL));
            }
        });
        findViewById(R.id.btn_messages).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent messaging = new Intent(Intent.ACTION_MAIN);
                messaging.addCategory(Intent.CATEGORY_APP_MESSAGING);
                messaging.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(messaging);
                } catch (Throwable t) {
                    startActivity(new Intent(Intent.ACTION_VIEW).setType("vnd.android-dir/mms-sms"));
                }
            }
        });
        askPermissions();
        Intent service = new Intent(this, LinkService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(this);
        render();
    }

    @Override
    protected void onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        render();
    }

    private void render() {
        ((TextView) findViewById(R.id.status_link)).setText(prefs.getString("link", "Starting the link…"));
        ((TextView) findViewById(R.id.status_calls)).setText(prefs.getString("calls", "Checking calls…"));
        ((TextView) findViewById(R.id.status_sms)).setText(prefs.getString("texts", "Checking texts…"));
        ((TextView) findViewById(R.id.status_detail)).setText(prefs.getString("detail",
                "Pair the phone in Bluetooth. Allow calls, contacts, and text messages. "
                        + "Then answer in Phone and reply in Messages."));
        ((TextView) findViewById(R.id.status_inbox)).setText(prettyInbox(prefs.getString("inbox", "")));
        Switch power = findViewById(R.id.switch_power);
        boolean on = prefs.getBoolean("enabled", true);
        bindingSwitch = true;
        power.setChecked(on);
        bindingSwitch = false;
    }

    private String prettyInbox(String raw) {
        if (raw == null || raw.length() == 0) return "No texts yet.";
        String[] rows = raw.split("\n");
        StringBuilder out = new StringBuilder();
        int start = Math.max(0, rows.length - 8);
        for (int i = start; i < rows.length; i++) {
            String[] parts = rows[i].split("\t", 3);
            if (parts.length < 3) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append("me".equals(parts[0]) ? "You" : parts[1]).append(": ").append(parts[2]);
        }
        return out.length() == 0 ? "No texts yet." : out.toString();
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        ArrayList<String> needed = new ArrayList<String>();
        needed.add(Manifest.permission.READ_SMS);
        needed.add(Manifest.permission.RECEIVE_SMS);
        needed.add(Manifest.permission.SEND_SMS);
        needed.add(Manifest.permission.READ_PHONE_STATE);
        needed.add(Manifest.permission.READ_CONTACTS);
        if (Build.VERSION.SDK_INT >= 31) needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS);
        ArrayList<String> missing = new ArrayList<String>();
        for (String permission : needed) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[missing.size()]), REQ);
    }
}

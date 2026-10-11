package com.cabin.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;

public class StatusActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int REQ = 7;
    private SharedPreferences prefs;
    private Switch swOn;
    private RadioGroup rgMode;
    private RadioGroup rgAudio;
    private CheckBox cbMedia;
    /** True while render() sets widgets, so their listeners don't send requests. */
    private boolean rendering;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_status);
        prefs = Prefs.get(this);
        swOn = findViewById(R.id.sw_on);
        rgMode = findViewById(R.id.rg_mode);
        rgAudio = findViewById(R.id.rg_audio);
        cbMedia = findViewById(R.id.cb_media);

        swOn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (!rendering) request("MODE", on ? selectedMode() : Prefs.MODE_OFF);
            }
        });
        rgMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                if (!rendering) request("MODE", selectedMode());
            }
        });
        rgAudio.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                if (!rendering) request("CALL_AUDIO", id == R.id.rb_audio_phone ? "phone" : "tablet");
            }
        });
        cbMedia.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (!rendering) request("MEDIA_SINK", on ? "1" : "0");
            }
        });

        findViewById(R.id.btn_reply).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText field = findViewById(R.id.reply_body);
                String body = field.getText().toString().trim();
                if (body.length() == 0) return;
                if (!Prefs.MODE_HFP.equals(Prefs.mode(StatusActivity.this))) {
                    detail("Replies go through the phone in Hands-free (HFP) mode.");
                    return;
                }
                field.setText("");
                LinkService.reply(prefs.getString("reply_to", ""), body);
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
                if (!Prefs.MODE_HFP.equals(Prefs.mode(StatusActivity.this))) {
                    detail("Texts sync in Hands-free (HFP) mode.");
                    return;
                }
                startForegroundService(new Intent(StatusActivity.this, LinkService.class).setAction("pull"));
                LinkService.pullUnread();
            }
        });
        findViewById(R.id.btn_sap).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                v.setEnabled(false);
                detail("Asking the phone for SIM access. Accept the prompt on the phone…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String result = SapClient.probe();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                v.setEnabled(true);
                                detail(result + "\n\n" + modemNote());
                            }
                        });
                    }
                }, "cabin-sap").start();
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
                    detail("No messaging app is installed.");
                }
            }
        });
        askPermissions();
        ConfigReceiver.apply(this);
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

    private String selectedMode() {
        return rgMode.getCheckedRadioButtonId() == R.id.rb_sap ? Prefs.MODE_SAP : Prefs.MODE_HFP;
    }

    private void request(String key, String value) {
        if (!RootQueue.set(this, key, value)) {
            detail("Could not reach the Cabin module. Is it installed and the device rebooted?");
        }
    }

    private void detail(String text) {
        prefs.edit().putString("detail", text).apply();
    }

    private String modemNote() {
        String modem = prefs.getString("modem", "none");
        if ("none".equals(modem)) {
            return "This device has no cellular modem, so it cannot use the phone's SIM itself. "
                    + "Use Hands-free (HFP) mode.";
        }
        if ("qcom".equals(modem)) {
            return "This device's modem has a Qualcomm remote-SIM interface. Handing the SIM to it "
                    + "is device-specific and not wired up yet.";
        }
        return "No remote-SIM interface was found on this device's modem.";
    }

    private void render() {
        rendering = true;
        try {
            String mode = Prefs.mode(this);
            boolean on = !Prefs.MODE_OFF.equals(mode);
            String shown = on ? mode : Prefs.lastOn(this);
            swOn.setChecked(on);
            rgMode.check(Prefs.MODE_SAP.equals(shown) ? R.id.rb_sap : R.id.rb_hfp);
            rgAudio.check(Prefs.audioOnTablet(this) ? R.id.rb_audio_tablet : R.id.rb_audio_phone);
            cbMedia.setChecked(prefs.getBoolean("media_sink", false));
            boolean hfp = Prefs.MODE_HFP.equals(mode);
            for (int i = 0; i < rgAudio.getChildCount(); i++) rgAudio.getChildAt(i).setEnabled(hfp);
            cbMedia.setEnabled(hfp);

            TextView pending = findViewById(R.id.status_pending);
            String p = prefs.getString("pending", null);
            pending.setVisibility(p == null ? View.GONE : View.VISIBLE);
            pending.setText("Applying " + p + " - Bluetooth restarts for a few seconds…");

            TextView link = findViewById(R.id.status_link);
            TextView calls = findViewById(R.id.status_calls);
            TextView sms = findViewById(R.id.status_sms);
            if (hfp) {
                link.setText(prefs.getString("link", "Starting the link…"));
                calls.setText(prefs.getString("calls", "Checking calls…"));
                sms.setText(prefs.getString("texts", "Checking texts…"));
            } else if (Prefs.MODE_SAP.equals(mode)) {
                link.setText("SIM Access mode");
                calls.setText(modemNote());
                sms.setText("Use \"Test SIM access\" to check that your phone shares its SIM.");
            } else {
                link.setText("Cabin is off");
                calls.setText("Bluetooth is back to stock. Turn the switch on to link your phone again.");
                sms.setText("");
            }
            ((TextView) findViewById(R.id.status_detail)).setText(prefs.getString("detail",
                    "Pair the phone in Bluetooth. Allow calls, contacts, and text messages. "
                            + "Then answer in Phone and reply in Messages."));
            String to = prefs.getString("reply_to", "");
            ((TextView) findViewById(R.id.status_inbox)).setText(prettyInbox(prefs.getString("inbox", "")));
            ((EditText) findViewById(R.id.reply_body)).setHint(to.length() == 0 ? "Reply" : "Reply to " + to);
            findViewById(R.id.btn_reply).setEnabled(hfp && to.length() > 0);
        } finally {
            rendering = false;
        }
    }

    private static String prettyInbox(String raw) {
        if (raw == null || raw.length() == 0) return "No texts yet.";
        String[] rows = raw.split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, rows.length - 8); i < rows.length; i++) {
            String[] parts = rows[i].split("\t", 3);
            if (parts.length < 3) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append("me".equals(parts[0]) ? "You" : parts[1]).append(": ").append(parts[2]);
        }
        return out.length() == 0 ? "No texts yet." : out.toString();
    }

    private void askPermissions() {
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
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQ);
    }
}

package com.cabin.bridge;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LinkService extends Service {
    private static final int NOTIF_LINK = 1;
    private static final int NOTIF_CALL = 2;
    private static final String PREFS = "cabin";
    private static final String ACCOUNT_ID = "cabin-hf";

    private static volatile LinkService self;
    private static final Map<Integer, Object> CALLS = new HashMap<Integer, Object>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> seenMessages = new HashSet<String>();
    private final Set<String> forwardedSms = new HashSet<String>();
    private final Set<String> announced = new HashSet<String>();

    private BluetoothAdapter adapter;
    private Object hfp;
    private Object map;
    private Object pbap;
    private BluetoothDevice phone;
    private boolean systemOwnsCalls;
    private boolean receiversRegistered;
    private ContentObserver smsObserver;

    private final BluetoothProfile.ServiceListener profiles = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BtReflect.HEADSET_CLIENT) hfp = proxy;
            else if (profile == BtReflect.MAP_CLIENT) map = proxy;
            else if (profile == BtReflect.PBAP_CLIENT) pbap = proxy;
            attachBonded();
            publish();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BtReflect.HEADSET_CLIENT) hfp = null;
            else if (profile == BtReflect.MAP_CLIENT) map = null;
            else if (profile == BtReflect.PBAP_CLIENT) pbap = null;
            publish();
        }
    };

    private final BroadcastReceiver bluetoothEvents = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device != null && isConnectedAction(action, intent)) phone = device;
            if (BtReflect.ACTION_CALL_CHANGED.equals(action)) {
                Object call = intent.getParcelableExtra(BtReflect.EXTRA_CALL);
                if (call != null) onCall(call);
            } else if (BtReflect.ACTION_MAP_MESSAGE.equals(action)) {
                onMapMessage(intent);
            } else if (BtReflect.ACTION_HFP_CONNECTION.equals(action)
                    || BtReflect.ACTION_MAP_CONNECTION.equals(action)) {
                attachBonded();
                publish();
            }
        }
    };

    static void answer() {
        LinkService s = self;
        if (s == null || s.hfp == null || s.phone == null) return;
        BtReflect.acceptCall(s.hfp, s.phone);
        ensureAudio();
    }

    static void reject() {
        LinkService s = self;
        if (s == null || s.hfp == null || s.phone == null) return;
        BtReflect.rejectCall(s.hfp, s.phone);
        s.clearCallNotification();
    }

    static void hangup(Object call) {
        LinkService s = self;
        if (s == null || s.hfp == null || s.phone == null) return;
        if (call != null) BtReflect.terminateCall(s.hfp, s.phone, call);
        else BtReflect.rejectCall(s.hfp, s.phone);
    }

    static boolean dial(String number) {
        LinkService s = self;
        if (s == null || s.hfp == null || s.phone == null || number == null) return false;
        boolean ok = BtReflect.dial(s.hfp, s.phone, number);
        s.note(ok ? "Dialing " + number + " through your phone." : "The phone did not accept the dial.");
        return ok;
    }

    static void dtmf(char tone) {
        LinkService s = self;
        if (s == null || s.hfp == null || s.phone == null) return;
        BtReflect.sendDtmf(s.hfp, s.phone, tone);
    }

    static void ensureAudio() {
        LinkService s = self;
        if (s == null || s.hfp == null || s.phone == null) return;
        if (Prefs.audioOnTablet(s)) BtReflect.connectAudio(s.hfp, s.phone);
        else BtReflect.disconnectAudio(s.hfp, s.phone);
    }

    static Object callObject(int id) {
        return CALLS.get(id);
    }

    static void clearCallNotification() {
        LinkService s = self;
        if (s != null) s.stopCallAlert();
    }

    static void note(String text) {
        LinkService s = self;
        if (s != null) s.detail(text);
    }

    /** Sends a reply typed in Cabin's own inbox (for tablets without a usable SMS app). */
    static void reply(String address, String body) {
        LinkService s = self;
        if (s == null || body == null || body.trim().length() == 0) return;
        String text = body.trim();
        String to = address == null || address.length() == 0 ? "Unknown" : address;
        boolean sent = s.map != null && s.phone != null && BtReflect.sendMessage(s.map, s.phone, to, text);
        if (sent) SmsBridge.insertSent(s, to, text, System.currentTimeMillis());
        s.rememberText(to, text, true);
        s.detail(sent ? "Reply sent through your phone." : "The phone did not take the reply. Is it connected?");
        s.publish();
    }

    static void pullUnread() {
        LinkService s = self;
        if (s != null) s.requestUnread();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        self = this;
        if (!Prefs.MODE_HFP.equals(Prefs.mode(this))) {
            // Started by the system or a stale intent while Cabin is off or in SAP mode.
            startInForeground("Off");
            stopSelf();
            return;
        }
        adapter = BluetoothAdapter.getDefaultAdapter();
        loadSeen();
        systemOwnsCalls = systemHandsFreeService();
        if (!systemOwnsCalls) registerOurAccount();
        else forgetOurAccount();
        startInForeground("Waiting for your phone");
        registerBtReceivers();
        bindProfiles();
        watchOutbox();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                publish();
            }
        }, 8000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.MODE_HFP.equals(Prefs.mode(this))) {
            startInForeground("Off");
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground(phone == null ? "Waiting for your phone" : "Phone linked");
        if ("pull".equals(intent == null ? null : intent.getAction())) requestUnread();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (smsObserver != null) getContentResolver().unregisterContentObserver(smsObserver);
        if (receiversRegistered) unregisterReceiver(bluetoothEvents);
        if (adapter != null && hfp != null && phone != null) {
            BtReflect.setAudioRouteAllowed(hfp, phone, true);
        }
        if (adapter != null) {
            closeProfile(BtReflect.HEADSET_CLIENT);
            closeProfile(BtReflect.MAP_CLIENT);
            closeProfile(BtReflect.PBAP_CLIENT);
        }
        if (self == this) self = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void bindProfiles() {
        if (adapter == null) {
            detail("Bluetooth is off.");
            return;
        }
        adapter.getProfileProxy(this, profiles, BtReflect.HEADSET_CLIENT);
        adapter.getProfileProxy(this, profiles, BtReflect.MAP_CLIENT);
        adapter.getProfileProxy(this, profiles, BtReflect.PBAP_CLIENT);
    }

    private void closeProfile(int which) {
        try {
            adapter.closeProfileProxy(which, null);
        } catch (Throwable ignored) {
            // The proxy object is hidden; closing is best-effort.
        }
    }

    private void attachBonded() {
        if (adapter == null || !adapter.isEnabled()) return;
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException se) {
            detail("Allow Bluetooth for Cabin, then reopen it.");
            return;
        }
        if (bonded == null) return;
        for (BluetoothDevice device : bonded) {
            if (!isPhone(device)) continue;
            if (hfp != null) BtReflect.connect(hfp, device);
            if (map != null) BtReflect.connect(map, device);
            if (pbap != null) BtReflect.connect(pbap, device);
        }
        phone = firstConnected(hfp);
        if (phone == null) phone = firstConnected(map);
        applyAudioPreference();
        if (systemOwnsCalls) enableSystemAccounts();
    }

    /** Only phones; headphones and speakers bonded to this tablet are left alone. */
    private static boolean isPhone(BluetoothDevice device) {
        try {
            BluetoothClass cls = device.getBluetoothClass();
            return cls == null || cls.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Call audio: on this tablet, or left on the phone (and any car it is connected to). */
    private void applyAudioPreference() {
        if (hfp == null || phone == null) return;
        BtReflect.setAudioRouteAllowed(hfp, phone, Prefs.audioOnTablet(this));
    }

    private BluetoothDevice firstConnected(Object profile) {
        List<BluetoothDevice> list = BtReflect.connected(profile);
        return list.isEmpty() ? null : list.get(0);
    }

    private void onCall(Object call) {
        int id = BtReflect.callId(call);
        int state = BtReflect.callState(call);
        String number = BtReflect.callNumber(call);
        if (id >= 0) CALLS.put(id, call);
        if (state == BtReflect.CALL_TERMINATED && id >= 0) CALLS.remove(id);
        if (!systemOwnsCalls) {
            String token = id + ":" + (number == null ? "" : number);
            if (state == BtReflect.CALL_INCOMING || state == BtReflect.CALL_WAITING) {
                if (announced.add(token)) {
                    placeIncoming(id, number);
                    maybeNotifyCall(number);
                }
            } else if (state == BtReflect.CALL_TERMINATED) {
                announced.remove(token);
            }
            CabinConnectionService.onRemote(id, number, state, call);
            if (state == BtReflect.CALL_ACTIVE) ensureAudio();
            if (state == BtReflect.CALL_TERMINATED) stopCallAlert();
        } else if (state == BtReflect.CALL_ACTIVE) {
            // The system call service doesn't always open the SCO audio link itself.
            ensureAudio();
        }
        publish();
    }

    private void placeIncoming(int id, String number) {
        if (number == null) number = "";
        TelecomManager telecom = getSystemService(TelecomManager.class);
        if (telecom == null) return;
        Bundle extras = new Bundle();
        extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("tel", number, null));
        extras.putString("cabin_number", number);
        extras.putInt("cabin_id", id);
        try {
            telecom.addNewIncomingCall(ourHandle(), extras);
        } catch (Throwable t) {
            Log.w(BtReflect.TAG, "addNewIncomingCall failed", t);
            detail("Could not show this call in the Phone app. " + t.getClass().getSimpleName());
            maybeNotifyCall(number);
        }
    }

    private void onMapMessage(Intent intent) {
        String handle = intent.getStringExtra(BtReflect.EXTRA_MESSAGE_HANDLE);
        String body = intent.getStringExtra(Intent.EXTRA_TEXT);
        String sender = senderAddress(intent);
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        String key = (device == null ? "" : device.getAddress()) + "|" + (handle == null ? body : handle);
        if (key.length() > 1 && !seenMessages.add(key)) return;
        long when = System.currentTimeMillis();
        boolean ok = SmsBridge.insertInbox(this, sender, body == null ? "" : body, when);
        rememberText(sender, body == null ? "" : body, false);
        detail(ok
                ? "Text from " + sender + " sent to Messages."
                : "Text from " + sender + " is in Cabin. Messages did not take the copy.");
        saveSeen();
        publish();
    }

    private String senderAddress(Intent intent) {
        String uri = intent.getStringExtra(BtReflect.EXTRA_SENDER_URI);
        if (uri != null && uri.startsWith("tel:")) return Uri.parse(uri).getSchemeSpecificPart();
        String name = intent.getStringExtra(BtReflect.EXTRA_SENDER_NAME);
        if (name != null && name.trim().length() > 0) return name.trim();
        return "Unknown";
    }

    private void requestUnread() {
        if (map == null || phone == null) {
            detail("No phone is connected for messages yet.");
            publish();
            return;
        }
        boolean ok = BtReflect.getUnreadMessages(map, phone);
        detail(ok ? "Asking the phone for unread texts." : "This phone did not share messages.");
        publish();
    }

    private void watchOutbox() {
        smsObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                forwardOutbox();
            }
        };
        try {
            getContentResolver().registerContentObserver(Uri.parse("content://sms"), true, smsObserver);
        } catch (Throwable t) {
            Log.w(BtReflect.TAG, "sms observer failed", t);
            detail("Messages access is not granted yet. Reboot once after flashing Cabin.");
        }
    }

    private void forwardOutbox() {
        if (hasReadySim()) return;
        if (map == null || phone == null) return;
        Cursor cursor = null;
        try {
            cursor = SmsBridge.outbox(this);
            if (cursor == null) return;
            int idCol = cursor.getColumnIndex("_id");
            int addrCol = cursor.getColumnIndex("address");
            int bodyCol = cursor.getColumnIndex("body");
            while (cursor.moveToNext()) {
                String id = cursor.getString(idCol);
                if (id == null || forwardedSms.contains(id)) continue;
                String address = cursor.getString(addrCol);
                String body = cursor.getString(bodyCol);
                if (address == null || body == null) continue;
                forwardedSms.add(id);
                boolean sent = BtReflect.sendMessage(map, phone, address, body);
                if (sent) {
                    SmsBridge.markSent(this, Long.parseLong(id));
                    detail("Reply to " + address + " sent through your phone.");
                } else {
                    forwardedSms.remove(id);
                    detail("Could not send that reply through the phone.");
                }
            }
        } catch (Throwable t) {
            Log.w(BtReflect.TAG, "outbox forward failed", t);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private boolean hasReadySim() {
        try {
            TelephonyManager telephony = getSystemService(TelephonyManager.class);
            return telephony != null && telephony.getSimState() == TelephonyManager.SIM_STATE_READY;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean systemHandsFreeService() {
        PackageManager pm = getPackageManager();
        for (String pkg : BtReflect.BT_PACKAGES) {
            for (String cls : BtReflect.HFP_CONNSERV) {
                ComponentName cn = new ComponentName(pkg, cls);
                try {
                    ServiceInfo info = pm.getServiceInfo(cn, PackageManager.MATCH_DISABLED_COMPONENTS);
                    int state = pm.getComponentEnabledSetting(cn);
                    if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
                    if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.enabled) return true;
                } catch (Throwable ignored) {
                    // Not in this build of the Bluetooth app.
                }
            }
        }
        return false;
    }

    private void registerOurAccount() {
        TelecomManager telecom = getSystemService(TelecomManager.class);
        if (telecom == null) return;
        PhoneAccount account = new PhoneAccount.Builder(ourHandle(), "Phone")
                .setShortDescription("Your phone, through Cabin")
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                .build();
        try {
            telecom.registerPhoneAccount(account);
            enableAccount(telecom, ourHandle());
            selectOutgoingIfEmpty(telecom, ourHandle());
        } catch (Throwable t) {
            Log.w(BtReflect.TAG, "phone account failed", t);
            detail("Could not register with the Phone app.");
        }
    }

    private void forgetOurAccount() {
        forgetAccount(this);
    }

    /** Removes Cabin's fallback calling account from Telecom (when off or in SAP mode). */
    static void forgetAccount(Context context) {
        try {
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            if (telecom != null) telecom.unregisterPhoneAccount(handle(context));
        } catch (Throwable ignored) {
        }
    }

    private void enableSystemAccounts() {
        TelecomManager telecom = getSystemService(TelecomManager.class);
        if (telecom == null) return;
        try {
            enableScheme(telecom, PhoneAccount.SCHEME_TEL);
            enableScheme(telecom, "hfpc");
        } catch (Throwable t) {
            Log.w(BtReflect.TAG, "enable system accounts failed", t);
        }
    }

    private void enableScheme(TelecomManager telecom, String scheme) {
        List<PhoneAccountHandle> handles = accountsFor(telecom, scheme);
        for (PhoneAccountHandle handle : handles) {
            ComponentName cn = handle.getComponentName();
            if (cn == null || !BtReflect.isBluetoothPackage(cn.getPackageName())) continue;
            enableAccount(telecom, handle);
            selectOutgoingIfEmpty(telecom, handle);
        }
    }

    private void enableAccount(TelecomManager telecom, PhoneAccountHandle handle) {
        try {
            telecom.getClass()
                    .getMethod("enablePhoneAccount", PhoneAccountHandle.class, boolean.class)
                    .invoke(telecom, handle, Boolean.TRUE);
        } catch (Throwable t) {
            Log.w(BtReflect.TAG, "enablePhoneAccount failed", t);
        }
    }

    private void selectOutgoingIfEmpty(TelecomManager telecom, PhoneAccountHandle handle) {
        try {
            Object current = telecom.getClass().getMethod("getUserSelectedOutgoingPhoneAccount").invoke(telecom);
            if (current == null) {
                telecom.getClass()
                        .getMethod("setUserSelectedOutgoingPhoneAccount", PhoneAccountHandle.class)
                        .invoke(telecom, handle);
            }
        } catch (Throwable t) {
            Log.w(BtReflect.TAG, "outgoing account failed", t);
        }
    }

    @SuppressWarnings("unchecked")
    private List<PhoneAccountHandle> accountsFor(TelecomManager telecom, String scheme) {
        try {
            Object result = telecom.getClass()
                    .getMethod("getPhoneAccountsSupportingScheme", String.class)
                    .invoke(telecom, scheme);
            if (result instanceof List) return (List<PhoneAccountHandle>) result;
        } catch (Throwable t) {
            Log.w(BtReflect.TAG, "list accounts failed", t);
        }
        return new java.util.ArrayList<PhoneAccountHandle>();
    }

    private PhoneAccountHandle ourHandle() {
        return handle(this);
    }

    private static PhoneAccountHandle handle(Context context) {
        return new PhoneAccountHandle(new ComponentName(context, CabinConnectionService.class), ACCOUNT_ID);
    }

    private void registerBtReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BtReflect.ACTION_CALL_CHANGED);
        filter.addAction(BtReflect.ACTION_HFP_CONNECTION);
        filter.addAction(BtReflect.ACTION_AG_EVENT);
        filter.addAction(BtReflect.ACTION_MAP_MESSAGE);
        filter.addAction(BtReflect.ACTION_MAP_CONNECTION);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bluetoothEvents, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(bluetoothEvents, filter);
        }
        receiversRegistered = true;
    }

    private boolean isConnectedAction(String action, Intent intent) {
        int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
        return state == BluetoothProfile.STATE_CONNECTED
                && (BtReflect.ACTION_HFP_CONNECTION.equals(action)
                || BtReflect.ACTION_MAP_CONNECTION.equals(action));
    }

    private void maybeNotifyCall(String number) {
        TelecomManager telecom = getSystemService(TelecomManager.class);
        String dialer = null;
        try {
            dialer = telecom == null ? null : telecom.getDefaultDialerPackage();
        } catch (Throwable ignored) {
        }
        if (dialer != null && dialer.length() > 0) return;
        Intent answer = new Intent(this, CallActionReceiver.class).setAction(CallActionReceiver.ACTION_ANSWER);
        Intent reject = new Intent(this, CallActionReceiver.class).setAction(CallActionReceiver.ACTION_REJECT);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder builder = new Notification.Builder(this, CabinApp.CHANNEL_CALLS)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle(number == null || number.length() == 0 ? "Incoming call" : number)
                .setContentText("Answer on this tablet")
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setFullScreenIntent(pendingActivity(), true)
                .addAction(new Notification.Action.Builder(0, "Decline",
                        PendingIntent.getBroadcast(this, 2, reject, flags)).build())
                .addAction(new Notification.Action.Builder(0, "Answer",
                        PendingIntent.getBroadcast(this, 3, answer, flags)).build());
        NotificationManager().notify(NOTIF_CALL, builder.build());
    }

    private android.app.NotificationManager NotificationManager() {
        return getSystemService(android.app.NotificationManager.class);
    }

    private void stopCallAlert() {
        NotificationManager().cancel(NOTIF_CALL);
    }

    private void startInForeground(String text) {
        Notification notification = new Notification.Builder(this, CabinApp.CHANNEL_LINK)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle("Cabin")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pendingActivity())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            try {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL;
                startForeground(NOTIF_LINK, notification, type);
                return;
            } catch (Throwable ignored) {
                try {
                    startForeground(NOTIF_LINK, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                    return;
                } catch (Throwable ignoredAgain) {
                    // Fall through to the two-argument form.
                }
            }
        }
        startForeground(NOTIF_LINK, notification);
    }

    private PendingIntent pendingActivity() {
        Intent open = new Intent(this, StatusActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(this, 1, open, flags);
    }

    private void publish() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String link;
        if (adapter == null || !adapter.isEnabled()) link = "Bluetooth is off";
        else if (phone != null) link = "Phone connected";
        else if (hfp == null && map == null) link = "Car Bluetooth profiles are not on this tablet";
        else link = "Pair your phone, and allow calls, contacts, and messages";

        String calls;
        if (systemOwnsCalls) calls = "Calls open in the Phone app";
        else if (hfp != null) calls = "Calls open in the Phone app, through Cabin";
        else calls = "Hands-free calling is missing from this tablet's Bluetooth";

        String texts;
        if (map != null && phone != null) texts = "Texts open in the Messages app";
        else if (map != null) texts = "Message access is on. Waiting for the phone.";
        else texts = "Message access is missing from this tablet's Bluetooth";

        prefs.edit()
                .putString("link", link)
                .putString("calls", calls)
                .putString("texts", texts)
                .apply();
        startInForeground(phone != null ? "Phone linked" : "Waiting for your phone");
    }

    /** Last few texts, newest last, for Cabin's own inbox. Rows: who-flag TAB address TAB body. */
    private void rememberText(String who, String body, boolean mine) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String prev = prefs.getString("inbox", "");
        String safeWho = who == null ? "" : who.replace('\t', ' ').replace('\n', ' ');
        String safeBody = body == null ? "" : body.replace('\t', ' ').replace('\n', ' ');
        String row = (mine ? "me" : "them") + "\t" + safeWho + "\t" + safeBody;
        String next = prev.length() == 0 ? row : prev + "\n" + row;
        if (next.length() > 4000) next = next.substring(next.indexOf('\n', next.length() - 4000) + 1);
        SharedPreferences.Editor e = prefs.edit().putString("inbox", next);
        if (!mine) e.putString("reply_to", safeWho);
        e.apply();
    }

    private void detail(String text) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("detail", text).apply();
        Log.i(BtReflect.TAG, text);
    }

    private void loadSeen() {
        Set<String> saved = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet("seen", null);
        if (saved != null) seenMessages.addAll(saved);
        Set<String> sent = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet("forwarded", null);
        if (sent != null) forwardedSms.addAll(sent);
    }

    private void saveSeen() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putStringSet("seen", new HashSet<String>(seenMessages))
                .putStringSet("forwarded", new HashSet<String>(forwardedSms))
                .apply();
    }
}

package com.cabin.bridge;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Presents the phone's cellular calls to the tablet's Phone app.
 * Used only when the ROM's own hands-free ConnectionService is missing.
 */
public class CabinConnectionService extends ConnectionService {
    private static final Map<Integer, CabinConnection> BY_ID = new HashMap<Integer, CabinConnection>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static android.telephony.TelephonyManager telephony;
    private static android.content.Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        telephony = getSystemService(android.telephony.TelephonyManager.class);
        appContext = getApplicationContext();
    }

    /** Whether a Phone-app call already exists for this phone call (by id or number). */
    static boolean tracks(int id, String number) {
        if (id >= 0 && BY_ID.containsKey(id)) return true;
        for (CabinConnection c : BY_ID.values()) {
            if (sameNumber(c.number, number)) return true;
        }
        return false;
    }

    /** "+16468208701" vs "6468208701": compare digits, allowing a country code on one side. */
    static boolean sameNumber(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.replaceAll("[^0-9]", "");
        String y = b.replaceAll("[^0-9]", "");
        if (x.length() < 7 || y.length() < 7) return x.equals(y) && x.length() > 0;
        return x.endsWith(y) || y.endsWith(x);
    }

    static void remember(int id, String number) {
        CabinConnection existing = BY_ID.get(id);
        if (existing != null) existing.number = number == null ? "" : number;
    }

    static void onRemote(int id, String number, int state, Object call) {
        CabinConnection connection = BY_ID.get(id);
        if (connection == null && number != null) {
            for (CabinConnection candidate : BY_ID.values()) {
                if (number.equals(candidate.number)) {
                    connection = candidate;
                    break;
                }
            }
        }
        if (connection == null) return;
        connection.btCall = call;
        connection.applyRemote(state);
    }

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle account, ConnectionRequest request) {
        String number = readNumber(request);
        int id = readId(request);
        CabinConnection connection = new CabinConnection(number, true);
        connection.btCall = LinkService.callObject(id);
        if (id >= 0) BY_ID.put(id, connection);
        return connection;
    }

    /**
     * A call started on the phone itself (not from this device), shown in the Phone app.
     * Overrides ConnectionService's hidden onCreateUnknownConnection; not in the public SDK,
     * so no @Override, but Telecom calls it by this exact signature.
     */
    public Connection onCreateUnknownConnection(PhoneAccountHandle account, ConnectionRequest request) {
        String number = readNumber(request);
        int id = readId(request);
        Bundle extras = request.getExtras();
        int state = extras == null ? BtReflect.CALL_DIALING : extras.getInt("cabin_state", BtReflect.CALL_DIALING);
        CabinConnection connection = new CabinConnection(number, false);
        connection.btCall = LinkService.callObject(id);
        if (id >= 0) BY_ID.put(id, connection);
        connection.applyRemote(state);
        android.util.Log.i(BtReflect.TAG, "call started on the phone, now in the Phone app");
        return connection;
    }

    @Override
    public Connection onCreateOutgoingConnection(PhoneAccountHandle account, ConnectionRequest request) {
        String number = readNumber(request);
        CabinConnection connection = new CabinConnection(number, false);
        if (!LinkService.dial(number)) {
            connection.setDisconnected(new DisconnectCause(DisconnectCause.ERROR));
            return connection;
        }
        BY_ID.put(numberKey(number), connection);
        return connection;
    }

    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle account, ConnectionRequest request) {
        LinkService.note("Phone app did not accept the incoming call.");
    }

    private static String readNumber(ConnectionRequest request) {
        if (request.getAddress() != null) {
            String specific = request.getAddress().getSchemeSpecificPart();
            if (specific != null && specific.length() > 0) return specific;
        }
        Bundle extras = request.getExtras();
        if (extras != null) {
            String number = extras.getString("cabin_number");
            if (number != null) return number;
        }
        return "";
    }

    private static int numberKey(String number) {
        int hash = number == null ? 0 : number.hashCode();
        return hash == Integer.MIN_VALUE ? -2 : -Math.abs(hash);
    }

    private static int readId(ConnectionRequest request) {
        Bundle extras = request.getExtras();
        if (extras == null) return -1;
        return extras.getInt("cabin_id", -1);
    }

    static final class CabinConnection extends Connection {
        String number;
        Object btCall;
        private boolean closed;
        private int lastRoute = -1;
        private final boolean incoming;
        private final long created = android.os.SystemClock.elapsedRealtime();

        // Speaker/earpiece taps reach Telecom, but the car-kit audio isn't an Android audio
        // stream, so nothing tells the HAL to move it. Nudge it whenever the route changes.
        @Override
        public void onCallAudioStateChanged(android.telecom.CallAudioState state) {
            if (state == null || closed) return;
            int route = state.getRoute();
            // A headset or car connected to this device can't carry the call: its audio
            // comes from the phone over a separate Bluetooth link, which this device can't
            // pass through. Keep it on this device's own earpiece instead.
            if (route == android.telecom.CallAudioState.ROUTE_BLUETOOTH && !relayToHeadset()) {
                android.util.Log.i(BtReflect.TAG, "call audio can't go to a Bluetooth device here; using the earpiece");
                setAudioRoute(android.telecom.CallAudioState.ROUTE_EARPIECE);
                return;
            }
            if (route != lastRoute) {
                boolean first = lastRoute == -1;
                lastRoute = route;
                if (!first) LinkService.nudgeAudioRoute();
            }
        }

        /**
         * RELAY=call (experimental): leave the call on this device's headset once the phone's
         * call link is up (the chip only accepts the headset's link second). The headset then
         * hears the caller, but the caller may hear only noise.
         */
        private boolean relayToHeadset() {
            return appContext != null && "call".equals(Prefs.relay(appContext)) && LinkService.callAudioUp();
        }

        CabinConnection(String number, boolean incoming) {
            this.number = number == null ? "" : number;
            setAddress(Uri.fromParts("tel", this.number, null), TelecomManager.PRESENTATION_ALLOWED);
            setAudioModeIsVoip(false);
            setConnectionCapabilities(CAPABILITY_MUTE | CAPABILITY_SUPPORT_HOLD);
            this.incoming = incoming;
            if (incoming) {
                setInitializing();
                setRinging();
            } else {
                setDialing();
            }
        }

        void applyRemote(int state) {
            if (closed) return;
            if (state == BtReflect.CALL_ACTIVE) {
                setActive();
                LinkService.ensureAudio();
                // Answered on the phone while the Phone app was still screening the incoming
                // call: when screening finishes it sets the call back to ringing. Re-assert.
                if (incoming && android.os.SystemClock.elapsedRealtime() - created < 10000) {
                    MAIN.postDelayed(this::reassertActive, 2500);
                    MAIN.postDelayed(this::reassertActive, 6000);
                }
            } else if (state == BtReflect.CALL_DIALING || state == BtReflect.CALL_ALERTING) {
                setDialing();
            } else if (state == BtReflect.CALL_INCOMING || state == BtReflect.CALL_WAITING) {
                setRinging();
            } else if (state == BtReflect.CALL_HELD) {
                setOnHold();
            } else if (state == BtReflect.CALL_TERMINATED) {
                close(new DisconnectCause(DisconnectCause.REMOTE));
            }
        }

        private void reassertActive() {
            if (closed || getState() != STATE_ACTIVE || telephony == null) return;
            try {
                if (telephony.getCallState() != android.telephony.TelephonyManager.CALL_STATE_RINGING) return;
            } catch (SecurityException e) {
                return;
            }
            android.util.Log.i(BtReflect.TAG, "call is active on the phone but ringing here; fixing");
            setRinging();
            setActive();
        }

        // Telecom answers through onAnswer(int videoState) (the Phone app passes a video
        // state even for voice calls), and Connection's default for that does nothing.
        // Handle both entry points.
        @Override
        public void onAnswer(int videoState) {
            android.util.Log.i(BtReflect.TAG, "Phone app pressed Answer");
            LinkService.answer();
            setActive();
        }

        @Override
        public void onAnswer() {
            onAnswer(android.telecom.VideoProfile.STATE_AUDIO_ONLY);
        }

        @Override
        public void onReject() {
            android.util.Log.i(BtReflect.TAG, "Phone app pressed Decline");
            LinkService.reject();
            close(new DisconnectCause(DisconnectCause.REJECTED));
        }

        @Override
        public void onReject(int rejectReason) {
            onReject();
        }

        @Override
        public void onReject(String replyMessage) {
            onReject();
        }

        @Override
        public void onDisconnect() {
            LinkService.hangup(btCall);
            close(new DisconnectCause(DisconnectCause.LOCAL));
        }

        @Override
        public void onAbort() {
            onDisconnect();
        }

        @Override
        public void onPlayDtmfTone(char c) {
            LinkService.dtmf(c);
        }

        @Override
        public void onStopDtmfTone() {}

        private void close(DisconnectCause cause) {
            if (closed) return;
            closed = true;
            setDisconnected(cause);
            destroy();
            Integer drop = null;
            for (Map.Entry<Integer, CabinConnection> entry : BY_ID.entrySet()) {
                if (entry.getValue() == this) drop = entry.getKey();
            }
            if (drop != null) BY_ID.remove(drop);
            LinkService.clearCallNotification();
        }
    }
}

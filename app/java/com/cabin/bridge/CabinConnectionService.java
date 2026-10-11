package com.cabin.bridge;

import android.net.Uri;
import android.os.Bundle;
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

        CabinConnection(String number, boolean incoming) {
            this.number = number == null ? "" : number;
            setAddress(Uri.fromParts("tel", this.number, null), TelecomManager.PRESENTATION_ALLOWED);
            setAudioModeIsVoip(false);
            setConnectionCapabilities(CAPABILITY_MUTE | CAPABILITY_SUPPORT_HOLD);
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

        @Override
        public void onAnswer() {
            android.util.Log.i(BtReflect.TAG, "Phone app pressed Answer");
            LinkService.answer();
            setActive();
            LinkService.ensureAudio();
        }

        @Override
        public void onReject() {
            android.util.Log.i(BtReflect.TAG, "Phone app pressed Decline");
            LinkService.reject();
            close(new DisconnectCause(DisconnectCause.REJECTED));
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

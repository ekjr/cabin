package com.cabin.bridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

/**
 * Bluetooth SIM Access Profile (SAP 1.1) client: borrows the phone's SIM over RFCOMM.
 *
 * Android has no built-in SAP client, so this speaks the protocol directly. Feeding the
 * SIM into this tablet's own modem needs a vendor remote-SIM interface; until one is wired
 * in, {@link #probe} connects, reads the SIM's ATR and ICCID to prove access, and
 * disconnects so the phone gets its SIM back.
 */
final class SapClient {
    static final UUID SAP_UUID = UUID.fromString("0000112D-0000-1000-8000-00805F9B34FB");

    // Message IDs
    private static final int CONNECT_REQ = 0x00;
    private static final int CONNECT_RESP = 0x01;
    private static final int DISCONNECT_REQ = 0x02;
    private static final int DISCONNECT_RESP = 0x03;
    private static final int DISCONNECT_IND = 0x04;
    private static final int TRANSFER_APDU_REQ = 0x05;
    private static final int TRANSFER_APDU_RESP = 0x06;
    private static final int TRANSFER_ATR_REQ = 0x07;
    private static final int TRANSFER_ATR_RESP = 0x08;
    private static final int STATUS_IND = 0x11;
    private static final int ERROR_RESP = 0x12;

    // Parameter IDs
    private static final int P_MAX_MSG_SIZE = 0x00;
    private static final int P_CONNECTION_STATUS = 0x01;
    private static final int P_RESULT_CODE = 0x02;
    private static final int P_COMMAND_APDU = 0x04;
    private static final int P_RESPONSE_APDU = 0x05;
    private static final int P_ATR = 0x06;
    private static final int P_STATUS_CHANGE = 0x08;

    private static final int MAX_MSG = 0x0400;

    private final BluetoothSocket socket;
    private final InputStream in;
    private final OutputStream out;

    /** A received SAP message: id plus parameters indexed by parameter ID. */
    private static final class Msg {
        int id;
        final byte[][] params = new byte[0x20][];
    }

    private SapClient(BluetoothSocket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /** Connects to the phone's SAP server, reads ATR + ICCID, and hands the SIM back. */
    static String probe() {
        BluetoothDevice phone = pickPhone();
        if (phone == null) return "No paired phone found. Pair your phone in Bluetooth first.";
        SapClient sap = null;
        StringBuilder r = new StringBuilder();
        try {
            BluetoothSocket socket = phone.createRfcommSocketToServiceRecord(SAP_UUID);
            BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
            socket.connect();
            sap = new SapClient(socket);
            r.append("Connected to ").append(name(phone)).append(".\n");
            sap.connect();
            byte[] atr = sap.atr();
            r.append("SIM answered (ATR ").append(hex(atr)).append(").\n");
            String iccid = sap.readIccid();
            if (iccid != null) r.append("SIM ICCID ").append(iccid).append(".\n");
            r.append("SIM access works. The phone has its SIM back now.");
        } catch (IOException e) {
            Log.w(BtReflect.TAG, "SAP probe failed", e);
            r.append(r.length() == 0
                    ? "The phone did not accept a SIM Access connection. Most phones have SAP turned off: "
                    + "on a rooted Android phone set bluetooth.profile.sap.server.enabled=true; iPhones "
                    + "do not support it.\n(" + e.getMessage() + ")"
                    : "Stopped: " + e.getMessage());
        } finally {
            if (sap != null) sap.close();
        }
        return r.toString();
    }

    private static BluetoothDevice pickPhone() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return null;
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null) return null;
        for (BluetoothDevice d : bonded) {
            BluetoothClass c = d.getBluetoothClass();
            if (c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE) return d;
        }
        return null;
    }

    private static String name(BluetoothDevice d) {
        String n = d.getName();
        return n == null ? d.getAddress() : n;
    }

    void connect() throws IOException {
        send(CONNECT_REQ, P_MAX_MSG_SIZE, new byte[]{(byte) (MAX_MSG >> 8), (byte) MAX_MSG});
        Msg m = expect(CONNECT_RESP);
        byte[] status = m.params[P_CONNECTION_STATUS];
        int s = status == null ? -1 : status[0] & 0xff;
        // 0x00 OK, 0x04 OK but a call is ongoing on the phone (SIM is handed over once it ends).
        if (s != 0x00 && s != 0x04) throw new IOException("phone refused SIM access (status " + s + ")");
        // The server follows with STATUS_IND "card reset" once the SIM is ours.
        Msg ind = read();
        if (ind.id != STATUS_IND) throw new IOException("unexpected message 0x" + Integer.toHexString(ind.id));
    }

    byte[] atr() throws IOException {
        send(TRANSFER_ATR_REQ, -1, null);
        Msg m = expect(TRANSFER_ATR_RESP);
        checkResult(m);
        return m.params[P_ATR] == null ? new byte[0] : m.params[P_ATR];
    }

    /** Sends one command APDU to the phone's SIM; returns response data + SW1 SW2. */
    byte[] apdu(byte[] command) throws IOException {
        send(TRANSFER_APDU_REQ, P_COMMAND_APDU, command);
        Msg m = expect(TRANSFER_APDU_RESP);
        checkResult(m);
        return m.params[P_RESPONSE_APDU] == null ? new byte[0] : m.params[P_RESPONSE_APDU];
    }

    /** Reads EF_ICCID (2FE2), trying UICC then classic GSM command class. */
    String readIccid() {
        try {
            byte[] r = apdu(bytes("00A40804022FE2"));
            if (sw1(r) == 0x90 || sw1(r) == 0x61) {
                byte[] data = apdu(bytes("00B000000A"));
                if (sw1(data) == 0x90) return bcd(data, data.length - 2);
            }
            apdu(bytes("A0A40000023F00"));
            r = apdu(bytes("A0A40000022FE2"));
            if (sw1(r) == 0x9F || sw1(r) == 0x90) {
                byte[] data = apdu(bytes("A0B000000A"));
                if (sw1(data) == 0x90) return bcd(data, data.length - 2);
            }
        } catch (IOException e) {
            Log.w(BtReflect.TAG, "ICCID read failed", e);
        }
        return null;
    }

    void close() {
        try {
            send(DISCONNECT_REQ, -1, null);
            long until = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < until) {
                int id = read().id;
                if (id == DISCONNECT_RESP || id == DISCONNECT_IND) break;
            }
        } catch (IOException ignored) {
            // Closing the socket also ends the SAP session.
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void checkResult(Msg m) throws IOException {
        byte[] rc = m.params[P_RESULT_CODE];
        if (rc != null && rc[0] != 0) throw new IOException("SIM error (result " + (rc[0] & 0xff) + ")");
    }

    private Msg expect(int id) throws IOException {
        while (true) {
            Msg m = read();
            if (m.id == id) return m;
            if (m.id == ERROR_RESP) throw new IOException("phone reported a SAP error");
            if (m.id == DISCONNECT_IND) throw new IOException("phone ended SIM access");
            // STATUS_IND and other notifications can arrive at any time; skip them.
        }
    }

    /** Header: MsgId, NumParams, 2 reserved. Param: Id, reserved, Length(2), value, pad to 4. */
    private void send(int msgId, int paramId, byte[] value) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(msgId);
        b.write(paramId < 0 ? 0 : 1);
        b.write(0);
        b.write(0);
        if (paramId >= 0) {
            b.write(paramId);
            b.write(0);
            b.write(value.length >> 8);
            b.write(value.length);
            b.write(value, 0, value.length);
            for (int i = value.length; i % 4 != 0; i++) b.write(0);
        }
        out.write(b.toByteArray());
        out.flush();
    }

    private Msg read() throws IOException {
        Msg m = new Msg();
        byte[] head = readFully(4);
        m.id = head[0] & 0xff;
        int count = head[1] & 0xff;
        for (int i = 0; i < count; i++) {
            byte[] ph = readFully(4);
            int pid = ph[0] & 0xff;
            int len = ((ph[2] & 0xff) << 8) | (ph[3] & 0xff);
            byte[] v = readFully(len);
            int pad = (4 - len % 4) % 4;
            if (pad > 0) readFully(pad);
            if (pid < m.params.length) m.params[pid] = v;
        }
        return m;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new IOException("connection closed");
            off += r;
        }
        return buf;
    }

    private static int sw1(byte[] r) {
        return r.length >= 2 ? r[r.length - 2] & 0xff : -1;
    }

    private static String bcd(byte[] data, int len) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int lo = data[i] & 0x0f;
            int hi = (data[i] >> 4) & 0x0f;
            if (lo <= 9) s.append(lo);
            if (hi <= 9) s.append(hi);
        }
        return s.toString();
    }

    private static byte[] bytes(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02X", x));
        return s.toString();
    }
}

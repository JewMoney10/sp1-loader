package com.joshua.sp1loader;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Raw USB CDC-ACM transport for the SP-1 stem player.
 *
 * This talks to the device the same way a desktop OS's built-in CDC-ACM driver
 * would (SET_LINE_CODING + SET_CONTROL_LINE_STATE, then bulk transfer for data),
 * using Android's UsbManager/UsbDeviceConnection directly. No external library
 * needed, so this should work in AIDE Pro without adding a Gradle dependency.
 *
 * We don't yet know the SP-1's exact VID/PID or interface layout, so step 1
 * is just: plug it in, call logAttachedDevices(), and read the logcat output
 * (or Log.i("SP1USB", ...) target) to see what actually shows up.
 *
 * USAGE:
 *   Sp1UsbSerial usb = new Sp1UsbSerial(context);
 *   usb.registerPermissionReceiver();
 *   usb.logAttachedDevices();       // first run: see what's connected
 *   usb.requestConnection(device);  // once you've picked the right device
 *   // after permission is granted, usb.isConnected() becomes true
 *   usb.write(someBytes);
 *   usb.setReadListener(bytes -> { ... });
 */
public class Sp1UsbSerial {

    private static final String TAG = "SP1USB";
    private static final String ACTION_USB_PERMISSION = "com.joshua.sp1loader.USB_PERMISSION";

    // Identified from a live scan: vid=0x2367 pid=0x1701, CDC-ACM layout
    // (Interface 0 = control, Interface 1 = data with bulk IN 0x81 / OUT 0x01).
    private static final int SP1_VENDOR_ID = 0x2367;
    private static final int SP1_PRODUCT_ID = 0x1701;

    // Standard USB CDC class request codes (USB CDC 1.2 spec)
    private static final int REQ_SET_LINE_CODING = 0x20;
    private static final int REQ_SET_CONTROL_LINE_STATE = 0x22;
    private static final int REQTYPE_CLASS_INTERFACE_OUT = 0x21; // host->device | class | interface

    // Interface classes we're looking for
    private static final int USB_CLASS_CDC_CONTROL = 0x02; // aka "Communications"
    private static final int USB_CLASS_CDC_DATA = 0x0A;

    private final Context context;
    private final UsbManager usbManager;

    private UsbDeviceConnection connection;
    private UsbDevice connectedDevice; // the specific device we opened, for matching detach events
    private UsbInterface dataInterface;
    private UsbInterface controlInterface; // may be null on some devices
    private UsbEndpoint endpointIn;
    private UsbEndpoint endpointOut;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean readThreadRunning = new AtomicBoolean(false);
    private final AtomicBoolean transferInProgress = new AtomicBoolean(false);
    private final AtomicBoolean abortRequested = new AtomicBoolean(false);
    private ReadListener readListener;
    private ConnectionListener connectionListener;

    // Protocol-level receive buffer: raw bytes accumulate here until a
    // complete COBS packet (terminated by 0x00) can be pulled off.
    private final Object rxLock = new Object();
    private final ArrayList<Byte> rxBuffer = new ArrayList<>();
    private int seq = 0;

    public interface ReadListener {
        void onDataReceived(byte[] data, int len);
    }

    /** Lets the UI react to connection state without needing Logcat. */
    public interface ConnectionListener {
        void onConnected(UsbDevice device);
        void onConnectionFailed(String reason);
        void onDisconnected();
        /** Fired when the SP-1 vanishes from USB unexpectedly (e.g. it reboots when switching modes). */
        void onUnexpectedDetach();
    }

    /** Register a listener to be notified of connect/fail/disconnect events. */
    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    public Sp1UsbSerial(Context context) {
        this.context = context.getApplicationContext();
        this.usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
    }

    // ---------------------------------------------------------------
    // Step 0: diagnostics - run this first with the SP-1 plugged in
    // ---------------------------------------------------------------

    /** Logs every attached USB device and its interfaces/endpoints/classes. */
    public void logAttachedDevices() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices.isEmpty()) {
            Log.i(TAG, "No USB devices currently attached.");
            return;
        }
        for (UsbDevice device : devices.values()) {
            Log.i(TAG, String.format("Device: name=%s vid=0x%04X pid=0x%04X interfaces=%d",
                    device.getDeviceName(), device.getVendorId(), device.getProductId(),
                    device.getInterfaceCount()));
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface intf = device.getInterface(i);
                Log.i(TAG, String.format("  Interface %d: class=0x%02X subclass=0x%02X endpoints=%d",
                        intf.getId(), intf.getInterfaceClass(), intf.getInterfaceSubclass(),
                        intf.getEndpointCount()));
                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint ep = intf.getEndpoint(e);
                    String dir = (ep.getDirection() == UsbConstants.USB_DIR_IN) ? "IN" : "OUT";
                    String type = (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) ? "BULK"
                            : (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) ? "INTERRUPT"
                            : "OTHER";
                    Log.i(TAG, String.format("    Endpoint: dir=%s type=%s addr=0x%02X",
                            dir, type, ep.getAddress()));
                }
            }
        }
    }

    /**
     * Same info as logAttachedDevices(), but returned as a String so it can be
     * shown directly in the app's own UI (a TextView, AlertDialog, etc.) instead
     * of needing to find Logcat inside AIDE.
     */
    public String getAttachedDevicesSummary() {
        StringBuilder sb = new StringBuilder();
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices.isEmpty()) {
            return "No USB devices currently attached.";
        }
        for (UsbDevice device : devices.values()) {
            sb.append(String.format("Device: %s\nvid=0x%04X pid=0x%04X interfaces=%d\n",
                    device.getDeviceName(), device.getVendorId(), device.getProductId(),
                    device.getInterfaceCount()));
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface intf = device.getInterface(i);
                sb.append(String.format("  Interface %d: class=0x%02X subclass=0x%02X endpoints=%d\n",
                        intf.getId(), intf.getInterfaceClass(), intf.getInterfaceSubclass(),
                        intf.getEndpointCount()));
                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint ep = intf.getEndpoint(e);
                    String dir = (ep.getDirection() == UsbConstants.USB_DIR_IN) ? "IN" : "OUT";
                    String type = (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) ? "BULK"
                            : (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) ? "INTERRUPT"
                            : "OTHER";
                    sb.append(String.format("    Endpoint: dir=%s type=%s addr=0x%02X\n",
                            dir, type, ep.getAddress()));
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Convenience: returns the first attached USB device, or null if none. */
    public UsbDevice getFirstAttachedDevice() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices.isEmpty()) return null;
        return devices.values().iterator().next();
    }

    /**
     * Returns the SP-1 specifically (matched by VID/PID), or null if it isn't
     * currently attached. Use this instead of getFirstAttachedDevice() once
     * other USB devices (hubs, etc.) might also be present.
     */
    public UsbDevice getSp1Device() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        for (UsbDevice device : devices.values()) {
            if (device.getVendorId() == SP1_VENDOR_ID && device.getProductId() == SP1_PRODUCT_ID) {
                return device;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Step 1: permission
    // ---------------------------------------------------------------

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            synchronized (this) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) {
                    Log.i(TAG, "USB permission granted for " + device.getDeviceName());
                    openConnection(device);
                } else {
                    Log.w(TAG, "USB permission denied");
                    if (connectionListener != null) {
                        connectionListener.onConnectionFailed("USB permission denied");
                    }
                }
            }
        }
    };

    /**
     * Watches for the SP-1 unexpectedly leaving/rejoining USB - this happens
     * when it switches from boot mode into transfer mode, since bootloader
     * and app are separate firmware images and "exit bootloader" is a real
     * reboot, not just a state flag. When that happens mid-session, our old
     * UsbDeviceConnection is dead even though the device is still physically
     * plugged in.
     */
    private final BroadcastReceiver usbAttachDetachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (device != null && connectedDevice != null
                        && device.getDeviceName().equals(connectedDevice.getDeviceName())) {
                    Log.i(TAG, "SP-1 detached unexpectedly: " + device.getDeviceName());
                    handleUnexpectedDetach();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (device != null) {
                    Log.i(TAG, String.format("USB device attached: vid=0x%04X pid=0x%04X",
                            device.getVendorId(), device.getProductId()));
                }
            }
        }
    };

    private void handleUnexpectedDetach() {
        readThreadRunning.set(false);
        connected.set(false);
        connection = null; // already gone at the OS level - nothing to close/release
        connectedDevice = null;
        if (connectionListener != null) {
            connectionListener.onUnexpectedDetach();
        }
    }

    /** Call once, e.g. in Activity.onCreate(), before requesting a connection. */
    public void registerPermissionReceiver() {
        IntentFilter permissionFilter = new IntentFilter(ACTION_USB_PERMISSION);
        // RECEIVER_NOT_EXPORTED requires API 33+; drop the flag if targeting lower.
        context.registerReceiver(permissionReceiver, permissionFilter);

        IntentFilter attachDetachFilter = new IntentFilter();
        attachDetachFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        attachDetachFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        context.registerReceiver(usbAttachDetachReceiver, attachDetachFilter);
    }

    public void unregisterPermissionReceiver() {
        try {
            context.unregisterReceiver(permissionReceiver);
        } catch (IllegalArgumentException ignored) {
            // wasn't registered
        }
        try {
            context.unregisterReceiver(usbAttachDetachReceiver);
        } catch (IllegalArgumentException ignored) {
            // wasn't registered
        }
    }



    /** Kicks off the permission dialog (if needed) then opens the connection. */
    public void requestConnection(UsbDevice device) {
        if (device == null) {
            Log.w(TAG, "requestConnection called with null device");
            return;
        }
        if (usbManager.hasPermission(device)) {
            openConnection(device);
            return;
        }
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                context, 0, new Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_MUTABLE);
        usbManager.requestPermission(device, permissionIntent);
    }

    // ---------------------------------------------------------------
    // Step 2: claim interfaces + CDC bring-up
    // ---------------------------------------------------------------

    private void openConnection(UsbDevice device) {
        connectedDevice = device;
        connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "openDevice() failed");
            if (connectionListener != null) connectionListener.onConnectionFailed("openDevice() failed");
            connectedDevice = null;
            return;
        }

        // Find the data interface (has both a bulk IN and bulk OUT endpoint)
        // and, if present, a separate CDC control interface.
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == USB_CLASS_CDC_CONTROL) {
                controlInterface = intf;
            }
            UsbEndpoint in = null, out = null;
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep;
                else out = ep;
            }
            if (in != null && out != null) {
                dataInterface = intf;
                endpointIn = in;
                endpointOut = out;
            }
        }

        if (dataInterface == null) {
            Log.e(TAG, "No interface with bulk IN+OUT endpoints found - check logAttachedDevices() output");
            if (connectionListener != null) {
                connectionListener.onConnectionFailed("No bulk IN+OUT interface found on device");
            }
            connection.close();
            connection = null;
            connectedDevice = null;
            return;
        }

        // force=true: take the interface even if a kernel driver has it claimed.
        boolean claimedData = connection.claimInterface(dataInterface, true);
        boolean claimedControl = (controlInterface == null)
                || connection.claimInterface(controlInterface, true);
        if (!claimedData || !claimedControl) {
            Log.e(TAG, "claimInterface failed (data=" + claimedData + " control=" + claimedControl + ")");
            if (connectionListener != null) {
                connectionListener.onConnectionFailed(
                        "claimInterface failed (data=" + claimedData + " control=" + claimedControl + ")");
            }
            connection.close();
            connection = null;
            connectedDevice = null;
            return;
        }

        if (!sendLineCoding(115200) || !sendControlLineState(true, true)) {
            Log.e(TAG, "CDC bring-up control transfers failed");
            if (connectionListener != null) {
                connectionListener.onConnectionFailed("CDC bring-up control transfers failed");
            }
            connection.close();
            connection = null;
            connectedDevice = null;
            return;
        }

        connected.set(true);
        startReadThread();
        Log.i(TAG, "SP-1 connected over USB");
        if (connectionListener != null) connectionListener.onConnected(device);
    }

    private boolean sendLineCoding(int baudRate) {
        int targetInterface = (controlInterface != null) ? controlInterface.getId() : dataInterface.getId();
        byte[] lineCoding = new byte[7];
        lineCoding[0] = (byte) (baudRate & 0xFF);
        lineCoding[1] = (byte) ((baudRate >> 8) & 0xFF);
        lineCoding[2] = (byte) ((baudRate >> 16) & 0xFF);
        lineCoding[3] = (byte) ((baudRate >> 24) & 0xFF);
        lineCoding[4] = 0; // 1 stop bit
        lineCoding[5] = 0; // no parity
        lineCoding[6] = 8; // 8 data bits
        int result = connection.controlTransfer(
                REQTYPE_CLASS_INTERFACE_OUT, REQ_SET_LINE_CODING,
                0, targetInterface, lineCoding, lineCoding.length, 1000);
        Log.i(TAG, "SET_LINE_CODING result=" + result);
        return result >= 0;
    }

    private boolean sendControlLineState(boolean dtr, boolean rts) {
        int targetInterface = (controlInterface != null) ? controlInterface.getId() : dataInterface.getId();
        int value = (dtr ? 0x01 : 0) | (rts ? 0x02 : 0);
        int result = connection.controlTransfer(
                REQTYPE_CLASS_INTERFACE_OUT, REQ_SET_CONTROL_LINE_STATE,
                value, targetInterface, null, 0, 1000);
        Log.i(TAG, "SET_CONTROL_LINE_STATE result=" + result);
        return result >= 0;
    }

    // ---------------------------------------------------------------
    // Step 3: raw read/write
    // ---------------------------------------------------------------

    public boolean isConnected() {
        return connected.get();
    }

    public void setReadListener(ReadListener listener) {
        this.readListener = listener;
    }

    /** Writes raw bytes out to the device. Returns bytes written, or -1 on failure. */
    public int write(byte[] data) {
        if (connection == null || endpointOut == null) return -1;
        return connection.bulkTransfer(endpointOut, data, data.length, 2000);
    }

    private void startReadThread() {
        if (readThreadRunning.get()) return;
        readThreadRunning.set(true);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[4096];
                while (readThreadRunning.get() && connection != null) {
                    int len = connection.bulkTransfer(endpointIn, buf, buf.length, 500);
                    if (len > 0) {
                        byte[] chunk = new byte[len];
                        System.arraycopy(buf, 0, chunk, 0, len);
                        synchronized (rxLock) {
                            for (byte b : chunk) rxBuffer.add(b);
                            rxLock.notifyAll();
                        }
                        if (readListener != null) {
                            readListener.onDataReceived(chunk, len);
                        }
                    }
                    // len == 0 or negative just means "nothing arrived within timeout" - keep looping
                }
            }
        }, "SP1UsbReadThread");
        t.setDaemon(true);
        t.start();
    }

    /** Clears any buffered-but-unconsumed received bytes. */
    public void drainRx() {
        synchronized (rxLock) {
            rxBuffer.clear();
        }
    }

    /**
     * Blocks (up to timeoutMs) waiting for a complete 0x00-terminated COBS
     * packet to show up in the rx buffer. Returns null on timeout.
     */
    private byte[] readPacket(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (rxLock) {
            while (true) {
                int zeroIdx = -1;
                for (int i = 0; i < rxBuffer.size(); i++) {
                    if (rxBuffer.get(i) == 0) {
                        zeroIdx = i;
                        break;
                    }
                }
                if (zeroIdx >= 0) {
                    byte[] pkt = new byte[zeroIdx + 1];
                    for (int i = 0; i <= zeroIdx; i++) {
                        pkt[i] = rxBuffer.remove(0);
                    }
                    return pkt;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return null;
                try {
                    rxLock.wait(remaining);
                } catch (InterruptedException e) {
                    return null;
                }
            }
        }
    }

    /**
     * Sends a command and blocks (this calling thread) until a response
     * packet arrives or timeoutMs elapses. Call this from a background
     * thread, never directly from a UI click handler, since it blocks.
     */
    public Sp1Protocol.Response sendCmd(int cmd, byte[] payload, long timeoutMs) {
        drainRx();
        byte[] packet = Sp1Protocol.buildPacket(cmd, payload, seq++);
        write(packet);
        byte[] raw = readPacket(timeoutMs);
        if (raw == null) return null;
        return Sp1Protocol.parseResponse(raw);
    }

    /** Sends a command without waiting for (or expecting) a reply. */
    public void sendNoReply(int cmd, byte[] payload) {
        byte[] packet = Sp1Protocol.buildPacket(cmd, payload, seq++);
        write(packet);
    }

    /** Reports transfer progress (0-100) and a short status line. */
    public interface TransferProgressListener {
        void onProgress(int percent, String statusText);
    }

    /**
     * Writes `data` to the device starting at sector `sectorOffset`, in
     * 128-byte chunks, verifying the device's write counter after every 64
     * chunks (one sector) and retrying that sector up to 3 times if the
     * counter doesn't match (a dropped packet). Ported closely from
     * transferData() in stemloader.js, including its exact retry mechanics.
     * Blocks the calling thread - call from a background thread.
     *
     * @param data          raw bytes to write - should be a multiple of
     *                      SECTOR_SIZE for clean sector alignment
     * @param sectorOffset  destination offset in SP-1 memory, in sectors
     * @param progress      optional progress callback, may be null
     * @return a description of the result (success or specific failure)
     */
    public String writeData(byte[] data, int sectorOffset, TransferProgressListener progress) {
        if (!isConnected()) return "Not connected.";

        sendCmd(Sp1Protocol.CMD_CLEAR_FOR_WRITE, new byte[0], 3000);

        int songByteOffset = sectorOffset * Sp1Protocol.SECTOR_SIZE;
        int dataLen = data.length;
        int chunkCounter = 0;
        int numRetries = 0;
        int numSectorRewrites = 0;

        for (int offset = 0; offset < dataLen; offset += Sp1Protocol.CHUNK_SIZE) {
            if (chunkCounter == Sp1Protocol.CHUNKS_PER_SECTOR) {
                int writeCounter = -1;
                Sp1Protocol.Response counterResp =
                        sendCmd(Sp1Protocol.CMD_GET_WRITE_COUNTER, new byte[0], 3000);
                if (counterResp != null && counterResp.crcOk && counterResp.payload.length >= 4) {
                    writeCounter = Sp1Protocol.readLE32(counterResp.payload, 0);
                }
                if (writeCounter != chunkCounter) {
                    if (numRetries < 3) {
                        offset -= Sp1Protocol.SECTOR_SIZE;
                        numRetries++;
                        numSectorRewrites++;
                        sendCmd(Sp1Protocol.CMD_CLEAR_FOR_WRITE, new byte[0], 3000);
                        if (progress != null) {
                            progress.onProgress((int) (100L * Math.max(offset, 0) / dataLen),
                                    "Dropped sector at 0x" + Integer.toHexString(offset)
                                            + " - retry #" + numRetries);
                        }
                    } else {
                        return "Failed: sector at offset 0x" + Integer.toHexString(offset)
                                + " did not verify after 3 attempts";
                    }
                } else {
                    numRetries = 0;
                }
                chunkCounter = 0;
            }

            chunkCounter++;
            byte[] payload = new byte[Sp1Protocol.PAYLOAD_SIZE];
            Sp1Protocol.writeLE32(payload, 0, chunkCounter);
            Sp1Protocol.writeLE32(payload, 4, songByteOffset + offset);
            int copyLen = Math.min(Sp1Protocol.CHUNK_SIZE, dataLen - offset);
            System.arraycopy(data, offset, payload, 8, copyLen);
            sendNoReply(Sp1Protocol.CMD_WRITE, payload);

            if (progress != null) {
                int pct = (int) (100L * offset / dataLen);
                progress.onProgress(pct, pct + "%");
            }
        }

        // The loop above only verifies a sector once the FIRST chunk of the
        // NEXT sector is about to be sent - so the very last sector written
        // (including single-sector writes like metadata/album-end, or each
        // sector in a streamed transfer) is never checked without this.
        if (chunkCounter > 0) {
            boolean verified = false;
            for (int attempt = 0; attempt < 3 && !verified; attempt++) {
                int writeCounter = -1;
                Sp1Protocol.Response counterResp =
                        sendCmd(Sp1Protocol.CMD_GET_WRITE_COUNTER, new byte[0], 3000);
                if (counterResp != null && counterResp.crcOk && counterResp.payload.length >= 4) {
                    writeCounter = Sp1Protocol.readLE32(counterResp.payload, 0);
                }
                if (writeCounter == chunkCounter) {
                    verified = true;
                } else if (attempt < 2) {
                    numSectorRewrites++;
                    sendCmd(Sp1Protocol.CMD_CLEAR_FOR_WRITE, new byte[0], 3000);
                    int sectorStart = dataLen - chunkCounter * Sp1Protocol.CHUNK_SIZE;
                    int resendCounter = 0;
                    for (int off = sectorStart; off < dataLen; off += Sp1Protocol.CHUNK_SIZE) {
                        resendCounter++;
                        byte[] payload = new byte[Sp1Protocol.PAYLOAD_SIZE];
                        Sp1Protocol.writeLE32(payload, 0, resendCounter);
                        Sp1Protocol.writeLE32(payload, 4, songByteOffset + off);
                        int copyLen = Math.min(Sp1Protocol.CHUNK_SIZE, dataLen - off);
                        System.arraycopy(data, off, payload, 8, copyLen);
                        sendNoReply(Sp1Protocol.CMD_WRITE, payload);
                    }
                }
            }
            if (!verified) {
                return "Failed: final sector at offset 0x" + Integer.toHexString(sectorOffset)
                        + " did not verify after 3 attempts";
            }
        }

        if (progress != null) progress.onProgress(100, "Done");
        return "OK - wrote " + dataLen + " bytes (" + (dataLen / Sp1Protocol.SECTOR_SIZE)
                + " sectors) at sector offset 0x" + Integer.toHexString(sectorOffset)
                + (numSectorRewrites > 0 ? ", " + numSectorRewrites + " sector(s) needed a retry" : "");
    }

    /**
     * Transfers the full current track list from Sp1AlbumState. Writes
     * metadata + album-end reflecting the WHOLE current list every time
     * (matching the website), then transfers each song's audio - but skips
     * any song already marked fully transferred, and resumes any partially
     * transferred song from where it left off, persisting progress after
     * each sector so an interrupted transfer can pick back up later.
     * Blocks the calling thread - call from a background thread.
     */
    public String transferAlbum(Sp1AlbumState albumState, TransferProgressListener progress) {
        if (!isConnected()) return "Not connected.";

        List<Sp1AlbumState.Song> songs = albumState.getSongs();
        if (songs.isEmpty()) return "No songs in the track list.";

        transferInProgress.set(true);
        try {
            List<Sp1AlbumBuilder.Song> builderSongs = new ArrayList<Sp1AlbumBuilder.Song>();
            for (Sp1AlbumState.Song s : songs) {
                builderSongs.add(new Sp1AlbumBuilder.Song(s.artist, s.title, s.lengthSectors));
            }
            Sp1AlbumBuilder.BuiltAlbum album;
            try {
                album = Sp1AlbumBuilder.build(albumState.getAlbumTitle(), builderSongs);
            } catch (IllegalArgumentException e) {
                return "Album layout failed: " + e.getMessage();
            }

            if (progress != null) progress.onProgress(0, "Writing album metadata...");
            String metaResult = writeData(album.metadataSector, 0, null);
            if (!metaResult.startsWith("OK")) return "Metadata write failed: " + metaResult;
            if (abortRequested.getAndSet(false)) return "Transfer stopped by user. Progress has been saved.";

            if (progress != null) progress.onProgress(0, "Writing album-end marker...");
            String endResult = writeData(album.albumEndSector, album.albumEndSectorIndex, null);
            if (!endResult.startsWith("OK")) return "Album-end write failed: " + endResult;
            if (abortRequested.getAndSet(false)) return "Transfer stopped by user. Progress has been saved.";

            int totalRemainingSectors = 0;
            for (Sp1AlbumState.Song s : songs) {
                if (!s.isDone()) totalRemainingSectors += (s.lengthSectors - s.transferredSectors);
            }
            int sectorsWrittenSoFar = 0;

            for (int i = 0; i < songs.size(); i++) {
                Sp1AlbumState.Song stateSong = songs.get(i);
                stateSong.offsetSectors = album.songs.get(i).offsetSectors; // keep in sync with the layout just built

                if (stateSong.isDone()) {
                    continue; // already fully transferred - matches the website's green-dot skip
                }

                InputStream in;
                try {
                    in = context.getContentResolver().openInputStream(android.net.Uri.parse(stateSong.fileUri));
                } catch (Exception e) {
                    return "Failed to open \"" + stateSong.title + "\": " + e.getMessage()
                            + " (file may have moved - try re-adding it)";
                }

                Sp1AudioEncoder.WavHeader header;
                try {
                    header = Sp1AudioEncoder.readWavHeader(in);
                } catch (Exception e) {
                    closeQuietly(in);
                    return "WAV parsing failed for \"" + stateSong.title + "\": " + e.getMessage();
                }

                Sp1AudioEncoder.SectorEncoder encoder = new Sp1AudioEncoder.SectorEncoder(header.numChannels, stateSong.bpm);
                int resumeFromSector = stateSong.transferredSectors;

                for (int sectorIndex = 0; sectorIndex < header.totalSectors; sectorIndex++) {
                    int framesInSector = Math.min(Sp1AudioEncoder.FRAMES_PER_SECTOR,
                            header.totalFrames - sectorIndex * Sp1AudioEncoder.FRAMES_PER_SECTOR);

                    byte[] sectorBytes;
                    try {
                        sectorBytes = encoder.encodeNextSector(in, framesInSector, sectorIndex);
                    } catch (IOException e) {
                        closeQuietly(in);
                        return "Failed reading audio for \"" + stateSong.title + "\" at sector "
                                + sectorIndex + ": " + e.getMessage();
                    }

                    if (sectorIndex < resumeFromSector) {
                        continue; // already transferred in a prior run - still had to decode to stay in sync with the stream
                    }

                    String sectorResult = writeData(sectorBytes, stateSong.offsetSectors + sectorIndex, null);
                    if (!sectorResult.startsWith("OK")) {
                        closeQuietly(in);
                        return "Failed writing \"" + stateSong.title + "\" at sector " + sectorIndex + ": " + sectorResult;
                    }
                    albumState.markSectorTransferred(stateSong, sectorIndex);

                    sectorsWrittenSoFar++;
                    if (progress != null && totalRemainingSectors > 0) {
                        int pct = (int) (100L * sectorsWrittenSoFar / totalRemainingSectors);
                        progress.onProgress(pct, "Song " + (i + 1) + "/" + songs.size() + " (\"" + stateSong.title
                                + "\"): sector " + (sectorIndex + 1) + "/" + header.totalSectors);
                    }

                    if (abortRequested.getAndSet(false)) {
                        closeQuietly(in);
                        return "Transfer stopped by user. Progress has been saved - reconnect and tap\n"
                                + "Transfer Album again to continue where you left off.";
                    }
                }
                closeQuietly(in);
            }

            return "Album transfer complete (" + songs.size() + " song(s)).";
        } finally {
            transferInProgress.set(false);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            if (in != null) in.close();
        } catch (Exception ignored) {
        }
    }


    /**
     * Writes a small, deterministic block of dummy data to a sector far
     * beyond any plausible existing album, purely to validate the WRITE +
     * verify-counter mechanism against the real device without risking
     * anything that matters. There's no READ command in this protocol, so
     * this only confirms chunk delivery (no dropped packets) - it can't
     * confirm the bytes landed correctly, only that the device acknowledged
     * receiving the right number of them.
     */
    public String testWrite(TransferProgressListener progress) {
        int testSector = 0x70000; // deep into the ~0x76000-sector capacity, nowhere near a small album
        int numSectors = 3;
        byte[] data = new byte[numSectors * Sp1Protocol.SECTOR_SIZE];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i & 0xFF); // simple repeating pattern
        }
        return writeData(data, testSector, progress);
    }


    /**
     * Sends CMD_ECHO with a "heartbeat" payload and describes the result as
     * a String, ready to show directly in the UI. Blocks the calling thread -
     * call this from a background thread.
     */
    public String sendEcho() {
        if (!isConnected()) return "Not connected.";
        byte[] payload;
        try {
            payload = "heartbeat".getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            payload = "heartbeat".getBytes();
        }
        Sp1Protocol.Response resp = sendCmd(Sp1Protocol.CMD_ECHO, payload, 3000);
        if (resp == null) return "No response (timed out after 3s)";
        if (!resp.crcOk) return "CRC error in response";
        if (resp.cmd == Sp1Protocol.CMD_ERROR) {
            return "SP-1 error: " + new String(resp.payload);
        }
        if (resp.cmd != Sp1Protocol.CMD_ECHO + 1) {
            return "Unexpected response cmd: 0x" + Integer.toHexString(resp.cmd);
        }
        return "Echo OK - got back: \"" + new String(resp.payload) + "\"";
    }

    /**
     * Converts a response payload the way the SP-1's GET_DEV_STATE reply works:
     * each byte is a single digit (0-9), concatenated into a string like
     * "00100" (boot mode) or "10510" (transfer mode, ready). Matches the JS's
     * `payload.join("")` behavior exactly.
     */
    private static String payloadAsDigitString(byte[] payload) {
        StringBuilder sb = new StringBuilder();
        for (byte b : payload) {
            sb.append(b & 0xFF);
        }
        return sb.toString();
    }

    /** Converts bytes to a String, stopping at the first null byte (C-string style). */
    private static String bytesToCString(byte[] b) {
        int end = b.length;
        for (int i = 0; i < b.length; i++) {
            if (b[i] == 0) {
                end = i;
                break;
            }
        }
        return new String(b, 0, end);
    }

    private static String toHexString(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte value : b) {
            sb.append(String.format("%02x", value & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Queries the device's state, automatically switches it out of boot mode
     * into transfer mode if needed, then (once in transfer mode) checks the
     * existing album data, device ID, and album title. Returns a multi-line
     * summary ready to show in the UI. Blocks the calling thread - call this
     * from a background thread.
     */
    public String checkDeviceState() {
        if (!isConnected()) return "Not connected.";
        StringBuilder log = new StringBuilder();

        Sp1Protocol.Response stateResp = sendCmd(Sp1Protocol.CMD_GET_DEV_STATE, new byte[0], 3000);
        if (stateResp == null) return "No response to GET_DEV_STATE (timed out)";
        if (!stateResp.crcOk) return "CRC error on GET_DEV_STATE response";
        if (stateResp.cmd == Sp1Protocol.CMD_ERROR) {
            return "SP-1 error: " + bytesToCString(stateResp.payload);
        }

        String stateStr = payloadAsDigitString(stateResp.payload);
        log.append("Device state: ").append(stateStr).append("\n");

        if ("00100".equals(stateStr)) {
            log.append("Boot mode - switching to transfer mode...\n");
            sendCmd(Sp1Protocol.CMD_SET_DEV_STATE, new byte[]{1}, 3000);
            sendNoReply(Sp1Protocol.CMD_EXIT_BOOTLOADER, new byte[0]);
            log.append("Sent mode-switch commands. The SP-1 reboots into transfer mode at this\n")
               .append("point, which disconnects and re-enumerates over USB (this is expected -\n")
               .append("not an error). Wait a few seconds, tap Connect to SP-1 again once it's\n")
               .append("back, then Check Device State again.\n");
            return log.toString();
        }

        if (!"10510".equals(stateStr)) {
            log.append("Device is not in transfer-ready state - stopping here.\n");
            return log.toString();
        }

        log.append("Transfer mode: ready.\n");

        boolean albumIsRead = false;
        Sp1Protocol.Response verify = sendCmd(Sp1Protocol.CMD_VERIFY_DATA, new byte[0], 3000);
        if (verify != null && verify.crcOk && verify.payload.length >= 2) {
            albumIsRead = verify.payload[0] != 0;
            int errNum = verify.payload[1] & 0xFF;
            log.append("Album data: ").append(albumIsRead ? "OK" : Sp1Protocol.describeAlbumError(errNum))
                    .append("\n");
        } else {
            log.append("VERIFY_DATA: no/invalid response\n");
        }

        Sp1Protocol.Response devId = sendCmd(Sp1Protocol.CMD_GET_DEV_ID, new byte[0], 3000);
        if (devId != null && devId.crcOk) {
            log.append("Device ID: ").append(toHexString(devId.payload)).append("\n");
        }

        if (albumIsRead) {
            Sp1Protocol.Response albumTitle = sendCmd(Sp1Protocol.CMD_GET_ALBUM_TITLE, new byte[0], 3000);
            if (albumTitle != null && albumTitle.crcOk) {
                log.append("Album on device: \"").append(bytesToCString(albumTitle.payload)).append("\"\n");
            }
        }

        return log.toString();
    }

    // ---------------------------------------------------------------
    // Teardown
    // ---------------------------------------------------------------

    /**
     * Also used as the "abort" action: if a transfer is currently running,
     * this doesn't tear down the connection directly (that would race with
     * the transfer thread mid-write) - it just asks the transfer to stop at
     * its next checkpoint. The transfer loop itself returns cleanly, and
     * the caller (see transferAlbum()'s "Transfer stopped by user" result)
     * is expected to call disconnect() again afterward on the main thread
     * to do the actual teardown.
     */
    public void disconnect() {
        if (transferInProgress.get()) {
            abortRequested.set(true);
            return;
        }
        boolean wasConnected = connected.get();
        readThreadRunning.set(false);
        connected.set(false);
        if (connection != null) {
            if (dataInterface != null) connection.releaseInterface(dataInterface);
            if (controlInterface != null) connection.releaseInterface(controlInterface);
            connection.close();
            connection = null;
        }
        connectedDevice = null;
        if (wasConnected && connectionListener != null) {
            connectionListener.onDisconnected();
        }
    }
}


package com.joshua.sp1loader;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test/control screen - no XML layout files needed, everything is built in
 * code. Diagnostic buttons (scan/connect/echo/state/test write) at top for
 * debugging the USB link; below that, a real track-list editor: add songs
 * (title/artist come from the WAV's own metadata, filename as fallback),
 * delete songs, and transfer the whole album - skipping anything already
 * fully transferred, same as the website.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_ADD_SONG = 2001;

    private Sp1UsbSerial usb;
    private Sp1AlbumState albumState;
    private TextView output;
    private LinearLayout songListContainer;
    private TextView albumTitleView;
    private boolean transferActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        usb = new Sp1UsbSerial(this);
        usb.registerPermissionReceiver();
        albumState = new Sp1AlbumState(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        // --- Diagnostic tools ---

        final Button scanButton = new Button(this);
        scanButton.setText("Scan USB devices");
        root.addView(scanButton);

        final Button connectButton = new Button(this);
        connectButton.setText("Connect to SP-1");
        root.addView(connectButton);

        final Button disconnectButton = new Button(this);
        disconnectButton.setText("Disconnect");
        disconnectButton.setEnabled(false);
        root.addView(disconnectButton);

        final Button echoButton = new Button(this);
        echoButton.setText("Send ECHO");
        echoButton.setEnabled(false);
        root.addView(echoButton);

        final Button stateButton = new Button(this);
        stateButton.setText("Check Device State");
        stateButton.setEnabled(false);
        root.addView(stateButton);

        final Button testWriteButton = new Button(this);
        testWriteButton.setText("Test Write");
        testWriteButton.setEnabled(false);
        root.addView(testWriteButton);

        final ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        root.addView(progressBar);

        // --- Track list ---

        albumTitleView = new TextView(this);
        albumTitleView.setPadding(0, 24, 0, 0);
        root.addView(albumTitleView);

        songListContainer = new LinearLayout(this);
        songListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(songListContainer);

        final Button addSongButton = new Button(this);
        addSongButton.setText("Add Song");
        root.addView(addSongButton);

        final Button transferAlbumButton = new Button(this);
        transferAlbumButton.setText("Transfer Album");
        transferAlbumButton.setEnabled(false);
        root.addView(transferAlbumButton);

        output = new TextView(this);
        output.setText("Tap 'Scan USB devices' with the SP-1 plugged in.");
        output.setTextIsSelectable(true);
        output.setPadding(0, 24, 0, 0);
        output.setGravity(Gravity.START);
        root.addView(output);

        ScrollView outerScroll = new ScrollView(this);
        outerScroll.addView(root);
        setContentView(outerScroll);
        refreshSongListUI();

        usb.setConnectionListener(new Sp1UsbSerial.ConnectionListener() {
            @Override
            public void onConnected(UsbDevice device) {
                output.setText("Connected to " + device.getDeviceName() + " - ready.");
                connectButton.setEnabled(false);
                disconnectButton.setEnabled(true);
                echoButton.setEnabled(true);
                stateButton.setEnabled(true);
                testWriteButton.setEnabled(true);
                transferAlbumButton.setEnabled(true);
            }

            @Override
            public void onConnectionFailed(String reason) {
                output.setText("Connection failed: " + reason);
                connectButton.setEnabled(true);
                disconnectButton.setEnabled(false);
                echoButton.setEnabled(false);
                stateButton.setEnabled(false);
                testWriteButton.setEnabled(false);
                transferAlbumButton.setEnabled(false);
            }

            @Override
            public void onDisconnected() {
                output.setText("Disconnected.");
                connectButton.setEnabled(true);
                disconnectButton.setEnabled(false);
                echoButton.setEnabled(false);
                stateButton.setEnabled(false);
                testWriteButton.setEnabled(false);
                transferAlbumButton.setEnabled(false);
            }

            @Override
            public void onUnexpectedDetach() {
                output.setText("SP-1 disconnected from USB (likely rebooting into transfer mode) -\n"
                        + "wait a few seconds, then tap Connect to SP-1 again.");
                connectButton.setEnabled(true);
                disconnectButton.setEnabled(false);
                echoButton.setEnabled(false);
                stateButton.setEnabled(false);
                testWriteButton.setEnabled(false);
                transferAlbumButton.setEnabled(false);
            }
        });

        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                output.setText(usb.getAttachedDevicesSummary());
            }
        });

        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UsbDevice device = usb.getSp1Device();
                if (device == null) {
                    output.setText("SP-1 not found among attached USB devices - check it's plugged in.");
                    return;
                }
                output.setText("Requesting permission for: " + device.getDeviceName()
                        + "\n(a system dialog should pop up - approve it)");
                usb.requestConnection(device);
            }
        });

        disconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (transferActive) {
                    output.setText("Stopping transfer...");
                }
                usb.disconnect();
            }
        });

        echoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                output.setText("Sending ECHO...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String result = usb.sendEcho();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                output.setText(result);
                            }
                        });
                    }
                }, "SP1EchoThread").start();
            }
        });

        stateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                output.setText("Checking device state...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String result = usb.checkDeviceState();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                output.setText(result);
                            }
                        });
                    }
                }, "SP1StateThread").start();
            }
        });

        testWriteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                output.setText("Test writing 3 dummy sectors to sector 0x70000 (far outside any\n"
                        + "real album - safe, but this does touch the device)...");
                progressBar.setProgress(0);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String result = usb.testWrite(new Sp1UsbSerial.TransferProgressListener() {
                            @Override
                            public void onProgress(final int percent, final String statusText) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        progressBar.setProgress(percent);
                                        output.setText(statusText);
                                    }
                                });
                            }
                        });
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                output.setText(result);
                            }
                        });
                    }
                }, "SP1TestWriteThread").start();
            }
        });

        addSongButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, REQUEST_ADD_SONG);
            }
        });

        transferAlbumButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (albumState.getSongs().isEmpty()) {
                    output.setText("Add at least one song first.");
                    return;
                }
                output.setText("Starting album transfer...");
                progressBar.setProgress(0);

                transferActive = true;
                scanButton.setEnabled(false);
                connectButton.setEnabled(false);
                echoButton.setEnabled(false);
                stateButton.setEnabled(false);
                testWriteButton.setEnabled(false);
                addSongButton.setEnabled(false);
                transferAlbumButton.setEnabled(false);
                // disconnectButton stays enabled - it's how a transfer gets stopped
                refreshSongListUI(); // also disables each row's Delete button

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String result = usb.transferAlbum(albumState,
                                new Sp1UsbSerial.TransferProgressListener() {
                                    @Override
                                    public void onProgress(final int percent, final String statusText) {
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                progressBar.setProgress(percent);
                                                output.setText(statusText);
                                            }
                                        });
                                    }
                                });
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                output.setText(result);
                                transferActive = false;
                                refreshSongListUI(); // re-enables each row's Delete button

                                // Not connection-gated - always safe to re-enable regardless of outcome.
                                scanButton.setEnabled(true);
                                addSongButton.setEnabled(true);

                                if (result != null && result.startsWith("Transfer stopped by user")) {
                                    // usb.disconnect() only *requested* a stop while the transfer
                                    // was running - now that it's actually done, call it again on
                                    // this (main) thread to do the real teardown and reset the
                                    // connection-gated buttons via the normal onDisconnected() callback.
                                    usb.disconnect();
                                } else {
                                    // normal completion (success or a real failure) - still connected,
                                    // so just re-enable what was disabled for the transfer.
                                    echoButton.setEnabled(true);
                                    stateButton.setEnabled(true);
                                    testWriteButton.setEnabled(true);
                                    transferAlbumButton.setEnabled(true);
                                }
                            }
                        });
                    }
                }, "SP1TransferAlbumThread").start();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_ADD_SONG || resultCode != RESULT_OK || data == null) {
            return;
        }
        final Uri uri = data.getData();
        if (uri == null) return;

        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            // Not all providers support this - if it fails, the file just needs
            // re-adding later if it becomes unreachable, same as the website.
        }

        final String displayName = getFileDisplayName(uri);
        output.setText("Reading \"" + displayName + "\"...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    Sp1AudioEncoder.WavHeader header = Sp1AudioEncoder.readWavHeader(in);

                    String title = header.titleFromMetadata;
                    if (title == null || title.length() == 0) {
                        title = titleFromFileName(displayName);
                    }
                    final String finalTitle = title;
                    final String finalArtist = header.artistFromMetadata;
                    final double finalBpm = bpmFromFileName(displayName);
                    final int lengthSectors = header.totalSectors;

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Sp1AlbumState.Song added = albumState.addSong(uri.toString(), displayName,
                                    finalTitle, finalArtist, finalBpm, lengthSectors);
                            if (added == null) {
                                output.setText("Can't add \"" + finalTitle
                                        + "\" - not enough space left on the SP-1 (4GB / ~56 minutes total).");
                            } else {
                                refreshSongListUI();
                                output.setText("Added \"" + finalTitle + "\" (" + lengthSectors + " sectors).");
                            }
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            output.setText("Failed to add \"" + displayName + "\": " + e.getMessage());
                        }
                    });
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }, "SP1AddSongThread").start();
    }

    /** Rebuilds the on-screen track list from the current album state. */
    private void refreshSongListUI() {
        songListContainer.removeAllViews();
        List<Sp1AlbumState.Song> songs = albumState.getSongs();
        for (int i = 0; i < songs.size(); i++) {
            final int index = i;
            Sp1AlbumState.Song song = songs.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);

            String status = song.isDone() ? "done"
                    : (song.transferredSectors > 0 ? "partial (" + song.transferredSectors + "/" + song.lengthSectors + ")"
                    : "pending");
            TextView label = new TextView(this);
            label.setText((index + 1) + ". " + song.title + " - " + song.artist
                    + " (" + song.lengthSectors + " sectors, " + status + ")");
            label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(label);

            Button deleteButton = new Button(this);
            deleteButton.setText("Delete");
            deleteButton.setEnabled(!transferActive);
            deleteButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    albumState.removeSong(index);
                    refreshSongListUI();
                }
            });
            row.addView(deleteButton);

            songListContainer.addView(row);
        }
        albumTitleView.setText("Album: \"" + albumState.getAlbumTitle() + "\" - "
                + formatDuration(albumState.getAlbumLengthSectors() - 1) + "  "
                + formatBytes((long) albumState.getAlbumLengthSectors() * Sp1Protocol.SECTOR_SIZE)
                + " / " + formatBytes((long) Sp1Protocol.MAX_SECTORS * Sp1Protocol.SECTOR_SIZE));
    }

    private String formatDuration(int songSectors) {
        long totalSeconds = (long) Sp1AudioEncoder.FRAMES_PER_SECTOR * Math.max(0, songSectors) / 48000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private String formatBytes(long bytes) {
        double val = bytes;
        String unit = "B";
        if (val > 1000) { val /= 1000; unit = "KB"; }
        if (val > 1000) { val /= 1000; unit = "MB"; }
        if (val > 1000) { val /= 1000; unit = "GB"; }
        return String.format("%.1f%s", val, unit);
    }

    private String getFileDisplayName(Uri uri) {
        String result = null;
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    result = cursor.getString(nameIndex);
                }
            } finally {
                cursor.close();
            }
        }
        if (result == null) result = uri.getLastPathSegment();
        if (result == null) result = "untitled";
        return result;
    }

    private String titleFromFileName(String fileName) {
        String title = fileName;
        int dot = title.lastIndexOf('.');
        if (dot > 0) title = title.substring(0, dot);
        title = title.replaceAll("[_-]", " ").trim();
        if (title.length() == 0) title = fileName;
        return title;
    }

    /** Matches the reference site's own trick: a filename containing "66BPM" sets BPM to 66. */
    private double bpmFromFileName(String fileName) {
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*bpm", Pattern.CASE_INSENSITIVE).matcher(fileName);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException e) {
                return 80.0;
            }
        }
        return 80.0;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        usb.unregisterPermissionReceiver();
        usb.disconnect();
    }
}

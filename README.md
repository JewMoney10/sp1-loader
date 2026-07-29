# SP1 Loader

A native Android app for transferring songs to a Teenage Engineering SP-1 stem player over USB.

## Why this exists

The official [SP-1 stem loader](https://solderless.engineering/stemloader/) is a browser tool built on the Web Serial API. Web Serial works fine on desktop Chrome/Edge, but Android support is spotty — even on a fully updated Chrome, the transfer would fail with `Could not find Android Serial Service`, since that's an OS-level component that's only rolled out to a limited set of devices so far. There's also no reliable way around it: Android's kernel typically claims USB CDC-ACM devices before a browser's WebUSB/Serial layer can get to them.

Going native sidesteps this entirely. Android's own `UsbManager`/`UsbDeviceConnection` APIs have supported raw USB Host access since Android 3.1 — no browser, no OS-level serial service dependency, no external library.

## What it does

- Connects directly to the SP-1 over USB (raw CDC-ACM, no Web Serial)
- Speaks the SP-1's actual wire protocol (COBS framing, CRC8, the same command set the official tool uses)
- Encodes standard 24-bit/48kHz WAV files into the SP-1's on-flash audio format (block-shuffled sectors, interleaved stem frames, embedded tempo/LED sync data) — streamed one sector at a time, so memory use stays constant regardless of song length
- Keeps a local track list: add songs, delete them, see what's already transferred — since the SP-1 itself has no way to report its own song list back
- Resumable transfers: an interrupted transfer picks up where it left off rather than starting over
- Safe to interrupt: every sector's progress is saved as it's written, and stopping mid-transfer finishes cleanly rather than corrupting anything

## Credit

The wire protocol, audio format, and album metadata layout weren't publicly documented anywhere as a full spec — they were reconstructed from:
- The [SP-1-dev wiki](https://github.com/timknapen/SP-1-dev/wiki) (Tim Knapen) — flash layout, album metadata format, audio encoding
- The official stem loader's own client-side JavaScript (`sp-1_protocol.js`, `wav-converter.js`, `stemloader.js`) — the actual command set, packet framing, and transfer sequencing

This project exists entirely thanks to that groundwork. If you're looking for the official, actively maintained tool with a proper UI, use [solderless.engineering/stemloader](https://solderless.engineering/stemloader/) — this project is specifically for people who can't get that working from an Android phone.

## Requirements

- An Android phone with USB Host (OTG) support
- A Teenage Engineering SP-1
- Song files as 24-bit/48kHz WAV (up to 8 channels — 4 stereo stems). [sp1-merge](https://github.com/softmodded/sp1-merge) is a handy tool for combining separate stem files into one.

## Status

Personal project, built and tested against one SP-1 unit. Not affiliated with Teenage Engineering or Solderless.

## License

none
# SP1 Loader — Instructions

## Connecting the SP-1

1. Hold down the **1** and **4** track buttons on the SP-1.
2. While still holding them, plug the SP-1 into your phone.
3. Keep holding until the **track 1 LED** lights up, then release. The SP-1 is now in boot mode.
4. In the app, tap **Scan USB devices** to confirm the SP-1 shows up as an attached device.
5. Tap **Connect to SP-1**. A system permission dialog may appear — approve it.
6. Tap **Check Device State**. This tells the SP-1 to switch from boot mode into transfer mode. When it does, track 1's LED turns off and the side LEDs light up — the SP-1 actually reboots at this point, which briefly disconnects it from USB (that's expected, not an error).
7. Tap **Connect to SP-1** again. The app reconnects — this time to the SP-1 in transfer mode, ready to receive songs.

At this point you're ready to add and transfer songs.

## Adding songs

- Tap **Add Song** and pick a WAV file (24-bit / 48kHz, up to 8 channels).
- **Title and artist** come from the file's own embedded metadata if it has any; otherwise the filename is used (underscores and dashes become spaces).
- **BPM** is picked up automatically if the filename contains something like `66BPM`; otherwise it defaults to 80.
- Each added song appears in the track list with its length and status: pending, partial, or done.
- **Delete** removes a song from the list. Note: this shifts every song after it to a new position on the device, so those songs will need to be re-transferred even if they'd already finished — this matches how the official tool behaves too, since storage on the SP-1 is sequential.

## Transferring

- Tap **Transfer Album** to send the whole track list. Songs already fully transferred are skipped; a song that was only partly transferred picks up where it left off rather than starting over.
- While data is actively being written, the **track 1 LED blinks**.
- The progress bar and status line show which song and sector are currently being written.
- This is genuinely slow — expect roughly 10 minutes of transfer per 1 minute of audio. That's a limit of the SP-1's own firmware and USB speed, not something the app can speed up.

### While a transfer is running

- Every button except **Disconnect** disables itself for the duration — Scan, Connect, Echo, Check State, Test Write, Add Song, and each song's own Delete button. This isn't just to keep things tidy: deleting a song or starting a second transfer while one is already writing to the device could corrupt what's being sent.
- **Your progress is safe no matter what.** Every sector gets saved to the app's own memory the moment it's confirmed written — not just at the end. If the app closes, the phone dies, or the cable comes loose, whatever finished is still marked done next time you open the app, and Transfer Album will pick up from there rather than starting the whole album over.
- **To stop a transfer on purpose, tap Disconnect.** It won't cut off mid-write — it finishes whatever sector is currently in progress, saves that progress, and *then* disconnects cleanly. Give it a moment; the status line will confirm once it's actually stopped. Everything else re-enables automatically once it does.

## The other buttons

- **Send Echo** — a quick round-trip check that the connection is alive and responding correctly. Doesn't touch the device's storage at all.
- **Test Write** — writes a small block of throwaway test data to a sector deep in the SP-1's storage (sector `0x70000`), far from where any real album lives. It's there to confirm the write mechanism itself is working correctly.
  - **Does it interfere with adding files? No.** Add Song only touches the app's own track list, not the device. Transfer Album only ever writes to the metadata sector and the sectors your actual songs occupy, starting right after the metadata — nowhere near where the test data sits, unless a single album ever grew to fill nearly the entire 4GB.
  - **Does the test data stay on the device? Yes** — nothing automatically erases it. But since no album's metadata ever points at that location, it's invisible to playback and completely harmless. It would only ever get overwritten if some future album genuinely grew that large.
- **Disconnect** — normally, a clean and immediate release of the USB connection. During an active transfer, it instead acts as a safe "stop" button — see above.
- **Scan USB devices** — a diagnostic listing of every USB device currently attached and its interfaces. Mainly useful if the SP-1 isn't showing up as expected.

## If something goes wrong

- If the app suddenly shows "SP-1 disconnected from USB," that's usually just the boot-to-transfer-mode reboot from step 6 — wait a few seconds and tap **Connect to SP-1** again.
- If a transfer gets interrupted (cable unplugged, app closed), the SP-1 can seem unresponsive with audio disabled. This is normal — long-press the SP-1's function button to restart it, then reconnect and run **Transfer Album** again. It will pick up any unfinished song from where it left off rather than starting over.

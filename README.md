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

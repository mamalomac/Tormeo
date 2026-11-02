# Tormeo

A lightweight Android TV and Android phone client for **Stremio addons** (Cinemeta, Torrentio, …), made for
**weak hardware**: the goal is for 4K HDR to play without stuttering and without buffering in the middle of a video.

| Build | For | ABI | Android |
|---|---|---|---|
| `app-tv-release.apk` | Android TV / TV boxes (Mi Box, X96, H96, Tanix, Chromecast…) | **armeabi-v7a** (32-bit ARM) | 6.0+ (API 23) |
| `app-mobile-release.apk` | Phones / tablets | arm64-v8a + armeabi-v7a | 6.0+ (API 23) |

The armeabi-v7a TV build also runs on 64-bit chips that have a 32-bit system, which is the case on most cheap TV boxes.

## Download / build

* **Ready-made APKs:** GitHub → *Actions* → the latest *Build APK* run → *Artifacts*
  (`tormeo-tv-armeabi-v7a`, `tormeo-mobile`).
* **Locally:** JDK 17 + Android SDK, then
  ```bash
  ./gradlew assembleTvRelease assembleMobileRelease
  ```
  The APKs end up in `app/build/outputs/apk/<tv|mobile>/release/`. They are signed with the debug key, so they install right away.
  Use your own key for publishing.

Install on a TV: `adb install app-tv-release.apk`, or copy it to a USB stick and use a file manager / "Downloader".

## How 4K HDR without stuttering works on weak hardware

1. **Hardware decoding only.** Video always goes through `MediaCodec` (Media3/ExoPlayer). Software decoding is never used for video.
   That is the main reason weak SoCs stutter.
2. **Device check.** The app reads which decoders the hardware has (HEVC 4K / 10-bit, AV1, VP9, Dolby Vision) and what the screen
   supports (4K, HDR10, HLG, DV). Streams the device would not play smoothly are hidden: AV1 without a HW decoder, 4K on a
   1080p decoder, Dolby Vision profile 5 without DV (wrong colors), and so on. *Settings → Ovaj uređaj* shows the report.
3. **Smart source pick.** Every source is scored by resolution, HDR, codec, seeders, size and estimated bitrate. Remuxes of
   80+ Mbps over P2P get a lower score, and debrid-cached sources (⚡) get a higher one. *"Pusti najbolji izvor"* starts the best one automatically.
4. **Disk is the buffer, not RAM.** A local HTTP server (`127.0.0.1`) feeds the player:
   * **Torrent:** libtorrent downloads sequentially inside a window ahead of the playhead (1 GB by default). The next ~20 s get
     *piece deadlines* (time-critical mode: requests go to the fastest peers). Disk usage stays bounded.
   * **Debrid/HTTP:** a background thread downloads up to ~75% of a disk *ring buffer* (768 MB by default) ahead,
     so network drops don't reach the player.
   * The player's RAM buffer is small (48–192 MB depending on RAM), so there are no out-of-memory problems on 1–2 GB boxes.
5. **Pre-buffer before starting.** It downloads the start of the file plus the end of the file (MKV *cues* / MP4 *moov*), so playback and
   seeking start without pauses. 15 s of video by default, adjustable.
6. **Tunneled playback** on TV: the decoder sends frames straight to the display pipeline, which takes load off the CPU/GPU. If a
   firmware misbehaves with it, the app turns it off by itself and continues.
7. **Refresh-rate matching:** the TV switches to 23.976/24/25/50/60 Hz (and to 4K mode for 4K content). No 24p judder.
8. **Audio:** passthrough (AC3/E-AC3/DTS/TrueHD) to the receiver/TV when supported. Otherwise ffmpeg decodes the audio in software,
   which is cheap. Video always stays on hardware.
9. Lightweight UI: Jetpack Compose without heavy libraries, small image cache, RGB_565 posters, no animations.

### Realistic expectations

* The device must have a **hardware HEVC 10-bit 4K decoder** (almost every 4K box from the last 6–7 years: Amlogic S905X2/X3/X4,
  Rockchip, Allwinner H6/H616…). The app shows this on the *Ovaj uređaj* screen.
* **P2P speed depends on seeders.** A 4K *encode* (15–40 Mbps, 10–30 GB) with plenty of seeders plays well. A 4K *remux*
  (60–100 Mbps) over torrent often doesn't, so it is marked with a warning.
* **For "always smooth"** use a debrid service (Real-Debrid, AllDebrid, TorBox…). Configure Torrentio at
  `torrentio.strem.fun/configure`, copy the install link and paste it under *Settings → Addoni*.
  Cached sources (⚡ DIREKTNO) play straight over HTTP with a disk buffer.

## Remote control (TV)

* D-pad: navigation. OK opens the controls. Left/right: −10 s / +30 s. NAZAD (Back) hides the controls or exits.
* The ⚙ button in the player: audio track, subtitles, speed.
* Long-press on an item in "Nastavi gledanje" (Continue watching) opens the movie/series details.

## Subtitles (OpenSubtitles)

* The OpenSubtitles v3 addon is installed by default.
* **Settings → Titlovi:** pick a language (e.g. *srpski*) and the best subtitle is loaded automatically before playback.
  Order: Serbian ↔ Croatian ↔ Bosnian (the best-synced one wins), then English.
* Ranking: release-name match, same FPS as the video (a 25 fps subtitle drifts on a 23.976 fps film), CAM/TS subtitles last.
* In the player: the **CC Titlovi** button (shown with the controls) or the **MENU / CC** remote key → list of all subtitles or "Bez titla".
* Windows-1250/1251 encodings and old MicroDVD `.sub` files are converted automatically. A broken subtitle is dropped and the movie keeps playing.
* Subtitles embedded in MKV files are still available via the player's ⚙ button.

## Addons

Cinemeta (catalogs, search, metadata) and Torrentio (sources) are installed by default. Any Stremio addon
(`…/manifest.json` or a `stremio://…` link) can be added under *Settings → Addoni*. The app supports the `catalog`, `meta` and `stream`
resources.

## Code structure

```
app/src/main/java/com/tormeo/app/
├── data/          Stremio addon protocol (models, AddonRepository), settings, watch progress
├── device/        DeviceCaps – detection of HW decoders and screen capabilities
├── stream/        StreamCandidate – parsing of Torrentio/addon titles, compatibility, scoring
├── torrent/       TorrentEngine (libtorrent4j), TorrentSource, HttpSource (disk ring buffer), StreamServer (local HTTP)
├── player/        PlayerActivity – Media3 ExoPlayer, tunneling, frame-rate matching, pre-buffer
└── ui/            Compose screens: Home, Search, Detail, Streams, Settings
```

Tests: `./gradlew testMobileDebugUnitTest`. They cover the ring buffer and local server end to end (byte-for-byte with seeks),
and stream parsing/scoring on a real Torrentio response.

## Windows

Not included in this version. The planned path: a separate lightweight client on top of **libmpv** (`hwdec=d3d11va`,
HDR passthrough via `d3d11` + `target-colorspace-hint`) with the same addon/torrent logic.

## Note

Tormeo is just a player and an addon client. It does not host or index content. You are responsible for what you play
and for complying with the laws of your country.

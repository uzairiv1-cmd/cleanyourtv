# IPTV Player

An IPTV player for Android TV, Google TV and Fire TV that opens an **Xtream Codes** account or an
**M3U / M3U8 link**. It is built for the remote: Live TV, Movies and Series tabs, categories on the
left, channels or posters on the right, and a Media3 player.

It is a CleanYourTV add-on: a normal app that works with any launcher. It also has a module card, so on
CleanYourTV it can sit in the dock as **IPTV Player · Xtream and M3U** once you approve it.

It is free and MIT-licensed, with no ads, no accounts, no tracking and no background services. It comes
with no channels and only plays the playlists you add. Use only services you are allowed to watch.

## What it does

| | |
| :- | :- |
| Xtream Codes | Log in with server, username and password. The login is checked before saving, and the account's expiry date is shown. Live TV, Movies (VOD) and Series with seasons and episodes. Each category is loaded when you open it, so large accounts open quickly. Live streams use HLS (`.m3u8`) when the account allows it, and MPEG-TS otherwise |
| M3U / M3U8 links | `#EXTINF` with `tvg-name`, `tvg-logo`, `group-title`, plus `#EXTGRP`. The playlist is read line by line, so big lists don't run the TV out of memory. Entries are sorted into Live TV, Movies and Series by their `/movie/` or `/series/` path or their file extension |
| Xtream link pasted as M3U | A `…/get.php?username=…&password=…` link is recognised and saved as an Xtream account, which adds Movies and Series |
| Several playlists | Add, edit, delete and switch between them under **Playlists** |
| Favourites | Long-press OK on a channel, movie or series to add it or remove it. Each playlist has its own **★ Favourites** list |
| Filter | Type in the filter box to narrow the category you have open |
| Player | Media3 (ExoPlayer): HLS and progressive streams (MPEG-TS, MP4, MKV), audio and subtitle tracks from the controls. On live TV, Up/Down and Channel +/- change channel while the controls are hidden, Info shows the channel name, and a stream that drops is reconnected. Movies and episodes start again where you left them |

Not done yet: an EPG/TV guide, catch-up, recording, a search across every category at once, parental PINs,
DASH/RTMP/UDP streams, and DRM.

## Install

Build it (below), then install it from the repo root as with every add-on:

```bash
cd addons/iptv-player && ./gradlew assembleDebug && cd ../..
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py --push addons/iptv-player/app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

You can also use `adb install -r app/build/outputs/apk/debug/app-debug.apk`. It opens from the TV's app list as
**IPTV Player**.

The debug APK is signed with a throwaway key that changes from one machine to the next. For a copy you keep,
build a release signed with your own key: the launcher ties its approval of the card to the signing key, so a
different key makes it ask again.

```bash
IPTV_KEYSTORE=/path/to/release.jks IPTV_KEYSTORE_PASSWORD=… IPTV_KEY_ALIAS=… IPTV_KEY_PASSWORD=… ./gradlew assembleRelease
```

## Build

JDK 17 and the Android SDK (platform 34, build-tools 34.0.0), as for the launcher. In this folder:

```bash
./gradlew --no-daemon testReleaseUnitTest assembleRelease
```

The unit tests cover the M3U and Xtream parsers (`app/src/test`).

## Use

1. On first start, choose **Xtream Codes** or **M3U link**.
   - Xtream: the server as your provider gives it (`http://host:port`), your username and your password.
     A pasted `get.php` or `player_api.php` link fills in all three.
   - M3U: the full `http://` or `https://` link.
2. **Save** checks an Xtream login. If the check fails, the message says why; **Save anyway** keeps it.
3. Choose **Live TV**, **Movies** or **Series**, move through the categories on the left (each one loads
   when you stop on it), and press OK to play. In Series, OK on a show lists its episodes and Back
   returns.
4. In the player: Back leaves. On live TV, Up/Down or Channel +/- change channel, and OK shows the controls.

## Permissions

- `INTERNET` is the only permission. It fetches playlists, logos and streams, and only from the servers you add.
- `usesCleartextTraffic` is on because most IPTV panels and playlists use plain http.
- Logins and links are kept in the app's private storage on the TV. `allowBackup` is off, so they are not
  copied to a cloud backup or to another device. They are not encrypted beyond Android's own app
  sandbox, so anyone with ADB root access to the TV could read them.
- No background services, no start at boot, no analytics.

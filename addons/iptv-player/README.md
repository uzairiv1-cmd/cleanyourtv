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

## Test it on your TV

You need the APK file and a way to get it onto the TV. Either way works on any Android TV, Google TV
or Fire TV running Android 8 or newer.

**Without a computer: a USB stick or "Send files to TV"**

1. Copy `app-debug.apk` to a USB stick, or send it with the free *Send files to TV* app (on the phone and on
   the TV).
2. On the TV, install a file manager from the Play Store (for example *File Commander*, or *Downloader* on Fire TV).
3. Allow that app to install apps: **Settings → Apps → Security & restrictions → Unknown sources**. On Google TV:
   **Settings → Privacy → Security & restrictions → Unknown sources**. On Fire TV: **My Fire TV → Developer options →
   Install unknown apps**.
4. Open the APK in the file manager and press **Install**. Then switch Unknown sources off again for that app.

**With a computer: ADB over the network**

1. On the TV, open **Settings → Device Preferences → About** (on Google TV: **Settings → System → About**) and press
   OK on **Android TV OS build** seven times, until it says you are a developer.
2. In **Settings → Device Preferences → Developer options**, switch on **USB debugging** (and **Network debugging**
   if the TV has it). Note the TV's IP address under **Settings → Network**.
3. On the computer, with [platform-tools](https://developer.android.com/tools/releases/platform-tools) installed:

   ```bash
   adb connect <tv ip>:5555          # accept the prompt on the TV
   adb install -r app-debug.apk
   ```

4. When you have finished testing, switch **USB debugging** and **Network debugging** off again: while they are on,
   anyone on your network can control the TV.

From this repository, with `tools/venv` set up, the same can be done with `tools/tv.py`:

```bash
cd addons/iptv-player && ./gradlew assembleDebug && cd ../..
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py --push addons/iptv-player/app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

**On an Android phone**

The same APK installs on an Android phone running Android 8 or newer (not on an iPhone). Open the APK on the
phone, from a download or a file sent to yourself, and allow your browser or Files app to install apps when Android
asks. Every screen turns to landscape, as on the TV. Tap to choose, long-press for a favourite, and tap the video for the
player controls. Changing channel with Up/Down needs a remote or keyboard, so on a phone go back to the list for
another channel.

**What to try**

- A legal test playlist: choose **M3U link** and enter `https://iptv-org.github.io/iptv/index.m3u` (free-to-air
  channels collected by the iptv-org project; some of them are always offline).
- Your own Xtream Codes account: server, username and password from your provider.
- Every screen with the remote alone: the tabs, the categories (Up/Down), the list (Left back to the categories),
  Back from each screen, long-press OK for a favourite, Up/Down to change channel in the player, OK for the
  controls, and audio and subtitle tracks there.

The debug APK is signed with a throwaway key. Installing the Play version later over it fails with a signature
error: uninstall the test version first.

## Build

JDK 17 and the Android SDK (platform 36). In this folder:

```bash
./gradlew --no-daemon testReleaseUnitTest lintRelease assembleDebug
```

The unit tests cover the M3U and Xtream parsers (`app/src/test`).

## Publish on Google Play

Only you can publish it: the upload goes through your own Google Play developer account. The text for the
listing, the data safety and content rating answers, the graphics and a privacy policy are in
[`play-store/`](play-store/): [`listing.md`](play-store/listing.md) and
[`privacy-policy.md`](play-store/privacy-policy.md).

1. **Choose the app ID** in `gradle.properties` (`iptvAppId`), for example `com.yourname.iptvplayer`. It cannot
   change after the first upload.
2. **Make an upload key**, once, and keep it and its passwords somewhere safe outside this repository:

   ```bash
   keytool -genkeypair -v -keystore ~/iptv-upload.jks -keyalg RSA -keysize 4096 -validity 10000 -alias upload
   ```

3. **Build the bundle** signed with it:

   ```bash
   IPTV_KEYSTORE=~/iptv-upload.jks IPTV_KEYSTORE_PASSWORD=… IPTV_KEY_ALIAS=upload IPTV_KEY_PASSWORD=… \
     ./gradlew --no-daemon bundleRelease
   ```

   The file to upload is `app/build/outputs/bundle/release/app-release.aab`. For every later upload, raise
   `iptvVersionCode` in `gradle.properties` by one.
4. **In the Play Console** ([play.google.com/console](https://play.google.com/console), one-time registration fee):
   create the app, accept **Play App Signing**, and fill in the store listing, privacy policy URL, data safety,
   content rating and app access from `play-store/listing.md`. Add the TV banner and at least one TV screenshot,
   then opt in to **Android TV** under Advanced settings → Form factors.
5. **Test first.** Upload the bundle to *Internal testing* to install it on your own TV from Play. New personal
   developer accounts must then run a *Closed test* with at least 12 testers for 14 days before Production
   opens up.

Play reviews IPTV players strictly. Keep the listing and screenshots free of providers, channel names and
logos, and say that the app comes with no content. The app says so on its first screen too.

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

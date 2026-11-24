# Google Play listing: IPTV Player

Text to paste into the Play Console. Keep the listing free of provider names, channel names, logos
and "free TV" wording: Play removes IPTV apps that look like they offer content.

## App name (30 characters max)

IPTV Player for TV

## Short description (80 characters max)

Play your own Xtream Codes or M3U playlist on Android TV. No content included.

## Full description

IPTV Player plays the IPTV playlist you already have, on your Android TV, built for the remote.

IMPORTANT: IPTV Player does not include any channels, playlists or content. You need a playlist or an
account from a provider you are allowed to use. The developer is not affiliated with any provider.

• Xtream Codes accounts: sign in with server, username and password. The app checks the login and shows when
  the account expires
• M3U and M3U8 links, including very large playlists
• Live TV, Movies and Series, each with its categories, and Series with seasons and episodes
• Favourites: long-press OK on a channel, movie or series
• Change channel with Up/Down or Channel +/-, with automatic reconnect when a live stream drops
• Movies and episodes start where you stopped
• Audio track and subtitle choices
• Several playlists, and you can switch between them
• No ads, no account, no tracking. Your logins stay on your TV

## Category and tags

- App category: Video Players & Editors
- Tags: video player, Android TV

## Data safety form

- Does your app collect or share any of the required user data types? **No**
- Because nothing is collected or shared, the form ends after that answer. Logins and playlists stay on the
  device, which Play does not count as collection.
- Do you provide a way for users to request that their data is deleted? Nothing is collected; data on the
  device is deleted by uninstalling the app or clearing its data.

## Content rating questionnaire

- Category: **Utility, productivity, communication or other** (a player with no built-in content)
- Answer **No** to the violence, sexuality, language, gambling and controlled-substance questions about the
  app's own content
- Does the app let users access unrestricted internet or user-supplied content? **Yes**: users can play
  streams from any address they enter

## App access (for the reviewer)

Play's reviewer needs something to play. Give them a **legal** test playlist under App access → "All or
some functionality is restricted", for example the public iptv-org index of free-to-air channels:

- M3U link: `https://iptv-org.github.io/iptv/index.m3u`
- Instructions: "Open the app → M3U link → paste the link → Save. Choose any category and press OK."

Never give the reviewer a paid provider's login.

## Graphics

| Asset | Size | Where it comes from |
| :- | :- | :- |
| App icon | 512 × 512 PNG | `play-store/icon-512.png` |
| Feature graphic | 1024 × 500 PNG | `play-store/feature-graphic.png` |
| TV banner | 1280 × 720 PNG | `play-store/tv-banner.png` |
| TV screenshots | at least 1, 1920 × 1080 or 1280 × 720 | Take them on your TV with the test playlist above (`tools/tv.py --screenshot`). Don't show a paid provider's channels or logos |

## Privacy policy URL

`play-store/privacy-policy.md` from this repository, once it is on the default branch:
`https://github.com/<you>/<repo>/blob/main/addons/iptv-player/play-store/privacy-policy.md`.
Fill in the contact line first. GitHub Pages or any page you control works as well.

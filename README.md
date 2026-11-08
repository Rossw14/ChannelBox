# ChannelBox: an IPTV player for Sony Google TV / Android TV

ChannelBox is a live TV app built for the TV remote. On first launch it loads the free UK
channel list (about 300 publicly available streams collected by the open-source
[iptv-org](https://github.com/iptv-org/iptv) project), grouped by category with channel logos.
You can add your own M3U playlist link later in Settings.

## What it does

- A full-screen live player for HLS (`.m3u8`), DASH (`.mpd`) and plain stream links.
- A side panel listing **Favourites**, **All channels** and each category (News, Entertainment, Kids and so on).
- Channel changing with ▲▼ or CH+/CH−, plus typed channel numbers.
- Favourites: hold OK on a channel, or press the Menu button.
- When it starts, it carries on with the channel you were last watching.
- Channels that fail are dimmed and labelled, so you can skip past dead links.
- Your playlist link's channels are listed first, with the free lists merged in after.
- Channel lists are kept on the TV, so the app still opens if a list is briefly unreachable.

## Remote controls

| While watching | |
|---|---|
| OK or ◀ | Open the channel list |
| ▲ / ▼, CH+ / CH− | Previous / next channel |
| ▶ or Info | Show what's on |
| Number keys | Jump to a channel number |
| Menu | Add or remove the current channel from Favourites |
| Back, Back | Exit |

| In the channel list | |
|---|---|
| OK | Watch the channel |
| Hold OK or Menu | Add or remove from Favourites |
| ◀ / ▶ | Move between categories and channels |
| Back | Return to the picture |

## Getting the app onto your TV

### 1. Build the APK (free, nothing to install)

1. Create a free account at github.com, then make a new **private** repository called `ChannelBox`.
2. Upload this whole folder to it. The easiest route is **Add file → Upload files**, dragging in everything,
   including the hidden `.github` folder. If the web uploader skips that folder, create
   `.github/workflows/build.yml` by hand and paste in its contents.
3. Open the **Actions** tab. A "Build APK" run starts automatically and takes about 3–5 minutes.
4. When it shows a green tick, open the run and download **ChannelBox-apk** under *Artifacts*.
   Unzip it to get `ChannelBox.apk`.

Each time you change the code and upload again, a new APK is built. Because every build is
signed with the same key (`app/release.keystore`), new versions install over the old one
without losing your favourites or settings.

> If you'd rather build on a PC: install Android Studio, choose **Open** on this folder,
> then use **Build → Build App Bundle(s)/APK(s) → Build APK(s)**.

### 2. Allow the TV to install it

On the Sony TV:

1. **Settings → System → About** and select **Android TV OS build** 7 times. This turns on
   developer mode, and a message confirms it.
2. From the Google Play Store on the TV, install **Send files to TV** and a file manager such as **File Commander**.
3. **Settings → Apps → Security & restrictions → Unknown sources** and switch it on for your file manager.

### 3. Send and install

1. Install **Send files to TV** on your phone as well, then send `ChannelBox.apk` to the TV.
   A USB stick works too.
2. On the TV, open the file manager, go to the Download folder (or the USB stick), select
   `ChannelBox.apk` and choose **Install**.
3. ChannelBox then appears in your apps row on the home screen. You can move it to the favourites row.

## Adding your own playlist later

Open ChannelBox, go to **Settings**, and paste an M3U or M3U8 link into "Your own playlist".
Typing long links with the remote is slow, so it's easier to use the **Google TV app** on your
phone (the remote button, then the keyboard icon).

## Customising

- **Built-in lists:** edit `BUILT_IN` in `app/src/main/java/uk/co/channelbox/PlaylistRepository.kt`.
  iptv-org publishes lists for every country, category and language. See its
  [PLAYLISTS.md](https://github.com/iptv-org/iptv/blob/master/PLAYLISTS.md).
- **Colours:** `app/src/main/res/values/colors.xml`.
- **Wording:** `app/src/main/res/values/strings.xml`.

Possible next steps: a TV guide (EPG) using iptv-org's guide data, search, and Xtream-code logins.

## Good to know

- These are public streams that other people run, so some will be offline at any given time.
  Those channels are dimmed in the list. Some are marked *Geo-blocked* or *Not 24/7* in their name.
- In the UK you need a **TV Licence** to watch live TV as it's broadcast, on any channel and through any app.
- Only add playlists you're entitled to use.

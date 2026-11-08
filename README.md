# ChannelBox: an IPTV player for Sony Google TV / Android TV

ChannelBox is a live TV app built for the TV remote. It loads the full free channel list from the
open-source [iptv-org](https://github.com/iptv-org/iptv) project (about 10,000 publicly available
channels from 187 countries), organised by country and then category, with channel logos.
Out of the box it shows the UK, Ireland, the USA, Canada, Australia and New Zealand; you can change
that in Settings. You can also add your own M3U playlist link.

## What it does

- A full-screen live player for HLS (`.m3u8`), DASH (`.mpd`) and plain stream links.
- A side panel with **Favourites**, **Recently watched**, **Search** and your countries.
  Open a country to see its categories (News, Entertainment, Kids and so on).
- **Search** across every channel you've chosen, typed with the remote or the on-screen keyboard's mic.
- Channel changing with ▲▼ or CH+/CH−, plus typed channel numbers.
- Favourites: hold OK on a channel, or press the Menu button.
- When it starts, it carries on with the channel you were last watching.
- **One entry per channel.** Duplicates are merged, and if a stream fails the next one is tried automatically.
- **Offline channels are hidden.** The TV quietly checks which channels answer, every few hours.
  You can switch this off in Settings. Changing channel with ▲▼ skips channels that are down.
- **TV guide.** Each channel row shows what's on now, and the banner shows Now and Next.
  The **Guide** button on the remote (or on screen) opens a full-screen, Sky-style grid guide for the
  list you're in, with programme details at the top and the live picture carrying on in the top-right corner.
- **Picture quality limit** in Settings (Automatic, 1080p, 720p or 480p) for slower broadband, plus a bigger
  playback buffer so streams stutter less.
- Your playlist link's channels are listed first, with the free lists merged in after.
- Channel lists are kept on the TV, so the app still opens if a list is briefly unreachable.

## Remote controls

| While watching | |
|---|---|
| OK or ◀ | Open the channel list |
| ▲ / ▼, CH+ / CH− | Previous / next channel |
| ▶ or Info | Show what's on |
| Guide | Open the TV guide (press again, or Back, to close) |
| Number keys | Jump to a channel number |
| Menu | Add or remove the current channel from Favourites |
| Back, Back | Exit |

| In the channel list | |
|---|---|
| OK | Watch the channel, or open a country / start a search |
| Hold OK or Menu | Add or remove from Favourites |
| ▲ / ▼ | Move through the list (stops at the top and bottom) |
| CH+ / CH− | Jump a page up or down the list |
| ◀ / ▶ | Move between categories and channels |
| Back | In a country's list on the left: back to the countries. Otherwise: return to the picture |

| In the TV guide | |
|---|---|
| ▲ / ▼ | Move between channels |
| ◀ / ▶ | Earlier / later programmes |
| CH+ / CH− | Page up / down |
| Number keys | Jump to that channel |
| OK | Watch that channel |
| Back or Guide | Close the guide |

## Getting the app onto your TV

### 1. Build the APK (free, nothing to install)

1. Create a free account at github.com, then make a new **private** repository called `ChannelBox`.
2. Upload this whole folder to it. The easiest route is **Add file → Upload files**, dragging in everything,
   including the hidden `.github` folder. If the web uploader skips that folder, create
   `.github/workflows/main.yml` (and `guide.yml`) by hand and paste in its contents.
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

## TV guide set-up (one time)

The listings come from the free iptv-org guide tools. These don't publish a ready-made UK guide,
so this project includes a job (`.github/workflows/guide.yml`) that GitHub runs twice a day.
It collects about two days of listings for every playlist in `guide-playlists.txt` (UK, Ireland, USA,
Canada, Australia, New Zealand and International to start with), side by side, and saves them to a
branch called `guide`.

The TV can only download that file if the project is **public**: **Settings → General →
Danger Zone → Change visibility → Make public**. The project contains only the app's code, so
nothing personal is exposed.

To run the guide job straight away instead of waiting, go to **Actions → Update TV guide → Run workflow**.

## Updating the app

Every time GitHub builds ChannelBox, the new version is published under **Releases** on your
project page. On the TV, go to **Settings → Check for updates** and choose **Install**.
The first time, Android asks you to allow ChannelBox to install apps: switch it on, press Back,
and choose Check for updates again. ChannelBox also checks once a day and tells you when an
update is waiting. You can also download `ChannelBox.apk` from the Releases page.

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

- **Guide coverage:** add playlist links to `guide-playlists.txt` (for example if you turn on the Ireland list).

Possible next steps: a full grid-style guide, search, recordings reminders, and Xtream-code logins.

## Good to know

- These are public streams that other people run, so some will be offline at any given time.
  Those channels are dimmed in the list. Some are marked *Geo-blocked* or *Not 24/7* in their name.
- In the UK you need a **TV Licence** to watch live TV as it's broadcast, on any channel and through any app.
- Only add playlists you're entitled to use.

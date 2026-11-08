package uk.co.channelbox

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : Activity(), ChannelAdapter.Listener, GroupAdapter.Listener {

    private lateinit var prefs: Prefs

    // Views
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var groupList: RecyclerView
    private lateinit var channelList: RecyclerView
    private lateinit var listTitle: TextView
    private lateinit var emptyText: TextView
    private lateinit var banner: View
    private lateinit var bannerNumber: TextView
    private lateinit var bannerName: TextView
    private lateinit var bannerInfo: TextView
    private lateinit var bannerStatus: TextView
    private lateinit var numberEntry: TextView
    private lateinit var loadingView: View
    private lateinit var loadingText: TextView

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()

    // State
    private var allChannels: List<Channel> = emptyList()
    private var groups: List<Group> = emptyList()
    private var currentGroupIndex = 0
    private var visibleChannels: List<Channel> = emptyList()
    private var playingList: List<Channel> = emptyList()
    private var playingIndex = -1
    private var currentChannel: Channel? = null
    private var triedHlsFallback = false
    private val failedUrls = HashSet<String>()
    private var loadedSettingsVersion = -1
    private var lastBackPress = 0L
    private var digits = ""
    private var loading = false

    private val hideBanner = Runnable { banner.visibility = View.GONE }
    private val commitDigits = Runnable { jumpToNumber() }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        playerView = findViewById(R.id.player_view)
        overlay = findViewById(R.id.overlay)
        groupList = findViewById(R.id.group_list)
        channelList = findViewById(R.id.channel_list)
        listTitle = findViewById(R.id.list_title)
        emptyText = findViewById(R.id.empty_text)
        banner = findViewById(R.id.banner)
        bannerNumber = findViewById(R.id.banner_number)
        bannerName = findViewById(R.id.banner_name)
        bannerInfo = findViewById(R.id.banner_info)
        bannerStatus = findViewById(R.id.banner_status)
        numberEntry = findViewById(R.id.number_entry)
        loadingView = findViewById(R.id.loading)
        loadingText = findViewById(R.id.loading_text)

        groupAdapter = GroupAdapter(this)
        channelAdapter = ChannelAdapter(this)
        channelAdapter.failedUrls = failedUrls
        groupList.layoutManager = LinearLayoutManager(this)
        groupList.adapter = groupAdapter
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = channelAdapter
        channelList.itemAnimator = null
        groupList.itemAnimator = null

        findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.reload_button).setOnClickListener { loadPlaylist(force = true) }

        loadedSettingsVersion = prefs.settingsVersion
        loadPlaylist(force = false)
    }

    override fun onStart() {
        super.onStart()
        createPlayer()
        currentChannel?.let { startPlayback(it, forceHls = false) }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.settingsVersion != loadedSettingsVersion) {
            loadedSettingsVersion = prefs.settingsVersion
            loadPlaylist(force = true)
        }
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        loader.shutdownNow()
    }

    // ---------------------------------------------------------------- playlist

    private fun loadPlaylist(force: Boolean) {
        if (loading) return
        loading = true
        loadingText.setText(R.string.loading_channels)
        loadingView.visibility = View.VISIBLE
        val appContext = applicationContext
        loader.execute {
            val result = PlaylistRepository.load(appContext, prefs, force)
            handler.post {
                loading = false
                if (!isDestroyed) onPlaylistLoaded(result)
            }
        }
    }

    private fun onPlaylistLoaded(result: PlaylistRepository.LoadResult) {
        loadingView.visibility = View.GONE
        allChannels = result.channels
        rebuildGroups(keepSelection = currentChannel != null)

        if (result.errors.isNotEmpty()) {
            toast(getString(R.string.some_lists_failed, result.errors.joinToString("\n")), long = true)
        }

        if (allChannels.isEmpty()) {
            emptyText.setText(R.string.no_channels)
            showOverlay()
            return
        }

        if (currentChannel == null) {
            val last = prefs.lastChannelUrl
            val allIndex = groups.indexOfFirst { it.kind == Group.Kind.ALL }
            val lastIndex = allChannels.indexOfFirst { it.url == last }
            if (last != null && lastIndex >= 0 && allIndex >= 0) {
                // Pick up where you left off, like a normal TV.
                selectGroup(allIndex)
                playChannel(groups[allIndex].channels, lastIndex)
                hideOverlay()
            } else {
                showOverlay()
            }
        } else if (overlay.visibility == View.VISIBLE) {
            showOverlay()
        }
    }

    private fun rebuildGroups(keepSelection: Boolean) {
        val previous = groups.getOrNull(currentGroupIndex)
        val favourites = prefs.favourites

        val built = ArrayList<Group>()
        val favChannels = allChannels.filter { it.url in favourites }
        if (favChannels.isNotEmpty()) {
            built.add(Group("favourites", favChannels, Group.Kind.FAVOURITES))
        }
        built.add(Group("all", allChannels, Group.Kind.ALL))

        val byCategory = LinkedHashMap<String, MutableList<Channel>>()
        for (channel in allChannels) {
            for (g in channel.groups) byCategory.getOrPut(g) { ArrayList() }.add(channel)
        }
        byCategory.keys
            .sortedWith(compareBy<String>({ it == M3uParser.OTHER }, { it.lowercase() }))
            .forEach { name -> built.add(Group(name, byCategory.getValue(name), Group.Kind.CATEGORY)) }

        groups = built
        groupAdapter.submit(groups)
        channelAdapter.favourites = favourites

        var index = -1
        if (keepSelection && previous != null) {
            index = groups.indexOfFirst { it.kind == previous.kind && it.name == previous.name }
        }
        if (index < 0) {
            index = groups.indexOfFirst { it.kind == Group.Kind.ALL }.coerceAtLeast(0)
        }
        selectGroup(index)
    }

    private fun selectGroup(index: Int) {
        if (groups.isEmpty()) {
            visibleChannels = emptyList()
            channelAdapter.submit(visibleChannels)
            emptyText.visibility = View.VISIBLE
            listTitle.text = ""
            return
        }
        val previousSelected = groupAdapter.selected
        currentGroupIndex = index.coerceIn(0, groups.size - 1)
        val group = groups[currentGroupIndex]
        groupAdapter.selected = currentGroupIndex
        // Only redraw the two rows that changed, so focus isn't disturbed.
        if (previousSelected in groups.indices) groupAdapter.notifyItemChanged(previousSelected)
        groupAdapter.notifyItemChanged(currentGroupIndex)

        visibleChannels = group.channels
        channelAdapter.submit(visibleChannels)
        val title = when (group.kind) {
            Group.Kind.FAVOURITES -> getString(R.string.favourites)
            Group.Kind.ALL -> getString(R.string.all_channels)
            Group.Kind.CATEGORY -> group.name
        }
        listTitle.text = getString(R.string.list_title, title, visibleChannels.size)
        if (visibleChannels.isEmpty()) {
            emptyText.visibility = View.VISIBLE
        } else {
            emptyText.visibility = View.GONE
            val playingPos = visibleChannels.indexOfFirst { it.url == currentChannel?.url }
            channelList.scrollToPosition(if (playingPos >= 0) playingPos else 0)
        }
    }

    // ---------------------------------------------------------------- playback

    private fun createPlayer() {
        if (player != null) return
        val exo = ExoPlayer.Builder(this).build()
        exo.setAudioAttributes(AudioAttributes.DEFAULT, true)
        exo.playWhenReady = true
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    currentChannel?.let { ch ->
                        if (failedUrls.remove(ch.url)) channelAdapter.notifyDataSetChanged()
                    }
                    if (bannerStatus.text.isNotEmpty()) bannerStatus.text = ""
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlaybackError(error)
            }
        })
        playerView.player = exo
        player = exo
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    private fun playChannel(list: List<Channel>, index: Int) {
        if (list.isEmpty()) return
        val i = index.mod(list.size)
        val channel = list[i]
        playingList = list
        playingIndex = i
        currentChannel = channel
        triedHlsFallback = false
        prefs.lastChannelUrl = channel.url

        startPlayback(channel, forceHls = false)
        showBanner(channel, null)
        channelAdapter.playingUrl = channel.url
        channelAdapter.notifyDataSetChanged()
    }

    private fun startPlayback(channel: Channel, forceHls: Boolean) {
        val exo = player ?: return

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(channel.userAgent ?: DEFAULT_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        channel.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }

        val item = MediaItem.Builder().setUri(channel.url)
        val mime = if (forceHls) MimeTypes.APPLICATION_M3U8 else guessMimeType(channel.url)
        if (mime != null) item.setMimeType(mime)

        val source = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
            .createMediaSource(item.build())
        exo.setMediaSource(source)
        exo.prepare()
        exo.play()
    }

    private fun guessMimeType(url: String): String? {
        val lower = url.lowercase()
        val path = lower.substringBefore('?')
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            lower.contains("m3u8") -> MimeTypes.APPLICATION_M3U8
            else -> null
        }
    }

    private fun handlePlaybackError(error: PlaybackException) {
        val channel = currentChannel ?: return
        val exo = player ?: return

        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            exo.seekToDefaultPosition()
            exo.prepare()
            return
        }
        // Some links don't say they're HLS; try that once before giving up.
        if (!triedHlsFallback && guessMimeType(channel.url) == null) {
            triedHlsFallback = true
            startPlayback(channel, forceHls = true)
            return
        }

        failedUrls.add(channel.url)
        channelAdapter.failedUrls = failedUrls
        channelAdapter.notifyDataSetChanged()

        val reason = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                getString(R.string.error_refused)
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                getString(R.string.error_offline)
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                getString(R.string.error_format)
            else -> getString(R.string.error_generic)
        }
        showBanner(channel, reason)
    }

    private fun zap(delta: Int) {
        if (playingList.isEmpty()) {
            if (visibleChannels.isNotEmpty()) playChannel(visibleChannels, 0)
            return
        }
        playChannel(playingList, playingIndex + delta)
    }

    // ---------------------------------------------------------------- on-screen bits

    private fun showBanner(channel: Channel, status: String?) {
        bannerNumber.text = if (playingIndex >= 0) (playingIndex + 1).toString() else ""
        bannerName.text = channel.displayName
        bannerInfo.text = (listOf(channel.groups.joinToString(", ")) + channel.tags)
            .filter { it.isNotEmpty() }
            .joinToString("  ·  ")
        bannerStatus.text = status ?: ""
        bannerStatus.visibility = if (status == null) View.GONE else View.VISIBLE
        banner.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, if (status == null) 4_000L else 8_000L)
    }

    private fun showOverlay() {
        overlay.visibility = View.VISIBLE
        banner.visibility = View.GONE
        if (visibleChannels.isEmpty()) {
            focusGroup(currentGroupIndex)
        } else {
            val playingPos = visibleChannels.indexOfFirst { it.url == currentChannel?.url }
            focusChannel(if (playingPos >= 0) playingPos else 0)
        }
    }

    private fun hideOverlay() {
        if (currentChannel == null) return
        overlay.visibility = View.GONE
    }

    private fun focusChannel(position: Int) {
        if (visibleChannels.isEmpty()) return
        val pos = position.coerceIn(0, visibleChannels.size - 1)
        (channelList.layoutManager as LinearLayoutManager)
            .scrollToPositionWithOffset(pos, channelList.height / 3)
        requestFocusAt(channelList, pos, 0)
    }

    private fun focusGroup(position: Int) {
        if (groups.isEmpty()) {
            findViewById<View>(R.id.settings_button).requestFocus()
            return
        }
        val pos = position.coerceIn(0, groups.size - 1)
        groupList.scrollToPosition(pos)
        requestFocusAt(groupList, pos, 0)
    }

    /** Lists lay themselves out a frame later, so retry briefly until the row exists. */
    private fun requestFocusAt(list: RecyclerView, position: Int, attempt: Int) {
        list.post {
            val row = list.findViewHolderForAdapterPosition(position)?.itemView
            if (row != null) {
                row.requestFocus()
            } else if (attempt < 8) {
                handler.postDelayed({ requestFocusAt(list, position, attempt + 1) }, 40)
            }
        }
    }

    private fun toast(message: String, long: Boolean = false) {
        Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- list callbacks

    override fun onChannelClick(position: Int) {
        playChannel(visibleChannels, position)
        hideOverlay()
    }

    override fun onChannelLongClick(position: Int) {
        visibleChannels.getOrNull(position)?.let { toggleFavourite(it) }
    }

    override fun onGroupFocused(position: Int) {
        if (position != currentGroupIndex) selectGroup(position)
    }

    override fun onGroupClick(position: Int) {
        if (position != currentGroupIndex) selectGroup(position)
        focusChannel(0)
    }

    private fun toggleFavourite(channel: Channel) {
        val favourites = prefs.favourites.toMutableSet()
        val added = favourites.add(channel.url)
        if (!added) favourites.remove(channel.url)
        prefs.favourites = favourites
        toast(getString(if (added) R.string.fav_added else R.string.fav_removed, channel.displayName))

        // Remember which row had focus so it stays put after the lists refresh.
        val focusedRow = currentFocus?.let { channelList.findContainingItemView(it) }
        val focusedPos = focusedRow?.let { channelList.getChildAdapterPosition(it) } ?: -1

        rebuildGroups(keepSelection = true)
        if (overlay.visibility == View.VISIBLE) {
            if (visibleChannels.isNotEmpty() && focusedPos >= 0) {
                focusChannel(focusedPos)
            } else {
                focusGroup(currentGroupIndex)
            }
        }
    }

    // ---------------------------------------------------------------- remote control

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode

        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) handleBack()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            typeDigit(code - KeyEvent.KEYCODE_0)
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP -> { zap(+1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
        }

        val overlayVisible = overlay.visibility == View.VISIBLE
        if (overlayVisible) {
            val focus = currentFocus
            val inChannels = focus != null && channelList.findContainingItemView(focus) != null
            val inGroups = focus != null && groupList.findContainingItemView(focus) != null
            when {
                code == KeyEvent.KEYCODE_MENU && inChannels -> {
                    val row = channelList.findContainingItemView(focus!!)!!
                    onChannelLongClick(channelList.getChildAdapterPosition(row))
                    return true
                }
                code == KeyEvent.KEYCODE_DPAD_LEFT && inChannels -> {
                    focusGroup(currentGroupIndex)
                    return true
                }
                code == KeyEvent.KEYCODE_DPAD_RIGHT && inGroups -> {
                    if (visibleChannels.isNotEmpty()) {
                        val playingPos = visibleChannels.indexOfFirst { it.url == currentChannel?.url }
                        focusChannel(if (playingPos >= 0) playingPos else 0)
                    }
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        // Full-screen viewing
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> zap(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> zap(+1)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT -> showOverlay()
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_INFO -> currentChannel?.let { showBanner(it, null) }
            KeyEvent.KEYCODE_MENU -> currentChannel?.let { toggleFavourite(it) }
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun handleBack() {
        val now = SystemClock.uptimeMillis()
        val overlayVisible = overlay.visibility == View.VISIBLE

        if (overlayVisible && currentChannel != null) {
            hideOverlay() // back to the picture
            return
        }
        if (now - lastBackPress < 2_000) {
            finish()
            return
        }
        lastBackPress = now
        toast(getString(R.string.press_back_again))
    }

    private fun typeDigit(digit: Int) {
        if (digits.length >= 4) digits = ""
        digits += digit
        numberEntry.text = digits
        numberEntry.visibility = View.VISIBLE
        handler.removeCallbacks(commitDigits)
        handler.postDelayed(commitDigits, 1_500)
    }

    private fun jumpToNumber() {
        val number = digits.toIntOrNull()
        digits = ""
        numberEntry.visibility = View.GONE
        if (number == null) return

        val list = if (overlay.visibility == View.VISIBLE || playingList.isEmpty()) {
            visibleChannels
        } else {
            playingList
        }
        if (number in 1..list.size) {
            playChannel(list, number - 1)
            hideOverlay()
        } else {
            toast(getString(R.string.no_such_channel, number))
        }
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; BRAVIA 4K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

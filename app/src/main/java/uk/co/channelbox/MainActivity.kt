package uk.co.channelbox

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity(), ChannelAdapter.Listener, GroupAdapter.Listener {

    private lateinit var prefs: Prefs
    private lateinit var health: HealthChecker
    private lateinit var timeFormat: java.text.DateFormat

    // Views
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var groupList: RecyclerView
    private lateinit var channelList: RecyclerView
    private lateinit var listTitle: TextView
    private lateinit var emptyText: TextView
    private lateinit var statusText: TextView
    private lateinit var scheduleColumn: View
    private lateinit var scheduleTitle: TextView
    private lateinit var scheduleList: LinearLayout
    private lateinit var banner: View
    private lateinit var bannerNumber: TextView
    private lateinit var bannerName: TextView
    private lateinit var bannerInfo: TextView
    private lateinit var bannerNext: TextView
    private lateinit var bannerStatus: TextView
    private lateinit var numberEntry: TextView
    private lateinit var loadingView: View
    private lateinit var loadingText: TextView

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val checker = Executors.newSingleThreadExecutor()
    @Volatile private var checkGeneration = 0

    // Channel data
    private var allChannels: List<Channel> = emptyList()
    private var favourites: Set<String> = emptySet()
    private var hideOffline = true
    private var guide = GuideData.EMPTY
    private var guideState = GuideState.LOADING
    private var healthText = ""

    // What's on screen
    private var groups: List<Group> = emptyList()
    private var currentGroupIndex = 0
    private var visibleChannels: List<Channel> = emptyList()
    private var guideMode = false

    // What's playing
    private var playingList: List<Channel> = emptyList()
    private var playingIndex = -1
    private var currentChannel: Channel? = null
    private var streamQueue: List<Stream> = emptyList()
    private var streamIndex = 0
    private var triedHlsFallback = false

    private var loadedSettingsVersion = -1
    private var loading = false
    private var reloadPending = false
    private var lastLoadTime = 0L
    private var lastBackPress = 0L
    private var digits = ""

    private enum class GuideState { LOADING, READY, NOT_SET_UP, FAILED }

    private val hideBanner = Runnable { banner.visibility = View.GONE }
    private val commitDigits = Runnable { jumpToNumber() }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        health = HealthChecker(applicationContext)
        timeFormat = android.text.format.DateFormat.getTimeFormat(this)

        playerView = findViewById(R.id.player_view)
        overlay = findViewById(R.id.overlay)
        groupList = findViewById(R.id.group_list)
        channelList = findViewById(R.id.channel_list)
        listTitle = findViewById(R.id.list_title)
        emptyText = findViewById(R.id.empty_text)
        statusText = findViewById(R.id.status_text)
        scheduleColumn = findViewById(R.id.schedule_column)
        scheduleTitle = findViewById(R.id.schedule_title)
        scheduleList = findViewById(R.id.schedule_list)
        banner = findViewById(R.id.banner)
        bannerNumber = findViewById(R.id.banner_number)
        bannerName = findViewById(R.id.banner_name)
        bannerInfo = findViewById(R.id.banner_info)
        bannerNext = findViewById(R.id.banner_next)
        bannerStatus = findViewById(R.id.banner_status)
        numberEntry = findViewById(R.id.number_entry)
        loadingView = findViewById(R.id.loading)
        loadingText = findViewById(R.id.loading_text)

        groupAdapter = GroupAdapter(this)
        channelAdapter = ChannelAdapter(
            listener = this,
            describe = { describeChannel(it) },
            isOffline = { health.channelStatus(it) == false },
            isFavourite = { isFavourite(it) },
        )
        groupList.layoutManager = LinearLayoutManager(this)
        groupList.adapter = groupAdapter
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = channelAdapter
        channelList.itemAnimator = null
        groupList.itemAnimator = null

        findViewById<View>(R.id.guide_button).setOnClickListener { setGuideMode(!guideMode) }
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
        streamQueue.getOrNull(streamIndex)?.let { startPlayback(it, forceHls = false) }
    }

    override fun onResume() {
        super.onResume()
        when {
            prefs.settingsVersion != loadedSettingsVersion -> {
                loadedSettingsVersion = prefs.settingsVersion
                loadPlaylist(force = true)
            }
            // Coming back after a few hours: pick up new listings and re-check channels.
            SystemClock.elapsedRealtime() - lastLoadTime > 3L * 60 * 60 * 1000 -> loadPlaylist(force = false)
        }
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        checkGeneration++
        handler.removeCallbacksAndMessages(null)
        loader.shutdownNow()
        checker.shutdownNow()
    }

    // ---------------------------------------------------------------- loading

    private fun loadPlaylist(force: Boolean) {
        if (loading) {
            reloadPending = true
            return
        }
        loading = true
        lastLoadTime = SystemClock.elapsedRealtime()
        hideOffline = prefs.hideOffline
        if (force) health.forgetFailures()
        loadingText.setText(R.string.loading_channels)
        loadingView.visibility = View.VISIBLE

        val appContext = applicationContext
        loader.execute {
            val playlist = PlaylistRepository.load(appContext, prefs, force)
            handler.post { if (!isDestroyed) onPlaylistLoaded(playlist) }

            val guideUrls = ArrayList<String>()
            if (BuildConfig.GUIDE_URL.isNotEmpty()) guideUrls.add(BuildConfig.GUIDE_URL)
            guideUrls.addAll(playlist.guideUrls)
            val wanted = playlist.channels.mapNotNullTo(HashSet()) { it.guideId }
            val guideResult = GuideRepository.load(appContext, guideUrls.distinct(), wanted, force)

            handler.post {
                loading = false
                if (isDestroyed) return@post
                onGuideLoaded(guideResult)
                if (reloadPending) {
                    reloadPending = false
                    loadPlaylist(force = true)
                }
            }
        }
    }

    private fun onPlaylistLoaded(result: PlaylistRepository.LoadResult) {
        loadingView.visibility = View.GONE
        allChannels = result.channels
        favourites = prefs.favourites
        rebuildGroups(keepSelection = currentChannel != null)

        if (result.errors.isNotEmpty()) {
            toast(getString(R.string.some_lists_failed, result.errors.joinToString("\n")), long = true)
        }

        if (allChannels.isEmpty()) {
            emptyText.setText(R.string.no_channels)
            showOverlay(guide = false)
        } else if (currentChannel == null) {
            val last = prefs.lastChannel
            val allIndex = groups.indexOfFirst { it.kind == Group.Kind.ALL }
            val allList = groups.getOrNull(allIndex)?.channels.orEmpty()
            val lastIndex = allList.indexOfFirst { it.id == last || (last != null && it.hasUrl(setOf(last))) }
            if (lastIndex >= 0) {
                // Pick up where you left off, like a normal TV.
                selectGroup(allIndex)
                playChannel(allList, lastIndex)
                hideOverlay()
            } else {
                showOverlay(guide = false)
            }
        } else {
            // Settings changed or Refresh pressed while watching: keep playing,
            // but point at the updated version of the channel.
            val playingId = currentChannel?.id
            allChannels.firstOrNull { it.id == playingId }?.let { currentChannel = it }
            if (overlay.visibility == View.VISIBLE) showOverlay(guideMode)
        }
        startHealthCheck()
    }

    private fun onGuideLoaded(result: GuideRepository.LoadResult) {
        guide = result.data
        guideState = when {
            !result.configured -> GuideState.NOT_SET_UP
            result.data.isEmpty -> GuideState.FAILED
            else -> GuideState.READY
        }
        updateStatusText()
        channelAdapter.notifyDataSetChanged()
        if (guideMode) showSchedule(focusedChannel() ?: currentChannel)
        if (banner.visibility == View.VISIBLE) currentChannel?.let { showBanner(it, null) }
    }

    private fun startHealthCheck() {
        val generation = ++checkGeneration
        val channels = allChannels
        healthText = getString(R.string.checking_channels_start)
        updateStatusText()
        checker.execute {
            health.checkAll(
                channels,
                onProgress = { done, total ->
                    handler.post {
                        if (generation == checkGeneration) {
                            healthText = getString(R.string.checking_channels, done, total)
                            updateStatusText()
                        }
                    }
                },
                isCancelled = { generation != checkGeneration },
            )
            handler.post {
                if (!isDestroyed && generation == checkGeneration) onHealthChecked()
            }
        }
    }

    private fun onHealthChecked() {
        val working = allChannels.count { health.channelStatus(it) != false }
        healthText = getString(R.string.channels_working, working, allChannels.size)
        updateStatusText()
        refreshLists()
    }

    private fun updateStatusText() {
        val guideText = when (guideState) {
            GuideState.LOADING -> getString(R.string.guide_loading)
            GuideState.READY -> getString(R.string.guide_ready, guide.channelCount)
            GuideState.NOT_SET_UP -> getString(R.string.guide_not_set_up)
            GuideState.FAILED -> getString(R.string.guide_failed)
        }
        statusText.text = listOf(healthText, guideText).filter { it.isNotEmpty() }.joinToString("\n")
    }

    // ---------------------------------------------------------------- lists

    private fun isFavourite(channel: Channel): Boolean =
        channel.id in favourites || channel.hasUrl(favourites)

    /** Channels to list: working (or not yet checked) ones, unless the setting says show all. */
    private fun shownChannels(): List<Channel> {
        if (!hideOffline) return allChannels
        val playingId = currentChannel?.id
        return allChannels.filter { health.channelStatus(it) != false || it.id == playingId }
    }

    private fun rebuildGroups(keepSelection: Boolean) {
        val previous = groups.getOrNull(currentGroupIndex)
        val shown = shownChannels()

        val built = ArrayList<Group>()
        val favChannels = shown.filter { isFavourite(it) }
        if (favChannels.isNotEmpty()) {
            built.add(Group("favourites", favChannels, Group.Kind.FAVOURITES))
        }
        built.add(Group("all", shown, Group.Kind.ALL))

        val byCategory = LinkedHashMap<String, MutableList<Channel>>()
        for (channel in shown) {
            for (g in channel.groups) byCategory.getOrPut(g) { ArrayList() }.add(channel)
        }
        byCategory.keys
            .sortedWith(compareBy<String>({ it == M3uParser.OTHER }, { it.lowercase() }))
            .forEach { name -> built.add(Group(name, byCategory.getValue(name), Group.Kind.CATEGORY)) }

        groups = built
        groupAdapter.submit(groups)

        var index = -1
        if (keepSelection && previous != null) {
            index = groups.indexOfFirst { it.kind == previous.kind && it.name == previous.name }
        }
        if (index < 0) {
            index = groups.indexOfFirst { it.kind == Group.Kind.ALL }.coerceAtLeast(0)
        }
        selectGroup(index)
    }

    /** Rebuild the lists while keeping the highlighted row where it was. */
    private fun refreshLists() {
        val focus = currentFocus
        val channelRow = focus?.let { channelList.findContainingItemView(it) }
        val groupRow = focus?.let { groupList.findContainingItemView(it) }
        val focusedId = channelRow?.let { visibleChannels.getOrNull(channelList.getChildAdapterPosition(it))?.id }

        rebuildGroups(keepSelection = true)
        if (overlay.visibility != View.VISIBLE) return
        when {
            channelRow != null -> {
                if (visibleChannels.isEmpty()) {
                    focusGroup(currentGroupIndex)
                } else {
                    val i = visibleChannels.indexOfFirst { it.id == focusedId }
                    focusChannel(if (i >= 0) i else 0)
                }
            }
            groupRow != null -> focusGroup(currentGroupIndex)
        }
    }

    private fun selectGroup(index: Int) {
        if (groups.isEmpty()) return
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
            val playingPos = visibleChannels.indexOfFirst { it.id == currentChannel?.id }
            channelList.scrollToPosition(if (playingPos >= 0) playingPos else 0)
        }
    }

    private fun describeChannel(channel: Channel): String {
        if (health.channelStatus(channel) == false) return getString(R.string.unavailable)
        guide.current(channel, System.currentTimeMillis())?.let {
            return getString(R.string.now_short, it.title)
        }
        val parts = ArrayList<String>()
        parts.add(channel.groups.joinToString(", "))
        if (channel.streams.size > 1) parts.add(getString(R.string.stream_count, channel.streams.size))
        return parts.joinToString("  ·  ")
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
                    val stream = streamQueue.getOrNull(streamIndex) ?: return
                    if (health.record(stream.url, true)) channelAdapter.notifyDataSetChanged()
                    if (bannerStatus.visibility == View.VISIBLE) {
                        bannerStatus.text = ""
                        bannerStatus.visibility = View.GONE
                    }
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
        prefs.lastChannel = channel.id

        streamQueue = health.ordered(channel)
        streamIndex = 0
        triedHlsFallback = false
        streamQueue.firstOrNull()?.let { startPlayback(it, forceHls = false) }

        showBanner(channel, null)
        channelAdapter.playingId = channel.id
        channelAdapter.notifyDataSetChanged()
    }

    private fun startPlayback(stream: Stream, forceHls: Boolean) {
        val exo = player ?: return

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(stream.userAgent ?: Net.BROWSER_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        stream.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }

        val item = MediaItem.Builder().setUri(stream.url)
        val mime = if (forceHls) MimeTypes.APPLICATION_M3U8 else guessMimeType(stream.url)
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
        val stream = streamQueue.getOrNull(streamIndex) ?: return

        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            exo.seekToDefaultPosition()
            exo.prepare()
            return
        }
        // Some links don't say they're HLS; try that once before moving on.
        if (!triedHlsFallback && guessMimeType(stream.url) == null) {
            triedHlsFallback = true
            startPlayback(stream, forceHls = true)
            return
        }

        health.record(stream.url, false)

        // Try the channel's next stream, if it has one.
        if (streamIndex + 1 < streamQueue.size) {
            streamIndex++
            triedHlsFallback = false
            showBanner(channel, getString(R.string.trying_backup, streamIndex + 1, streamQueue.size))
            startPlayback(streamQueue[streamIndex], forceHls = false)
            return
        }

        checker.execute { health.save() }
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

    /** Next/previous channel, skipping ones known not to work. */
    private fun zap(delta: Int) {
        if (playingList.isEmpty()) {
            if (visibleChannels.isNotEmpty()) playChannel(visibleChannels, 0)
            return
        }
        var index = playingIndex
        for (step in 1..playingList.size) {
            index = (index + delta).mod(playingList.size)
            if (health.channelStatus(playingList[index]) != false) break
        }
        playChannel(playingList, index)
    }

    // ---------------------------------------------------------------- on-screen bits

    private fun time(millis: Long): String = timeFormat.format(Date(millis))

    private fun showBanner(channel: Channel, status: String?) {
        bannerNumber.text = if (playingIndex >= 0) (playingIndex + 1).toString() else ""
        bannerName.text = channel.name

        val now = System.currentTimeMillis()
        val current = guide.current(channel, now)
        val next = guide.next(channel, now)
        if (current != null) {
            bannerInfo.text = getString(R.string.now_until, current.title, time(current.stop))
            bannerNext.text = next?.let { getString(R.string.next_at, time(it.start), it.title) } ?: ""
        } else {
            bannerInfo.text = channel.groups.joinToString(", ")
            bannerNext.text = ""
        }
        bannerNext.visibility = if (bannerNext.text.isEmpty()) View.GONE else View.VISIBLE

        bannerStatus.text = status ?: ""
        bannerStatus.visibility = if (status == null) View.GONE else View.VISIBLE
        banner.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, if (status == null) 5_000L else 8_000L)
    }

    private fun showOverlay(guide: Boolean) {
        overlay.visibility = View.VISIBLE
        banner.visibility = View.GONE
        setGuideMode(guide)
        if (visibleChannels.isEmpty()) {
            focusGroup(currentGroupIndex)
        } else {
            val playingPos = visibleChannels.indexOfFirst { it.id == currentChannel?.id }
            focusChannel(if (playingPos >= 0) playingPos else 0)
        }
    }

    private fun hideOverlay() {
        if (currentChannel == null) return
        overlay.visibility = View.GONE
        if (guideMode) setGuideMode(false)
    }

    /** The TV guide panel: the picker widens to full screen with a schedule on the right. */
    private fun setGuideMode(on: Boolean) {
        guideMode = on
        scheduleColumn.visibility = if (on) View.VISIBLE else View.GONE
        val params = overlay.layoutParams
        params.width = if (on) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        overlay.layoutParams = params
        if (on) showSchedule(focusedChannel() ?: currentChannel ?: visibleChannels.firstOrNull())
    }

    private fun showSchedule(channel: Channel?) {
        scheduleList.removeAllViews()
        if (channel == null) {
            scheduleTitle.text = ""
            return
        }
        scheduleTitle.text = channel.name

        val now = System.currentTimeMillis()
        val programmes = guide.upcoming(channel, now).take(14)
        if (programmes.isEmpty()) {
            val message = layoutInflater.inflate(R.layout.item_programme, scheduleList, false)
            message.findViewById<TextView>(R.id.programme_time).visibility = View.GONE
            message.findViewById<TextView>(R.id.programme_title).text = when (guideState) {
                GuideState.READY -> getString(R.string.no_guide_for_channel)
                GuideState.LOADING -> getString(R.string.guide_loading)
                GuideState.NOT_SET_UP -> getString(R.string.guide_not_set_up_long)
                GuideState.FAILED -> getString(R.string.guide_failed_long)
            }
            message.findViewById<TextView>(R.id.programme_desc).visibility = View.GONE
            scheduleList.addView(message)
            return
        }

        for (p in programmes) {
            val row = layoutInflater.inflate(R.layout.item_programme, scheduleList, false)
            val isNow = p.start <= now && p.stop > now
            row.isActivated = isNow
            row.findViewById<TextView>(R.id.programme_time).text =
                if (isNow) getString(R.string.on_now) else time(p.start)
            row.findViewById<TextView>(R.id.programme_title).text = p.title

            val details = ArrayList<String>()
            if (isNow) details.add(getString(R.string.until_time, time(p.stop)))
            p.subtitle?.let { details.add(it) }
            if (isNow) p.description?.let { details.add(it) }
            val desc = row.findViewById<TextView>(R.id.programme_desc)
            desc.text = details.joinToString("\n")
            desc.visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
            scheduleList.addView(row)
        }
    }

    private fun focusedChannel(): Channel? {
        val focus = currentFocus ?: return null
        val row = channelList.findContainingItemView(focus) ?: return null
        return visibleChannels.getOrNull(channelList.getChildAdapterPosition(row))
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
                handler.postDelayed({ requestFocusAt(list, position, attempt + 1) }, 40L)
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

    override fun onChannelFocused(position: Int) {
        if (guideMode) showSchedule(visibleChannels.getOrNull(position))
    }

    override fun onGroupFocused(position: Int) {
        if (position != currentGroupIndex) selectGroup(position)
    }

    override fun onGroupClick(position: Int) {
        if (position != currentGroupIndex) selectGroup(position)
        focusChannel(0)
    }

    private fun toggleFavourite(channel: Channel) {
        val updated = favourites.toMutableSet()
        val wasFavourite = isFavourite(channel)
        updated.remove(channel.id)
        channel.streams.forEach { updated.remove(it.url) } // tidy up 1.0-style entries
        if (!wasFavourite) updated.add(channel.id)
        favourites = updated
        prefs.favourites = updated
        toast(getString(if (wasFavourite) R.string.fav_removed else R.string.fav_added, channel.name))
        refreshLists()
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
            KeyEvent.KEYCODE_GUIDE -> { pressGuide(); return true }
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
                        val playingPos = visibleChannels.indexOfFirst { it.id == currentChannel?.id }
                        focusChannel(if (playingPos >= 0) playingPos else 0)
                    }
                    return true
                }
                code == KeyEvent.KEYCODE_DPAD_RIGHT && inChannels -> return true // nothing to the right
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
            KeyEvent.KEYCODE_DPAD_LEFT -> showOverlay(guide = false)
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_INFO -> currentChannel?.let { showBanner(it, null) }
            KeyEvent.KEYCODE_MENU -> currentChannel?.let { toggleFavourite(it) }
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    /** Guide button: open the guide, or close it if it's already showing. */
    private fun pressGuide() {
        when {
            overlay.visibility != View.VISIBLE -> showOverlay(guide = true)
            !guideMode -> setGuideMode(true)
            currentChannel != null -> hideOverlay()
            else -> setGuideMode(false)
        }
    }

    private fun handleBack() {
        val now = SystemClock.uptimeMillis()
        val overlayVisible = overlay.visibility == View.VISIBLE

        if (overlayVisible && currentChannel != null) {
            hideOverlay() // back to the picture
            return
        }
        if (overlayVisible && guideMode) {
            setGuideMode(false)
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
        handler.postDelayed(commitDigits, 1_500L)
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
}

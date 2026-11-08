package uk.co.channelbox

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Date
import java.util.Locale
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
    private lateinit var searchBox: EditText
    private lateinit var emptyText: TextView
    private lateinit var statusText: TextView
    private lateinit var guideScreen: View
    private lateinit var epgGrid: EpgGridView
    private lateinit var guideHeading: TextView
    private lateinit var guideChannel: TextView
    private lateinit var guideTitle: TextView
    private lateinit var guideTime: TextView
    private lateinit var guideDesc: TextView
    private lateinit var guideTabs: LinearLayout
    private lateinit var guideTabsScroll: HorizontalScrollView
    private lateinit var guideSearch: EditText
    private lateinit var trickBar: View
    private lateinit var trickIcon: TextView
    private lateinit var trickText: TextView
    private lateinit var trickProgress: ProgressBar
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
    private var allCountries = false
    private var selectedCountries: Set<String> = emptySet()
    private var guide = GuideData.EMPTY
    private var guideState = GuideState.LOADING
    private var healthText = ""

    // What's on screen
    private var groups: List<Group> = emptyList()
    private var currentGroupIndex = 0
    private var visibleChannels: List<Channel> = emptyList()
    private var guideOpen = false
    private var guideChannels: List<Channel> = emptyList()
    private var guideListName = ""
    private var guideCategories: List<GuideTab> = emptyList()
    private var lastGuideTabKey: String? = null
    private var guideSearchQuery = ""
    private var guideCategoryIndex = 0
    private var guideTabsFocused = false

    // Pause / rewind
    private var liveBaselineMs = C.TIME_UNSET
    private var captureLiveBaseline = true
    private var timeshifted = false
    /** null = the top level (countries); otherwise the country being browsed. */
    private var browseCountry: String? = null
    private var searchQuery = ""

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
    private val searchRunnable = Runnable { runSearch() }
    private val stallCheck = Runnable { onStalled() }
    private val guideSearchRunnable = Runnable { runGuideSearch() }
    private val trickUpdater = object : Runnable {
        override fun run() {
            updateTrickBar()
            if (trickBar.visibility == View.VISIBLE) handler.postDelayed(this, 1_000L)
        }
    }
    private val hideTrickBar = Runnable {
        if (player?.playWhenReady != false) trickBar.visibility = View.GONE
    }

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
        searchBox = findViewById(R.id.search_box)
        emptyText = findViewById(R.id.empty_text)
        statusText = findViewById(R.id.status_text)
        guideScreen = findViewById(R.id.guide_screen)
        epgGrid = findViewById(R.id.epg_grid)
        guideHeading = findViewById(R.id.guide_heading)
        guideChannel = findViewById(R.id.guide_channel)
        guideTitle = findViewById(R.id.guide_title)
        guideTime = findViewById(R.id.guide_time)
        guideDesc = findViewById(R.id.guide_desc)
        guideTabs = findViewById(R.id.guide_tabs)
        guideTabsScroll = findViewById(R.id.guide_tabs_scroll)
        guideSearch = findViewById(R.id.guide_search)
        guideSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                guideSearchQuery = s?.toString().orEmpty()
                handler.removeCallbacks(guideSearchRunnable)
                handler.postDelayed(guideSearchRunnable, 300L)
            }
        })
        guideSearch.setOnEditorActionListener { _, _, _ ->
            hideKeyboard(guideSearch)
            runGuideSearch()
            if (guideChannels.isNotEmpty()) {
                setGuideTabsFocused(false)
                epgGrid.requestFocus()
            }
            true
        }
        trickBar = findViewById(R.id.trick_bar)
        trickIcon = findViewById(R.id.trick_icon)
        trickText = findViewById(R.id.trick_text)
        trickProgress = findViewById(R.id.trick_progress)
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

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                handler.removeCallbacks(searchRunnable)
                handler.postDelayed(searchRunnable, 300L)
            }
        })
        searchBox.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            runSearch()
            if (visibleChannels.isNotEmpty()) focusChannel(0)
            true
        }

        epgGrid.timeFormat = timeFormat
        epgGrid.listener = object : EpgGridView.Listener {
            override fun onGuideFocus(row: Int, programme: Programme?, slotStart: Long) =
                showGuideInfo(row, programme, slotStart)

            override fun onGuideSelect(row: Int, programme: Programme?) {
                val channel = guideChannels.getOrNull(row) ?: return
                if (channel.id == currentChannel?.id) {
                    // Already in the little picture: OK again goes full screen.
                    closeGuide()
                    showBanner(channel, null)
                } else {
                    // Like Sky: the first OK changes the little picture, and you stay in the guide.
                    playChannel(guideChannels, row)
                    epgGrid.playingId = channel.id
                }
            }

            override fun onGuideTopEdge() {
                if (currentGuideTab()?.kind == TabKind.SEARCH) {
                    guideSearch.requestFocus()
                } else {
                    setGuideTabsFocused(true)
                }
            }
        }
        findViewById<View>(R.id.guide_button).setOnClickListener { openGuide() }
        findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.reload_button).setOnClickListener { loadPlaylist(force = true) }

        loadedSettingsVersion = prefs.settingsVersion
        loadPlaylist(force = false)
        checkForUpdateQuietly()
    }

    /** Once a day, see if a newer ChannelBox has been built and mention it. */
    private fun checkForUpdateQuietly() {
        if (!Updater.isAvailable) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < 24L * 60 * 60 * 1000) return
        prefs.lastUpdateCheck = now
        checker.execute {
            val newer = runCatching { Updater.checkForNewer() }.getOrNull()
            if (newer != null) handler.post { if (!isDestroyed) toast(getString(R.string.update_ready_toast), long = true) }
        }
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
        allCountries = prefs.allCountries
        selectedCountries = prefs.countries
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
        val browsing = browseCountry
        if (browsing != null && !allCountries && browsing !in selectedCountries) browseCountry = null
        rebuildGroups(keepSelection = currentChannel != null)

        if (result.errors.isNotEmpty()) {
            toast(getString(R.string.some_lists_failed, result.errors.joinToString("\n")), long = true)
        }

        if (allChannels.isEmpty()) {
            emptyText.setText(R.string.no_channels)
            showOverlay()
        } else if (currentChannel == null) {
            val last = prefs.lastChannel
            val matches = { c: Channel -> c.id == last || (last != null && c.hasUrl(setOf(last))) }
            // Pick up where you left off, like a normal TV, zapping within its country.
            val groupIndex = groups.indexOfFirst { g ->
                (g.kind == Group.Kind.COUNTRY || g.kind == Group.Kind.ALL || g.kind == Group.Kind.MY_PLAYLIST) &&
                    g.channels.any(matches)
            }
            if (last != null && groupIndex >= 0) {
                selectGroup(groupIndex)
                val list = groups[groupIndex].channels
                playChannel(list, list.indexOfFirst(matches))
                hideOverlay()
            } else {
                showOverlay()
            }
        } else {
            // Settings changed or Refresh pressed while watching: keep playing,
            // but point at the updated version of the channel.
            val playingId = currentChannel?.id
            allChannels.firstOrNull { it.id == playingId }?.let { currentChannel = it }
            if (guideOpen) rebuildGuideTabs(keepPosition = true)
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
        if (guideOpen) rebuildGuideTabs(keepPosition = true)
        if (banner.visibility == View.VISIBLE) currentChannel?.let { showBanner(it, null) }
    }

    private fun startHealthCheck() {
        val generation = ++checkGeneration
        val channels = countryChannels()
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
        val inScope = countryChannels()
        val working = inScope.count { health.channelStatus(it) != false }
        healthText = getString(R.string.channels_working, working, inScope.size)
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

    /** Channels in the countries you've picked, plus anything only in your own playlist. */
    private fun countryChannels(): List<Channel> =
        if (allCountries) allChannels
        else allChannels.filter { ch -> ch.isCustom || ch.countries.any { it in selectedCountries } }

    /** Those channels, minus ones that aren't working (unless the setting says show all). */
    private fun shownChannels(): List<Channel> {
        val inScope = countryChannels()
        if (!hideOffline) return inScope
        val playingId = currentChannel?.id
        return inScope.filter { health.channelStatus(it) != false || it.id == playingId }
    }

    private fun countryOrder(name: String): Int = when (name) {
        Prefs.HOME_COUNTRY -> 0
        "International" -> 2
        M3uParser.OTHER -> 3
        else -> 1
    }

    /**
     * The left-hand list has two levels:
     *  - top: Favourites, Recently watched, Search, your playlist, then each country
     *  - inside a country: Back, All channels in that country, then its categories
     */
    private fun rebuildGroups(keepSelection: Boolean) {
        val previousKey = groups.getOrNull(currentGroupIndex)?.key
        val shown = shownChannels()
        val built = ArrayList<Group>()

        val country = browseCountry
        if (country == null) {
            val favs = shown.filter { isFavourite(it) }
            if (favs.isNotEmpty()) {
                built.add(Group("favourites", getString(R.string.favourites), favs, Group.Kind.FAVOURITES))
            }
            val byId = shown.associateBy { it.id }
            val recent = prefs.recent.mapNotNull { byId[it] }
            if (recent.isNotEmpty()) {
                built.add(Group("recent", getString(R.string.recent), recent, Group.Kind.RECENT))
            }
            built.add(Group("search", getString(R.string.search), searchResults(shown), Group.Kind.SEARCH))
            val mine = shown.filter { it.isCustom }
            if (mine.isNotEmpty()) {
                built.add(Group("mine", getString(R.string.my_playlist), mine, Group.Kind.MY_PLAYLIST))
            }
            val byCountry = LinkedHashMap<String, MutableList<Channel>>()
            for (ch in shown) {
                for (c in ch.countries) {
                    if (allCountries || c in selectedCountries) byCountry.getOrPut(c) { ArrayList() }.add(ch)
                }
            }
            byCountry.keys
                .sortedWith(compareBy<String>({ countryOrder(it) }, { it.lowercase() }))
                .forEach { built.add(Group("country:$it", it, byCountry.getValue(it), Group.Kind.COUNTRY)) }
            built.add(Group("all", getString(R.string.all_channels), shown, Group.Kind.ALL))
        } else {
            val inCountry = shown.filter { country in it.countries }
            built.add(Group("back", getString(R.string.back_to_countries), inCountry, Group.Kind.BACK))
            built.add(Group("country-all:$country", getString(R.string.all_in_country, country), inCountry, Group.Kind.ALL))
            val byCategory = LinkedHashMap<String, MutableList<Channel>>()
            for (ch in inCountry) {
                for (g in ch.groups) byCategory.getOrPut(g) { ArrayList() }.add(ch)
            }
            byCategory.keys
                .sortedWith(compareBy<String>({ it == M3uParser.OTHER }, { it.lowercase() }))
                .forEach { built.add(Group("cat:$it", it, byCategory.getValue(it), Group.Kind.CATEGORY)) }
        }

        groups = built
        groupAdapter.submit(groups)

        var index = if (keepSelection && previousKey != null) groups.indexOfFirst { it.key == previousKey } else -1
        if (index < 0) index = defaultGroupIndex()
        selectGroup(index)
    }

    /** Inside a country: its "All" row. At the top: the first country (the UK). */
    private fun defaultGroupIndex(): Int {
        if (browseCountry != null) {
            return groups.indexOfFirst { it.kind == Group.Kind.ALL }.coerceAtLeast(0)
        }
        val firstCountry = groups.indexOfFirst { it.kind == Group.Kind.COUNTRY }
        return if (firstCountry >= 0) firstCountry else 0
    }

    private fun openCountry(name: String) {
        browseCountry = name
        rebuildGroups(keepSelection = false)
        focusGroup(currentGroupIndex)
    }

    private fun closeCountry() {
        val name = browseCountry ?: return
        browseCountry = null
        rebuildGroups(keepSelection = false)
        val index = groups.indexOfFirst { it.kind == Group.Kind.COUNTRY && it.label == name }
        if (index >= 0) selectGroup(index)
        focusGroup(currentGroupIndex)
    }

    // ---------------------------------------------------------------- search

    private fun searchResults(from: List<Channel>): List<Channel> {
        val words = searchQuery.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return from.asSequence()
            .filter { ch -> words.all { ch.searchText.contains(it) } }
            .sortedBy { if (it.name.lowercase().startsWith(words[0])) 0 else 1 }
            .take(300)
            .toList()
    }

    private fun runSearch() {
        val index = groups.indexOfFirst { it.kind == Group.Kind.SEARCH }
        if (index < 0) return
        val updated = groups.toMutableList()
        updated[index] = groups[index].copy(channels = searchResults(shownChannels()))
        groups = updated
        groupAdapter.submit(groups)
        if (currentGroupIndex == index) selectGroup(index)
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(field: View = searchBox) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun showKeyboard(field: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Rebuild the lists while keeping the highlighted row where it was. */
    private fun refreshLists() {
        val focus = currentFocus
        val channelRow = focus?.let { channelList.findContainingItemView(it) }
        val groupRow = focus?.let { groupList.findContainingItemView(it) }
        val focusedId = channelRow?.let { visibleChannels.getOrNull(channelList.getChildAdapterPosition(it))?.id }

        rebuildGroups(keepSelection = true)
        if (guideOpen) rebuildGuideTabs(keepPosition = true)
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
        val title = if (group.kind == Group.Kind.BACK) browseCountry.orEmpty() else group.label
        listTitle.text = getString(R.string.list_title, title, visibleChannels.size)
        searchBox.visibility = if (group.kind == Group.Kind.SEARCH) View.VISIBLE else View.GONE
        emptyText.text = when {
            group.kind == Group.Kind.SEARCH && searchQuery.isBlank() -> getString(R.string.search_prompt)
            group.kind == Group.Kind.SEARCH -> getString(R.string.search_none, searchQuery)
            allChannels.isEmpty() -> getString(R.string.no_channels)
            else -> getString(R.string.nothing_here)
        }
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
        val inCountryList = browseCountry != null ||
            groups.getOrNull(currentGroupIndex)?.kind == Group.Kind.COUNTRY
        if (!inCountryList) channel.countries.firstOrNull()?.let { parts.add(it) }
        parts.add(channel.groups.joinToString(", "))
        if (channel.streams.size > 1) parts.add(getString(R.string.stream_count, channel.streams.size))
        return parts.joinToString("  ·  ")
    }

    // ---------------------------------------------------------------- playback

    private fun createPlayer() {
        if (player != null) return
        // A bigger safety buffer and a cautious first guess at the connection speed,
        // so slower broadband stutters less.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 60_000, 2_500, 6_000)
            .build()
        val bandwidthMeter = DefaultBandwidthMeter.Builder(this)
            .setInitialBitrateEstimate(1_500_000L)
            .build()
        val exo = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .build()
        val maxHeight = prefs.maxHeight
        if (maxHeight > 0) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(maxHeight * 2, maxHeight)
                .build()
        }
        exo.setAudioAttributes(AudioAttributes.DEFAULT, true)
        exo.playWhenReady = true
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // If the picture sits buffering for too long, try the next stream.
                handler.removeCallbacks(stallCheck)
                if (playbackState == Player.STATE_BUFFERING && exo.playWhenReady) {
                    handler.postDelayed(stallCheck, STALL_TIMEOUT_MS)
                }
                if (playbackState == Player.STATE_READY) {
                    if (captureLiveBaseline && exo.isCurrentMediaItemLive) {
                        liveBaselineMs = exo.currentLiveOffset
                        captureLiveBaseline = false
                    }
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
        handler.removeCallbacks(stallCheck)
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
        val recent = prefs.recent.toMutableList()
        recent.remove(channel.id)
        recent.add(0, channel.id)
        prefs.recent = recent.take(30)

        // With a quality limit set, try streams within the limit first.
        val maxHeight = prefs.maxHeight
        streamQueue = health.ordered(channel).let { ordered ->
            if (maxHeight <= 0) ordered
            else ordered.sortedBy { s -> if ((s.height ?: 0) > maxHeight) 1 else 0 }
        }
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
        captureLiveBaseline = true
        liveBaselineMs = C.TIME_UNSET
        timeshifted = false
        handler.removeCallbacks(stallCheck)
        trickBar.visibility = View.GONE
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
            if (timeshifted) showBanner(channel, getString(R.string.back_to_live_paused_too_long))
            timeshifted = false
            exo.seekToDefaultPosition()
            exo.prepare()
            exo.play()
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
        if (guideOpen) return
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

    /** The channel picker is now the guide-style page. */
    private fun showOverlay() {
        openGuide()
    }

    private fun hideOverlay() {
        if (currentChannel == null) return
        overlay.visibility = View.GONE
    }

    // ---------------------------------------------------------------- TV guide page

    private val guideTick = object : Runnable {
        override fun run() {
            epgGrid.tick()
            handler.postDelayed(this, 60_000L)
        }
    }

    private enum class TabKind { FAVOURITES, ALL, RECENT, SEARCH, LISTED, CATEGORY, SETTINGS }

    private class GuideTab(val kind: TabKind, val label: String, val channels: List<Channel>) {
        val key: String get() = "$kind:$label"
    }

    private fun currentGuideTab(): GuideTab? = guideCategories.getOrNull(guideCategoryIndex)

    /**
     * Opens the guide-style channel picker: tabs across the top (Favourites
     * first), and every channel from your ticked countries A to Z underneath.
     */
    private fun openGuide() {
        if (!guideOpen) {
            guideOpen = true
            overlay.visibility = View.GONE
            banner.visibility = View.GONE
            guideScreen.visibility = View.VISIBLE

            // Shrink the live picture into the top-right corner.
            val params = playerView.layoutParams as android.widget.FrameLayout.LayoutParams
            params.width = resources.getDimensionPixelSize(R.dimen.guide_preview_width)
            params.height = resources.getDimensionPixelSize(R.dimen.guide_preview_height)
            params.gravity = android.view.Gravity.TOP or android.view.Gravity.END
            params.topMargin = resources.getDimensionPixelSize(R.dimen.guide_preview_top)
            params.marginEnd = resources.getDimensionPixelSize(R.dimen.guide_preview_end)
            playerView.layoutParams = params

            handler.removeCallbacks(guideTick)
            handler.postDelayed(guideTick, 60_000L)
        }
        epgGrid.playingId = currentChannel?.id
        rebuildGuideTabs(keepPosition = false)
        setGuideTabsFocused(false)
        epgGrid.requestFocus()

        // Nothing on yet? Start the highlighted channel in the little picture.
        if (currentChannel == null && guideChannels.isNotEmpty()) {
            playChannel(guideChannels, epgGrid.focusRow)
            epgGrid.playingId = currentChannel?.id
        }
    }

    private fun closeGuide() {
        if (!guideOpen) return
        guideOpen = false
        handler.removeCallbacks(guideTick)
        hideKeyboard(guideSearch)
        guideScreen.visibility = View.GONE
        val params = playerView.layoutParams as android.widget.FrameLayout.LayoutParams
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.topMargin = 0
        params.marginEnd = 0
        playerView.layoutParams = params
    }

    /** All the channels the picker can show: your ticked countries, A to Z. */
    private fun guideBaseChannels(): List<Channel> =
        shownChannels().sortedWith(compareBy<Channel>({ it.name.lowercase().removePrefix("the ") }, { it.name }))

    private fun searchIn(from: List<Channel>, query: String): List<Channel> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return from.asSequence()
            .filter { ch -> words.all { ch.searchText.contains(it) } }
            .sortedBy { if (it.name.lowercase().startsWith(words[0])) 0 else 1 }
            .take(300)
            .toList()
    }

    /**
     * Tabs: ★ Favourites, All channels, Recently watched, Search, With listings,
     * then categories (biggest first), then Settings.
     */
    private fun rebuildGuideTabs(keepPosition: Boolean) {
        val previousKey = currentGuideTab()?.key ?: lastGuideTabKey
        val focusedId = if (keepPosition) guideChannels.getOrNull(epgGrid.focusRow)?.id else currentChannel?.id
        val base = guideBaseChannels()
        val byId = base.associateBy { it.id }

        val tabs = ArrayList<GuideTab>()
        val favs = base.filter { isFavourite(it) }
        if (favs.isNotEmpty()) tabs.add(GuideTab(TabKind.FAVOURITES, getString(R.string.guide_tab_favourites), favs))
        tabs.add(GuideTab(TabKind.ALL, getString(R.string.guide_tab_all), base))
        val recent = prefs.recent.mapNotNull { byId[it] }
        if (recent.isNotEmpty()) tabs.add(GuideTab(TabKind.RECENT, getString(R.string.guide_tab_recent), recent))
        tabs.add(GuideTab(TabKind.SEARCH, getString(R.string.guide_tab_search), searchIn(base, guideSearchQuery)))
        val listed = base.filter { guide.programmes(it).isNotEmpty() }
        if (listed.isNotEmpty() && listed.size < base.size) {
            tabs.add(GuideTab(TabKind.LISTED, getString(R.string.guide_tab_listed), listed))
        }
        val byCategory = LinkedHashMap<String, MutableList<Channel>>()
        for (ch in base) for (g in ch.groups) byCategory.getOrPut(g) { ArrayList() }.add(ch)
        byCategory.entries
            .sortedWith(compareBy<Map.Entry<String, MutableList<Channel>>>({ it.key == M3uParser.OTHER }, { -it.value.size }))
            .forEach { tabs.add(GuideTab(TabKind.CATEGORY, it.key, it.value)) }
        tabs.add(GuideTab(TabKind.SETTINGS, getString(R.string.guide_tab_settings), emptyList()))
        guideCategories = tabs

        guideTabs.removeAllViews()
        val padH = (14 * resources.displayMetrics.density).toInt()
        val padV = (6 * resources.displayMetrics.density).toInt()
        for (tab in tabs) {
            val view = TextView(this)
            val showCount = tab.kind != TabKind.SEARCH && tab.kind != TabKind.SETTINGS
            view.text = if (showCount) "${tab.label}  ${tab.channels.size}" else tab.label
            view.setTextColor(0xFFFFFFFF.toInt())
            view.textSize = 15f
            view.setPadding(padH, padV, padH, padV)
            view.setBackgroundResource(R.drawable.tab_bg)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.marginEnd = padV
            guideTabs.addView(view, params)
        }

        val index = tabs.indexOfFirst { it.key == previousKey }.takeIf { it >= 0 } ?: 0
        applyGuideCategory(index, focusedId)
    }

    private fun applyGuideCategory(index: Int, focusId: String? = currentChannel?.id) {
        if (guideCategories.isEmpty()) return
        guideCategoryIndex = index.mod(guideCategories.size)
        val tab = guideCategories[guideCategoryIndex]
        lastGuideTabKey = tab.key
        guideChannels = tab.channels
        guideHeading.text = getString(R.string.guide_heading, tab.label)
        guideSearch.visibility = if (tab.kind == TabKind.SEARCH) View.VISIBLE else View.GONE
        val startRow = guideChannels.indexOfFirst { it.id == focusId }.coerceAtLeast(0)
        epgGrid.setData(guideChannels, guide, startRow)
        if (guideChannels.isEmpty()) showEmptyGuideInfo(tab)
        updateGuideTabs()
    }

    private fun showEmptyGuideInfo(tab: GuideTab) {
        guideChannel.text = ""
        guideTime.text = ""
        when (tab.kind) {
            TabKind.SETTINGS -> {
                guideTitle.setText(R.string.guide_settings_title)
                guideDesc.setText(R.string.guide_settings_desc)
            }
            TabKind.SEARCH -> {
                guideTitle.text = if (guideSearchQuery.isBlank()) {
                    getString(R.string.guide_search_title)
                } else {
                    getString(R.string.search_none, guideSearchQuery)
                }
                guideDesc.setText(R.string.guide_search_desc)
            }
            else -> {
                guideTitle.setText(if (loading) R.string.loading_channels else R.string.nothing_here)
                guideDesc.text = if (allChannels.isEmpty() && !loading) getString(R.string.no_channels) else ""
            }
        }
    }

    private fun runGuideSearch() {
        val index = guideCategories.indexOfFirst { it.kind == TabKind.SEARCH }
        if (index < 0) return
        val updated = guideCategories.toMutableList()
        updated[index] = GuideTab(TabKind.SEARCH, guideCategories[index].label, searchIn(guideBaseChannels(), guideSearchQuery))
        guideCategories = updated
        if (guideCategoryIndex == index) {
            guideChannels = updated[index].channels
            epgGrid.setData(guideChannels, guide, 0)
            if (guideChannels.isEmpty()) showEmptyGuideInfo(updated[index])
        }
    }

    private fun setGuideTabsFocused(focused: Boolean) {
        guideTabsFocused = focused
        updateGuideTabs()
    }

    private fun updateGuideTabs() {
        for (i in 0 until guideTabs.childCount) {
            val tab = guideTabs.getChildAt(i)
            tab.isActivated = i == guideCategoryIndex
            tab.isSelected = guideTabsFocused && i == guideCategoryIndex
        }
        guideTabs.getChildAt(guideCategoryIndex)?.let { tab ->
            guideTabsScroll.post { guideTabsScroll.smoothScrollTo((tab.left - tab.width).coerceAtLeast(0), 0) }
        }
    }

    /** OK (or ▼) on a tab. */
    private fun enterGuideTab(viaOk: Boolean) {
        when (currentGuideTab()?.kind) {
            TabKind.SETTINGS -> if (viaOk) startActivity(Intent(this, SettingsActivity::class.java))
            TabKind.SEARCH -> {
                setGuideTabsFocused(false)
                guideSearch.requestFocus()
                showKeyboard(guideSearch)
            }
            else -> if (guideChannels.isNotEmpty()) {
                setGuideTabsFocused(false)
                epgGrid.requestFocus()
            }
        }
    }

    // ---------------------------------------------------------------- stalls, pause and rewind

    private fun onStalled() {
        val exo = player ?: return
        val channel = currentChannel ?: return
        if (exo.playbackState != Player.STATE_BUFFERING || !exo.playWhenReady) return
        if (streamIndex + 1 < streamQueue.size) {
            streamIndex++
            triedHlsFallback = false
            showBanner(channel, getString(R.string.stalled_switching, streamIndex + 1, streamQueue.size))
            startPlayback(streamQueue[streamIndex], forceHls = false)
        } else {
            showBanner(channel, getString(R.string.stalled_no_backup))
        }
    }

    private fun togglePause() {
        val exo = player ?: return
        if (currentChannel == null) return
        if (exo.playWhenReady) {
            if (!exo.isCurrentMediaItemSeekable && exo.isCurrentMediaItemLive) {
                toast(getString(R.string.trick_cant_rewind))
                return
            }
            exo.pause()
            timeshifted = true
        } else {
            exo.play()
        }
        showTrickBar()
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        if (currentChannel == null) return
        if (!exo.isCurrentMediaItemSeekable) {
            toast(getString(R.string.trick_cant_rewind))
            return
        }
        val duration = exo.duration
        var target = (exo.currentPosition + deltaMs).coerceAtLeast(0L)
        if (duration != C.TIME_UNSET) {
            // Fast-forwarding to within a few seconds of the end means "back to live".
            if (exo.isCurrentMediaItemLive && deltaMs > 0 && target >= duration - LIVE_SNAP_MS) {
                goLive()
                return
            }
            target = target.coerceAtMost(duration)
        }
        exo.seekTo(target)
        timeshifted = true
        showTrickBar()
    }

    private fun goLive() {
        val exo = player ?: return
        exo.seekToDefaultPosition()
        exo.play()
        timeshifted = false
        showTrickBar()
    }

    /** How far behind the live picture we are, in ms (0 when live or unknown). */
    private fun behindLiveMs(): Long {
        val exo = player ?: return 0
        if (!exo.isCurrentMediaItemLive) return 0
        val offset = exo.currentLiveOffset
        if (offset == C.TIME_UNSET) return 0
        val baseline = if (liveBaselineMs != C.TIME_UNSET) liveBaselineMs else 0L
        return (offset - baseline).coerceAtLeast(0L)
    }

    private fun showTrickBar() {
        trickBar.visibility = View.VISIBLE
        handler.removeCallbacks(trickUpdater)
        handler.removeCallbacks(hideTrickBar)
        updateTrickBar()
        handler.postDelayed(trickUpdater, 1_000L)
        if (player?.playWhenReady != false) handler.postDelayed(hideTrickBar, 4_000L)
    }

    private fun updateTrickBar() {
        val exo = player ?: return
        val behind = behindLiveMs()
        val isLive = behind < LIVE_SNAP_MS
        val paused = !exo.playWhenReady
        trickIcon.text = when {
            paused -> "❚❚"
            isLive -> "●"
            else -> "▶"
        }
        trickIcon.setTextColor(if (!paused && isLive) 0xFFFF4D57.toInt() else 0xFFFFFFFF.toInt())
        val parts = ArrayList<String>()
        parts.add(
            when {
                paused && !isLive -> getString(R.string.trick_paused_behind, formatDuration(behind))
                paused -> getString(R.string.trick_paused)
                isLive -> getString(R.string.trick_live)
                else -> getString(R.string.trick_behind, formatDuration(behind))
            }
        )
        val duration = exo.duration
        if (exo.isCurrentMediaItemSeekable && duration != C.TIME_UNSET && duration > 0) {
            parts.add(getString(R.string.trick_range, formatDuration(duration)))
            trickProgress.progress = ((exo.currentPosition * 1000) / duration).toInt().coerceIn(0, 1000)
            trickProgress.visibility = View.VISIBLE
        } else {
            trickProgress.visibility = View.GONE
        }
        trickText.text = parts.joinToString("  ·  ")
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.UK, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.UK, "%d:%02d", minutes, seconds)
        }
    }

    // ---------------------------------------------------------------- sound and subtitles

    private class TrackOption(val group: Tracks.Group, val index: Int, val label: String)

    private fun trackOptions(type: Int): List<TrackOption> {
        val exo = player ?: return emptyList()
        val options = ArrayList<TrackOption>()
        for (group in exo.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                options.add(TrackOption(group, i, trackLabel(group.getTrackFormat(i), options.size + 1, type)))
            }
        }
        return options
    }

    private fun trackLabel(format: Format, number: Int, type: Int): String {
        val language = format.language
            ?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { code -> Locale.forLanguageTag(code).displayLanguage.ifEmpty { code } }
        val base = format.label?.takeIf { it.isNotBlank() }
            ?: language
            ?: getString(if (type == C.TRACK_TYPE_AUDIO) R.string.track_n else R.string.subtitle_n, number)
        val surround = when {
            type != C.TRACK_TYPE_AUDIO || format.channelCount <= 2 -> ""
            format.channelCount == 6 -> " (5.1)"
            else -> " (${format.channelCount} channels)"
        }
        return base + surround
    }

    private fun subtitlesOff(): Boolean =
        player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true

    private fun currentTrackLabel(type: Int): String? {
        if (type == C.TRACK_TYPE_TEXT && subtitlesOff()) return null
        return trackOptions(type).firstOrNull { it.group.isTrackSelected(it.index) }?.label
    }

    /** Menu button while watching: favourite, sound and subtitles. */
    private fun showOptions() {
        val channel = currentChannel ?: return
        val items = arrayOf(
            getString(if (isFavourite(channel)) R.string.opt_remove_fav else R.string.opt_add_fav),
            getString(R.string.opt_sound, currentTrackLabel(C.TRACK_TYPE_AUDIO) ?: getString(R.string.opt_default)),
            getString(R.string.opt_subtitles, currentTrackLabel(C.TRACK_TYPE_TEXT) ?: getString(R.string.subtitles_off)),
        )
        AlertDialog.Builder(this)
            .setTitle(channel.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleFavourite(channel)
                    1 -> chooseTrack(C.TRACK_TYPE_AUDIO)
                    2 -> chooseTrack(C.TRACK_TYPE_TEXT)
                }
            }
            .show()
    }

    private fun chooseTrack(type: Int) {
        val exo = player ?: return
        val options = trackOptions(type)
        val isText = type == C.TRACK_TYPE_TEXT
        if (options.isEmpty() || (!isText && options.size < 2)) {
            toast(getString(if (isText) R.string.no_subtitles else R.string.no_sound_choices))
            return
        }
        val labels = ArrayList<String>()
        if (isText) labels.add(getString(R.string.subtitles_off))
        labels.addAll(options.map { it.label })
        val selected = options.indexOfFirst { it.group.isTrackSelected(it.index) }
        val checked = when {
            isText && (subtitlesOff() || selected < 0) -> 0
            isText -> selected + 1
            else -> selected.coerceAtLeast(0)
        }
        AlertDialog.Builder(this)
            .setTitle(if (isText) R.string.choose_subtitles else R.string.choose_sound)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                val builder = exo.trackSelectionParameters.buildUpon()
                if (isText && which == 0) {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    val option = options[if (isText) which - 1 else which]
                    builder.setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.index))
                    // Remember the language for other channels too.
                    val language = option.group.getTrackFormat(option.index).language
                    if (!language.isNullOrBlank() && language != "und") {
                        if (isText) builder.setPreferredTextLanguage(language) else builder.setPreferredAudioLanguage(language)
                    }
                }
                exo.trackSelectionParameters = builder.build()
                dialog.dismiss()
            }
            .show()
    }

    /** Close the guide back to the picture; with nothing playing, Back twice exits. */
    private fun leaveGuide() {
        if (currentChannel == null) {
            confirmExit()
            return
        }
        closeGuide()
    }

    private fun confirmExit() {
        val now = SystemClock.uptimeMillis()
        if (now - lastBackPress < 2_000) {
            finish()
            return
        }
        lastBackPress = now
        toast(getString(R.string.press_back_again))
    }

    private fun showGuideInfo(row: Int, programme: Programme?, slotStart: Long) {
        val channel = guideChannels.getOrNull(row) ?: return
        guideChannel.text = getString(R.string.guide_channel_line, row + 1, channel.name)
        if (programme == null) {
            guideTitle.text = getString(R.string.guide_no_listings)
            guideTime.text = ""
            guideDesc.text = channel.groups.joinToString(", ")
            return
        }
        guideTitle.text = programme.title
        val now = System.currentTimeMillis()
        guideTime.text = when {
            programme.start <= now && programme.stop > now -> getString(
                R.string.guide_time_now, time(programme.start), time(programme.stop),
                ((programme.stop - now) / 60_000L).toInt().coerceAtLeast(1),
            )
            isSameDay(programme.start, now) -> getString(
                R.string.guide_time_later, time(programme.start), time(programme.stop),
            )
            else -> getString(
                R.string.guide_time_day, dayName(programme.start), time(programme.start), time(programme.stop),
            )
        }
        guideDesc.text = listOfNotNull(programme.subtitle, programme.description).joinToString(" — ")
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
        val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
            ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun dayName(millis: Long): String =
        java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(Date(millis))

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

    override fun onChannelFocused(position: Int) = Unit

    override fun onGroupFocused(position: Int) {
        if (position != currentGroupIndex) selectGroup(position)
    }

    override fun onGroupClick(position: Int) {
        val group = groups.getOrNull(position) ?: return
        when (group.kind) {
            Group.Kind.COUNTRY -> openCountry(group.label)
            Group.Kind.BACK -> closeCountry()
            Group.Kind.SEARCH -> {
                if (position != currentGroupIndex) selectGroup(position)
                searchBox.requestFocus()
                showKeyboard()
            }
            else -> {
                if (position != currentGroupIndex) selectGroup(position)
                focusChannel(0)
            }
        }
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

        if (guideOpen) {
            if (currentFocus === guideSearch) {
                // Typing a search: arrows leave the box, everything else types.
                when (code) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        hideKeyboard(guideSearch)
                        if (guideChannels.isNotEmpty()) epgGrid.requestFocus()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        hideKeyboard(guideSearch)
                        epgGrid.requestFocus()
                        setGuideTabsFocused(true)
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }
            if (guideTabsFocused) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> applyGuideCategory(guideCategoryIndex - 1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> applyGuideCategory(guideCategoryIndex + 1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> enterGuideTab(viaOk = false)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> enterGuideTab(viaOk = true)
                    KeyEvent.KEYCODE_DPAD_UP -> Unit
                    KeyEvent.KEYCODE_GUIDE -> leaveGuide()
                    else -> return super.dispatchKeyEvent(event)
                }
                return true
            }
            when (code) {
                KeyEvent.KEYCODE_PROG_RED -> applyGuideCategory(guideCategoryIndex - 1)
                KeyEvent.KEYCODE_PROG_GREEN -> applyGuideCategory(guideCategoryIndex + 1)
                KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_MENU ->
                    guideChannels.getOrNull(epgGrid.focusRow)?.let { toggleFavourite(it) }
                KeyEvent.KEYCODE_PROG_BLUE -> {
                    val search = guideCategories.indexOfFirst { it.kind == TabKind.SEARCH }
                    if (search >= 0) {
                        applyGuideCategory(search)
                        enterGuideTab(viaOk = true)
                    }
                }
                KeyEvent.KEYCODE_GUIDE -> leaveGuide()
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> epgGrid.page(-1)
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> epgGrid.page(1)
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> typeDigit(code - KeyEvent.KEYCODE_0)
                else -> {
                    if (!epgGrid.hasFocus()) epgGrid.requestFocus()
                    return super.dispatchKeyEvent(event)
                }
            }
            return true
        }

        if (currentFocus is EditText) {
            // Typing in the search box: only handle leaving it with the arrows.
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && visibleChannels.isNotEmpty()) {
                hideKeyboard()
                focusChannel(0)
                return true
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && searchBox.selectionStart == 0) {
                hideKeyboard()
                focusGroup(currentGroupIndex)
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            typeDigit(code - KeyEvent.KEYCODE_0)
            return true
        }
        if (code == KeyEvent.KEYCODE_GUIDE) {
            pressGuide()
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (player?.playWhenReady == true) togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (player?.playWhenReady == false) togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-SEEK_STEP_MS); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(SEEK_STEP_MS); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> { seekBy(-SEEK_BIG_STEP_MS); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> { seekBy(SEEK_BIG_STEP_MS); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { goLive(); return true }
            KeyEvent.KEYCODE_CAPTIONS -> { chooseTrack(C.TRACK_TYPE_TEXT); return true }
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { chooseTrack(C.TRACK_TYPE_AUDIO); return true }
        }

        val overlayVisible = overlay.visibility == View.VISIBLE
        val focus = if (overlayVisible) currentFocus else null
        val channelRow = focus?.let { channelList.findContainingItemView(it) }
        val groupRow = focus?.let { groupList.findContainingItemView(it) }
        val channelPos = channelRow?.let { channelList.getChildAdapterPosition(it) } ?: RecyclerView.NO_POSITION
        val groupPos = groupRow?.let { groupList.getChildAdapterPosition(it) } ?: RecyclerView.NO_POSITION

        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                // In the list, CH+/CH- jump a page at a time; otherwise they change channel.
                if (channelPos != RecyclerView.NO_POSITION) moveInList(channelList, visibleChannels.size, channelPos, -PAGE_SIZE)
                else zap(+1)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (channelPos != RecyclerView.NO_POSITION) moveInList(channelList, visibleChannels.size, channelPos, PAGE_SIZE)
                else zap(-1)
                return true
            }
        }

        if (overlayVisible) {
            val inChannels = channelRow != null
            val inGroups = groupRow != null
            val upDown = when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> -1
                KeyEvent.KEYCODE_DPAD_DOWN -> 1
                else -> 0
            }
            when {
                // Up from the first search result goes back to the search box.
                upDown < 0 && channelPos == 0 && searchBox.visibility == View.VISIBLE -> {
                    searchBox.requestFocus()
                    return true
                }
                // Keep Up/Down inside the list, however fast you scroll.
                upDown != 0 && channelPos != RecyclerView.NO_POSITION -> {
                    moveInList(channelList, visibleChannels.size, channelPos, upDown)
                    return true
                }
                upDown != 0 && groupPos != RecyclerView.NO_POSITION -> {
                    if (upDown < 0 && groupPos == 0) {
                        findViewById<View>(R.id.guide_button).requestFocus() // up to the buttons
                    } else {
                        moveInList(groupList, groups.size, groupPos, upDown)
                    }
                    return true
                }
                code == KeyEvent.KEYCODE_MENU && inChannels -> {
                    onChannelLongClick(channelPos)
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
            KeyEvent.KEYCODE_DPAD_LEFT -> showOverlay()
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_INFO -> currentChannel?.let { showBanner(it, null) }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_YELLOW -> showOptions()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    /**
     * Moves the highlight within one list, stopping at the top and bottom
     * instead of letting it jump across to the other column.
     */
    private fun moveInList(list: RecyclerView, count: Int, from: Int, delta: Int) {
        if (count == 0 || from == RecyclerView.NO_POSITION) return
        val target = (from + delta).coerceIn(0, count - 1)
        if (target == from) return
        val row = list.findViewHolderForAdapterPosition(target)?.itemView
        if (row != null && Math.abs(delta) == 1) {
            row.requestFocus() // the list scrolls itself to keep it on screen
        } else {
            (list.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(target, list.height / 3)
            requestFocusAt(list, target, 0)
        }
    }

    /** Guide button: open the full-screen guide, or close it if it's showing. */
    private fun pressGuide() {
        if (guideOpen) leaveGuide() else openGuide()
    }

    private fun handleBack() {
        if (guideOpen) {
            leaveGuide()
            return
        }
        val now = SystemClock.uptimeMillis()
        val overlayVisible = overlay.visibility == View.VISIBLE
        val inGroups = currentFocus?.let { groupList.findContainingItemView(it) } != null

        // In a country's list on the left, Back goes up to the list of countries.
        if (overlayVisible && inGroups && browseCountry != null) {
            closeCountry()
            return
        }
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
        handler.postDelayed(commitDigits, 1_500L)
    }

    private fun jumpToNumber() {
        val number = digits.toIntOrNull()
        digits = ""
        numberEntry.visibility = View.GONE
        if (number == null) return

        if (guideOpen) {
            if (number in 1..guideChannels.size) epgGrid.jumpToRow(number - 1)
            else toast(getString(R.string.no_such_channel, number))
            return
        }

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
        /** Rows jumped by CH+/CH- while the channel list is open. */
        private const val PAGE_SIZE = 8

        private const val STALL_TIMEOUT_MS = 10_000L
        private const val SEEK_STEP_MS = 15_000L
        private const val SEEK_BIG_STEP_MS = 60_000L
        /** Within this of the live edge counts as "live". */
        private const val LIVE_SNAP_MS = 8_000L
    }
}

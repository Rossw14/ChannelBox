package uk.co.channelbox

import android.app.Activity
import android.app.AlertDialog
import android.widget.Button
import android.widget.TextView
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Toast

/** Choose which channels to show, and add your own M3U link. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private val countryBoxes = ArrayList<Pair<String, CheckBox>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        val iptvOrg = findViewById<CheckBox>(R.id.iptv_org)
        iptvOrg.isChecked = prefs.iptvOrgEnabled

        val freeTv = findViewById<CheckBox>(R.id.free_tv)
        freeTv.isChecked = prefs.freeTvEnabled

        val allCountries = findViewById<CheckBox>(R.id.all_countries)
        allCountries.isChecked = prefs.allCountries

        // Countries the app has seen (with channel counts); a short list before the first load.
        val known = PlaylistRepository.savedCountries(this)
            .ifEmpty { Prefs.DEFAULT_COUNTRIES.map { Pair(it, 0) } }
        val selected = prefs.countries
        // Ticked ones first (the UK at the very top), then the rest A–Z.
        val ordered = known.sortedWith(
            compareBy<Pair<String, Int>>(
                { it.first != Prefs.HOME_COUNTRY },
                { it.first !in selected },
                { it.first.lowercase() },
            )
        )

        val container = findViewById<LinearLayout>(R.id.country_container)
        val padding = dp(12)
        for ((name, count) in ordered) {
            val box = CheckBox(this).apply {
                text = if (count > 0) getString(R.string.country_with_count, name, count) else name
                isChecked = name in selected
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.item_bg)
                setPadding(padding, padding, padding, padding)
                isFocusable = true
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.bottomMargin = dp(4)
            container.addView(box, params)
            countryBoxes.add(name to box)
        }
        fun updateCountryVisibility() {
            container.visibility =
                if ((iptvOrg.isChecked || freeTv.isChecked) && !allCountries.isChecked) View.VISIBLE else View.GONE
            allCountries.visibility = if (iptvOrg.isChecked || freeTv.isChecked) View.VISIBLE else View.GONE
        }
        updateCountryVisibility()
        iptvOrg.setOnCheckedChangeListener { _, _ -> updateCountryVisibility() }
        freeTv.setOnCheckedChangeListener { _, _ -> updateCountryVisibility() }
        allCountries.setOnCheckedChangeListener { _, _ -> updateCountryVisibility() }

        val hideOffline = findViewById<CheckBox>(R.id.hide_offline)
        hideOffline.isChecked = prefs.hideOffline

        val qualityGroup = findViewById<RadioGroup>(R.id.quality_group)
        val qualityOptions = listOf(
            R.id.quality_auto to 0,
            R.id.quality_1080 to 1080,
            R.id.quality_720 to 720,
            R.id.quality_480 to 480,
        )
        val savedQuality = prefs.maxHeight
        qualityGroup.check(qualityOptions.firstOrNull { it.second == savedQuality }?.first ?: R.id.quality_auto)

        val urlField = findViewById<EditText>(R.id.custom_url)
        urlField.setText(prefs.customUrl)

        findViewById<View>(R.id.save_button).setOnClickListener {
            val url = urlField.text.toString().trim()
            val chosen = countryBoxes.filter { it.second.isChecked }.map { it.first }.toSet()

            if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                toast(getString(R.string.url_invalid))
                return@setOnClickListener
            }
            if (!iptvOrg.isChecked && !freeTv.isChecked && url.isEmpty()) {
                toast(getString(R.string.need_a_source))
                return@setOnClickListener
            }
            if ((iptvOrg.isChecked || freeTv.isChecked) && !allCountries.isChecked && chosen.isEmpty()) {
                toast(getString(R.string.need_a_country))
                return@setOnClickListener
            }
            prefs.iptvOrgEnabled = iptvOrg.isChecked
            prefs.freeTvEnabled = freeTv.isChecked
            prefs.allCountries = allCountries.isChecked
            if (chosen.isNotEmpty()) prefs.countries = chosen
            prefs.customUrl = url
            prefs.hideOffline = hideOffline.isChecked
            prefs.maxHeight = qualityOptions.firstOrNull { it.first == qualityGroup.checkedRadioButtonId }?.second ?: 0
            prefs.settingsVersion = prefs.settingsVersion + 1
            toast(getString(R.string.saved))
            finish()
        }
        findViewById<View>(R.id.cancel_button).setOnClickListener { finish() }

        setUpUpdates()
        iptvOrg.requestFocus()
    }

    // ---------------------------------------------------------------- updates

    private fun setUpUpdates() {
        val status = findViewById<TextView>(R.id.update_status)
        val button = findViewById<Button>(R.id.update_button)
        val version = Updater.installedVersionName()
        if (!Updater.isAvailable) {
            status.text = getString(R.string.updates_unavailable, version)
            button.visibility = View.GONE
            return
        }
        status.text = getString(R.string.installed_version, version)
        button.setOnClickListener {
            button.isEnabled = false
            button.setText(R.string.checking_updates)
            Thread {
                val result = runCatching { Updater.checkForNewer() }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    button.isEnabled = true
                    button.setText(R.string.check_updates)
                    result.onSuccess { release ->
                        if (release == null) toast(getString(R.string.up_to_date)) else offerUpdate(release)
                    }.onFailure { e ->
                        toast(getString(R.string.update_failed, e.message ?: e.javaClass.simpleName))
                    }
                }
            }.start()
        }
    }

    private fun offerUpdate(release: Updater.Release) {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_found_title)
            .setMessage(getString(R.string.update_found_message, release.versionName))
            .setPositiveButton(R.string.update_install) { _, _ -> downloadAndInstall() }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun downloadAndInstall() {
        toast(getString(R.string.downloading_update))
        Thread {
            val result = runCatching { Updater.download(applicationContext) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                result.onSuccess { file ->
                    if (!Updater.install(this, file)) toast(getString(R.string.update_allow_install))
                }.onFailure { e ->
                    toast(getString(R.string.update_failed, e.message ?: e.javaClass.simpleName))
                }
            }
        }.start()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

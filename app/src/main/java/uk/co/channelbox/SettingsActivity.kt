package uk.co.channelbox

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/** Choose which channel lists to load, and add your own M3U link. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private val checkBoxes = ArrayList<Pair<PlaylistSource, CheckBox>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        val container = findViewById<LinearLayout>(R.id.source_container)
        val enabled = prefs.enabledSources
        val padding = dp(12)
        for (source in PlaylistRepository.BUILT_IN) {
            val box = CheckBox(this).apply {
                text = source.label
                isChecked = source.id in enabled
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
            checkBoxes.add(source to box)
        }

        val urlField = findViewById<EditText>(R.id.custom_url)
        urlField.setText(prefs.customUrl)

        findViewById<android.view.View>(R.id.save_button).setOnClickListener {
            val url = urlField.text.toString().trim()
            val chosen = checkBoxes.filter { it.second.isChecked }.map { it.first.id }.toSet()

            if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                toast(getString(R.string.url_invalid))
                return@setOnClickListener
            }
            if (chosen.isEmpty() && url.isEmpty()) {
                toast(getString(R.string.need_a_source))
                return@setOnClickListener
            }
            prefs.enabledSources = chosen
            prefs.customUrl = url
            prefs.settingsVersion = prefs.settingsVersion + 1
            toast(getString(R.string.saved))
            finish()
        }
        findViewById<android.view.View>(R.id.cancel_button).setOnClickListener { finish() }

        checkBoxes.firstOrNull()?.second?.requestFocus()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

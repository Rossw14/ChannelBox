package uk.co.channelbox

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/**
 * Self-update. Every GitHub build publishes ChannelBox.apk and a small
 * version.json as the project's "latest release"; this checks that and installs
 * a newer copy. Because every build is signed with the same key, it installs
 * straight over the top and keeps your settings.
 */
object Updater {

    data class Release(val versionCode: Int, val versionName: String)

    /** Empty when the app wasn't built on GitHub (then updates aren't available). */
    val isAvailable: Boolean get() = BuildConfig.UPDATE_URL.isNotEmpty()

    fun installedVersionName(): String = BuildConfig.VERSION_NAME

    /** Asks GitHub for the newest build. Returns null if this copy is already the newest. */
    fun checkForNewer(): Release? {
        val json = JSONObject(String(Net.get("${BuildConfig.UPDATE_URL}/version.json", maxBytes = 64 * 1024), Charsets.UTF_8))
        val release = Release(json.getInt("versionCode"), json.optString("versionName", ""))
        return if (release.versionCode > BuildConfig.VERSION_CODE) release else null
    }

    /** Downloads the newest APK and returns the file. */
    fun download(context: Context): File {
        val bytes = Net.get("${BuildConfig.UPDATE_URL}/ChannelBox.apk", maxBytes = 80 * 1024 * 1024)
        val dir = if (Build.VERSION.SDK_INT >= 24) {
            File(context.cacheDir, "updates")
        } else {
            File(context.externalCacheDir ?: context.cacheDir, "updates")
        }
        dir.mkdirs()
        val file = File(dir, "ChannelBox.apk")
        file.writeBytes(bytes)
        return file
    }

    /**
     * Opens the TV's installer. The first time, Android may ask you to allow
     * ChannelBox to install apps; returns false if it sent you to that setting.
     */
    fun install(activity: Activity, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
                return false
            } catch (e: ActivityNotFoundException) {
                // Some TVs don't have this screen; the installer will ask instead.
            }
        }
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        } else {
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        return true
    }
}

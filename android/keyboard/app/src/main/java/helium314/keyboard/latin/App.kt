// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
package helium314.keyboard.latin

import android.app.Application
import android.os.Build
import com.voicerewriter.BridgeSync
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.upgradeToolbarPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugFlags.init(this)
        FoldableUtils.init(this)
        Settings.init(this)
        SubtypeSettings.init(this)

        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { // do some uncritical work in background for faster startup
            SupportedEmojis.load(this@App)
            LayoutUtilsCustom.removeMissingLayouts(this@App)
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            Log.i(
                "startup", "Starting ${applicationInfo.processName} version ${packageInfo.versionName} (${
                    packageInfo.versionCode
                }) on Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
            )
        }

        RichInputMethodManager.init(this)
        checkVersionUpgrade(this)
        helium314.keyboard.latin.utils.enableEmojiToolbarKey(prefs())
        installBundledEmojiDict()
        if (BuildConfig.DEBUG) // do this on every debug apk start because we may work on adding a new toolbar key
            upgradeToolbarPrefs(prefs())
        transferOldPinnedClips(this) // todo: remove in a few months, maybe end 2026
        app = this
        Defaults.initDynamicDefaults(this)
        // Refresh the managed mobile rules as soon as the keyboard process starts. WorkManager
        // waits for a network connection, so offline dictation keeps using its cached snapshot.
        runCatching { BridgeSync.schedule(this) }
    }

    companion object {
        // used so JniUtils can access application once
        private var app: App? = null
        fun getApp(): App? {
            val application = app
            app = null
            return application
        }
    }

    // OpenWhispr: ship the CLDR emoji dictionary so the emoji palette's search key is available out of the box.
    private fun installBundledEmojiDict() = CoroutineScope(Dispatchers.IO).launch {
        try {
            listOf(java.util.Locale.US, java.util.Locale.UK, java.util.Locale.ENGLISH).forEach { locale ->
                val dir = helium314.keyboard.latin.utils.DictionaryInfoUtils.getCacheDirectoryForLocale(locale, this@App) ?: return@forEach
                val target = java.io.File(dir, "emoji_en.dict")
                if (target.exists() && target.length() > 0) return@forEach
                assets.open("emoji_dicts/emoji_en.dict").use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        } catch (e: Exception) { Log.w("App", "emoji dict install failed", e) }
    }
}

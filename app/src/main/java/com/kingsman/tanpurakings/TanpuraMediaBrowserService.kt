package com.kingsman.tanpurakings

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.MediaBrowserServiceCompat

/**
 * Android Auto / Bluetooth media-browser entry point.
 *
 * Android Auto connects to this service when the user opens the app in the
 * car's dashboard.  We expose the 12 tanpura drone notes as a flat list of
 * playable media items.  Tapping a note fires [onPlayFromMediaId] which
 * delegates to [AudioManager.playNote] exactly as if the user tapped the
 * note button inside the phone app.
 *
 * The [MediaSessionCompat] token is shared with [AudioPlaybackService] via
 * [AudioManager.mediaSessionToken], so the lock-screen card, steering-wheel
 * controls, and Android Auto all use the same session.
 *
 * Registered in AndroidManifest.xml with:
 *   • intent-filter: android.media.browse.MediaBrowserService
 *   • meta-data: com.google.android.gms.car.application → @xml/automotive_app_desc
 */
class TanpuraMediaBrowserService : MediaBrowserServiceCompat() {

    companion object {
        private const val MEDIA_ROOT_ID = "tanpura_root"
    }

    private val noteKeys   = listOf("c","csharp","d","dsharp","e","f","fsharp","g","gsharp","a","asharp","b")
    private val noteLabels = listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")

    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        // Ensure AudioManager is ready (safe to call multiple times — it guards
        // internally with isInitialized).  This matters when Android Auto
        // connects before the user has opened the phone app.
        AudioManager.init(this)

        // Wire up the shared MediaSession token so Android Auto can control
        // playback state and display now-playing metadata.
        AudioManager.mediaSessionToken?.let { setSessionToken(it) }
    }

    // -------------------------------------------------------------------------
    // Content hierarchy
    // -------------------------------------------------------------------------

    /** Known trusted clients allowed to browse and control playback. */
    private val allowedClients = setOf(
        "com.google.android.projection.genie",   // Android Auto
        "com.google.android.autosimulator",       // AA desktop head unit
        "com.android.bluetooth",                  // Bluetooth media browser
        "com.google.android.googlequicksearchbox",// Google Assistant
        packageName                               // This app itself
    )

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        // Only allow known trusted clients to browse media.
        if (clientPackageName !in allowedClients) return null
        return BrowserRoot(MEDIA_ROOT_ID, null)
    }

    /**
     * Returns the flat list of 12 drone notes.
     * Using [MediaBrowserServiceCompat.Result] explicitly avoids the
     * ambiguity with Kotlin's built-in [kotlin.Result] type.
     */
    override fun onLoadChildren(
        parentId: String,
        result: MediaBrowserServiceCompat.Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        if (parentId != MEDIA_ROOT_ID) {
            result.sendResult(ArrayList())
            return
        }

        val items = ArrayList<MediaBrowserCompat.MediaItem>()
        noteKeys.zip(noteLabels).forEach { (key, label) ->
            val desc = MediaDescriptionCompat.Builder()
                .setMediaId(key)
                .setTitle(label)
                .setSubtitle("Tanpura drone — $label")
                .build()
            items.add(MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
        }

        result.sendResult(items)
    }
}

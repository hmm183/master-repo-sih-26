package nisargpatel.deadreckoning.util

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import java.io.File

private const val TAG = "NavigationMapHolder"
private const val APP_USER_AGENT = "DeadReckoningPro/1.0 (Android; nisargpatel.deadreckoning)"

/**
 * Singleton holder for the primary navigation MapView.
 * Prevents MapView destruction, relocation jumps, and tile re-downloading
 * when navigating between Compose tabs.
 */
object NavigationMapHolder {

    @Volatile
    private var mapViewInstance: MapView? = null
    var hasCenteredOnUser: Boolean = false

    fun getOrCreateMapView(context: Context): MapView {
        val existing = mapViewInstance
        if (existing != null) {
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }

        synchronized(this) {
            mapViewInstance?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                return it
            }

            // Configure disk & memory cache
            val config = Configuration.getInstance()
            config.userAgentValue = APP_USER_AGENT
            val basePath = File(context.cacheDir, "osmdroid").apply { mkdirs() }
            val tileCache = File(basePath, "tiles").apply { mkdirs() }
            config.osmdroidBasePath = basePath
            config.osmdroidTileCache = tileCache
            config.tileDownloadThreads = 8
            config.cacheMapTileCount = 200.toShort()
            config.tileDownloadMaxQueueSize = 150
            config.expirationExtendedDuration = 1000L * 60 * 60 * 24 * 60
            config.tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
            config.tileFileSystemCacheTrimBytes = 250L * 1024 * 1024

            val map = MapView(context.applicationContext).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                setDestroyMode(false)
                isTilesScaledToDpi = true
                isHorizontalMapRepetitionEnabled = false
                isVerticalMapRepetitionEnabled = false
                controller.setZoom(17.0)

                val rotationOverlay = RotationGestureOverlay(context.applicationContext, this)
                rotationOverlay.isEnabled = true
                overlays.add(rotationOverlay)

                try {
                    IndiaBoundaryOverlayHelper.applyOfficialBoundary(context.applicationContext, this)
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying boundary overlay", e)
                }
            }

            mapViewInstance = map
            return map
        }
    }

    fun onResume() {
        mapViewInstance?.onResume()
    }

    fun onPause() {
        mapViewInstance?.onPause()
    }

    fun resetCameraCentering() {
        hasCenteredOnUser = false
    }
}

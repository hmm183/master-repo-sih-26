package nisargpatel.deadreckoning.activity

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import nisargpatel.deadreckoning.ui.navigation.IDRAppShell
import org.osmdroid.config.Configuration
import java.io.File

class MainContainerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize OSMDroid cache with persistent disk storage
        val config = Configuration.getInstance()
        config.load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        val basePath = File(cacheDir, "osmdroid").apply { mkdirs() }
        val tileCache = File(basePath, "tiles").apply { mkdirs() }
        config.osmdroidBasePath = basePath
        config.osmdroidTileCache = tileCache
        config.userAgentValue = "DeadReckoningPro/1.0 (Android; nisargpatel.deadreckoning)"
        config.tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
        config.tileFileSystemCacheTrimBytes = 250L * 1024 * 1024
        config.cacheMapTileCount = 200.toShort()
        config.tileDownloadThreads = 8
        config.tileDownloadMaxQueueSize = 150
        config.expirationExtendedDuration = 1000L * 60 * 60 * 24 * 60

        setContent {
            IDRAppShell()
        }
    }
}

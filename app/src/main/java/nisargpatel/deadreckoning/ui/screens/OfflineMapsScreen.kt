package nisargpatel.deadreckoning.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nisargpatel.deadreckoning.data.OfflineMapCache
import nisargpatel.deadreckoning.data.OfflineRoadNetwork
import nisargpatel.deadreckoning.ui.theme.AutomotiveCardBg
import nisargpatel.deadreckoning.ui.theme.AutomotiveCardBorder
import nisargpatel.deadreckoning.ui.theme.AutomotiveDarkBg
import nisargpatel.deadreckoning.ui.theme.ErrorRed
import nisargpatel.deadreckoning.ui.theme.PrimaryBlue
import nisargpatel.deadreckoning.ui.theme.SuccessGreen
import nisargpatel.deadreckoning.ui.theme.TextPrimary
import nisargpatel.deadreckoning.ui.theme.TextSecondary
import org.osmdroid.util.GeoPoint
import kotlinx.coroutines.launch

@Composable
fun OfflineMapsScreen(currentPosition: GeoPoint?) {
    val context = LocalContext.current
    val cache = remember { OfflineMapCache(context) }
    val roadNetwork = remember { OfflineRoadNetwork.get(context) }
    val state by cache.state.collectAsState()
    val roadState by roadNetwork.state.collectAsState()
    val scope = rememberCoroutineScope()
    val pbfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { roadNetwork.importPbf(uri, "regional-map.osm.pbf") }
    }
    val hasLocation = currentPosition != null && (currentPosition.latitude != 0.0 || currentPosition.longitude != 0.0)

    Column(
        modifier = Modifier.fillMaxSize().background(AutomotiveDarkBg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Map, contentDescription = null, tint = PrimaryBlue)
            Spacer(Modifier.padding(5.dp))
            Column {
                Text("OFFLINE MAP CACHE", color = TextPrimary, fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text("Persistent OpenStreetMap tiles for GNSS-denied navigation", color = TextSecondary, fontSize = 12.sp)
            }
        }

        Surface(color = AutomotiveCardBg, border = androidx.compose.foundation.BorderStroke(1.dp, AutomotiveCardBorder)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CACHE STATUS", color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(formatBytes(state.cachedBytes), color = TextPrimary, fontWeight = FontWeight.Black, fontSize = 28.sp)
                Text("${state.message}${if (state.totalTiles > 0) " (${state.downloadedTiles}/${state.totalTiles} tiles)" else ""}", color = TextSecondary, fontSize = 13.sp)
                if (state.isDownloading) CircularProgressIndicator(color = PrimaryBlue)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { currentPosition?.let { position -> cache.cacheAround(position, 1_500.0) } },
                enabled = hasLocation && !state.isDownloading,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Icon(Icons.Default.DownloadForOffline, contentDescription = null)
                Spacer(Modifier.padding(3.dp))
                Text("LOCAL (1.5 KM)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            Button(
                onClick = { currentPosition?.let { position -> cache.cacheRegionalArea(position, 15.0) } },
                enabled = hasLocation && !state.isDownloading,
                modifier = Modifier.weight(1.3f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
            ) {
                Icon(Icons.Default.DownloadForOffline, contentDescription = null)
                Spacer(Modifier.padding(3.dp))
                Text("WHOLE REGION (15 KM)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        if (!hasLocation) Text("Start navigation and wait for a GNSS fix before caching an area.", color = TextSecondary, fontSize = 12.sp)

        Surface(color = AutomotiveCardBg, border = androidx.compose.foundation.BorderStroke(1.dp, AutomotiveCardBorder)) {
            Column(Modifier.padding(16.dp)) {
                Text("REGIONAL ROAD PACKAGES", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("${roadState.segmentCount} road segments", color = TextPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text("${roadState.message}${if (roadState.restrictionCount > 0) " | ${roadState.restrictionCount} turn restrictions" else ""}", color = TextSecondary, fontSize = 12.sp)
                if (roadState.packages.isNotEmpty()) Text(roadState.packages.last().displayName, color = TextSecondary, fontSize = 12.sp)
                if (roadState.isDownloading) CircularProgressIndicator(color = SuccessGreen, modifier = Modifier.padding(top = 8.dp))
            }
        }
        OutlinedButton(
            onClick = { pbfPicker.launch(arrayOf("application/octet-stream", "application/x-protobuf", "*/*")) },
            enabled = !roadState.isDownloading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.DownloadForOffline, contentDescription = null, tint = SuccessGreen)
            Spacer(Modifier.padding(4.dp))
            Text("IMPORT REGIONAL .OSM.PBF", color = SuccessGreen, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = { cache.clearCache(); roadNetwork.clear() },
            enabled = (state.cachedBytes > 0 || roadState.segmentCount > 0) && !state.isDownloading && !roadState.isDownloading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = ErrorRed)
            Spacer(Modifier.padding(4.dp))
            Text("CLEAR OFFLINE CACHE", color = ErrorRed, fontWeight = FontWeight.Bold)
        }
        Text("Import a regional .osm.pbf package to build a persistent local graph. It preserves drivable-road access, one-way roads, roundabouts, speed tags, lane tags, and node-based turn restrictions.", color = TextSecondary, fontSize = 12.sp)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format("%.2f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

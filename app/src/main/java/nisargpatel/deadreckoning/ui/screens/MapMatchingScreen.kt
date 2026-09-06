package nisargpatel.deadreckoning.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import nisargpatel.deadreckoning.ui.components.ConfidenceIndicator
import nisargpatel.deadreckoning.ui.theme.*
import nisargpatel.deadreckoning.ui.viewmodel.NavigationViewModel
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private fun createDotMarkerDrawable(colorInt: Int, size: Int = 40): Drawable {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val fillPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = colorInt
        style = AndroidPaint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, fillPaint)
    val strokePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = AndroidPaint.Style.STROKE
        strokeWidth = 3f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, strokePaint)
    return BitmapDrawable(null, bitmap)
}

@Composable
fun MapMatchingScreen(
    viewModel: NavigationViewModel
) {
    val matchingState by viewModel.mapMatchingState.collectAsState()
    val rawMarkerIcon = remember { createDotMarkerDrawable(AndroidColor.parseColor("#F59E0B"), 40) } // Amber/Orange
    val snapMarkerIcon = remember { createDotMarkerDrawable(AndroidColor.parseColor("#10B981"), 44) } // Emerald Green
    val candidateIcon = remember { createDotMarkerDrawable(AndroidColor.parseColor("#3B82F6"), 34) } // Electric Blue

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AutomotiveDarkBg)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = Icons.Default.AltRoute, contentDescription = "Map Matching", tint = PrimaryBlue, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(text = "MAP MATCHING", color = PrimaryBlue, fontWeight = FontWeight.Black, fontSize = 16.sp, letterSpacing = 0.5.sp)
                    Text(text = "OSM Topology & HMM Engine", color = TextSecondary, fontSize = 11.sp)
                }
            }
            ConfidenceIndicator(percentage = matchingState.matchConfidencePercentage, label = "")
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── Visual Map with Real-Time Candidates & Probabilities ────────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AutomotiveCardBorder),
            shadowElevation = 6.dp
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            isTilesScaledToDpi = true
                            controller.setZoom(17.5)
                        }
                    },
                    update = { map ->
                        map.overlays.clear()

                        val rawLat = matchingState.rawPositionLat
                        val rawLon = matchingState.rawPositionLon
                        val snapLat = matchingState.matchedPositionLat
                        val snapLon = matchingState.matchedPositionLon

                        val hasRaw = rawLat != 0.0 && rawLon != 0.0
                        val hasSnap = snapLat != 0.0 && snapLon != 0.0

                        if (hasRaw && hasSnap) {
                            val rawPoint = GeoPoint(rawLat, rawLon)
                            val snapPoint = GeoPoint(snapLat, snapLon)

                            // 1. Dotted Projection Line from Raw DR to Snapped Road
                            val connector = Polyline().apply {
                                outlinePaint.color = AndroidColor.parseColor("#F59E0B")
                                outlinePaint.strokeWidth = 4f
                                setPoints(listOf(rawPoint, snapPoint))
                            }
                            map.overlays.add(connector)

                            // 2. Snapped Position Marker (Green)
                            val snapMarker = Marker(map).apply {
                                position = snapPoint
                                icon = snapMarkerIcon
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                title = "Matched: ${matchingState.selectedRoadName}"
                            }
                            map.overlays.add(snapMarker)

                            // 3. Raw DR Position Marker (Orange)
                            val rawMarker = Marker(map).apply {
                                position = rawPoint
                                icon = rawMarkerIcon
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                title = "Raw DR Estimate"
                            }
                            map.overlays.add(rawMarker)

                            map.controller.setCenter(snapPoint)
                        } else if (hasSnap) {
                            val snapPoint = GeoPoint(snapLat, snapLon)
                            val snapMarker = Marker(map).apply {
                                position = snapPoint
                                icon = snapMarkerIcon
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                title = "Matched: ${matchingState.selectedRoadName}"
                            }
                            map.overlays.add(snapMarker)
                            map.controller.setCenter(snapPoint)
                        }

                        // 4. Candidate Road Markers with Probability Labels
                        matchingState.candidateRoads.forEach { cand ->
                            if (cand.latitude != 0.0 && cand.longitude != 0.0) {
                                val candMarker = Marker(map).apply {
                                    position = GeoPoint(cand.latitude, cand.longitude)
                                    icon = candidateIcon
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                    title = "${cand.roadName} (${cand.probabilityPercentage}% Prob)"
                                }
                                map.overlays.add(candMarker)
                            }
                        }

                        map.invalidate()
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Floating Map Legend (Top Left)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AutomotiveDarkBg.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, AutomotiveCardBorder),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).background(Color(0xFFF59E0B), CircleShape))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Raw DR Point", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).background(Color(0xFF10B981), CircleShape))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Road Snap Point", color = Color(0xFF10B981), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Selected Matched Road Banner
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = AutomotiveCardBg,
            border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.6f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "CURRENT MATCHED ROAD", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(text = matchingState.selectedRoadName, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "Distance from Road Axis", color = TextSecondary, fontSize = 12.sp)
                    Text(text = String.format(java.util.Locale.US, "%.1f m", matchingState.distanceFromRoadMeters), color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Candidate Roads & Probabilities List
        Text(text = "CANDIDATE ROAD PROBABILITIES", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(8.dp))

        matchingState.candidateRoads.forEach { candidate ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = AutomotiveCardBg,
                border = BorderStroke(1.dp, AutomotiveCardBorder)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = candidate.roadName, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            text = if (candidate.bearingDegrees != 0.0)
                                "Bearing: ${candidate.bearingDegrees.toInt()}° • Candidate Weight"
                            else
                                "Topological Candidate",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = (if (candidate.probabilityPercentage > 50) SuccessGreen else PrimaryBlue).copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "${candidate.probabilityPercentage}% Prob",
                            color = if (candidate.probabilityPercentage > 50) SuccessGreen else PrimaryBlue,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Raw DR vs Matched Coordinates Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = AutomotiveCardBg,
            border = BorderStroke(1.dp, AutomotiveCardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(text = "COORDINATE SNAP TRANSFORMATION", color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                val rawLatStr = if (matchingState.rawPositionLat != 0.0) String.format(java.util.Locale.US, "%.6f", matchingState.rawPositionLat) else "Waiting..."
                val rawLonStr = if (matchingState.rawPositionLon != 0.0) String.format(java.util.Locale.US, "%.6f", matchingState.rawPositionLon) else "Waiting..."
                val snapLatStr = if (matchingState.matchedPositionLat != 0.0) String.format(java.util.Locale.US, "%.6f", matchingState.matchedPositionLat) else "Waiting..."
                val snapLonStr = if (matchingState.matchedPositionLon != 0.0) String.format(java.util.Locale.US, "%.6f", matchingState.matchedPositionLon) else "Waiting..."
                Text(text = "Raw DR: $rawLatStr, $rawLonStr", color = TextSecondary, fontSize = 12.sp)
                Text(text = "Snapped: $snapLatStr, $snapLonStr", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

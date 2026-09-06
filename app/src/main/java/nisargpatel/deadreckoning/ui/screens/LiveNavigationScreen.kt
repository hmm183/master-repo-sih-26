package nisargpatel.deadreckoning.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nisargpatel.deadreckoning.domain.model.NavigationMode
import nisargpatel.deadreckoning.domain.state.NavigationEvent
import nisargpatel.deadreckoning.ui.components.UberVehicleMarker
import nisargpatel.deadreckoning.ui.theme.*
import nisargpatel.deadreckoning.ui.viewmodel.NavigationViewModel
import nisargpatel.deadreckoning.util.NavigationMapHolder
import nisargpatel.deadreckoning.util.PlaceSearchHelper
import nisargpatel.deadreckoning.util.PlaceSuggestion
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private const val TAG = "LiveNavMap"

private var destinationPinDrawableCache: Drawable? = null

private fun getDestinationPinDrawable(context: android.content.Context): Drawable {
    return destinationPinDrawableCache ?: run {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)

        // Ground shadow
        val shadowPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#40000000")
            style = AndroidPaint.Style.FILL
        }
        canvas.drawOval(size * 0.25f, size * 0.82f, size * 0.75f, size * 0.95f, shadowPaint)

        // Pin body (Google Maps Red)
        val pinPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#EA4335")
            style = AndroidPaint.Style.FILL
        }
        val path = AndroidPath().apply {
            moveTo(size * 0.5f, size * 0.88f)
            cubicTo(size * 0.15f, size * 0.55f, size * 0.15f, size * 0.2f, size * 0.5f, size * 0.08f)
            cubicTo(size * 0.85f, size * 0.2f, size * 0.85f, size * 0.55f, size * 0.5f, size * 0.88f)
            close()
        }
        canvas.drawPath(path, pinPaint)

        // White outline border
        val borderPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            style = AndroidPaint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawPath(path, borderPaint)

        // White inner circle
        val circlePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            style = AndroidPaint.Style.FILL
        }
        canvas.drawCircle(size * 0.5f, size * 0.35f, size * 0.15f, circlePaint)

        // Red dot inside circle
        val dotPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#B91C1C")
            style = AndroidPaint.Style.FILL
        }
        canvas.drawCircle(size * 0.5f, size * 0.35f, size * 0.07f, dotPaint)

        BitmapDrawable(context.resources, bitmap).also { destinationPinDrawableCache = it }
    }
}

@Composable
fun LiveNavigationScreen(
    viewModel: NavigationViewModel
) {
    val navState by viewModel.navigationState.collectAsState()
    val gnssState by viewModel.gnssState.collectAsState()
    val sensorState by viewModel.sensorState.collectAsState()
    val routeInfo by viewModel.selectedRoute.collectAsState()
    var potholeAlert by remember { mutableStateOf<String?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var showSearchModal by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val effectiveHeading = when {
        sensorState.vehicleHeadingDegrees != 0f -> ((sensorState.vehicleHeadingDegrees.toDouble() % 360.0 + 360.0) % 360.0)
        sensorState.yawDegrees != 0f -> ((sensorState.yawDegrees.toDouble() % 360.0 + 360.0) % 360.0)
        navState.headingDegrees != 0.0 -> navState.headingDegrees
        gnssState.bearingDegrees != 0.0 -> gnssState.bearingDegrees.toDouble()
        else -> 0.0
    }

    val isJourneyActive = navState.isNavigating || (routeInfo.routePoints.isNotEmpty() && routeInfo.destinationName.isNotBlank() && routeInfo.destinationName != "None")

    LaunchedEffect(viewModel.events) {
        viewModel.events.collect { event ->
            if (event is NavigationEvent.PotholeDetected) {
                potholeAlert = event.severity
                delay(4_000L)
                potholeAlert = null
            }
        }
    }

    // Direct real-time vehicle marker rotation from 100Hz IMU / compass without Compose recomposition lag
    LaunchedEffect(mapViewRef) {
        val map = mapViewRef ?: return@LaunchedEffect
        viewModel.sensorState.collect { sensor ->
            val heading = when {
                sensor.vehicleHeadingDegrees != 0f -> ((sensor.vehicleHeadingDegrees.toDouble() % 360.0 + 360.0) % 360.0)
                sensor.yawDegrees != 0f -> ((sensor.yawDegrees.toDouble() % 360.0 + 360.0) % 360.0)
                else -> null
            }
            if (heading != null) {
                UberVehicleMarker.updateVehicleHeading(map, heading)
            }
        }
    }

    // Bind OSMDroid lifecycle with NavigationMapHolder (never detach/destroy MapView on tab change)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME  -> NavigationMapHolder.onResume()
                Lifecycle.Event.ON_PAUSE   -> NavigationMapHolder.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        NavigationMapHolder.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            NavigationMapHolder.onPause()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFE5E7EB))
    ) {
        // ── Layer 0: Retained OSMDroid map (Instant cache, zero reload) ─────
        AndroidView(
            factory = { ctx ->
                NavigationMapHolder.getOrCreateMapView(ctx).also { map ->
                    mapViewRef = map
                    map.onResume()
                }
            },
            update = { mapView ->
                mapViewRef = mapView

                mapView.overlays.removeAll(
                    mapView.overlays.filterIsInstance<Polyline>()
                        .filter { it.id == "uber_actual_track" }
                )

                val existingCasing = mapView.overlays.filterIsInstance<Polyline>()
                    .firstOrNull { it.id == "uber_target_route_casing" }
                val existingLine = mapView.overlays.filterIsInstance<Polyline>()
                    .firstOrNull { it.id == "uber_target_route" }
                val existingDestMarker = mapView.overlays.filterIsInstance<Marker>()
                    .firstOrNull { it.id == "destination_pin_marker" }

                if (routeInfo.routePoints.isNotEmpty()) {
                    // Google Maps outer route casing
                    val casingPolyline = existingCasing ?: Polyline().also { line ->
                        line.id = "uber_target_route_casing"
                        line.outlinePaint.color = AndroidColor.parseColor("#1D4ED8") // Darker navy blue border
                        line.outlinePaint.strokeWidth = 18.0f
                        line.outlinePaint.strokeCap = AndroidPaint.Cap.ROUND
                        line.outlinePaint.strokeJoin = AndroidPaint.Join.ROUND
                        mapView.overlays.add(0, line)
                    }
                    casingPolyline.setPoints(routeInfo.routePoints)

                    // Google Maps vibrant blue route core
                    val targetPolyline = existingLine ?: Polyline().also { line ->
                        line.id = "uber_target_route"
                        line.outlinePaint.color = AndroidColor.parseColor("#3B82F6") // Vibrant electric blue core
                        line.outlinePaint.strokeWidth = 12.0f
                        line.outlinePaint.strokeCap = AndroidPaint.Cap.ROUND
                        line.outlinePaint.strokeJoin = AndroidPaint.Join.ROUND
                        mapView.overlays.add(line)
                    }
                    targetPolyline.setPoints(routeInfo.routePoints)

                    // Destination Pin Marker
                    if (routeInfo.destinationPoint != null) {
                        val destMarker = existingDestMarker ?: Marker(mapView).also { m ->
                            m.id = "destination_pin_marker"
                            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            m.icon = getDestinationPinDrawable(mapView.context)
                            mapView.overlays.add(m)
                        }
                        destMarker.position = routeInfo.destinationPoint
                        destMarker.title = routeInfo.destinationName
                    }
                } else {
                    if (existingLine != null) mapView.overlays.remove(existingLine)
                    if (existingCasing != null) mapView.overlays.remove(existingCasing)
                    if (existingDestMarker != null) mapView.overlays.remove(existingDestMarker)
                }

                // Initial position center (only once on initial location acquisition, NEVER on tab switch)
                if (!NavigationMapHolder.hasCenteredOnUser && (navState.latitude != 0.0 || navState.longitude != 0.0)) {
                    val currentPos = GeoPoint(navState.latitude, navState.longitude)
                    mapView.controller.setCenter(currentPos)
                    mapView.controller.setZoom(17.0)
                    NavigationMapHolder.hasCenteredOnUser = true
                }

                // Vehicle marker position update (does NOT move map camera)
                if (navState.latitude != 0.0 || navState.longitude != 0.0) {
                    val currentPos = GeoPoint(navState.latitude, navState.longitude)
                    UberVehicleMarker.updateVehicleMarker(
                        mapView = mapView,
                        position = currentPos,
                        headingDegrees = effectiveHeading
                    )
                }
                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── Layer 1: Top Floating Badges & Search Card ──────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 10.dp, start = 14.dp, end = 14.dp)
        ) {
            // Short, compact top status badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Short Left Pill: GNSS + INS • Hybrid Mode
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.94f),
                    shadowElevation = 3.dp,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.8f)),
                    modifier = Modifier.height(34.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Glowing status dot
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .background(
                                    color = if (navState.mode == NavigationMode.AI_DEAD_RECKONING) Color(0xFFF59E0B) else Color(0xFF10B981),
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = when (navState.mode) {
                                NavigationMode.GNSS_INS -> "GNSS + INS"
                                NavigationMode.AI_DEAD_RECKONING -> "AI DEAD RECK"
                                NavigationMode.GNSS_RECOVERY -> "GNSS RECOVERY"
                                else -> "GNSS + INS"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color(0xFF0F172A)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = when (navState.mode) {
                                NavigationMode.GNSS_INS -> "• Hybrid"
                                NavigationMode.AI_DEAD_RECKONING -> "• Outage"
                                NavigationMode.GNSS_RECOVERY -> "• Reacquire"
                                else -> "• Hybrid"
                            },
                            fontWeight = FontWeight.Normal,
                            fontSize = 10.5.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                // Short Right Pill: Confidence 99%
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.94f),
                    shadowElevation = 3.dp,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.8f)),
                    modifier = Modifier.height(34.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SignalBarsIcon(confidence = navState.confidencePercentage)
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = "${navState.confidencePercentage}%",
                            fontSize = 12.5.sp,
                            color = Color(0xFF047857),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Confidence",
                            fontSize = 10.5.sp,
                            color = Color(0xFF64748B),
                            fontWeight = FontWeight.Normal
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Details",
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // When navigating: Turn-by-Turn Maneuver Banner; When idle: Slim Floating Search Pill
            if (isJourneyActive) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF0F172A),
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color(0xFF1E293B), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.TurnRight,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = routeInfo.nextManeuver.ifBlank { "Continue on route" },
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Towards ${routeInfo.destinationName}",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White.copy(alpha = 0.94f),
                    shadowElevation = 4.dp,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.85f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable { showSearchModal = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = Color(0xFF2563EB),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (routeInfo.destinationName.isNotBlank() && routeInfo.destinationName != "None")
                                routeInfo.destinationName
                            else
                                "Where to? Search destination...",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (routeInfo.destinationName.isNotBlank() && routeInfo.destinationName != "None")
                                Color(0xFF0F172A)
                            else
                                Color(0xFF64748B),
                            modifier = Modifier.weight(1f)
                        )
                        val latStr = if (navState.latitude != 0.0) String.format("%.2f", navState.latitude) else "16.52"
                        val lngStr = if (navState.longitude != 0.0) String.format("%.2f", navState.longitude) else "80.52"
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFEFF6FF)
                        ) {
                            Text(
                                text = "$latStr, $lngStr",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2563EB),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            // GNSS Outage Banner (Animated)
            AnimatedVisibility(
                visible = navState.mode == NavigationMode.AI_DEAD_RECKONING || navState.outageDurationSeconds > 0,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFDC2626),
                    shape = RoundedCornerShape(14.dp),
                    shadowElevation = 5.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.GpsOff,
                            contentDescription = "GNSS Outage",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "GNSS SIGNAL LOST",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp
                            )
                            Text(
                                text = "AI Dead Reckoning Active • Outage: ${navState.outageDurationSeconds}s",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // Pothole Alert Toast (Animated)
            AnimatedVisibility(
                visible = potholeAlert != null,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                potholeAlert?.let { alert ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFFEF3C7),
                        shape = RoundedCornerShape(14.dp),
                        shadowElevation = 5.dp,
                        border = BorderStroke(1.dp, Color(0xFFF59E0B))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(Color(0xFFF59E0B).copy(alpha = 0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFD97706),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "POTHOLE DETECTED",
                                    color = Color(0xFF92400E),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp
                                )
                                Text(
                                    text = alert,
                                    color = Color(0xFF78350F),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.5.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Map Floating Quick Action Controls (Right Center/Bottom) ──────
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White.copy(alpha = 0.92f),
            shadowElevation = 5.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.85f)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = if (isJourneyActive) 175.dp else 95.dp)
        ) {
            Column(
                modifier = Modifier.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // Layers button
                IconButton(
                    onClick = {
                        mapViewRef?.let { map ->
                            val current = map.tileProvider.tileSource
                            val next = when (current) {
                                TileSourceFactory.MAPNIK -> TileSourceFactory.OpenTopo
                                TileSourceFactory.OpenTopo -> TileSourceFactory.USGS_SAT
                                else -> TileSourceFactory.MAPNIK
                            }
                            map.setTileSource(next)
                            Toast.makeText(context, "Map: ${next.name()}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = "Map Layers",
                        tint = Color(0xFF1E293B),
                        modifier = Modifier.size(19.dp)
                    )
                }

                HorizontalDivider(modifier = Modifier.width(22.dp), color = Color(0xFFE2E8F0), thickness = 0.8.dp)

                // Zoom In (+)
                IconButton(
                    onClick = { mapViewRef?.controller?.zoomIn() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Zoom In",
                        tint = Color(0xFF1E293B),
                        modifier = Modifier.size(20.dp)
                    )
                }

                HorizontalDivider(modifier = Modifier.width(22.dp), color = Color(0xFFE2E8F0), thickness = 0.8.dp)

                // Zoom Out (-)
                IconButton(
                    onClick = { mapViewRef?.controller?.zoomOut() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Zoom Out",
                        tint = Color(0xFF1E293B),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // ── Map Scale Bar (Bottom Left) ────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, bottom = if (isJourneyActive) 175.dp else 95.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "200 m",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1E293B)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .width(38.dp)
                        .height(3.dp)
                        .background(Color(0xFF1E293B))
                )
            }
        }

        // ── Layer 3: Modern Auto Cockpit Bottom HUD (Collapsible) ──────────
        val isJourneyActive = navState.isNavigating || routeInfo.routePoints.isNotEmpty()
        val deg = ((effectiveHeading % 360 + 360) % 360).toInt()

        if (!isJourneyActive) {
            // Idle Mode: Compact floating glass cockpit bar (85%+ map completely open)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp, start = 14.dp, end = 14.dp)
                    .shadow(6.dp, RoundedCornerShape(26.dp)),
                color = Color.White.copy(alpha = 0.95f),
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.85f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Speed capsule
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(Color(0xFFF1F5F9), RoundedCornerShape(16.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        val displaySpeed = if (navState.speedKmh >= 0.5) navState.speedKmh else 0.0
                        Text(
                            text = String.format("%.1f", displaySpeed),
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            color = Color(0xFF0F172A)
                        )
                        Text(
                            text = " km/h",
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }

                    // Heading capsule
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(Color(0xFFF1F5F9), RoundedCornerShape(16.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Icon(Icons.Default.Explore, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "$deg°",
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            color = Color(0xFF0F172A)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = getCardinalShort(deg),
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }

                    // Recenter icon button
                    IconButton(
                        onClick = {
                            if (navState.latitude != 0.0 || navState.longitude != 0.0) {
                                mapViewRef?.let { map ->
                                    map.controller.animateTo(GeoPoint(navState.latitude, navState.longitude))
                                    map.controller.setZoom(17.0)
                                }
                            } else {
                                Toast.makeText(context, "Acquiring live location...", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color(0xFFEFF6FF), CircleShape)
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Recenter", tint = Color(0xFF2563EB), modifier = Modifier.size(18.dp))
                    }

                    // Primary Plan Route button
                    Button(
                        onClick = { showSearchModal = true },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2563EB),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.NearMe, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Plan", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        } else {
            // Active Navigation Mode: Compact cockpit dashboard
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp, start = 14.dp, end = 14.dp)
                    .shadow(8.dp, RoundedCornerShape(24.dp)),
                color = Color.White.copy(alpha = 0.96f),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.85f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Row 1: Key Trip Telemetry
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("ETA", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                            Text(
                                text = "${routeInfo.estimatedTimeMinutes} min",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF0F172A)
                            )
                        }
                        Column {
                            Text("REMAINING", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                            Text(
                                text = String.format("%.1f km", routeInfo.totalDistanceKm),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF0F172A)
                            )
                        }
                        Column {
                            Text("SPEED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                            val displaySpeed = if (navState.speedKmh >= 0.5) navState.speedKmh else 0.0
                            Text(
                                text = "${String.format("%.1f", displaySpeed)} km/h",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF2563EB)
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("HEADING", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                            Text(
                                text = "$deg° ${getCardinalShort(deg)}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF0F172A)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Row 2: Recenter + End Journey
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (navState.latitude != 0.0 || navState.longitude != 0.0) {
                                    mapViewRef?.let { map ->
                                        map.controller.animateTo(GeoPoint(navState.latitude, navState.longitude))
                                        map.controller.setZoom(17.0)
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(22.dp),
                            border = BorderStroke(1.dp, Color(0xFFCBD5E1))
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Recenter", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        Button(
                            onClick = {
                                viewModel.stopNavigation()
                                Toast.makeText(context, "Journey saved to Trips!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(22.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("End Journey", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    // Destination Search Modal Sheet
    if (showSearchModal) {
        DestinationSearchModal(
            navState = navState,
            onDismiss = { showSearchModal = false },
            onSelect = { name, geoPoint ->
                viewModel.selectDestination(name, geoPoint)
                showSearchModal = false
                mapViewRef?.let { map ->
                    val curLat = if (navState.latitude != 0.0) navState.latitude else 16.5216
                    val curLon = if (navState.longitude != 0.0) navState.longitude else 80.5216
                    val userPoint = GeoPoint(curLat, curLon)

                    val maxLat = maxOf(userPoint.latitude, geoPoint.latitude)
                    val minLat = minOf(userPoint.latitude, geoPoint.latitude)
                    val maxLon = maxOf(userPoint.longitude, geoPoint.longitude)
                    val minLon = minOf(userPoint.longitude, geoPoint.longitude)
                    val padLat = maxOf(0.005, (maxLat - minLat) * 0.3)
                    val padLon = maxOf(0.005, (maxLon - minLon) * 0.3)
                    val bBox = BoundingBox(maxLat + padLat, maxLon + padLon, minLat - padLat, minLon - padLon)

                    val distKm = userPoint.distanceToAsDouble(geoPoint) / 1000.0
                    if (distKm <= 80.0) {
                        try {
                            map.zoomToBoundingBox(bBox, true, 80)
                        } catch (e: Exception) {
                            map.controller.animateTo(userPoint)
                            map.controller.setZoom(16.5)
                        }
                    } else {
                        map.controller.animateTo(userPoint)
                        map.controller.setZoom(16.5)
                    }
                }
                Toast.makeText(context, "Navigating to $name", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

// ── 4-Bar Signal Indicator (Compact) ─────────────────────────────────────────
@Composable
private fun SignalBarsIcon(confidence: Int) {
    val activeBars = when {
        confidence >= 85 -> 4
        confidence >= 60 -> 3
        confidence >= 35 -> 2
        else -> 1
    }

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(13.dp)
    ) {
        val heights = listOf(5.dp, 7.5.dp, 10.dp, 13.dp)
        heights.forEachIndexed { index, height ->
            val isActive = index < activeBars
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height)
                    .background(
                        color = if (isActive) Color(0xFF10B981) else Color(0xFFCBD5E1),
                        shape = RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

// ── Cardinal direction helper ───────────────────────────────────────────────
private fun getCardinalShort(deg: Int): String {
    return when {
        deg >= 337.5 || deg < 22.5 -> "N"
        deg < 67.5 -> "NE"
        deg < 112.5 -> "E"
        deg < 157.5 -> "SE"
        deg < 202.5 -> "S"
        deg < 247.5 -> "SW"
        deg < 292.5 -> "W"
        else -> "NW"
    }
}

// ── Destination Search Modal ────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationSearchModal(
    navState: nisargpatel.deadreckoning.domain.state.NavigationState,
    onDismiss: () -> Unit,
    onSelect: (String, GeoPoint) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var destinationName by remember { mutableStateOf("") }
    var coordinates by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    val coordinateParts = coordinates.split(',').map(String::trim)
    val latitude = coordinateParts.getOrNull(0)?.toDoubleOrNull()
    val longitude = coordinateParts.getOrNull(1)?.toDoubleOrNull()
    val hasValidCoord = latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0

    val currentLat = if (navState.latitude != 0.0) navState.latitude else 16.5216
    val currentLon = if (navState.longitude != 0.0) navState.longitude else 80.5216

    // Debounced search when user types destination
    LaunchedEffect(destinationName) {
        val q = destinationName.trim()
        if (q.length < 2) {
            suggestions = emptyList()
            isSearching = false
            return@LaunchedEffect
        }
        delay(350)
        isSearching = true
        val results = PlaceSearchHelper.searchPlaces(q, currentLat, currentLon, context)
        suggestions = results
        isSearching = false
    }

    val quickPresets = listOf(
        "Airport",
        "Benz Circle",
        "Railway Station",
        "City Center"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White.copy(alpha = 0.98f),
        contentColor = Color(0xFF0F172A)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "WHERE TO?",
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    letterSpacing = 0.5.sp
                )
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF3B82F6)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick preset pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickPresets.forEach { presetName ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFEFF6FF),
                        border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                destinationName = presetName
                            }
                    ) {
                        Text(
                            text = presetName,
                            color = Color(0xFF1D4ED8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Destination Name Input Field
            OutlinedTextField(
                value = destinationName,
                onValueChange = { destinationName = it },
                label = { Text("Search address, place, or city") },
                placeholder = { Text("e.g. Vijayawada Airport, Benz Circle", color = Color(0xFF94A3B8)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF2563EB)) },
                trailingIcon = {
                    if (destinationName.isNotBlank()) {
                        IconButton(onClick = { destinationName = ""; suggestions = emptyList() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color(0xFF64748B))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF2563EB),
                    unfocusedBorderColor = Color(0xFFE2E8F0),
                    focusedContainerColor = Color(0xFFF8FAFC),
                    unfocusedContainerColor = Color(0xFFF8FAFC)
                ),
                shape = RoundedCornerShape(14.dp),
                singleLine = true
            )

            // Autocomplete suggestions list
            if (suggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 210.dp)
                ) {
                    items(suggestions) { suggestion ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, Color(0xFFF1F5F9)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable {
                                    val finalLabel = suggestion.title
                                    onSelect(finalLabel, suggestion.point)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color(0xFFEFF6FF), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Place,
                                        contentDescription = null,
                                        tint = Color(0xFF2563EB),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = suggestion.title,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0F172A),
                                        maxLines = 1
                                    )
                                    Text(
                                        text = suggestion.subtitle,
                                        fontSize = 11.sp,
                                        color = Color(0xFF64748B),
                                        maxLines = 1
                                    )
                                }
                                if (suggestion.distanceKm != null) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFFF1F5F9)
                                    ) {
                                        Text(
                                            text = "${suggestion.distanceKm} km",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF475569),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Coordinates (Optional) field
            OutlinedTextField(
                value = coordinates,
                onValueChange = { coordinates = it },
                label = { Text("Coordinates (Optional)") },
                placeholder = { Text("e.g. 16.5310, 80.5350", color = Color(0xFF94A3B8)) },
                leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color(0xFF64748B)) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF2563EB),
                    unfocusedBorderColor = Color(0xFFE2E8F0),
                    focusedContainerColor = Color(0xFFF8FAFC),
                    unfocusedContainerColor = Color(0xFFF8FAFC)
                ),
                shape = RoundedCornerShape(14.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(14.dp))

            val canStart = hasValidCoord || destinationName.isNotBlank()
            Button(
                onClick = {
                    if (hasValidCoord) {
                        val pt = GeoPoint(latitude!!, longitude!!)
                        val label = destinationName.ifBlank { String.format("%.4f, %.4f", pt.latitude, pt.longitude) }
                        onSelect(label, pt)
                    } else if (suggestions.isNotEmpty()) {
                        val top = suggestions.first()
                        onSelect(top.title, top.point)
                    } else if (destinationName.isNotBlank()) {
                        coroutineScope.launch {
                            isSearching = true
                            val results = PlaceSearchHelper.searchPlaces(destinationName.trim(), currentLat, currentLon, context)
                            isSearching = false
                            if (results.isNotEmpty()) {
                                val top = results.first()
                                onSelect(top.title, top.point)
                            } else {
                                Toast.makeText(context, "Location not found. Please check spelling or enter coordinates.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                enabled = canStart && !isSearching,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2563EB),
                    disabledContainerColor = Color(0xFFCBD5E1),
                    contentColor = Color.White
                )
            ) {
                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("FINDING LOCATION...", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                } else {
                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("START NAVIGATION", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

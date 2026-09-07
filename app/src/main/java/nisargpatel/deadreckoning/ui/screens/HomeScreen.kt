package nisargpatel.deadreckoning.ui.screens

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import nisargpatel.deadreckoning.domain.model.NavigationMode
import nisargpatel.deadreckoning.ui.viewmodel.HomeViewModel
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onStartNavClicked: () -> Unit,
    onSettingsClicked: () -> Unit = {},
    onModeClicked: () -> Unit = {}
) {
    val navState by viewModel.navigationState.collectAsState()
    val gnssState by viewModel.gnssState.collectAsState()
    val aiState by viewModel.aiState.collectAsState()
    val sensorState by viewModel.sensorState.collectAsState()

    val currentTime = remember {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    // Dynamic real-time heading from live sensor yaw, vehicle heading, or active navigation state
    val currentHeading = when {
        navState.isNavigating && navState.headingDegrees != 0.0 -> navState.headingDegrees
        sensorState.vehicleHeadingDegrees != 0f -> ((sensorState.vehicleHeadingDegrees.toDouble() % 360.0 + 360.0) % 360.0)
        sensorState.yawDegrees != 0f -> ((sensorState.yawDegrees.toDouble() % 360.0 + 360.0) % 360.0)
        navState.headingDegrees != 0.0 -> navState.headingDegrees
        else -> 0.0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF1F5F9))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Top Bar Header (Settings button removed for perfect vertical alignment)
        val effectiveMode = if (!gnssState.isAvailable) NavigationMode.AI_DEAD_RECKONING else navState.mode
        HomeTopHeader(
            mode = effectiveMode,
            onModeClicked = onModeClicked
        )

        // 2. Speed & Heading Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SpeedCard(
                speedKmh = navState.speedKmh,
                modifier = Modifier.weight(1f)
            )
            HeadingCard(
                headingDegrees = currentHeading,
                modifier = Modifier.weight(1f)
            )
        }

        // 3. Plan Route Banner Card (Contained dark section & dynamic live OSM minimap)
        PlanRouteCard(
            latitude = if (navState.latitude != 0.0) navState.latitude else gnssState.latitude,
            longitude = if (navState.longitude != 0.0) navState.longitude else gnssState.longitude,
            onPlanRouteClicked = onStartNavClicked
        )

        // 4. System Readiness Card
        SystemReadinessCard(
            gnssOnline = gnssState.isAvailable,
            imuOnline = sensorState.isAccelAvailable && sensorState.isGyroAvailable,
            v8Online = aiState.isModelLoaded,
            accuracyMeters = navState.accuracyMeters,
            modelVersion = if (aiState.isModelLoaded && aiState.modelVersion.isNotBlank()) aiState.modelVersion else "Unavailable",
            mountStabilityPercentage = if (sensorState.mountStabilityPercentage > 0) sensorState.mountStabilityPercentage else 97,
            lastUpdated = currentTime
        )

        // 5. Vehicle Signal Card
        VehicleSignalCard(
            predictedSpeedKmh = aiState.predictedSpeedKmh,
            motionClassification = if (aiState.motionClassification.isNotBlank() && aiState.motionClassification != "UNKNOWN") aiState.motionClassification else "Stationary"
        )

        Spacer(modifier = Modifier.height(14.dp))
    }
}

// -------------------------------------------------------------------------------------
// 1. TOP HEADER COMPONENT (Without Settings button -> Perfectly Centered & Aligned)
// -------------------------------------------------------------------------------------
@Composable
private fun HomeTopHeader(
    mode: NavigationMode,
    onModeClicked: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left branding
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Stylized 'N' Ribbon Brand Mark
            NavCoreBrandLogo()

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Text(
                    text = "SIH 2026",
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "SIH NavCore",
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp,
                    lineHeight = 25.sp
                )
                Text(
                    text = "GNSS, IMU, road graph, and outage recovery",
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 2
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Right Mode Badge Pill (Vertically centered with header)
        val (badgeText, subText, dotColor, badgeBg, badgeBorder) = when (mode) {
            NavigationMode.GNSS_INS -> Tuple5("GNSS + INS", "Hybrid Mode", Color(0xFF10B981), Color(0xFFECFDF5), Color(0xFFA7F3D0))
            NavigationMode.AI_DEAD_RECKONING -> Tuple5("AI DR", "Inertial Mode", Color(0xFFF59E0B), Color(0xFFFFFBEB), Color(0xFFFDE68A))
            NavigationMode.GNSS_RECOVERY -> Tuple5("RECOVERY", "Syncing", Color(0xFF3B82F6), Color(0xFFEFF6FF), Color(0xFFBFDBFE))
            NavigationMode.OFFLINE -> Tuple5("OFFLINE", "No Signal", Color(0xFF94A3B8), Color(0xFFF8FAFC), Color(0xFFE2E8F0))
            NavigationMode.CALIBRATION -> Tuple5("CALIBRATING", "Sensors", Color(0xFF8B5CF6), Color(0xFFF5F3FF), Color(0xFFDDD6FE))
            NavigationMode.ERROR -> Tuple5("ERROR", "Check Sensors", Color(0xFFEF4444), Color(0xFFFEF2F2), Color(0xFFFECACA))
        }

        Surface(
            shape = CircleShape,
            color = badgeBg,
            border = BorderStroke(1.dp, badgeBorder),
            modifier = Modifier
                .clickable { onModeClicked() }
                .shadow(1.dp, CircleShape)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(dotColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = badgeText,
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = subText,
                        color = Color(0xFF64748B),
                        fontWeight = FontWeight.Medium,
                        fontSize = 9.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = Color(0xFF64748B),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

@Composable
private fun NavCoreBrandLogo() {
    Canvas(modifier = Modifier.size(36.dp)) {
        val w = size.width
        val h = size.height

        // Left stroke of N (indigo-blue)
        val path1 = Path().apply {
            moveTo(w * 0.22f, h * 0.88f)
            cubicTo(w * 0.12f, h * 0.70f, w * 0.15f, h * 0.35f, w * 0.28f, h * 0.18f)
            cubicTo(w * 0.34f, h * 0.12f, w * 0.44f, h * 0.14f, w * 0.46f, h * 0.24f)
            lineTo(w * 0.36f, h * 0.84f)
            close()
        }
        drawPath(
            path = path1,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF38BDF8), Color(0xFF1E3A8A)),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )
        )

        // Diagonal fold stroke of N
        val path2 = Path().apply {
            moveTo(w * 0.34f, h * 0.22f)
            lineTo(w * 0.75f, h * 0.82f)
            cubicTo(w * 0.85f, h * 0.78f, w * 0.90f, h * 0.65f, w * 0.88f, h * 0.48f)
            lineTo(w * 0.52f, h * 0.16f)
            close()
        }
        drawPath(
            path = path2,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF2563EB), Color(0xFF1E1B4B)),
                start = Offset(w * 0.2f, 0f),
                end = Offset(w * 0.8f, h)
            )
        )

        // Right vertical fold
        val path3 = Path().apply {
            moveTo(w * 0.62f, h * 0.84f)
            lineTo(w * 0.78f, h * 0.18f)
            cubicTo(w * 0.85f, h * 0.15f, w * 0.92f, h * 0.25f, w * 0.90f, h * 0.40f)
            lineTo(w * 0.76f, h * 0.88f)
            close()
        }
        drawPath(
            path = path3,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF1D4ED8), Color(0xFF312E81)),
                start = Offset(w * 0.5f, 0f),
                end = Offset(w, h)
            )
        )
    }
}

// -------------------------------------------------------------------------------------
// 2. SPEED CARD & HEADING CARD
// -------------------------------------------------------------------------------------
@Composable
private fun SpeedCard(
    speedKmh: Double,
    modifier: Modifier = Modifier
) {
    val speedValueStr = if (speedKmh >= 0.5) String.format(Locale.US, "%.1f", speedKmh) else "0.0"
    val speedCategory = when {
        speedKmh < 0.5 -> "Stationary"
        speedKmh < 5.0 -> "Very low speed"
        speedKmh < 30.0 -> "City speed"
        speedKmh < 60.0 -> "Moderate speed"
        else -> "Cruising speed"
    }

    Surface(
        modifier = modifier
            .height(160.dp)
            .shadow(4.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF111928),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Subtle topo contour background
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 1.2f)
                val lineColor = Color(0xFF1E293B).copy(alpha = 0.5f)
                drawCircle(
                    color = lineColor,
                    radius = size.width * 0.85f,
                    center = Offset(size.width * 0.9f, size.height * 0.15f),
                    style = stroke
                )
                drawCircle(
                    color = lineColor,
                    radius = size.width * 1.15f,
                    center = Offset(size.width * 0.9f, size.height * 0.15f),
                    style = stroke
                )
                drawCircle(
                    color = lineColor,
                    radius = size.width * 1.45f,
                    center = Offset(size.width * 0.9f, size.height * 0.15f),
                    style = stroke
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Speed",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "SPEED",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                // Speed display
                Column {
                    Text(
                        text = speedValueStr,
                        color = Color.White,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 40.sp
                    )
                    Text(
                        text = "km/h",
                        color = Color(0xFFE2E8F0),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Status pill
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF1E293B),
                    border = BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFF10B981), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = speedCategory,
                            color = Color(0xFFE2E8F0),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeadingCard(
    headingDegrees: Double,
    modifier: Modifier = Modifier
) {
    val degInt = ((headingDegrees % 360 + 360) % 360).toInt()
    val cardinalText = when {
        degInt >= 337.5 || degInt < 22.5 -> "Facing North"
        degInt < 67.5 -> "Facing North-East"
        degInt < 112.5 -> "Facing East"
        degInt < 157.5 -> "Facing South-East"
        degInt < 202.5 -> "Facing South"
        degInt < 247.5 -> "Facing South-West"
        degInt < 292.5 -> "Facing West"
        else -> "Facing North-West"
    }

    val animatedRotation by animateFloatAsState(
        targetValue = headingDegrees.toFloat(),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "headingRotation"
    )

    Surface(
        modifier = modifier
            .height(160.dp)
            .shadow(3.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left info
            Column(
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(19.dp)
                            .background(Color(0xFFDBEAFE), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NearMe,
                            contentDescription = "Heading",
                            tint = Color(0xFF2563EB),
                            modifier = Modifier.size(11.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "HEADING",
                        color = Color(0xFF64748B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Column {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = "$degInt",
                            color = Color(0xFF0F172A),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            lineHeight = 32.sp
                        )
                        Text(
                            text = "°",
                            color = Color(0xFF0F172A),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Text(
                        text = "Degrees",
                        color = Color(0xFF0F172A),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = cardinalText,
                    color = Color(0xFF64748B),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 12.sp,
                    maxLines = 2,
                    softWrap = true
                )
            }

            Spacer(modifier = Modifier.width(2.dp))

            // Right Compass Dial Visual
            CompassDial(
                rotationAngle = animatedRotation,
                modifier = Modifier.size(50.dp)
            )
        }
    }
}

@Composable
private fun CompassDial(
    rotationAngle: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Outer dotted ring
        drawCircle(
            color = Color(0xFFE2E8F0),
            radius = r - 8.dp.toPx(),
            center = center,
            style = Stroke(
                width = 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f), 0f)
            )
        )

        // Cardinal Tick Marks
        val tickCount = 24
        for (i in 0 until tickCount) {
            val angle = i * (360f / tickCount)
            val rad = Math.toRadians(angle.toDouble())
            val isMajor = i % 6 == 0
            val startR = r - (if (isMajor) 13.dp.toPx() else 10.dp.toPx())
            val endR = r - 8.dp.toPx()

            val p1 = Offset(
                center.x + (startR * sin(rad)).toFloat(),
                center.y - (startR * cos(rad)).toFloat()
            )
            val p2 = Offset(
                center.x + (endR * sin(rad)).toFloat(),
                center.y - (endR * cos(rad)).toFloat()
            )
            drawLine(
                color = if (isMajor) Color(0xFF94A3B8) else Color(0xFFCBD5E1),
                start = p1,
                end = p2,
                strokeWidth = if (isMajor) 1.2.dp.toPx() else 0.8.dp.toPx()
            )
        }

        // Draw Cardinal Letters: N, E, S, W
        val textPaint = Paint().apply {
            textSize = 7.5.dp.toPx()
            textAlign = Paint.Align.CENTER
            color = android.graphics.Color.rgb(100, 116, 139)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textDist = r - 3.dp.toPx()
        drawContext.canvas.nativeCanvas.drawText("N", center.x, center.y - textDist + 2.5.dp.toPx(), textPaint)
        drawContext.canvas.nativeCanvas.drawText("S", center.x, center.y + textDist - 1.dp.toPx(), textPaint)
        drawContext.canvas.nativeCanvas.drawText("E", center.x + textDist - 1.dp.toPx(), center.y + 2.5.dp.toPx(), textPaint)
        drawContext.canvas.nativeCanvas.drawText("W", center.x - textDist + 1.dp.toPx(), center.y + 2.5.dp.toPx(), textPaint)

        // Draw compass arrow needle
        rotate(degrees = rotationAngle, pivot = center) {
            val arrowLength = r * 0.55f
            val arrowHalfWidth = r * 0.18f

            // North pointer (Blue gradient)
            val northPath = Path().apply {
                moveTo(center.x, center.y - arrowLength)
                lineTo(center.x + arrowHalfWidth, center.y + arrowLength * 0.25f)
                lineTo(center.x, center.y)
                close()
            }
            drawPath(
                path = northPath,
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF2563EB), Color(0xFF3B82F6)),
                    start = Offset(center.x, center.y - arrowLength),
                    end = Offset(center.x + arrowHalfWidth, center.y)
                )
            )

            // North pointer dark facet
            val northFacetPath = Path().apply {
                moveTo(center.x, center.y - arrowLength)
                lineTo(center.x - arrowHalfWidth, center.y + arrowLength * 0.25f)
                lineTo(center.x, center.y)
                close()
            }
            drawPath(
                path = northFacetPath,
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF1D4ED8), Color(0xFF1E40AF)),
                    start = Offset(center.x, center.y - arrowLength),
                    end = Offset(center.x - arrowHalfWidth, center.y)
                )
            )

            // South tail (Light gray)
            val southPath = Path().apply {
                moveTo(center.x, center.y)
                lineTo(center.x + arrowHalfWidth, center.y + arrowLength * 0.25f)
                lineTo(center.x, center.y + arrowLength * 0.45f)
                close()
            }
            drawPath(
                path = southPath,
                color = Color(0xFFCBD5E1)
            )

            val southFacetPath = Path().apply {
                moveTo(center.x, center.y)
                lineTo(center.x - arrowHalfWidth, center.y + arrowLength * 0.25f)
                lineTo(center.x, center.y + arrowLength * 0.45f)
                close()
            }
            drawPath(
                path = southFacetPath,
                color = Color(0xFF94A3B8)
            )
        }

        // Center pivot dot
        drawCircle(
            color = Color.White,
            radius = 2.5.dp.toPx(),
            center = center
        )
        drawCircle(
            color = Color(0xFF2563EB),
            radius = 1.5.dp.toPx(),
            center = center
        )
    }
}

// -------------------------------------------------------------------------------------
// 3. PLAN ROUTE BANNER CARD (Solid Dark Left Region + Live Dynamic OSM Minimap)
// -------------------------------------------------------------------------------------
@Composable
private fun PlanRouteCard(
    latitude: Double,
    longitude: Double,
    onPlanRouteClicked: () -> Unit
) {
    val latStr = if (latitude != 0.0) String.format(Locale.US, "%.4f", latitude) else "16.5216"
    val lonStr = if (longitude != 0.0) String.format(Locale.US, "%.4f", longitude) else "80.5217"
    val targetLat = if (latitude != 0.0) latitude else 16.5216
    val targetLon = if (longitude != 0.0) longitude else 80.5217

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .shadow(4.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF111928),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 1. Right-side Dynamic OSM Minimap
            Row(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.weight(0.48f))
                Box(
                    modifier = Modifier
                        .weight(0.52f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp))
                ) {
                    LiveOsmMinimap(
                        latitude = targetLat,
                        longitude = targetLon
                    )
                }
            }

            // 2. Seamless dark gradient overlay bridging the left dark panel into the minimap
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.0f to Color(0xFF111928),
                                0.48f to Color(0xFF111928),
                                0.56f to Color(0xFF111928).copy(alpha = 0.85f),
                                0.65f to Color(0xFF111928).copy(alpha = 0.25f),
                                0.75f to Color.Transparent
                            )
                        )
                    )
            )

            // 3. Left content (Completely contained inside the solid dark region)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Column: Icon + Text + Action Button
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 190.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(Color(0xFF1E293B), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AltRoute,
                                contentDescription = "Route",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(9.dp))
                        Column {
                            Text(
                                text = "Plan route",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                text = "Current Location",
                                color = Color(0xFF94A3B8),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                text = "$latStr, $lonStr",
                                color = Color(0xFFE2E8F0),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    // Blue Circular Action Button
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .shadow(4.dp, CircleShape)
                            .background(Color(0xFF3B82F6), CircleShape)
                            .clickable { onPlanRouteClicked() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = "Go",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Right: "View on map" button + Location Callout over Minimap
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // "View on map" pill button
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.95f),
                        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                        modifier = Modifier.clickable { onPlanRouteClicked() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "View on map",
                                color = Color(0xFF1E293B),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = Color(0xFF3B82F6),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }

                    // "You are here" Callout Badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.White,
                        shadowElevation = 3.dp,
                        border = BorderStroke(1.dp, Color(0xFFDBEAFE)),
                        modifier = Modifier.padding(bottom = 6.dp, end = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(Color(0xFF2563EB), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "You are here",
                                color = Color(0xFF2563EB),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveOsmMinimap(
    latitude: Double,
    longitude: Double
) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(false)
                setDestroyMode(false)
                isTilesScaledToDpi = true
                controller.setZoom(16.0)
                controller.setCenter(GeoPoint(latitude, longitude))
                // Disable user map dragging on the card
                setOnTouchListener { _, _ -> false }
            }
        },
        update = { mapView ->
            mapView.controller.setCenter(GeoPoint(latitude, longitude))
        },
        modifier = Modifier.fillMaxSize()
    )
}

// -------------------------------------------------------------------------------------
// 4. SYSTEM READINESS CARD
// -------------------------------------------------------------------------------------
@Composable
private fun SystemReadinessCard(
    gnssOnline: Boolean,
    imuOnline: Boolean,
    v8Online: Boolean,
    accuracyMeters: Double,
    modelVersion: String,
    mountStabilityPercentage: Int,
    lastUpdated: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "System Readiness",
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Last updated $lastUpdated",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // 3 Status Badges Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                StatusBadge(
                    icon = Icons.Default.GpsFixed,
                    title = "GNSS fix",
                    statusText = if (gnssOnline) "Online" else "Offline",
                    isOnline = gnssOnline,
                    accentColor = Color(0xFF10B981),
                    modifier = Modifier.weight(1f)
                )
                StatusBadge(
                    icon = Icons.Default.ShowChart,
                    title = "IMU live",
                    statusText = if (imuOnline) "Online" else "Offline",
                    isOnline = imuOnline,
                    accentColor = Color(0xFF10B981),
                    modifier = Modifier.weight(1f)
                )
                val displayModelName = if (modelVersion.isNotBlank() && modelVersion != "Unavailable") {
                    if (modelVersion.contains("PINO", ignoreCase = true)) "PINO-DR"
                    else if (modelVersion.contains("IDR", ignoreCase = true)) "IDR-V1"
                    else "AI Engine"
                } else {
                    "AI Engine"
                }
                StatusBadge(
                    icon = Icons.Default.Storage,
                    title = if (v8Online) "$displayModelName loaded" else "$displayModelName offline",
                    statusText = if (v8Online) "Online" else "Offline",
                    isOnline = v8Online,
                    accentColor = if (v8Online) Color(0xFF8B5CF6) else Color(0xFFEF4444),
                    modifier = Modifier.weight(1f)
                )
            }

            // Divider
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFFF1F5F9))
            )

            // 3 Metric Items Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricColumn(
                    icon = Icons.Default.TrackChanges,
                    label = "Position accuracy",
                    value = if (accuracyMeters > 0.0) String.format(Locale.US, "%.1f m", accuracyMeters) else "3.6 m",
                    valueColor = Color(0xFF0F172A),
                    modifier = Modifier.weight(1f)
                )
                VerticalSeparator()
                MetricColumn(
                    icon = Icons.Default.ViewInAr,
                    label = "Model build",
                    value = modelVersion,
                    valueColor = Color(0xFF64748B),
                    modifier = Modifier.weight(1f)
                )
                VerticalSeparator()
                MetricColumn(
                    icon = Icons.Default.Leaderboard,
                    label = "Mount stability",
                    value = "$mountStabilityPercentage%",
                    valueColor = if (mountStabilityPercentage >= 75) Color(0xFF10B981) else Color(0xFFF59E0B),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    statusText: String,
    isOnline: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isOnline) Color(0xFFECFDF5) else Color(0xFFFEF2F2)
    val borderColor = if (isOnline) Color(0xFFA7F3D0) else Color(0xFFFECACA)
    val effectiveColor = if (isOnline) accentColor else Color(0xFFEF4444)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = effectiveColor,
                modifier = Modifier.size(19.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Column {
                Text(
                    text = title,
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = statusText,
                    color = effectiveColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun MetricColumn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color(0xFF64748B),
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(
                text = label,
                color = Color(0xFF64748B),
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = value,
                color = valueColor,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun VerticalSeparator() {
    Box(
        modifier = Modifier
            .height(26.dp)
            .width(1.dp)
            .background(Color(0xFFE2E8F0))
    )
}

// -------------------------------------------------------------------------------------
// 5. VEHICLE SIGNAL CARD
// -------------------------------------------------------------------------------------
@Composable
private fun VehicleSignalCard(
    predictedSpeedKmh: Double,
    motionClassification: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            // Header: Car icon + "Vehicle Signal" + Real-time badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = "Vehicle",
                        tint = Color(0xFF0F172A),
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = "Vehicle Signal",
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(Color(0xFF10B981), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Real-time",
                        color = Color(0xFF334155),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            // Two Sub-cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // AI Speed Sub-Card
                SignalSubCard(
                    icon = Icons.Default.TrendingUp,
                    label = "AI speed",
                    value = String.format(Locale.US, "%.1f km/h", predictedSpeedKmh),
                    accentColor = Color(0xFF2563EB),
                    cardBg = Color(0xFFEFF6FF),
                    cardBorder = Color(0xFFDBEAFE),
                    barColor = Color(0xFF93C5FD),
                    modifier = Modifier.weight(1f)
                )

                // Motion Sub-Card
                SignalSubCard(
                    icon = Icons.Default.GraphicEq,
                    label = "Motion",
                    value = motionClassification,
                    accentColor = Color(0xFF16A34A),
                    cardBg = Color(0xFFF0FDF4),
                    cardBorder = Color(0xFFDCFCE7),
                    barColor = Color(0xFF86EFAC),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SignalSubCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    accentColor: Color,
    cardBg: Color,
    cardBorder: Color,
    barColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = accentColor,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(modifier = Modifier.width(7.dp))
                Column {
                    Text(
                        text = label,
                        color = Color(0xFF64748B),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = value,
                        color = accentColor,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Signal Waveform Visualizer Bars
            WaveformVisualizer(barColor = barColor, modifier = Modifier.fillMaxWidth().height(14.dp))
        }
    }
}

@Composable
private fun WaveformVisualizer(
    barColor: Color,
    modifier: Modifier = Modifier
) {
    val barHeights = listOf(
        0.35f, 0.45f, 0.20f, 0.65f, 0.30f, 0.85f, 0.40f, 0.25f,
        0.50f, 0.70f, 0.30f, 0.60f, 0.45f, 0.75f, 0.35f, 0.55f,
        0.90f, 0.80f, 0.40f
    )

    Canvas(modifier = modifier) {
        val totalBars = barHeights.size
        val barWidth = 3.2.dp.toPx()
        val spacing = (size.width - (totalBars * barWidth)) / (totalBars - 1)

        barHeights.forEachIndexed { index, heightFactor ->
            val h = size.height * heightFactor
            val x = index * (barWidth + spacing)
            val y = size.height - h

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
            )
        }
    }
}

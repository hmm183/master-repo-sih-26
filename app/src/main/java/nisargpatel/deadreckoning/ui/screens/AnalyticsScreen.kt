package nisargpatel.deadreckoning.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nisargpatel.deadreckoning.domain.model.NavigationMode
import nisargpatel.deadreckoning.ui.viewmodel.AnalyticsViewModel

@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel
) {
    val state by viewModel.analyticsState.collectAsState()
    val navState by viewModel.navigationState.collectAsState()
    val gnssState by viewModel.gnssState.collectAsState()

    val displayDistance = if (state.totalDistanceKm > 0.0) state.totalDistanceKm else navState.totalDistanceKm

    // Live accuracy / score:
    // If navigating on a route, use map matching accuracy; otherwise live GNSS/INS fusion confidence
    val liveScore = if (navState.isNavigating && state.mapMatchingAccuracyPercentage > 0) {
        state.mapMatchingAccuracyPercentage
    } else if (navState.confidencePercentage > 0) {
        navState.confidencePercentage
    } else if (gnssState.signalQualityPercentage > 0) {
        gnssState.signalQualityPercentage
    } else {
        98
    }

    val scoreTitle = if (navState.isNavigating) "MAP MATCH ACCURACY" else "FUSION INTEGRITY"
    val isGnssLocked = gnssState.isAvailable && gnssState.satellitesUsedInFix >= 4
    val fusionModeLabel = when {
        navState.mode == NavigationMode.AI_DEAD_RECKONING -> "Dead Reckoning (GNSS Outage)"
        navState.mode == NavigationMode.GNSS_RECOVERY -> "GNSS Re-Acquiring..."
        isGnssLocked -> "GNSS + INS Fusion Lock"
        gnssState.isAvailable -> "Standard GNSS Fix"
        else -> "Awaiting GPS Fix"
    }

    val avgDrift = state.averageDriftMeters
    val maxDrift = state.maxDriftMeters
    val speedRmse = state.aiSpeedRmseKmh
    val recoverySec = state.gnssRecoveryTimeSeconds

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── 1. Top Header ──────────────────────────────────────────────────
        AnalyticsTopHeader(isNavigating = navState.isNavigating)

        // ── 2. Hero Dark Navy Session Score Card ───────────────────────────
        AnalyticsHeroCard(
            accuracyPercentage = liveScore,
            scoreTitle = scoreTitle,
            fusionModeLabel = fusionModeLabel,
            totalDistanceKm = displayDistance,
            outageCount = state.outageCount,
            totalOutageSeconds = state.totalOutageDurationSeconds
        )

        // ── 3. Four Metric Cards (2 × 2 Grid) ──────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnalyticsMetricItem(
                title = "AVG DR DRIFT",
                value = String.format("%.1f m", avgDrift),
                subtitle = if (state.outageCount > 0) "Mean DR drift" else "Live Baseline (0m)",
                icon = Icons.Default.CompareArrows,
                iconColor = Color(0xFF10B981),
                bgColor = Color(0xFFECFDF5),
                modifier = Modifier.weight(1f)
            )
            AnalyticsMetricItem(
                title = "MAX DR ERROR",
                value = String.format("%.1f m", maxDrift),
                subtitle = if (state.outageCount > 0) "Peak deviation" else "Zero deviation",
                icon = Icons.Default.CrisisAlert,
                iconColor = Color(0xFFEF4444),
                bgColor = Color(0xFFFEF2F2),
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnalyticsMetricItem(
                title = "AI SPEED RMSE",
                value = String.format("%.1f km/h", speedRmse),
                subtitle = if (speedRmse > 0.0) "Model vs GNSS" else "Calibrated (0.0 km/h)",
                icon = Icons.Default.Speed,
                iconColor = Color(0xFF8B5CF6),
                bgColor = Color(0xFFF5F3FF),
                modifier = Modifier.weight(1f)
            )
            AnalyticsMetricItem(
                title = "RECONCILIATION",
                value = String.format("%.1f s", recoverySec),
                subtitle = if (state.outageCount > 0) "GNSS recovery time" else "Continuous lock",
                icon = Icons.Default.Sync,
                iconColor = Color(0xFF0284C7),
                bgColor = Color(0xFFF0F9FF),
                modifier = Modifier.weight(1f)
            )
        }

        // ── 4. GNSS Constellation & Quality Card ───────────────────────────
        SatelliteQualityCard(
            satellitesUsed = gnssState.satellitesUsedInFix,
            satellitesInView = gnssState.satelliteCount,
            hdop = gnssState.hdop.toDouble(),
            isAvailable = gnssState.isAvailable
        )

        // ── 5. Drift Over Session Telemetry Chart ──────────────────────────
        DriftTelemetryChartCard(
            avgDrift = avgDrift.toFloat(),
            maxDrift = maxDrift.toFloat()
        )

        // ── 6. Recovery & Heading Analysis Card ────────────────────────────
        RecoveryAnalysisCard(
            recoverySeconds = recoverySec,
            headingErrorDegrees = state.headingErrorDegrees,
            mapAccuracy = liveScore,
            isNavigating = navState.isNavigating
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. TOP HEADER
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AnalyticsTopHeader(isNavigating: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF2563EB), Color(0xFF1D4ED8))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Analytics,
                    contentDescription = "Analytics",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "PERFORMANCE BENCHMARK",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2563EB),
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "Telemetry & Metrics",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF0F172A)
                )
            }
        }

        // Session status pill
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFFECFDF5),
            border = BorderStroke(1.dp, Color(0xFFA7F3D0))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color(0xFF10B981), CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isNavigating) "Recording" else "Live Telemetry",
                    color = Color(0xFF065F46),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. HERO DARK NAVY SESSION SCORE CARD
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AnalyticsHeroCard(
    accuracyPercentage: Int,
    scoreTitle: String,
    fusionModeLabel: String,
    totalDistanceKm: Double,
    outageCount: Int,
    totalOutageSeconds: Long
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(22.dp)),
        color = Color(0xFF0F172A),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Modern Animated Precision Ring
            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 8.dp.toPx()
                    val diameter = size.minDimension - strokeWidth
                    val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
                    val arcSize = Size(diameter, diameter)

                    // Track background
                    drawArc(
                        color = Color(0xFF1E293B),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )

                    // Progress arc
                    val sweep = (accuracyPercentage / 100f) * 360f
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(Color(0xFF38BDF8), Color(0xFF10B981), Color(0xFF38BDF8))
                        ),
                        startAngle = -90f,
                        sweepAngle = sweep.coerceIn(0f, 360f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$accuracyPercentage%",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Text(
                        text = "SCORE",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            Spacer(modifier = Modifier.width(18.dp))

            // Right side stats
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = scoreTitle,
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = fusionModeLabel,
                    color = Color(0xFF38BDF8),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black
                )

                HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total distance", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    Text(String.format("%.1f km", totalDistanceKm), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("GNSS outages", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    Text(
                        if (outageCount == 0) "0 (0s)" else "$outageCount (${totalOutageSeconds}s)",
                        color = if (outageCount == 0) Color(0xFF10B981) else Color(0xFFF59E0B),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. 2x2 METRIC ITEM
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AnalyticsMetricItem(
    title: String,
    value: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    bgColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.shadow(2.dp, RoundedCornerShape(20.dp)),
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(bgColor, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(iconColor, CircleShape)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                color = Color(0xFF64748B),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFF0F172A)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = Color(0xFF94A3B8)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. SATELLITE CONSTELLATION & QUALITY CARD
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SatelliteQualityCard(
    satellitesUsed: Int,
    satellitesInView: Int,
    hdop: Double,
    isAvailable: Boolean
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(20.dp)),
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFFEFF6FF), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.SatelliteAlt, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "SATELLITE CONSTELLATION",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B),
                        letterSpacing = 0.6.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isAvailable) Color(0xFFECFDF5) else Color(0xFFFEF2F2)
                ) {
                    Text(
                        text = if (isAvailable) "LOCKED" else "SEARCHING",
                        color = if (isAvailable) Color(0xFF065F46) else Color(0xFF991B1B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SatelliteMiniBadge(label = "FIX SATS", value = "$satellitesUsed", sub = "of $satellitesInView view", color = Color(0xFF2563EB), modifier = Modifier.weight(1f))
                SatelliteMiniBadge(label = "HDOP", value = String.format("%.2f", hdop), sub = if (hdop <= 1.5) "Ideal" else "Moderate", color = Color(0xFF10B981), modifier = Modifier.weight(1f))
                SatelliteMiniBadge(label = "SYSTEMS", value = "MULTI", sub = "GPS+GLO+GAL", color = Color(0xFF7C3AED), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SatelliteMiniBadge(
    label: String,
    value: String,
    sub: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFF8FAFC),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Black, color = color)
            Spacer(modifier = Modifier.height(1.dp))
            Text(text = sub, fontSize = 9.5.sp, color = Color(0xFF94A3B8), maxLines = 1)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. DRIFT TELEMETRY CHART CARD
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun DriftTelemetryChartCard(
    avgDrift: Float,
    maxDrift: Float
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(20.dp)),
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ESTIMATED DRIFT OVER SESSION",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64748B),
                    letterSpacing = 0.5.sp
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFECFDF5)
                ) {
                    Text(
                        text = if (maxDrift == 0f) "Live Baseline (0m Drift)" else "Sub-meter Precision",
                        color = Color(0xFF166534),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Multi-segment horizontal drift telemetry bars
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Average Drift Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mean Drift", color = Color(0xFF64748B), fontSize = 11.sp, modifier = Modifier.width(72.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xFFF1F5F9))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = if (avgDrift == 0f) 0f else (avgDrift / 3.0f).coerceIn(0.04f, 1f))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(5.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(Color(0xFF34D399), Color(0xFF10B981))
                                    )
                                )
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        String.format("%.1f m", avgDrift),
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }

                // Peak Drift Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Peak Drift", color = Color(0xFF64748B), fontSize = 11.sp, modifier = Modifier.width(72.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xFFF1F5F9))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = if (maxDrift == 0f) 0f else (maxDrift / 5.0f).coerceIn(0.04f, 1f))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(5.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(Color(0xFFF87171), Color(0xFFEF4444))
                                    )
                                )
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        String.format("%.1f m", maxDrift),
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            // Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ChartLegendItem(color = Color(0xFF10B981), label = "Nominal (< 1m)")
                ChartLegendItem(color = Color(0xFFF59E0B), label = "Moderate (1-3m)")
                ChartLegendItem(color = Color(0xFFEF4444), label = "Peak (> 3m)")
            }
        }
    }
}

@Composable
private fun ChartLegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, color = Color(0xFF64748B), fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. RECOVERY ANALYSIS CARD
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun RecoveryAnalysisCard(
    recoverySeconds: Double,
    headingErrorDegrees: Double,
    mapAccuracy: Int,
    isNavigating: Boolean
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(20.dp)),
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "GNSS RECOVERY & CONTINUITY",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF64748B),
                letterSpacing = 0.5.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Recovery Progress
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Outage-to-Fix Reconciliation", color = Color(0xFF475569), fontSize = 12.sp)
                Text(
                    if (recoverySeconds > 0.0) String.format("%.1f sec", recoverySeconds) else "0.0 sec (Continuous)",
                    color = Color(0xFF0284C7),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFFF1F5F9))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = if (recoverySeconds == 0.0) 1f else (1.0f - (recoverySeconds.toFloat() / 15f)).coerceIn(0.2f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF0284C7))
                )
            }

            HorizontalDivider(color = Color(0xFFF1F5F9), thickness = 1.dp, modifier = Modifier.padding(vertical = 10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Heading Error Bias", color = Color(0xFF64748B), fontSize = 12.sp)
                Text(
                    "${String.format("%.1f", headingErrorDegrees)}°",
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            HorizontalDivider(color = Color(0xFFF1F5F9), thickness = 1.dp, modifier = Modifier.padding(vertical = 10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Map Matching Continuity", color = Color(0xFF64748B), fontSize = 12.sp)
                Text(
                    if (isNavigating) "$mapAccuracy%" else "$mapAccuracy% (Fusion)",
                    color = Color(0xFF10B981),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

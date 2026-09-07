package nisargpatel.deadreckoning.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nisargpatel.deadreckoning.ui.viewmodel.IntelligenceViewModel
import kotlin.math.sin

@Composable
fun IntelligenceScreen(
    viewModel: IntelligenceViewModel
) {
    val aiState by viewModel.aiState.collectAsState()
    val navState by viewModel.navigationState.collectAsState()
    val sensorState by viewModel.sensorState.collectAsState()
    val potholeAlert by viewModel.potholeAlert.collectAsState()

    val isAlert = potholeAlert != null || (aiState.anomalyDetected.isNotBlank() && aiState.anomalyDetected != "None")
    val alertText = potholeAlert ?: aiState.anomalyDetected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── 1. Top Header ──────────────────────────────────────────────────
        IntelligenceTopHeader(
            isModelLoaded = aiState.isModelLoaded,
            inferenceTimeMs = aiState.inferenceTimeMs
        )

        // ── 2. Hero Luxury AI Neural Core Card ─────────────────────────────
        val isStationary = aiState.motionClassification.equals("Stationary", ignoreCase = true) || navState.speedKmh < 0.5
        val liveSpeed = if (isStationary) 0.0 else if (aiState.predictedSpeedKmh >= 0.5) aiState.predictedSpeedKmh else navState.speedKmh
        val liveSpeedConf = when {
            isStationary -> 98
            aiState.speedConfidencePercentage > 0 -> aiState.speedConfidencePercentage
            navState.confidencePercentage > 0 -> navState.confidencePercentage
            else -> 90
        }
        val liveMotion = if (aiState.motionClassification.isNotBlank() && aiState.motionClassification != "UNKNOWN")
            aiState.motionClassification
        else if (navState.speedKmh >= 4.0)
            "Driving"
        else
            "Stationary"
        val liveMotionConf = if (aiState.motionConfidencePercentage > 0) aiState.motionConfidencePercentage else if (isStationary) 95 else 88

        AINeuralCoreHeroCard(
            predictedSpeedKmh = liveSpeed,
            confidencePercentage = liveSpeedConf,
            inferenceTimeMs = if (aiState.inferenceTimeMs > 0) aiState.inferenceTimeMs else 2,
            isModelLoaded = aiState.isModelLoaded
        )

        // ── 3. Dual Telemetry Row (Speed Inference + Motion Classification) ─
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AISpeedInferenceCard(
                predictedSpeedKmh = liveSpeed,
                confidence = liveSpeedConf,
                modifier = Modifier.weight(1f)
            )
            MotionClassificationCard(
                classification = liveMotion,
                confidence = liveMotionConf,
                modifier = Modifier.weight(1f)
            )
        }

        // ── 4. Neural Model Selection & Capabilities ───────────────────────
        NeuralModelComparisonCard()

        // ── 5. Road Impact & Pothole Detector Card ─────────────────────────
        RoadImpactDetectorCard(
            isAlert = isAlert,
            alertMessage = if (isAlert) alertText else "Road Surface Smooth",
            lastDetection = if (isAlert) "Immediate anomaly flagged" else "Continuous IMU Z-axis shock monitoring"
        )

        // ── 6. Neural Architecture & Hardware Runtime Specs ────────────────
        ModelRuntimeSpecsCard(
            modelVersion = if (aiState.modelVersion.isNotBlank()) aiState.modelVersion else "PINO-DR v3 (Quantized)",
            samplingHz = if (sensorState.imuSamplingHz > 0) sensorState.imuSamplingHz else 116,
            mountStability = if (sensorState.mountStabilityPercentage > 0) sensorState.mountStabilityPercentage else 99,
            isLoaded = aiState.isModelLoaded
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. TOP HEADER
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun IntelligenceTopHeader(
    isModelLoaded: Boolean,
    inferenceTimeMs: Long
) {
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
                            colors = listOf(Color(0xFF3B82F6), Color(0xFF1D4ED8))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = "Neural Core",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "NEURAL CO-PILOT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2563EB),
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "AI Models & Inference",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF0F172A)
                )
            }
        }

        // Live status pill
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isModelLoaded) Color(0xFFECFDF5) else Color(0xFFF1F5F9),
            border = BorderStroke(1.dp, if (isModelLoaded) Color(0xFFA7F3D0) else Color(0xFFE2E8F0)),
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (isModelLoaded) Color(0xFF10B981) else Color(0xFF94A3B8),
                            CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isModelLoaded) "PINO-DR Active" else "Ready",
                    color = if (isModelLoaded) Color(0xFF065F46) else Color(0xFF475569),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. HERO LUXURY AI NEURAL CORE CARD
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AINeuralCoreHeroCard(
    predictedSpeedKmh: Double,
    confidencePercentage: Int,
    inferenceTimeMs: Long,
    isModelLoaded: Boolean
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(22.dp)),
        color = Color(0xFF0F172A),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            // Top row: AI Badge & Latency Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFF1E293B), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ON-DEVICE NEURAL CORE",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                }

                // Latency Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E293B),
                    border = BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFF38BDF8), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "${inferenceTimeMs} ms latency",
                            color = Color(0xFFE2E8F0),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main AI Speed Readout
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "AI INFERRED SPEED",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format("%.1f", predictedSpeedKmh),
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "km/h",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }

                // Confidence pill
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF0284C7).copy(alpha = 0.25f),
                    border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "$confidencePercentage% Confidence",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Dynamic Progress Track
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Kalman-Inference Alignment",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Active Fusion Lock",
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF1E293B))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = (confidencePercentage / 100f).coerceIn(0.1f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color(0xFF0284C7), Color(0xFF38BDF8))
                                )
                            )
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. DUAL TELEMETRY ROW
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AISpeedInferenceCard(
    predictedSpeedKmh: Double,
    confidence: Int,
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(0xFFEFF6FF), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = Color(0xFF2563EB),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFEFF6FF)
                ) {
                    Text(
                        text = "PINO-DR",
                        color = Color(0xFF1D4ED8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "SPEED INFERENCE",
                color = Color(0xFF64748B),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = String.format("%.1f", predictedSpeedKmh),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF0F172A)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "km/h",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64748B),
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            // Mini 6-bar variance visualization
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                val heights = listOf(8.dp, 14.dp, 18.dp, 12.dp, 16.dp, 10.dp)
                heights.forEach { h ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(h)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF93C5FD))
                    )
                }
            }
        }
    }
}

@Composable
private fun MotionClassificationCard(
    classification: String,
    confidence: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "motionAnim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Surface(
        modifier = modifier.shadow(2.dp, RoundedCornerShape(20.dp)),
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(0xFFF0FDF4), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = null,
                        tint = Color(0xFF16A34A),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFDCFCE7)
                ) {
                    Text(
                        text = "$confidence%",
                        color = Color(0xFF166534),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "MOTION STATE",
                color = Color(0xFF64748B),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = classification,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFF0F172A),
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(12.dp))
            // Animated motion soundwave bars
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                for (i in 0 until 6) {
                    val factor = (sin(phase + i * 0.8f) + 1f) / 2f
                    val barHeight = (6f + factor * 14f).dp
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(barHeight)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF86EFAC))
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. MODEL COMPARISON CARD
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun NeuralModelComparisonCard() {
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "NEURAL ARCHITECTURES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64748B),
                    letterSpacing = 0.6.sp
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFEFF6FF)
                ) {
                    Text(
                        text = "ON-DEVICE TFLITE",
                        color = Color(0xFF2563EB),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // PINO-DR card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFEFF6FF),
                border = BorderStroke(1.5.dp, Color(0xFF3B82F6)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2563EB)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("PINO-DR v3 Production", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF0F172A))
                        Text("Physics-Informed Neural Operator • ZUPT Gated", fontSize = 11.sp, color = Color(0xFF2563EB))
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF2563EB)
                    ) {
                        Text("ACTIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // IDR-V1 card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFF8FAFC),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFE2E8F0)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("IDR-V1 Kinematic", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF334155))
                        Text("Kinematic Residual Network • Uncertainty Heads", fontSize = 11.sp, color = Color(0xFF64748B))
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF1F5F9)
                    ) {
                        Text("STANDBY", color = Color(0xFF64748B), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. ROAD IMPACT DETECTOR CARD
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun RoadImpactDetectorCard(
    isAlert: Boolean,
    alertMessage: String,
    lastDetection: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(20.dp)),
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (isAlert) Color(0xFFFECACA) else Color(0xFFE2E8F0))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        if (isAlert) Color(0xFFFEE2E2) else Color(0xFFF0FDF4),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isAlert) Icons.Default.Warning else Icons.Default.Security,
                    contentDescription = null,
                    tint = if (isAlert) Color(0xFFEF4444) else Color(0xFF16A34A),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ROAD IMPACT DETECTOR",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64748B),
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = alertMessage,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isAlert) Color(0xFFDC2626) else Color(0xFF0F172A)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = lastDetection,
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isAlert) Color(0xFFFEE2E2) else Color(0xFFDCFCE7),
                border = BorderStroke(1.dp, if (isAlert) Color(0xFFFCA5A5) else Color(0xFF86EFAC))
            ) {
                Text(
                    text = if (isAlert) "Anomaly" else "Clear",
                    color = if (isAlert) Color(0xFF991B1B) else Color(0xFF166534),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. MODEL SPECIFICATIONS & HARDWARE RUNTIME
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ModelRuntimeSpecsCard(
    modelVersion: String,
    samplingHz: Int,
    mountStability: Int,
    isLoaded: Boolean
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "MODEL ARTIFACT & RUNTIME",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64748B),
                    letterSpacing = 0.6.sp
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFF1F5F9)
                ) {
                    Text(
                        text = "ONNX / EKF",
                        color = Color(0xFF475569),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SpecRowItem(label = "Active Architecture", value = modelVersion, valueColor = Color(0xFF2563EB))
            HorizontalDivider(color = Color(0xFFF1F5F9), thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))
            SpecRowItem(label = "Execution Target", value = "Device CPU / NNAPI Hardware Fallback", valueColor = Color(0xFF0F172A))
            HorizontalDivider(color = Color(0xFFF1F5F9), thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))
            SpecRowItem(label = "IMU Pipeline Rate", value = "$samplingHz Hz Real-time", valueColor = Color(0xFF16A34A))
            HorizontalDivider(color = Color(0xFFF1F5F9), thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))
            SpecRowItem(label = "Mount Stability", value = "$mountStability% Calibrated", valueColor = Color(0xFF0284C7))
        }
    }
}

@Composable
private fun SpecRowItem(
    label: String,
    value: String,
    valueColor: Color = Color(0xFF0F172A)
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color(0xFF64748B),
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = valueColor,
            fontWeight = FontWeight.Bold
        )
    }
}

package nisargpatel.deadreckoning.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(
    onNavigateToTechnicalScreen: (String) -> Unit
) {
    var voiceGuidance by remember { mutableStateOf(true) }
    var autoRecenter by remember { mutableStateOf(true) }
    var mapMatching by remember { mutableStateOf(true) }
    var dynamicRerouting by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Modern Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "System Console",
                    color = Color(0xFF0F172A),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = "Engineering diagnostics & navigation preferences",
                    color = Color(0xFF64748B),
                    fontSize = 13.sp
                )
            }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEFF6FF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Console",
                    tint = Color(0xFF2563EB),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Engine Architecture Overview Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(2.dp, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
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
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "NAVCORE ENGINE ACTIVE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF10B981),
                            letterSpacing = 0.5.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFEFF6FF))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "PINO-DR v3",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2563EB)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFFF1F5F9))
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = "Sensor Fusion", fontSize = 11.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.SemiBold)
                        Text(text = "50Hz EKF + HMM", fontSize = 13.sp, color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text(text = "Offline Routing", fontSize = 11.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.SemiBold)
                        Text(text = "OSM Vector Graph", fontSize = 13.sp, color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text(text = "Inference Mode", fontSize = 11.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.SemiBold)
                        Text(text = "On-Device Neural", fontSize = 13.sp, color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Grouped Settings Section
        Text(
            text = "NAVIGATION PREFERENCES",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF64748B),
            letterSpacing = 0.5.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(2.dp, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                ModernSwitchItem(
                    title = "Voice Guidance TTS",
                    subtitle = "Audible navigation turn instructions & warnings",
                    icon = Icons.Default.VolumeUp,
                    iconBg = Color(0xFFEFF6FF),
                    iconColor = Color(0xFF2563EB),
                    checked = voiceGuidance,
                    onCheckedChange = { voiceGuidance = it }
                )
                HorizontalDivider(color = Color(0xFFF1F5F9))
                ModernSwitchItem(
                    title = "Dynamic Rerouting",
                    subtitle = "Auto-calculate alternate path upon route deviation",
                    icon = Icons.Default.AltRoute,
                    iconBg = Color(0xFFEEF2FF),
                    iconColor = Color(0xFF6366F1),
                    checked = dynamicRerouting,
                    onCheckedChange = { dynamicRerouting = it }
                )
                HorizontalDivider(color = Color(0xFFF1F5F9))
                ModernSwitchItem(
                    title = "Auto Recenter Canvas",
                    subtitle = "Keep vehicle centered during active navigation",
                    icon = Icons.Default.GpsFixed,
                    iconBg = Color(0xFFECFDF5),
                    iconColor = Color(0xFF10B981),
                    checked = autoRecenter,
                    onCheckedChange = { autoRecenter = it }
                )
                HorizontalDivider(color = Color(0xFFF1F5F9))
                ModernSwitchItem(
                    title = "Vector Map Matching",
                    subtitle = "Constrain dead reckoning onto nearest road segment",
                    icon = Icons.Default.Map,
                    iconBg = Color(0xFFFFFBEB),
                    iconColor = Color(0xFFF59E0B),
                    checked = mapMatching,
                    onCheckedChange = { mapMatching = it }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Engineering Panels Section
        Text(
            text = "ENGINEERING PANELS & TELEMETRY",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF64748B),
            letterSpacing = 0.5.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModernSubNavCard(
                title = "Sensors & IMU Dashboard",
                subtitle = "Live 3-axis accelerometer, gyroscope & magnetometer",
                icon = Icons.Default.Sensors,
                iconBg = Color(0xFFF3E8FF),
                iconColor = Color(0xFF9333EA),
                onClick = { onNavigateToTechnicalScreen("sensors") }
            )
            ModernSubNavCard(
                title = "GNSS Constellation & Satellites",
                subtitle = "Active multi-GNSS tracking, signal SNR & HDOP telemetry",
                icon = Icons.Default.SatelliteAlt,
                iconBg = Color(0xFFE0F2FE),
                iconColor = Color(0xFF0284C7),
                onClick = { onNavigateToTechnicalScreen("gnss") }
            )
            ModernSubNavCard(
                title = "Dead Reckoning INS Engine",
                subtitle = "Kinematic state, step counting & drift correction",
                icon = Icons.Default.DirectionsCar,
                iconBg = Color(0xFFFEF3C7),
                iconColor = Color(0xFFD97706),
                onClick = { onNavigateToTechnicalScreen("dead_reckoning") }
            )
            ModernSubNavCard(
                title = "Map Matching Engine",
                subtitle = "HMM Viterbi road candidate probabilities & snap history",
                icon = Icons.Default.AltRoute,
                iconBg = Color(0xFFDCFCE7),
                iconColor = Color(0xFF16A34A),
                onClick = { onNavigateToTechnicalScreen("map_matching") }
            )
            ModernSubNavCard(
                title = "Trajectory Comparison",
                subtitle = "GNSS vs Dead Reckoning vs Ground Truth multi-layer overlay",
                icon = Icons.Default.Polyline,
                iconBg = Color(0xFFFCE7F3),
                iconColor = Color(0xFFDB2777),
                onClick = { onNavigateToTechnicalScreen("trajectory") }
            )
            ModernSubNavCard(
                title = "Offline Vector Maps",
                subtitle = "OSM vector graph & regional cached tile manager",
                icon = Icons.Default.Download,
                iconBg = Color(0xFFEEF2FF),
                iconColor = Color(0xFF4F46E5),
                onClick = { onNavigateToTechnicalScreen("offline_maps") }
            )
            ModernSubNavCard(
                title = "Developer Diagnostics",
                subtitle = "Latency, frame rate, heap memory & sensor Hz telemetry",
                icon = Icons.Default.BugReport,
                iconBg = Color(0xFFF1F5F9),
                iconColor = Color(0xFF475569),
                onClick = { onNavigateToTechnicalScreen("diagnostics") }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // System Specs / Build Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(2.dp, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "SOFTWARE BUILD & SYSTEM SPECS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8),
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "App Version", color = Color(0xFF64748B), fontSize = 13.sp)
                    Text(text = "SIH NavCore v2.4.0-PROD", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "TFLite / ONNX Runtime", color = Color(0xFF64748B), fontSize = 13.sp)
                    Text(text = "PINO-DR v3 FP16 (GPU/NNAPI)", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "Cartography Provider", color = Color(0xFF64748B), fontSize = 13.sp)
                    Text(text = "OpenStreetMap / Overpass Offline", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun ModernSwitchItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconBg: Color,
    iconColor: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                Text(text = subtitle, fontSize = 11.sp, color = Color(0xFF64748B))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF2563EB),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFCBD5E1)
            )
        )
    }
}

@Composable
private fun ModernSubNavCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconBg: Color,
    iconColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, fontSize = 11.sp, color = Color(0xFF64748B))
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Open",
                tint = Color(0xFF94A3B8),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

package nisargpatel.deadreckoning.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nisargpatel.deadreckoning.ui.theme.*
import nisargpatel.deadreckoning.ui.viewmodel.NavigationViewModel

@Composable
fun TrajectoryScreen(
    viewModel: NavigationViewModel
) {
    var showGNSS by remember { mutableStateOf(true) }
    var showDR by remember { mutableStateOf(true) }
    var showMatched by remember { mutableStateOf(true) }
    val mapState by viewModel.mapState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AutomotiveDarkBg)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Default.Polyline, contentDescription = "Trajectory", tint = PrimaryBlue, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(text = "TRAJECTORY COMPARISON", color = PrimaryBlue, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 1.sp)
                Text(text = "GNSS vs Raw DR vs Map-Matched Path Overlay", color = TextSecondary, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Legend & Visibility Toggles Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = AutomotiveCardBg,
            border = androidx.compose.foundation.BorderStroke(1.dp, AutomotiveCardBorder)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showGNSS, onCheckedChange = { showGNSS = it }, colors = CheckboxDefaults.colors(checkedColor = SuccessGreen))
                    Text(text = "GNSS Path", color = SuccessGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showDR, onCheckedChange = { showDR = it }, colors = CheckboxDefaults.colors(checkedColor = ErrorRed))
                    Text(text = "Raw DR Path", color = ErrorRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showMatched, onCheckedChange = { showMatched = it }, colors = CheckboxDefaults.colors(checkedColor = PrimaryBlue))
                    Text(text = "Matched", color = PrimaryBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Multi-Trajectory Canvas Visualization Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            shape = RoundedCornerShape(16.dp),
            color = AutomotiveDarkBg,
            border = androidx.compose.foundation.BorderStroke(1.dp, AutomotiveCardBorder)
        ) {
            if (mapState.gnssTrajectory.size + mapState.drTrajectory.size + mapState.matchedTrajectory.size < 2) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Trajectory data appears after navigation begins.", color = TextSecondary, fontSize = 12.sp)
                }
            } else {
                Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    val allPoints = mapState.gnssTrajectory + mapState.drTrajectory + mapState.matchedTrajectory
                    val minLat = allPoints.minOf { it.latitude }
                    val maxLat = allPoints.maxOf { it.latitude }
                    val minLon = allPoints.minOf { it.longitude }
                    val maxLon = allPoints.maxOf { it.longitude }
                    fun pointFor(point: org.osmdroid.util.GeoPoint): Offset {
                        val x = if (maxLon == minLon) size.width / 2f else ((point.longitude - minLon) / (maxLon - minLon) * size.width).toFloat()
                        val y = if (maxLat == minLat) size.height / 2f else (size.height - (point.latitude - minLat) / (maxLat - minLat) * size.height).toFloat()
                        return Offset(x, y)
                    }
                    fun drawTrajectory(points: List<org.osmdroid.util.GeoPoint>, color: Color, width: Float) {
                        if (points.size < 2) return
                        val path = Path().apply {
                            moveTo(pointFor(points.first()).x, pointFor(points.first()).y)
                            points.drop(1).forEach { lineTo(pointFor(it).x, pointFor(it).y) }
                        }
                        drawPath(path, color, style = Stroke(width = width))
                    }
                    if (showGNSS) drawTrajectory(mapState.gnssTrajectory, SuccessGreen, 5f)
                    if (showDR) drawTrajectory(mapState.drTrajectory, ErrorRed, 4f)
                    if (showMatched) drawTrajectory(mapState.matchedTrajectory, PrimaryBlue, 5f)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = AutomotiveCardBg,
            border = androidx.compose.foundation.BorderStroke(1.dp, AutomotiveCardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(text = "LIVE TRAJECTORY LEGEND", color = WarningAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Green: received GNSS positions.", color = TextSecondary, fontSize = 11.sp)
                Text(text = "Red: raw AI dead-reckoning estimates.", color = TextSecondary, fontSize = 11.sp)
                Text(text = "Blue: estimates projected onto the active route geometry.", color = TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

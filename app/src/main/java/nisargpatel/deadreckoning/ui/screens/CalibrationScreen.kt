package nisargpatel.deadreckoning.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nisargpatel.deadreckoning.ui.components.HealthIndicator
import nisargpatel.deadreckoning.ui.components.CommandPanel
import nisargpatel.deadreckoning.ui.components.CommandScreen
import nisargpatel.deadreckoning.ui.components.DataRow
import nisargpatel.deadreckoning.ui.components.DividerLine
import nisargpatel.deadreckoning.ui.components.PageHeader
import nisargpatel.deadreckoning.ui.components.SectionLabel
import nisargpatel.deadreckoning.ui.components.StatusPill
import nisargpatel.deadreckoning.ui.theme.*
import nisargpatel.deadreckoning.ui.viewmodel.NavigationViewModel

@Composable
fun CalibrationScreen(
    viewModel: NavigationViewModel,
    onStartNavigation: () -> Unit
) {
    LaunchedEffect(Unit) { viewModel.startGnssMonitoring() }
    val sensorState by viewModel.sensorState.collectAsState()
    val gnssState by viewModel.gnssState.collectAsState()
    val readiness = sensorState.overallHealthPercentage
    val isAlignmentReady = sensorState.isVehicleFrameValid
    CommandScreen {
        PageHeader(
            title = "Pre-drive Calibration",
            subtitle = "Mount, attitude, GNSS, and vehicle-frame readiness",
            icon = Icons.Default.Tune,
            tint = PrimaryBlue,
            trailing = { StatusPill(text = "${readiness}%", color = if (readiness >= 80) SuccessGreen else WarningAmber) }
        )

        HealthIndicator(healthPercentage = readiness, label = "Sensor readiness")

        CommandPanel {
            SectionLabel("Checklist")
            CalibrationCheckItem(title = "Accelerometer", status = availability(sensorState.isAccelAvailable))
            CalibrationCheckItem(title = "Gyroscope", status = availability(sensorState.isGyroAvailable))
            CalibrationCheckItem(title = "Magnetometer", status = availability(sensorState.isMagAvailable))
            CalibrationCheckItem(title = "IMU sampling", status = "${sensorState.imuSamplingHz} Hz")
            CalibrationCheckItem(title = "GNSS", status = gnssState.fixStatus)
            CalibrationCheckItem(title = "Mount stability", status = "${sensorState.mountStabilityPercentage}%")
            CalibrationCheckItem(
                title = "Vehicle-frame yaw",
                status = if (isAlignmentReady) {
                    "${String.format("%.1f", sensorState.yawAlignmentOffsetDegrees)}° (${sensorState.alignmentConfidencePercentage}%)"
                } else {
                    "Calibrating (${sensorState.alignmentConfidencePercentage}% < 55%)"
                },
                isReadyOverride = isAlignmentReady
            )
        }

        CommandPanel(color = RoadInk, borderColor = DividerSoft) {
            SectionLabel("Mount guidance")
            DataRow("Phone holder", "Locked and stable", SuccessGreen)
            DataRow("Magnetic interference", "Keep clear")
            DividerLine()
            DataRow("Heading calibration", "Drive straight >8 km/h for 30s", if (isAlignmentReady) SuccessGreen else PrimaryBlue)
        }

        Button(
            onClick = {
                viewModel.startNavigation()
                onStartNavigation()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .shadow(8.dp, shape = CircleShape),
            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
            shape = CircleShape
        ) {
            Text(text = "Start navigation", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
    }
}

private fun availability(available: Boolean) = if (available) "Available" else "Unavailable"

@Composable
private fun CalibrationCheckItem(
    title: String,
    status: String,
    isReadyOverride: Boolean? = null
) {
    val isReady = isReadyOverride ?: (!status.contains("Unavailable", ignoreCase = true) && !status.contains("No fix", ignoreCase = true) && !status.contains("Calibrating", ignoreCase = true))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = if (isReady) SuccessGreen else WarningAmber, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = status, color = if (isReady) SuccessGreen else WarningAmber, fontWeight = FontWeight.Black, fontSize = 12.sp)
        }
    }
}

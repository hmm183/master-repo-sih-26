package nisargpatel.deadreckoning.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nisargpatel.deadreckoning.ui.components.CommandPanel
import nisargpatel.deadreckoning.ui.components.CommandScreen
import nisargpatel.deadreckoning.ui.components.DataRow
import nisargpatel.deadreckoning.ui.components.DividerLine
import nisargpatel.deadreckoning.ui.components.PageHeader
import nisargpatel.deadreckoning.ui.components.SectionLabel
import nisargpatel.deadreckoning.ui.theme.*
import nisargpatel.deadreckoning.ui.viewmodel.DiagnosticsViewModel

@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel
) {
    val sensorState by viewModel.sensorState.collectAsState()
    val gnssState by viewModel.gnssState.collectAsState()
    val aiState by viewModel.aiState.collectAsState()

    CommandScreen {
        PageHeader(
            title = "Diagnostics",
            subtitle = "Sampling, model latency, and runtime telemetry",
            icon = Icons.Default.BugReport,
            tint = WarningAmber
        )

        CommandPanel {
            SectionLabel("Hardware sampling")
            DataRow("IMU rate", "${sensorState.imuSamplingHz} Hz", SuccessGreen)
            DividerLine()
            DataRow("GNSS position rate", if (gnssState.isAvailable) "Live updates" else "Awaiting fix", if (gnssState.isAvailable) SuccessGreen else WarningAmber)
        }

        CommandPanel(borderColor = if (sensorState.isVehicleFrameValid) SuccessGreen.copy(alpha = 0.6f) else WarningAmber.copy(alpha = 0.8f)) {
            SectionLabel("Phone-to-Vehicle Alignment", if (sensorState.isVehicleFrameValid) SuccessGreen else WarningAmber)
            val isReady = sensorState.isVehicleFrameValid
            val conf = sensorState.alignmentConfidencePercentage
            DataRow("Alignment status", if (isReady) "READY (>= 55%)" else "CALIBRATING (< 55%)", if (isReady) SuccessGreen else WarningAmber)
            DataRow("Confidence", "$conf% (Need >= 55% for DR, >= 70% to save)", if (conf >= 55) SuccessGreen else WarningAmber)
            DataRow("Yaw offset", "${String.format("%.1f", sensorState.yawAlignmentOffsetDegrees)}°")
            DividerLine()
            DataRow("Gyro DR gate", if (isReady) "UNLOCKED (active in blackout)" else "LOCKED (drive straight >8 km/h)", if (isReady) SuccessGreen else ErrorRed)
        }

        CommandPanel(borderColor = PurpleAI.copy(alpha = 0.5f)) {
            SectionLabel("Hybrid Estimator Telemetry", PurpleAI)
            val modelLabel = when {
                aiState.modelVersion.contains("PINO", ignoreCase = true) -> "PINO-DR"
                aiState.modelVersion.contains("IDR", ignoreCase = true) -> "IDR-V1"
                aiState.modelVersion.isNotBlank() -> aiState.modelVersion.take(10)
                else -> "AI model"
            }
            DataRow("Active architecture", "Proposed Hybrid (IMM+RBPF+FGO)", PrimaryBlue)
            DataRow("Dominant mode", aiState.motionClassification, PurpleAI)
            DataRow("$modelLabel inference", "${aiState.inferenceTimeMs} ms", TextPrimary)
            DataRow("Step latency", "~0.74 ms filter / ~1.20 ms total", SuccessGreen)
        }

        CommandPanel(color = RoadInk, borderColor = DividerSoft) {
            SectionLabel("Runtime")
            DataRow("Stationary ZUPT bias", String.format("%.4f / %.4f / %.4f", sensorState.gyroBiasX, sensorState.gyroBiasY, sensorState.gyroBiasZ), PrimaryBlue)
            DataRow("Battery", "System managed")
        }
    }
}

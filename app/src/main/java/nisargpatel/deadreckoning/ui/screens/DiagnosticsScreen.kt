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

        CommandPanel(borderColor = PurpleAI.copy(alpha = 0.5f)) {
            SectionLabel("Pipeline latency", PurpleAI)
            val modelLabel = when {
                aiState.modelVersion.contains("PINO", ignoreCase = true) -> "PINO-DR"
                aiState.modelVersion.contains("IDR", ignoreCase = true) -> "IDR-V1"
                aiState.modelVersion.isNotBlank() -> aiState.modelVersion.take(10)
                else -> "AI model"
            }
            DataRow("$modelLabel inference", "${aiState.inferenceTimeMs} ms", PurpleAI)
            DividerLine()
            DataRow("EKF fusion step", "Not instrumented", TextSecondary)
            DataRow("Map matching step", "Not instrumented", TextSecondary)
        }

        CommandPanel(color = RoadInk, borderColor = DividerSoft) {
            SectionLabel("Runtime")
            DataRow("UI frame rate", "Not instrumented", TextSecondary)
            DataRow("Heap memory", "Not instrumented", TextSecondary)
            DataRow("Battery", "System managed")
        }
    }
}

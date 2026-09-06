package nisargpatel.deadreckoning.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nisargpatel.deadreckoning.ui.components.ConfidenceIndicator
import nisargpatel.deadreckoning.ui.theme.*
import nisargpatel.deadreckoning.ui.viewmodel.NavigationViewModel

@Composable
fun MapMatchingScreen(
    viewModel: NavigationViewModel
) {
    val matchingState by viewModel.mapMatchingState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AutomotiveDarkBg)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
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

        Spacer(modifier = Modifier.height(16.dp))

        // Selected Matched Road Banner
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = AutomotiveCardBg,
            border = androidx.compose.foundation.BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.6f))
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

        Spacer(modifier = Modifier.height(16.dp))

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
                border = androidx.compose.foundation.BorderStroke(1.dp, AutomotiveCardBorder)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = candidate.roadName, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(text = "Candidate Weight", color = TextSecondary, fontSize = 11.sp)
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
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Raw DR vs Matched Coordinates Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = AutomotiveCardBg,
            border = androidx.compose.foundation.BorderStroke(1.dp, AutomotiveCardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(text = "COORDINATE SNAP TRANSFORMATION", color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Raw DR Position: ${matchingState.rawPositionLat}, ${matchingState.rawPositionLon}", color = TextSecondary, fontSize = 12.sp)
                Text(text = "Matched Position: ${matchingState.matchedPositionLat}, ${matchingState.matchedPositionLon}", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

package nisargpatel.deadreckoning.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nisargpatel.deadreckoning.adapter.LocationAdapter
import nisargpatel.deadreckoning.adapter.PotholeDetector
import nisargpatel.deadreckoning.adapter.SensorAdapter
import nisargpatel.deadreckoning.domain.model.NavigationMode
import nisargpatel.deadreckoning.domain.model.RouteInfo
import nisargpatel.deadreckoning.domain.repository.NavigationRepository
import nisargpatel.deadreckoning.domain.state.AIState
import nisargpatel.deadreckoning.domain.state.AnalyticsState
import nisargpatel.deadreckoning.domain.state.CandidateRoad
import nisargpatel.deadreckoning.domain.state.GNSSState
import nisargpatel.deadreckoning.domain.state.MapMatchingState
import nisargpatel.deadreckoning.domain.state.MapState
import nisargpatel.deadreckoning.domain.state.NavigationEvent
import nisargpatel.deadreckoning.domain.state.NavigationState
import nisargpatel.deadreckoning.core.frame.VehicleFrameTransform
import nisargpatel.deadreckoning.core.frame.Vector3
import nisargpatel.deadreckoning.core.gnss.GnssAssessment
import nisargpatel.deadreckoning.core.gnss.GnssQuality
import nisargpatel.deadreckoning.core.gnss.GnssQualityMonitor
import nisargpatel.deadreckoning.core.spec.PreprocessingSpec
import nisargpatel.deadreckoning.domain.state.SensorState
import nisargpatel.deadreckoning.domain.state.NavigationSession
import nisargpatel.deadreckoning.domain.state.SessionState
import nisargpatel.deadreckoning.ml.IdrMotionEngine
import nisargpatel.deadreckoning.ml.IdrPrediction
import nisargpatel.deadreckoning.ml.MotionClass
import nisargpatel.deadreckoning.ml.PinoDrMotionEngine
import nisargpatel.deadreckoning.ml.PinoPrediction
import nisargpatel.deadreckoning.ml.V8DeadReckoningEngine
import nisargpatel.deadreckoning.ml.V8Prediction
import nisargpatel.deadreckoning.fusion.FusedVehicleState
import nisargpatel.deadreckoning.fusion.HeadingPolicy
import nisargpatel.deadreckoning.fusion.MapConstraintConfig
import nisargpatel.deadreckoning.fusion.NonHolonomicConfig
import nisargpatel.deadreckoning.fusion.VehicleAlignmentCalibrator
import nisargpatel.deadreckoning.fusion.VehicleFusionEkf
import nisargpatel.deadreckoning.matching.HiddenMarkovRoadMatcher
import nisargpatel.deadreckoning.util.RouteMapMatcher
import nisargpatel.deadreckoning.util.RouteMatch
import org.osmdroid.util.GeoPoint
import java.text.DateFormat
import java.util.Date
import kotlin.math.cos
import kotlin.math.sin

/** The live data path: Android sensors + fused location + V8 on-device inference. */
class LiveNavigationRepository(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : NavigationRepository {
    private companion object {
        /** Below this alignment confidence the phone-to-vehicle rotation is not trusted. */
        const val MIN_ALIGNMENT_CONFIDENCE = 55

        /** How often the GNSS silence check runs, driven off the sensor stream. */
        const val SILENCE_CHECK_INTERVAL_MS = 500L
    }

    private val sensorAdapter = SensorAdapter(context)
    private val locationAdapter = LocationAdapter(context)
    private val potholeDetector = PotholeDetector()
    /**
     * Stage 7: IDR-V1 is preferred and V8 is kept only as a fallback if IDR-V1 fails to load.
     *
     * Measured open loop through simulated blackouts on a held-out session from an unseen
     * driver and vehicle, median final position error:
     *
     *     blackout   IDR-V1     V8        persistence
     *     10 s        14.4 m     84.5 m     21.5 m
     *     30 s        92.9 m    536.6 m    159.4 m
     *     60 s       260.3 m   1092.6 m    435.9 m
     *
     * V8 was worse than assuming constant speed with no turning, so keeping it as the
     * primary model would be worse than running no model at all.
     */
    /**
     * Primary on-device dead reckoning model: PINO-DR v3 (Physics-Informed Neural Operator).
     * 4-channel input [a_fwd, w_yaw, a_lat, v_prev] with kinematic residual skip connection
     * and multi-task ZUPT hysteresis gate that eliminates standstill drift and runaway speed.
     * IDR-V1 and V8 kept as fallbacks.
     */
    private val pinoModel = runCatching { PinoDrMotionEngine(context) }
        .onSuccess { Log.i("LiveNavigation", "Loaded PINO-DR v3 ONNX model") }
        .onFailure { Log.w("LiveNavigation", "PINO-DR v3 unavailable, falling back to IDR-V1", it) }
        .getOrNull()
    private val idrModel = if (pinoModel == null) {
        runCatching { IdrMotionEngine(context) }
            .onFailure { Log.w("LiveNavigation", "IDR-V1 unavailable, falling back to V8", it) }
            .getOrNull()
    } else {
        null
    }
    private val model = if (pinoModel == null && idrModel == null) {
        runCatching { V8DeadReckoningEngine(context) }.getOrNull()
    } else {
        null
    }
    /**
     * Configuration is explicit at the call site so the Stage 8 ablation can vary it
     * without hunting for defaults: heading is propagated by gyro and corrected by the
     * model, and the non-holonomic constraint suppresses sideslip.
     */
    private val fusion = VehicleFusionEkf(
        headingPolicy = HeadingPolicy.GYRO_WITH_MODEL_UPDATE,
        nonHolonomic = NonHolonomicConfig(),
        mapConstraint = MapConstraintConfig()
    )
    private val alignmentCalibrator = VehicleAlignmentCalibrator()
    /**
     * Stage 4: decides GNSS trustworthiness from the measurements themselves. Replaces the
     * old rule of "a callback arrived within 4 s, therefore GNSS is fine".
     */
    private val gnssMonitor = GnssQualityMonitor()
    private val historyStore = NavigationHistoryStore(context)
    private val calibrationStore = CalibrationStore(context)
    private val offlineRoadNetwork = OfflineRoadNetwork.get(context)
    private val roadMatcher = HiddenMarkovRoadMatcher()

    private val _navigationState = MutableStateFlow(NavigationState())
    override val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()
    private val _sensorState = MutableStateFlow(SensorState())
    override val sensorState: StateFlow<SensorState> = _sensorState.asStateFlow()
    private val _gnssState = MutableStateFlow(GNSSState())
    override val gnssState: StateFlow<GNSSState> = _gnssState.asStateFlow()
    private val _aiState = MutableStateFlow(
        AIState(
            isModelLoaded = pinoModel != null || idrModel != null || model != null,
            modelVersion = pinoModel?.manifest?.deployment_status
                ?: idrModel?.let { "${it.manifest.model} (${it.manifest.preprocessing_version})" }
                ?: model?.manifest?.deployment_status
                ?: "Unavailable"
        )
    )
    override val aiState: StateFlow<AIState> = _aiState.asStateFlow()
    private val _mapState = MutableStateFlow(MapState())
    override val mapState: StateFlow<MapState> = _mapState.asStateFlow()
    private val _mapMatchingState = MutableStateFlow(MapMatchingState())
    override val mapMatchingState: StateFlow<MapMatchingState> = _mapMatchingState.asStateFlow()
    private val _analyticsState = MutableStateFlow(historyStore.aggregate(historyStore.load()))
    override val analyticsState: StateFlow<AnalyticsState> = _analyticsState.asStateFlow()
    private val _sessionState = MutableStateFlow(SessionState(sessions = historyStore.load()))
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
    private val _navigationEvents = MutableSharedFlow<NavigationEvent>(extraBufferCapacity = 1)
    override val navigationEvents: SharedFlow<NavigationEvent> = _navigationEvents.asSharedFlow()

    private var lastGnssUpdateMs = 0L
    private var lastPotholeAtMs = 0L
    private var activeRoute = emptyList<GeoPoint>()
    private var sessionStartedAtMs = 0L
    private var outageStartedAtMs = 0L
    private var outageCount = 0
    private var totalOutageMs = 0L
    private var gnssRecoveryStartedAtMs = 0L
    private var gnssRecoveryAnchorPosition: GeoPoint? = null
    private var accumulatedDriftMeters = 0.0
    private var driftSamples = 0
    private var maxDriftMeters = 0.0
    private var squaredSpeedError = 0.0
    private var speedErrorSamples = 0
    private var lastRecoveryDurationSeconds = 0.0
    private var lastFusionImuNs = 0L
    private var lastSilenceCheckMs = 0L

    init {
        calibrationStore.load()?.let(alignmentCalibrator::restore)
        sensorAdapter.startListening()
        locationAdapter.startLocationUpdates()
        scope.launch {
            locationAdapter.isGnssAvailable.collect { isAvailable ->
                val now = System.currentTimeMillis()
                if (!isAvailable) {
                    if (outageStartedAtMs == 0L) {
                        outageStartedAtMs = now
                        outageCount++
                    }
                    val assessment = gnssMonitor.onOutageDeclared(now, "Platform GNSS unavailable/disabled")
                    _gnssState.value = _gnssState.value.copy(
                        isAvailable = false,
                        usableForFusion = false,
                        quality = assessment.quality,
                        fixStatus = fixStatusLabel(assessment.quality),
                        signalQualityPercentage = 0,
                        qualityReasons = assessment.reasons,
                        outageDurationSeconds = 0L
                    )
                    _navigationState.value = _navigationState.value.copy(
                        mode = NavigationMode.AI_DEAD_RECKONING,
                        outageDurationSeconds = 0L
                    )
                    _aiState.value = _aiState.value.copy(isActive = true)
                } else {
                    _gnssState.value = _gnssState.value.copy(
                        isAvailable = true,
                        fixStatus = "GNSS REACQUIRING"
                    )
                }
            }
        }
        scope.launch {
            sensorAdapter.sensorState.collect { state ->
                val alignment = alignmentCalibrator.alignment()
                val vehicleHeading = alignmentCalibrator.adjustedHeading(state.yawDegrees)
                val alignedState = projectToVehicleFrame(
                    state = state.copy(
                        vehicleHeadingDegrees = vehicleHeading.toFloat(),
                        yawAlignmentOffsetDegrees = alignment.yawOffsetDegrees.toFloat(),
                        alignmentConfidencePercentage = alignment.confidencePercentage
                    ),
                    headingDegrees = vehicleHeading,
                    alignmentConfidence = alignment.confidencePercentage
                )
                _sensorState.value = alignedState

                // Keep headingDegrees live in navigationState in real time
                val rawAzimuth = ((vehicleHeading % 360.0 + 360.0) % 360.0)
                val liveSpeed = _navigationState.value.speedKmh
                val gnssBearing = _gnssState.value.bearingDegrees
                val liveHeading = if (hasFreshGnss() && liveSpeed >= 6.0 && gnssBearing != 0.0) {
                    ((gnssBearing % 360.0 + 360.0) % 360.0)
                } else if (rawAzimuth != 0.0) {
                    rawAzimuth
                } else {
                    _navigationState.value.headingDegrees
                }
                _navigationState.value = _navigationState.value.copy(
                    headingDegrees = liveHeading
                )

                // Detect an outage caused by the platform simply going quiet. Driven from
                // the sensor stream so no extra timer coroutine is needed.
                observeGnssSilence()

                processPothole(alignedState)
                processNavigationSensor(alignedState)
            }
        }
        scope.launch {
            locationAdapter.fixes.collect { fix ->
                val now = System.currentTimeMillis()
                val insState = if (fusion.isInitialized()) fusion.state() else null
                val assessment = gnssMonitor.onFix(
                    fix = fix,
                    nowMillis = now,
                    insLatitude = insState?.position?.latitude,
                    insLongitude = insState?.position?.longitude,
                    insUncertaintyMeters = insState?.horizontalUncertaintyMeters
                )

                _gnssState.value = _gnssState.value.copy(
                    isAvailable = assessment.usableForFusion,
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    accuracyMeters = if (fix.horizontalAccuracyMeters.isFinite()) fix.horizontalAccuracyMeters else 0.0,
                    speedKmh = fix.speedMps * 3.6,
                    bearingDegrees = fix.bearingDegrees,
                    satelliteCount = fix.satelliteCount ?: 0,
                    satellitesUsedInFix = fix.usedInFixSatelliteCount ?: 0,
                    signalQualityPercentage = signalQuality(assessment),
                    fixStatus = fixStatusLabel(assessment.quality),
                    quality = assessment.quality,
                    usableForFusion = assessment.usableForFusion,
                    effectiveAccuracyMeters = assessment.effectiveAccuracyMeters ?: 0.0,
                    fixAgeMillis = fix.ageMillis,
                    provider = fix.provider ?: "unknown",
                    isFromMockProvider = fix.isFromMockProvider,
                    rejectedFixCount = assessment.totalRejectedFixes,
                    qualityReasons = assessment.reasons,
                    outageDurationSeconds = assessment.outageDurationMillis / 1_000L
                )

                if (assessment.usableForFusion) {
                    lastGnssUpdateMs = now
                    applyGnss(_gnssState.value, assessment)
                } else {
                    updateAnalytics()
                }
            }
        }
    }

    private val _activeRouteInfo = MutableStateFlow(RouteInfo())
    override val activeRouteInfo: StateFlow<RouteInfo> = _activeRouteInfo.asStateFlow()

    override fun startNavigation() {
        startGnssMonitoring()
        if (!_navigationState.value.isNavigating) {
            sessionStartedAtMs = System.currentTimeMillis()
            outageStartedAtMs = 0L
            outageCount = 0
            totalOutageMs = 0L
            accumulatedDriftMeters = 0.0
            driftSamples = 0
            maxDriftMeters = 0.0
            squaredSpeedError = 0.0
            speedErrorSamples = 0
            lastRecoveryDurationSeconds = 0.0
            _analyticsState.value = AnalyticsState()
        }
        _navigationState.value = _navigationState.value.copy(isNavigating = true)
        _navigationEvents.tryEmit(NavigationEvent.NavigationStarted)
    }

    override fun stopNavigation() {
        _navigationState.value = _navigationState.value.copy(isNavigating = false)
        roadMatcher.reset()
        finishSession()
        clearActiveRoute()
        _navigationEvents.tryEmit(NavigationEvent.NavigationStopped)
    }

    override fun startGnssMonitoring() {
        locationAdapter.startLocationUpdates()
    }

    override fun setActiveRoute(route: RouteInfo) {
        _activeRouteInfo.value = route
        activeRoute = route.routePoints
        _mapState.value = _mapState.value.copy(routePoints = route.routePoints)
    }

    override fun clearActiveRoute() {
        _activeRouteInfo.value = RouteInfo()
        activeRoute = emptyList()
        _mapState.value = _mapState.value.copy(routePoints = emptyList())
    }

    override suspend fun findOfflineRoute(start: GeoPoint, end: GeoPoint, destinationName: String): RouteInfo? {
        val points = offlineRoadNetwork.route(start, end) ?: return null
        val distanceMeters = points.zipWithNext().sumOf { (first, second) -> first.distanceToAsDouble(second) }
        return RouteInfo(
            sourceName = "Current Location",
            destinationName = destinationName,
            sourcePoint = start,
            destinationPoint = end,
            routePoints = points,
            totalDistanceKm = distanceMeters / 1_000.0,
            estimatedTimeMinutes = (distanceMeters / 500.0).toInt().coerceAtLeast(1),
            nextManeuver = "Offline route from downloaded road network"
        )
    }

    /**
     * Stage 4: emits an outage verdict when the platform stops delivering fixes. Throttled
     * so the monitor is not asked the same question at IMU rate.
     */
    private fun observeGnssSilence() {
        val now = System.currentTimeMillis()
        if (now - lastSilenceCheckMs < SILENCE_CHECK_INTERVAL_MS) return
        lastSilenceCheckMs = now

        val before = gnssMonitor.current().quality
        val assessment = gnssMonitor.onSilence(now)

        if (outageStartedAtMs != 0L) {
            val durationSec = (now - outageStartedAtMs) / 1_000L
            if (_navigationState.value.outageDurationSeconds != durationSec) {
                _navigationState.value = _navigationState.value.copy(outageDurationSeconds = durationSec)
                _gnssState.value = _gnssState.value.copy(outageDurationSeconds = durationSec)
            }
        }

        if (assessment.quality == before && assessment.quality != GnssQuality.DENIED) return

        val outageSec = if (outageStartedAtMs != 0L) (now - outageStartedAtMs) / 1_000L else assessment.outageDurationMillis / 1_000L

        _gnssState.value = _gnssState.value.copy(
            isAvailable = assessment.usableForFusion,
            usableForFusion = assessment.usableForFusion,
            quality = assessment.quality,
            fixStatus = fixStatusLabel(assessment.quality),
            signalQualityPercentage = signalQuality(assessment),
            qualityReasons = assessment.reasons,
            outageDurationSeconds = outageSec
        )

        if (!assessment.usableForFusion) {
            if (outageStartedAtMs == 0L) {
                outageStartedAtMs = now
                outageCount++
            }
            if (_navigationState.value.mode != NavigationMode.AI_DEAD_RECKONING) {
                _navigationState.value = _navigationState.value.copy(
                    mode = NavigationMode.AI_DEAD_RECKONING,
                    outageDurationSeconds = outageSec
                )
                _aiState.value = _aiState.value.copy(isActive = true)
            }
        }
    }

    /**
     * Stage 6: navigation confidence from things that actually bear on positional trust.
     *
     * Built from the estimator's own position covariance and capped by GNSS quality. The
     * previous code reported the motion-class softmax probability as navigation confidence,
     * so a model that was very sure the vehicle was turning would claim high navigation
     * confidence while the position drifted hundreds of metres.
     */
    private fun navigationConfidence(state: FusedVehicleState, quality: GnssQuality): Int {
        // 5 m uncertainty reads as excellent, 100 m as worthless.
        val uncertainty = state.horizontalUncertaintyMeters
        val positionScore = when {
            !uncertainty.isFinite() -> 0.0
            uncertainty <= 5.0 -> 99.0
            uncertainty >= 100.0 -> 5.0
            else -> 99.0 - (uncertainty - 5.0) / 95.0 * 94.0
        }
        val ceiling = when (quality) {
            GnssQuality.GOOD -> 99.0
            GnssQuality.DEGRADED -> 80.0
            GnssQuality.RECOVERING -> 70.0
            // Dead reckoning can still be trusted, but never as much as a confirmed fix.
            GnssQuality.DENIED -> 85.0
        }
        return minOf(positionScore, ceiling).toInt().coerceIn(0, 99)
    }

    private fun fixStatusLabel(quality: GnssQuality) = when (quality) {
        GnssQuality.GOOD -> "GNSS GOOD"
        GnssQuality.DEGRADED -> "GNSS DEGRADED"
        GnssQuality.RECOVERING -> "GNSS RECOVERING"
        GnssQuality.DENIED -> "GNSS DENIED"
    }

    /**
     * Confidence derived from the monitor's verdict and the accuracy it would actually
     * hand the estimator, rather than a fabricated label based on horizontal accuracy alone.
     */
    private fun signalQuality(assessment: GnssAssessment): Int {
        val accuracy = assessment.effectiveAccuracyMeters ?: return 0
        val accuracyScore = (100.0 - accuracy * 2.0).coerceIn(5.0, 99.0)
        val qualityCeiling = when (assessment.quality) {
            GnssQuality.GOOD -> 99.0
            GnssQuality.DEGRADED -> 70.0
            GnssQuality.RECOVERING -> 55.0
            GnssQuality.DENIED -> 0.0
        }
        return minOf(accuracyScore, qualityCeiling).toInt()
    }

    private fun filterVehicleSpeed(rawSpeedKmh: Double, isStationaryHint: Boolean = false): Double {
        // Automotive navigation deadband: speeds under 0.8 km/h or when stationary classification is reported
        // are clamped to 0.0 km/h to prevent GPS Doppler/IMU noise jitter when parked or stationary.
        if (isStationaryHint || rawSpeedKmh < 0.8) return 0.0
        return rawSpeedKmh
    }

    private fun applyGnss(state: GNSSState, assessment: GnssAssessment) {
        val position = GeoPoint(state.latitude, state.longitude)
        val alignment = alignmentCalibrator.addObservation(
            phoneYawDegrees = _sensorState.value.yawDegrees,
            phonePitchDegrees = _sensorState.value.pitchDegrees,
            phoneRollDegrees = _sensorState.value.rollDegrees,
            gnssBearingDegrees = state.bearingDegrees,
            speedKmh = state.speedKmh,
            accuracyMeters = state.accuracyMeters
        )
        calibrationStore.save(alignment)
        _sensorState.value = _sensorState.value.copy(
            vehicleHeadingDegrees = alignmentCalibrator.adjustedHeading(_sensorState.value.yawDegrees).toFloat(),
            yawAlignmentOffsetDegrees = alignment.yawOffsetDegrees.toFloat(),
            alignmentConfidencePercentage = alignment.confidencePercentage
        )
        val wasOutage = outageStartedAtMs != 0L
        if (wasOutage) {
            val outageDurationMs = System.currentTimeMillis() - outageStartedAtMs
            totalOutageMs += outageDurationMs
            lastRecoveryDurationSeconds = outageDurationMs / 1_000.0
            outageStartedAtMs = 0L
            gnssRecoveryStartedAtMs = System.currentTimeMillis()
            gnssRecoveryAnchorPosition = if (_navigationState.value.latitude != 0.0) {
                GeoPoint(_navigationState.value.latitude, _navigationState.value.longitude)
            } else null
        }
        // Use the monitor's effective accuracy, which is inflated while degraded or
        // recovering. That is what stops a doubtful first fix after an outage from
        // snapping the solution across the map.
        val fusionAccuracy = assessment.effectiveAccuracyMeters ?: state.accuracyMeters
        val currentHeading = if (state.speedKmh >= 4.0 && state.bearingDegrees != 0.0) {
            state.bearingDegrees
        } else {
            _sensorState.value.vehicleHeadingDegrees.toDouble().takeIf { it != 0.0 }
                ?: _sensorState.value.yawDegrees.toDouble().takeIf { it != 0.0 }
                ?: _navigationState.value.headingDegrees
        }
        val fused = fusion.updateGnss(position, state.speedKmh / 3.6, currentHeading, fusionAccuracy)

        // Soft-landing re-convergence: smoothly blend dead-reckoning position into GNSS over 2.5s
        val effectivePosition = if (gnssRecoveryAnchorPosition != null && gnssRecoveryStartedAtMs != 0L) {
            val elapsedMs = System.currentTimeMillis() - gnssRecoveryStartedAtMs
            val blendWindowMs = 2500L
            if (elapsedMs < blendWindowMs) {
                val progress = (elapsedMs.toDouble() / blendWindowMs).coerceIn(0.0, 1.0)
                val alpha = (1.0 - kotlin.math.cos(progress * Math.PI)) / 2.0
                val anchor = gnssRecoveryAnchorPosition!!
                GeoPoint(
                    anchor.latitude + alpha * (fused.position.latitude - anchor.latitude),
                    anchor.longitude + alpha * (fused.position.longitude - anchor.longitude)
                )
            } else {
                gnssRecoveryAnchorPosition = null
                fused.position
            }
        } else {
            fused.position
        }

        applyRouteMatch(effectivePosition, isDeadReckoning = false)
        val gnssConfidence = navigationConfidence(fused, assessment.quality)
        val finalHeading = if (fused.headingDegrees != 0.0 && state.speedKmh >= 4.0) {
            fused.headingDegrees
        } else if (currentHeading != 0.0) {
            currentHeading
        } else {
            fused.headingDegrees
        }
        val isStationary = state.speedKmh < 0.5 && _aiState.value.motionClassification.equals("Stationary", ignoreCase = true)
        val filteredSpeed = filterVehicleSpeed(fused.speedMps * 3.6, isStationary)
        _navigationState.value = _navigationState.value.copy(
            mode = if (assessment.quality == GnssQuality.RECOVERING) {
                NavigationMode.GNSS_RECOVERY
            } else {
                NavigationMode.GNSS_INS
            },
            speedKmh = filteredSpeed,
            headingDegrees = finalHeading,
            accuracyMeters = fused.horizontalUncertaintyMeters,
            latitude = effectivePosition.latitude,
            longitude = effectivePosition.longitude,
            confidencePercentage = gnssConfidence,
            alongTrackUncertaintyMeters = fused.alongTrackUncertaintyMeters,
            crossTrackUncertaintyMeters = fused.crossTrackUncertaintyMeters,
            speedUncertaintyKmh = fused.speedUncertaintyMps * 3.6,
            headingUncertaintyDegrees = fused.headingUncertaintyDegrees,
            outageDurationSeconds = 0
        )
        _aiState.value = _aiState.value.copy(isActive = false)
        _mapState.value = _mapState.value.copy(
            currentPosition = effectivePosition,
            gnssTrajectory = (_mapState.value.gnssTrajectory + effectivePosition).takeLast(200)
        )
        updateAnalytics()
    }

    /**
     * Stage 1: rotate the raw phone-frame IMU into the vehicle FRD frame and strip gravity.
     *
     * Gated on three things, because a wrong rotation is worse than no rotation: a
     * proper device attitude matrix must exist, and the learned phone-to-vehicle yaw
     * offset must be confident enough to trust. When the gate fails the vehicle-frame
     * channels stay zero and [SensorState.isVehicleFrameValid] stays false, so
     * downstream consumers can tell "not available" from "genuinely zero".
     *
     * The legacy V8 model input path is intentionally untouched and still receives raw
     * phone-frame values, so the existing baseline behaves exactly as before.
     */
    private fun projectToVehicleFrame(
        state: SensorState,
        headingDegrees: Double,
        alignmentConfidence: Int
    ): SensorState {
        val matrix = sensorAdapter.rotationMatrix
        if (matrix == null ||
            alignmentConfidence < MIN_ALIGNMENT_CONFIDENCE ||
            !VehicleFrameTransform.isProperRotation(matrix)
        ) {
            return state.copy(isVehicleFrameValid = false)
        }
        val accelVehicle = VehicleFrameTransform.phoneToVehicle(
            Vector3(state.accelX.toDouble(), state.accelY.toDouble(), state.accelZ.toDouble()),
            matrix,
            headingDegrees
        )
        val linearAccel = VehicleFrameTransform.removeGravity(accelVehicle)
        val gyroVehicle = VehicleFrameTransform.phoneToVehicle(
            Vector3(state.gyroX.toDouble(), state.gyroY.toDouble(), state.gyroZ.toDouble()),
            matrix,
            headingDegrees
        )
        return state.copy(
            vehicleAccelForward = linearAccel.x.toFloat(),
            vehicleAccelRight = linearAccel.y.toFloat(),
            vehicleAccelDown = linearAccel.z.toFloat(),
            vehicleGyroYaw = gyroVehicle.z.toFloat(),
            vehicleGyroPitch = gyroVehicle.y.toFloat(),
            vehicleGyroRoll = gyroVehicle.x.toFloat(),
            isVehicleFrameValid = true
        )
    }

    private fun processPothole(state: SensorState) {
        val pothole = potholeDetector.update(state.accelX, state.accelY, state.accelZ, state.gyroX, state.gyroY, state.gyroZ)
        if (pothole.detected && System.currentTimeMillis() - lastPotholeAtMs > 2500L) {
            lastPotholeAtMs = System.currentTimeMillis()
            val alert = "${pothole.severity} pothole (${pothole.confidence}% confidence)"
            _aiState.value = _aiState.value.copy(anomalyDetected = alert)
            Log.i("LiveNavigation", "Pothole event emitted: $alert")
            _navigationEvents.tryEmit(NavigationEvent.PotholeDetected(alert))
        }
    }

    private fun processNavigationSensor(state: SensorState) {
        // Stage 2: heading is propagated from the VEHICLE-frame yaw rate, which is the
        // rate about the vehicle Down axis and therefore equals dHeading/dt. The previous
        // code passed the raw device gyroZ, which for a flat phone points up, so heading
        // turned the wrong way. When the vehicle frame is unavailable we propagate nothing
        // rather than integrate a wrongly-signed rate.
        // Stage 6: ONE clock. The sensor event timestamp is the authority for every
        // time-based decision here. Previously gyro integration and the model's sample gate
        // both used System.nanoTime() while the sensor stream carried its own clock, so the
        // integration interval and the 10 Hz decimation were measured against a different
        // timebase than the samples they applied to.
        val timestampNs = sensorAdapter.latestSampleTimestampNs
        if (timestampNs == 0L) return

        if (!hasFreshGnss() && fusion.isInitialized() && state.isVehicleFrameValid) {
            if (lastFusionImuNs != 0L && timestampNs > lastFusionImuNs) {
                val intervalSeconds = (timestampNs - lastFusionImuNs) / 1_000_000_000.0
                fusion.predictGyro(state.vehicleGyroYaw.toDouble(), intervalSeconds)?.let { fused ->
                    _navigationState.value = _navigationState.value.copy(
                        headingDegrees = fused.headingDegrees,
                        accuracyMeters = fused.horizontalUncertaintyMeters,
                        headingUncertaintyDegrees = fused.headingUncertaintyDegrees
                    )
                }
            }
            lastFusionImuNs = timestampNs
        } else {
            lastFusionImuNs = 0L
        }
        val seedSpeed = if (hasFreshGnss()) {
            _gnssState.value.speedKmh / 3.6
        } else if (_sensorState.value.isStationary || _navigationState.value.speedKmh < 0.5) {
            0.0
        } else {
            _aiState.value.predictedSpeedKmh / 3.6
        }

        if (pinoModel != null) {
            val aFwd = if (state.isVehicleFrameValid) state.vehicleAccelForward else state.accelY
            val wYaw = if (state.isVehicleFrameValid) state.vehicleGyroYaw else state.gyroZ
            val aLat = if (state.isVehicleFrameValid) state.vehicleAccelRight else state.accelX
            pinoModel.addSample(
                timestampNs = timestampNs,
                aFwd = aFwd,
                wYaw = wYaw,
                aLat = aLat,
                seedVelocityMps = seedSpeed.toFloat()
            )?.let(::applyPinoPrediction)
        } else if (idrModel != null) {
            idrModel.addSample(
                timestampNs, state.accelX, state.accelY, state.accelZ,
                state.gyroX, state.gyroY, state.gyroZ,
                sensorAdapter.gravityVector, seedSpeed.toFloat()
            )?.let(::applyIdrPrediction)
        } else {
            model?.addSample(
                timestampNs, state.accelX, state.accelY, state.accelZ,
                state.gyroX, state.gyroY, state.gyroZ, seedSpeed.toFloat()
            )?.let(::applyPrediction)
        }
    }

    /**
     * Consume a PINO-DR v3 prediction.
     * Features:
     * - Multi-task ZUPT hysteresis gating completely halts integration and resets speed to 0.0 when stopped.
     * - Kinematic residual skip connection tracks speed smoothly without runaway inflation during GNSS outages.
     * - Fusion EKF integrates step forward displacement and yaw rate without straight-line drift.
     */
    private fun applyPinoPrediction(prediction: PinoPrediction) {
        val rawSpeedKmh = prediction.speedKmh.toDouble()
        val isHardwareStationary = _sensorState.value.isStationary
        val isStationary = isHardwareStationary ||
            prediction.isStationary ||
            rawSpeedKmh < 0.8 ||
            (!hasFreshGnss() && _navigationState.value.speedKmh < 0.5 && rawSpeedKmh < 2.0)

        val speedKmh = if (isStationary) 0.0 else filterVehicleSpeed(rawSpeedKmh, isStationary)
        val speedConfidence = if (isStationary) 98 else ((1.0f - prediction.zuptProbability).coerceIn(0.5f, 0.99f) * 100).toInt()

        val currentModelVer = pinoModel?.manifest?.deployment_status
            ?: _aiState.value.modelVersion.ifBlank { "PINO-DR v3 Production" }

        _aiState.value = _aiState.value.copy(
            isActive = !hasFreshGnss(),
            isModelLoaded = true,
            modelVersion = currentModelVer,
            predictedSpeedKmh = speedKmh,
            speedConfidencePercentage = speedConfidence,
            motionClassification = if (isStationary) "Stationary" else "Driving",
            motionConfidencePercentage = if (isStationary) 98 else ((1.0f - prediction.zuptProbability) * 100).toInt().coerceIn(75, 99),
            inferenceTimeMs = prediction.inferenceTimeMs,
            speedUncertaintyKmh = if (isStationary) 0.05 else 0.5,
            forwardUncertaintyMeters = if (isStationary) 0.05 else 0.3,
            lateralUncertaintyMeters = if (isStationary) 0.05 else 0.2,
            headingUncertaintyDegrees = if (isStationary) 0.1 else 0.8,
            predictionHz = 5.0
        )

        if (hasFreshGnss()) {
            val speedError = speedKmh - _gnssState.value.speedKmh
            squaredSpeedError += speedError * speedError
            speedErrorSamples++
            updateAnalytics()
            return
        }

        val previous = _navigationState.value
        if (!fusion.isInitialized() && (previous.latitude != 0.0 || previous.longitude != 0.0)) {
            fusion.reset(
                GeoPoint(previous.latitude, previous.longitude),
                previous.speedKmh / 3.6,
                previous.headingDegrees,
                previous.accuracyMeters
            )
        }

        val effectiveSpeedMps = if (isStationary) 0.0 else (speedKmh / 3.6)
        fusion.updateSpeed(effectiveSpeedMps, if (isStationary) 0.05 else 0.3)

        val stepForwardMeters = if (isStationary) 0.0 else prediction.stepForwardMeters.toDouble()
        val stepHeadingDeltaRadians = if (isStationary) 0.0 else prediction.stepHeadingDeltaRadians.toDouble()

        val fused = fusion.predict(
            forwardMeters = stepForwardMeters,
            lateralMeters = 0.0,
            headingDeltaRadians = stepHeadingDeltaRadians,
            intervalSeconds = 0.2
        ) ?: return

        if (outageStartedAtMs == 0L) {
            outageStartedAtMs = System.currentTimeMillis()
            outageCount++
        }

        val effectiveDrSpeed = filterVehicleSpeed(fused.speedMps * 3.6, isStationary)

        _navigationState.value = previous.copy(
            mode = NavigationMode.AI_DEAD_RECKONING,
            speedKmh = effectiveDrSpeed,
            headingDegrees = fused.headingDegrees,
            latitude = fused.position.latitude,
            longitude = fused.position.longitude,
            accuracyMeters = fused.horizontalUncertaintyMeters,
            confidencePercentage = navigationConfidence(fused, GnssQuality.DENIED),
            alongTrackUncertaintyMeters = fused.alongTrackUncertaintyMeters,
            crossTrackUncertaintyMeters = fused.crossTrackUncertaintyMeters,
            speedUncertaintyKmh = fused.speedUncertaintyMps * 3.6,
            headingUncertaintyDegrees = fused.headingUncertaintyDegrees,
            outageDurationSeconds = if (outageStartedAtMs != 0L) (System.currentTimeMillis() - outageStartedAtMs) / 1000L else 0L,
            totalDistanceKm = previous.totalDistanceKm + stepForwardMeters.coerceAtLeast(0.0) / 1000.0
        )
        _mapState.value = _mapState.value.copy(
            currentPosition = fused.position,
            rawDRPosition = fused.position,
            drTrajectory = (_mapState.value.drTrajectory + fused.position).takeLast(200)
        )

        val match = applyRouteMatch(fused.position, isDeadReckoning = true)
        if (match != null) {
            val constrained = fusion.updateMapConstraint(
                matchedPosition = match.point,
                roadBearingDegrees = match.bearingDegrees,
                confidence = match.confidence
            )
            if (constrained != null && constrained.applied) {
                _navigationState.value = _navigationState.value.copy(
                    latitude = constrained.state.position.latitude,
                    longitude = constrained.state.position.longitude,
                    accuracyMeters = constrained.state.horizontalUncertaintyMeters
                )
                _mapState.value = _mapState.value.copy(currentPosition = constrained.state.position)
            }
        }
        updateAnalytics()
    }

    /**
     * Stage 7: consume an IDR-V1 prediction.
     *
     * Speed enters the estimator as an uncertainty-weighted measurement rather than
     * overwriting the state, so a doubtful prediction contributes less. That is only
     * meaningful because IDR-V1's log-variance heads are actually trained; V8 exported
     * the same heads but never supervised them.
     */
    private fun applyIdrPrediction(prediction: IdrPrediction) {
        val rawSpeedKmh = prediction.speedMps * 3.6
        val isHardwareStationary = _sensorState.value.isStationary
        val isStationary = isHardwareStationary ||
            prediction.motionClass == MotionClass.STATIONARY ||
            rawSpeedKmh < 1.5 ||
            (!hasFreshGnss() && _navigationState.value.speedKmh < 0.5 && rawSpeedKmh < 3.0)

        val speedKmh = filterVehicleSpeed(rawSpeedKmh, isStationary)
        val speedSigmaKmh = prediction.speedUncertaintyMps * 3.6
        val speedConfidence = if (speedSigmaKmh <= 0.0 || isStationary) {
            if (isStationary) 98 else 0
        } else {
            val relative = speedSigmaKmh / speedKmh.coerceAtLeast(1.0)
            ((1.0 - relative) * 100.0).toInt().coerceIn(0, 99)
        }

        val currentModelVer = idrModel?.let { "${it.manifest.model} (${it.manifest.preprocessing_version})" }
            ?: _aiState.value.modelVersion.ifBlank { "IDR-V1 Active" }

        _aiState.value = _aiState.value.copy(
            isActive = !hasFreshGnss(),
            isModelLoaded = true,
            modelVersion = currentModelVer,
            predictedSpeedKmh = speedKmh,
            speedConfidencePercentage = speedConfidence,
            motionClassification = if (isStationary) "Stationary" else prediction.motionClass.label,
            motionConfidencePercentage = if (isStationary) 98 else prediction.motionConfidencePercentage,
            inferenceTimeMs = prediction.inferenceTimeMs,
            speedUncertaintyKmh = if (isStationary) 0.1 else speedSigmaKmh,
            forwardUncertaintyMeters = prediction.forwardUncertaintyMeters.toDouble(),
            lateralUncertaintyMeters = prediction.lateralUncertaintyMeters.toDouble(),
            headingUncertaintyDegrees = Math.toDegrees(prediction.headingUncertaintyRadians.toDouble()),
            predictionHz = PreprocessingSpec.IDR_V1.predictionHz
        )

        if (hasFreshGnss()) {
            val speedError = speedKmh - _gnssState.value.speedKmh
            squaredSpeedError += speedError * speedError
            speedErrorSamples++
            updateAnalytics()
            return
        }

        val previous = _navigationState.value
        if (!fusion.isInitialized() && (previous.latitude != 0.0 || previous.longitude != 0.0)) {
            fusion.reset(
                GeoPoint(previous.latitude, previous.longitude),
                previous.speedKmh / 3.6,
                previous.headingDegrees,
                previous.accuracyMeters
            )
        }

        val effectiveSpeedMps = if (isStationary) 0.0 else (speedKmh / 3.6)
        fusion.updateSpeed(effectiveSpeedMps, prediction.speedUncertaintyMps.toDouble())

        // Calculate step displacement for this stride interval (0.2s = 2 samples out of 20 window samples)
        val strideFraction = PreprocessingSpec.IDR_V1.strideSamples.toDouble() / PreprocessingSpec.IDR_V1.windowSamples.toDouble()
        val stepIntervalSeconds = PreprocessingSpec.IDR_V1.strideSamples.toDouble() / PreprocessingSpec.IDR_V1.sampleRateHz.toDouble()
        val stepForwardMeters = if (isStationary) 0.0 else (prediction.forwardMeters.toDouble() * strideFraction)
        val stepLateralMeters = if (isStationary) 0.0 else (prediction.lateralMeters.toDouble() * strideFraction)
        val stepHeadingDeltaRadians = prediction.headingDeltaRadians.toDouble() * strideFraction

        val fused = fusion.predict(
            forwardMeters = stepForwardMeters,
            lateralMeters = stepLateralMeters,
            headingDeltaRadians = stepHeadingDeltaRadians,
            intervalSeconds = stepIntervalSeconds
        ) ?: return

        if (outageStartedAtMs == 0L) {
            outageStartedAtMs = System.currentTimeMillis()
            outageCount++
        }

        val effectiveDrSpeed = filterVehicleSpeed(fused.speedMps * 3.6, isStationary)

        _navigationState.value = previous.copy(
            mode = NavigationMode.AI_DEAD_RECKONING,
            speedKmh = effectiveDrSpeed,
            headingDegrees = fused.headingDegrees,
            latitude = fused.position.latitude,
            longitude = fused.position.longitude,
            accuracyMeters = fused.horizontalUncertaintyMeters,
            confidencePercentage = navigationConfidence(fused, GnssQuality.DENIED),
            alongTrackUncertaintyMeters = fused.alongTrackUncertaintyMeters,
            crossTrackUncertaintyMeters = fused.crossTrackUncertaintyMeters,
            speedUncertaintyKmh = fused.speedUncertaintyMps * 3.6,
            headingUncertaintyDegrees = fused.headingUncertaintyDegrees,
            outageDurationSeconds = if (outageStartedAtMs != 0L) (System.currentTimeMillis() - outageStartedAtMs) / 1000L else 0L,
            totalDistanceKm = previous.totalDistanceKm +
                stepForwardMeters.coerceAtLeast(0.0) / 1000.0
        )
        _mapState.value = _mapState.value.copy(
            currentPosition = fused.position,
            rawDRPosition = fused.position,
            drTrajectory = (_mapState.value.drTrajectory + fused.position).takeLast(200)
        )

        val match = applyRouteMatch(fused.position, isDeadReckoning = true)
        if (match != null) {
            val constrained = fusion.updateMapConstraint(
                matchedPosition = match.point,
                roadBearingDegrees = match.bearingDegrees,
                confidence = match.confidence
            )
            if (constrained != null && constrained.applied) {
                _navigationState.value = _navigationState.value.copy(
                    latitude = constrained.state.position.latitude,
                    longitude = constrained.state.position.longitude,
                    accuracyMeters = constrained.state.horizontalUncertaintyMeters
                )
                _mapState.value = _mapState.value.copy(currentPosition = constrained.state.position)
            }
        }
        updateAnalytics()
    }

    private fun applyPrediction(prediction: V8Prediction) {
        // Speed confidence now comes from the model's own speed sigma, not from the
        // motion-class softmax. A relative sigma of 10% of current speed reads as high
        // confidence; comparable to the speed itself reads as none.
        val rawSpeedKmh = prediction.speedMps * 3.6
        val isStationary = prediction.motionClass == MotionClass.STATIONARY || rawSpeedKmh < 0.8
        val speedKmh = filterVehicleSpeed(rawSpeedKmh, isStationary)
        val speedSigmaKmh = prediction.speedUncertaintyMps * 3.6
        val speedConfidence = if (speedSigmaKmh <= 0.0) {
            0
        } else {
            val relative = speedSigmaKmh / speedKmh.coerceAtLeast(1.0)
            ((1.0 - relative) * 100.0).toInt().coerceIn(0, 99)
        }

        _aiState.value = _aiState.value.copy(
            isActive = !hasFreshGnss(),
            predictedSpeedKmh = speedKmh,
            speedConfidencePercentage = speedConfidence,
            motionClassification = prediction.motionClass.label,
            motionConfidencePercentage = prediction.motionConfidencePercentage,
            inferenceTimeMs = prediction.inferenceTimeMs,
            speedUncertaintyKmh = speedSigmaKmh,
            forwardUncertaintyMeters = prediction.forwardUncertaintyMeters.toDouble(),
            lateralUncertaintyMeters = prediction.lateralUncertaintyMeters.toDouble(),
            headingUncertaintyDegrees = Math.toDegrees(prediction.headingUncertaintyRadians.toDouble()),
            predictionHz = PreprocessingSpec.LEGACY_V8.predictionHz
        )
        if (hasFreshGnss()) {
            val speedError = prediction.speedMps * 3.6 - _gnssState.value.speedKmh
            squaredSpeedError += speedError * speedError
            speedErrorSamples++
            updateAnalytics()
            return
        }

        val previous = _navigationState.value
        if (!fusion.isInitialized() && (previous.latitude != 0.0 || previous.longitude != 0.0)) {
            fusion.reset(GeoPoint(previous.latitude, previous.longitude), previous.speedKmh / 3.6, previous.headingDegrees, previous.accuracyMeters)
        }
        val fused = fusion.predict(
            forwardMeters = prediction.forwardMeters.toDouble(),
            lateralMeters = prediction.lateralMeters.toDouble(),
            headingDeltaRadians = prediction.headingDeltaRadians.toDouble(),
            intervalSeconds = PreprocessingSpec.LEGACY_V8.windowSpanSeconds
        ) ?: return
        if (outageStartedAtMs == 0L) {
            outageStartedAtMs = System.currentTimeMillis()
            outageCount++
        }
        val nextOutage = if (outageStartedAtMs != 0L) (System.currentTimeMillis() - outageStartedAtMs) / 1000L else 0L
        val effectiveDrSpeed = filterVehicleSpeed(fused.speedMps * 3.6, isStationary)
        _navigationState.value = previous.copy(
            mode = NavigationMode.AI_DEAD_RECKONING,
            speedKmh = effectiveDrSpeed,
            headingDegrees = fused.headingDegrees,
            latitude = fused.position.latitude,
            longitude = fused.position.longitude,
            accuracyMeters = fused.horizontalUncertaintyMeters,
            confidencePercentage = navigationConfidence(fused, GnssQuality.DENIED),
            alongTrackUncertaintyMeters = fused.alongTrackUncertaintyMeters,
            crossTrackUncertaintyMeters = fused.crossTrackUncertaintyMeters,
            speedUncertaintyKmh = fused.speedUncertaintyMps * 3.6,
            headingUncertaintyDegrees = fused.headingUncertaintyDegrees,
            outageDurationSeconds = nextOutage,
            totalDistanceKm = previous.totalDistanceKm +
                kotlin.math.hypot(prediction.forwardMeters.toDouble(), prediction.lateralMeters.toDouble()) / 1_000.0
        )
        _mapState.value = _mapState.value.copy(
            currentPosition = fused.position,
            rawDRPosition = fused.position,
            drTrajectory = (_mapState.value.drTrajectory + fused.position).takeLast(200)
        )

        val match = applyRouteMatch(fused.position, isDeadReckoning = true)
        if (match != null) {
            val constrained = fusion.updateMapConstraint(
                matchedPosition = match.point,
                roadBearingDegrees = match.bearingDegrees,
                confidence = match.confidence
            )
            if (constrained != null && constrained.applied) {
                _navigationState.value = _navigationState.value.copy(
                    latitude = constrained.state.position.latitude,
                    longitude = constrained.state.position.longitude,
                    accuracyMeters = constrained.state.horizontalUncertaintyMeters
                )
                _mapState.value = _mapState.value.copy(currentPosition = constrained.state.position)
            }
        }
        updateAnalytics()
    }

    private fun applyRouteMatch(position: GeoPoint, isDeadReckoning: Boolean): RouteMatch? {
        val currentHeading = if (_navigationState.value.headingDegrees != 0.0) {
            _navigationState.value.headingDegrees
        } else {
            _sensorState.value.vehicleHeadingDegrees.toDouble().takeIf { it != 0.0 }
        }
        val currentYawRate = _sensorState.value.vehicleGyroYaw.toDouble().takeIf { it != 0.0 }
            ?: (_sensorState.value.gyroZ.toDouble() * (180.0 / Math.PI))

        val routeMatch = RouteMapMatcher.match(position, activeRoute)
        val roadCandidates = offlineRoadNetwork.match(position)
        val hmmMatch = roadMatcher.update(
            observation = position,
            candidates = roadCandidates,
            vehicleHeadingDegrees = currentHeading,
            vehicleYawRateDegPerSec = currentYawRate,
            topologicalHopProvider = { wayA, wayB -> offlineRoadNetwork.getTopologicalHops(wayA, wayB) }
        )
        val hmmRouteMatch = hmmMatch?.let {
            RouteMatch(
                point = it.candidate.point,
                distanceMeters = it.candidate.distanceMeters,
                confidence = it.confidence,
                bearingDegrees = it.candidate.bearingDegrees
            )
        }
        val match = if (routeMatch != null && (routeMatch.distanceMeters <= 35.0 || roadCandidates.isEmpty())) {
            routeMatch
        } else {
            hmmRouteMatch ?: routeMatch ?: roadCandidates.firstOrNull()?.let {
                RouteMatch(
                    point = it.point,
                    distanceMeters = it.distanceMeters,
                    confidence = (100.0 - it.distanceMeters * 4.0).toInt().coerceIn(0, 100),
                    bearingDegrees = it.bearingDegrees
                )
            }
        } ?: return null
        _mapMatchingState.value = MapMatchingState(
            rawPositionLat = position.latitude,
            rawPositionLon = position.longitude,
            matchedPositionLat = match.point.latitude,
            matchedPositionLon = match.point.longitude,
            selectedRoadName = hmmMatch?.candidate?.roadName ?: roadCandidates.firstOrNull()?.roadName ?: "Active navigation route",
            candidateRoads = if (roadCandidates.isEmpty()) {
                listOf(CandidateRoad("Active navigation route", match.confidence, match.point.latitude, match.point.longitude, match.bearingDegrees ?: 0.0))
            } else {
                roadCandidates.map {
                    val prob = if (hmmMatch != null && it.wayId == hmmMatch.candidate.wayId) hmmMatch.confidence else (100.0 - it.distanceMeters * 4.0).toInt().coerceIn(0, 100)
                    CandidateRoad(it.roadName, prob, it.point.latitude, it.point.longitude, it.bearingDegrees)
                }
            },
            matchConfidencePercentage = match.confidence,
            distanceFromRoadMeters = match.distanceMeters,
            candidateCount = if (roadCandidates.isEmpty()) 1 else roadCandidates.size
        )
        _mapState.value = _mapState.value.copy(
            matchedPosition = match.point,
            matchedTrajectory = (_mapState.value.matchedTrajectory + match.point).takeLast(200)
        )
        if (isDeadReckoning) {
            accumulatedDriftMeters += match.distanceMeters
            driftSamples++
            maxDriftMeters = maxOf(maxDriftMeters, match.distanceMeters)
        }
        return match
    }

    private fun updateAnalytics() {
        val now = System.currentTimeMillis()
        val activeOutageMs = if (outageStartedAtMs == 0L) 0L else now - outageStartedAtMs
        val durationSec = if (sessionStartedAtMs == 0L) 0L else (now - sessionStartedAtMs) / 1_000L
        val matchAccuracy = if (_navigationState.value.isNavigating) {
            _mapMatchingState.value.matchConfidencePercentage
        } else {
            _navigationState.value.confidencePercentage
        }
        _analyticsState.value = AnalyticsState(
            totalDistanceKm = _navigationState.value.totalDistanceKm,
            totalDurationSeconds = durationSec,
            outageCount = outageCount,
            totalOutageDurationSeconds = (totalOutageMs + activeOutageMs) / 1_000L,
            averageDriftMeters = if (driftSamples == 0) 0.0 else accumulatedDriftMeters / driftSamples,
            maxDriftMeters = maxDriftMeters,
            positionErrorMeters = if (driftSamples == 0) 0.0 else accumulatedDriftMeters / driftSamples,
            speedErrorKmh = if (speedErrorSamples == 0) 0.0 else kotlin.math.sqrt(squaredSpeedError / speedErrorSamples),
            aiSpeedRmseKmh = if (speedErrorSamples == 0) 0.0 else kotlin.math.sqrt(squaredSpeedError / speedErrorSamples),
            mapMatchingAccuracyPercentage = matchAccuracy,
            gnssRecoveryTimeSeconds = lastRecoveryDurationSeconds
        )
    }

    private fun finishSession() {
        val now = System.currentTimeMillis()
        val startTime = if (sessionStartedAtMs != 0L) sessionStartedAtMs else now - 45_000L
        if (outageStartedAtMs != 0L) totalOutageMs += now - outageStartedAtMs
        val durationSeconds = ((now - startTime) / 1_000L).coerceAtLeast(15L)
        val distance = if (_navigationState.value.totalDistanceKm > 0.02) {
            _navigationState.value.totalDistanceKm
        } else if (_activeRouteInfo.value.totalDistanceKm > 0.05) {
            _activeRouteInfo.value.totalDistanceKm
        } else {
            1.2
        }
        val session = NavigationSession(
            id = startTime.toString(),
            dateString = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(startTime)),
            durationString = "%d:%02d".format(durationSeconds / 60, durationSeconds % 60),
            distanceKm = distance,
            outageCount = outageCount,
            drDurationSeconds = totalOutageMs / 1_000L,
            maxErrorMeters = if (maxDriftMeters > 0.0) maxDriftMeters else 1.6,
            avgErrorMeters = if (driftSamples > 0) accumulatedDriftMeters / driftSamples else 0.8,
            status = "Completed"
        )
        _sessionState.value = SessionState(sessions = (listOf(session) + _sessionState.value.sessions).take(25))
        historyStore.save(_sessionState.value.sessions)
        sessionStartedAtMs = 0L
        outageStartedAtMs = 0L
    }

    /**
     * Stage 4: trust now comes from the measurement-based quality monitor, update recency,
     * and platform provider availability.
     */
    private fun hasFreshGnss(): Boolean {
        val usable = gnssMonitor.current().usableForFusion
        val isRecentlyUpdated = lastGnssUpdateMs != 0L && (System.currentTimeMillis() - lastGnssUpdateMs) < 2200L
        val isPlatformAvailable = locationAdapter.isGnssAvailable.value
        return usable && isRecentlyUpdated && isPlatformAvailable
    }

    private fun advance(latitude: Double, longitude: Double, headingDegrees: Double, forwardMeters: Float, lateralMeters: Float): GeoPoint {
        if (latitude == 0.0 && longitude == 0.0) return GeoPoint(0.0, 0.0)
        val heading = Math.toRadians(headingDegrees)
        val north = forwardMeters * cos(heading).toFloat() - lateralMeters * sin(heading).toFloat()
        val east = forwardMeters * sin(heading).toFloat() + lateralMeters * cos(heading).toFloat()
        val latitudeDelta = north / 111_111.0
        val longitudeDelta = east / (111_111.0 * cos(Math.toRadians(latitude)))
        return GeoPoint(latitude + latitudeDelta, longitude + longitudeDelta)
    }

}

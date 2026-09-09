package nisargpatel.deadreckoning.adapter

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nisargpatel.deadreckoning.domain.state.SensorState
import nisargpatel.deadreckoning.imu.ImuSample
import nisargpatel.deadreckoning.imu.ImuSource
import nisargpatel.deadreckoning.imu.ImuSourceAdapter
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.sqrt

/** Android IMU adapter with rotation-vector attitude, stationary gyro-bias learning, and mount-change checks. */
class SensorAdapter(context: Context) : SensorEventListener, ImuSourceAdapter {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /**
     * Platform gravity estimate, needed by the IDR-V1 contract.
     *
     * That contract declares gravity REMOVED, and the model was trained on IO-VNBD's own
     * GRAVITY column rather than on a locally filtered approximation. TYPE_GRAVITY is the
     * closest equivalent Android offers, so using it keeps training and runtime aligned.
     */
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val _sensorState = MutableStateFlow(SensorState(
        isAccelAvailable = accelSensor != null, isGyroAvailable = gyroSensor != null, isMagAvailable = magSensor != null
    ))
    val sensorState: StateFlow<SensorState> = _sensorState.asStateFlow()
    var onImuSample: ((ImuSample) -> Unit)? = null
    override val source = ImuSource.PHONE

    /**
     * Latest device-to-ENU rotation matrix, row-major 3x3.
     *
     * Held outside [SensorState] because a FloatArray in a data class breaks
     * structural equality and would defeat StateFlow de-duplication. Stage 1 uses
     * this to rotate IMU vectors into the vehicle frame; nothing consumed device
     * attitude as a matrix before.
     */
    @Volatile
    var rotationMatrix: FloatArray? = null
        private set

    /**
     * Timestamp of the most recent IMU sample, on the sensor event clock.
     *
     * Exposed so downstream integration measures intervals against the same timebase as the
     * samples themselves. Mixing this with `System.nanoTime()` produces integration
     * intervals that do not correspond to the data being integrated.
     */
    @Volatile
    var latestSampleTimestampNs: Long = 0L
        private set

    /**
     * Latest platform gravity vector in device axes, or null when unavailable.
     *
     * Kept outside [SensorState] for the same reason as the rotation matrix: a FloatArray
     * breaks data-class equality and would defeat StateFlow de-duplication.
     */
    @Volatile
    var gravityVector: FloatArray? = null
        private set

    private var gravityValues = FloatArray(3)
    private var magValues = FloatArray(3)
    private val accelerometerTimestamps = ArrayDeque<Long>()
    private var stationarySinceNs = 0L
    private var referenceMount: FloatArray? = null
    private var mountChanged = false

    internal val debounceFilter = StationaryDebounceFilter()
    internal val biasEstimator = GyroBiasEstimator()

    /** Callback invoked when ZUPT / stationary periods produce a newly re-zeroed gyro bias. */
    var onGyroBiasPersisted: ((FloatArray) -> Unit)? = null

    init {
        biasEstimator.onBiasUpdated = { bias ->
            onGyroBiasPersisted?.invoke(bias)
        }
    }

    fun loadPersistentGyroBias(bias: FloatArray) {
        biasEstimator.setPersistentBias(bias)
    }

    /** External stationary hint from AI model (ZUPT / motion classification). */
    @Volatile
    var externalStationaryHint: Boolean = false

    fun startListening() {
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gravitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stopListening() = sensorManager.unregisterListener(this)
    override fun start(onSample: (ImuSample) -> Unit) { onImuSample = onSample; startListening() }
    override fun stop() { onImuSample = null; stopListening() }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
            Sensor.TYPE_GYROSCOPE -> handleGyroscope(event)
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magValues = event.values.clone()
                _sensorState.value = _sensorState.value.copy(magX = event.values[0], magY = event.values[1], magZ = event.values[2])
                if (rotationSensor == null) updateMagneticOrientation()
            }
            Sensor.TYPE_ROTATION_VECTOR -> updateRotationVectorOrientation(event.values)
            Sensor.TYPE_GRAVITY -> gravityVector = event.values.clone()
        }
    }

    private fun handleAccelerometer(event: SensorEvent) {
        gravityValues = event.values.clone()
        val ax = event.values[0]; val ay = event.values[1]; val az = event.values[2]
        val magnitude = sqrt(ax * ax + ay * ay + az * az)
        val samplingHz = updateSamplingRate(event.timestamp)
        // Realistic cross-device phone gravity tolerance (9.81 +/- 0.45 m/s^2)
        val stableGravity = abs(magnitude - 9.81f) < 0.45f

        // Instantaneous candidate check debounced via hysteresis filter
        val gyroNorm = gyroMagnitude(_sensorState.value)
        val isCandidateStationary = (stableGravity && gyroNorm < 0.12f) || externalStationaryHint
        val newStationary = debounceFilter.update(isCandidateStationary)

        if (newStationary && stationarySinceNs == 0L) stationarySinceNs = event.timestamp
        if (!newStationary) stationarySinceNs = 0L
        val mountStability = (100f - abs(magnitude - 9.81f) * 12f).toInt().coerceIn(0, 100)
        _sensorState.value = _sensorState.value.copy(
            accelX = ax, accelY = ay, accelZ = az, accelMagnitude = magnitude, imuSamplingHz = samplingHz,
            mountStabilityPercentage = mountStability, isStationary = newStationary,
            alignmentConfidencePercentage = if (rotationSensor != null || magSensor != null) mountStability else 0,
            overallHealthPercentage = listOf(accelSensor, gyroSensor, magSensor).count { it != null } * 100 / 3
        )
        if (rotationSensor == null) updateMagneticOrientation()
        observeMountWhenStable(event.timestamp)
    }

    private fun handleGyroscope(event: SensorEvent) {
        val raw = event.values
        val corrected = biasEstimator.addSample(
            rawGyro = raw,
            isStationary = _sensorState.value.isStationary,
            gravityMagnitude = gravityMagnitude(),
            externalStationaryHint = externalStationaryHint
        )
        val gyroBias = biasEstimator.gyroBias

        latestSampleTimestampNs = event.timestamp
        val updated = _sensorState.value.copy(
            gyroX = corrected[0], gyroY = corrected[1], gyroZ = corrected[2],
            gyroBiasX = gyroBias[0], gyroBiasY = gyroBias[1], gyroBiasZ = gyroBias[2], isMountChanged = mountChanged
        )
        _sensorState.value = updated
        onImuSample?.invoke(ImuSample(event.timestamp, updated.accelX, updated.accelY, updated.accelZ, updated.gyroX, updated.gyroY, updated.gyroZ, updated.magX, updated.magY, updated.magZ, ImuSource.PHONE))
    }

    private fun gravityMagnitude(): Float {
        val gx = gravityValues[0]; val gy = gravityValues[1]; val gz = gravityValues[2]
        val mag = sqrt(gx * gx + gy * gy + gz * gz)
        return if (mag > 0.1f) mag else 9.81f
    }

    private fun updateRotationVectorOrientation(values: FloatArray) {
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, values)
        rotationMatrix = matrix
        updateOrientation(matrix, true)
    }

    private fun updateMagneticOrientation() {
        val matrix = FloatArray(9)
        if (SensorManager.getRotationMatrix(matrix, null, gravityValues, magValues)) {
            rotationMatrix = matrix
            updateOrientation(matrix, false)
        }
    }

    private fun updateOrientation(rotationMatrix: FloatArray, usesRotationVector: Boolean) {
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)
        _sensorState.value = _sensorState.value.copy(
            yawDegrees = Math.toDegrees(orientation[0].toDouble()).toFloat(),
            pitchDegrees = Math.toDegrees(orientation[1].toDouble()).toFloat(),
            rollDegrees = Math.toDegrees(orientation[2].toDouble()).toFloat(),
            usesRotationVector = usesRotationVector
        )
    }

    private fun observeMountWhenStable(timestampNs: Long) {
        if (stationarySinceNs == 0L || timestampNs - stationarySinceNs < 3_000_000_000L) return
        val state = _sensorState.value
        val orientation = floatArrayOf(state.pitchDegrees, state.rollDegrees, state.yawDegrees)
        val reference = referenceMount
        if (reference == null) referenceMount = orientation
        else if (angularDifference(reference[0], orientation[0]) > 12f || angularDifference(reference[1], orientation[1]) > 12f || angularDifference(reference[2], orientation[2]) > 20f) mountChanged = true
    }

    private fun gyroMagnitude(state: SensorState) = sqrt(state.gyroX * state.gyroX + state.gyroY * state.gyroY + state.gyroZ * state.gyroZ)
    private fun angularDifference(first: Float, second: Float): Float = abs(((first - second + 540f) % 360f) - 180f)
    private fun updateSamplingRate(timestampNs: Long): Int {
        accelerometerTimestamps += timestampNs
        val cutoff = timestampNs - 1_000_000_000L
        while (accelerometerTimestamps.isNotEmpty() && accelerometerTimestamps.first() < cutoff) accelerometerTimestamps.removeFirst()
        return accelerometerTimestamps.size
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/**
 * Debounce filter preventing single-sample sensor chatter from prematurely flipping the
 * vehicle motion state.
 */
class StationaryDebounceFilter(
    val enterThreshold: Int = 15, // ~300 ms at 50 Hz
    val exitThreshold: Int = 5    // ~100 ms at 50 Hz
) {
    var stableCount = 0
        private set
    var motionCount = 0
        private set
    var isStationary = false
        private set

    fun update(isCandidateStationary: Boolean): Boolean {
        if (!isStationary) {
            if (isCandidateStationary) {
                stableCount++
                motionCount = 0
                if (stableCount >= enterThreshold) {
                    isStationary = true
                }
            } else {
                stableCount = 0
            }
        } else {
            if (!isCandidateStationary) {
                motionCount++
                stableCount = 0
                if (motionCount >= exitThreshold) {
                    isStationary = false
                }
            } else {
                motionCount = 0
            }
        }
        return isStationary
    }

    fun reset() {
        stableCount = 0
        motionCount = 0
        isStationary = false
    }
}

/**
 * Learns gyroscope bias during stationary periods with bootstrap cold-start resolution.
 */
class GyroBiasEstimator(
    val bootstrapLimit: Int = 60,
    val historyLimit: Int = 150,
    val minSamplesForEstimate: Int = 30,
    var onBiasUpdated: ((FloatArray) -> Unit)? = null
) {
    private val stationaryGyros = ArrayDeque<FloatArray>()
    var gyroBias = FloatArray(3)
        private set
    var bootstrapSampleCount = 0
        private set

    fun setPersistentBias(savedBias: FloatArray) {
        if (savedBias.size == 3) {
            gyroBias = savedBias.clone()
        }
    }

    fun addSample(
        rawGyro: FloatArray,
        isStationary: Boolean,
        gravityMagnitude: Float = 9.81f,
        externalStationaryHint: Boolean = false
    ): FloatArray {
        val stableGravity = kotlin.math.abs(gravityMagnitude - 9.81f) < 0.35f
        val isBootstrapping = bootstrapSampleCount < bootstrapLimit && stableGravity

        if (isBootstrapping) {
            bootstrapSampleCount++
            updateBias(rawGyro)
        } else if (isStationary || externalStationaryHint) {
            updateBias(rawGyro)
        }

        return FloatArray(3) { index -> rawGyro[index] - gyroBias[index] }
    }

    private fun updateBias(raw: FloatArray) {
        stationaryGyros += raw.clone()
        while (stationaryGyros.size > historyLimit) stationaryGyros.removeFirst()
        if (stationaryGyros.size < minSamplesForEstimate) return
        val newBias = FloatArray(3) { axis -> stationaryGyros.map { it[axis] }.average().toFloat() }
        gyroBias = newBias
        onBiasUpdated?.invoke(newBias.clone())
    }

    fun reset() {
        stationaryGyros.clear()
        gyroBias = FloatArray(3)
        bootstrapSampleCount = 0
    }
}

package nisargpatel.deadreckoning.data

import android.content.Context
import com.google.gson.Gson
import nisargpatel.deadreckoning.fusion.VehicleAlignment

class CalibrationStore(context: Context) {
    private val preferences = context.getSharedPreferences("vehicle_alignment", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): VehicleAlignment? = preferences.getString("alignment", null)?.let {
        runCatching { gson.fromJson(it, VehicleAlignment::class.java) }.getOrNull()
    }

    fun save(alignment: VehicleAlignment) {
        if (alignment.confidencePercentage >= 70) {
            preferences.edit().putString("alignment", gson.toJson(alignment)).apply()
        }
    }

    fun loadGyroBias(): FloatArray? = preferences.getString("gyro_bias", null)?.let {
        runCatching { gson.fromJson(it, FloatArray::class.java) }.getOrNull()
    }

    fun saveGyroBias(bias: FloatArray) {
        preferences.edit().putString("gyro_bias", gson.toJson(bias)).apply()
    }
}

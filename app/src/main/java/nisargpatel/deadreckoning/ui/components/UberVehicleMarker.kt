package nisargpatel.deadreckoning.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Top-Down Vehicle Car Marker helper for OSMDroid MapView.
 * Renders a sleek 3D top-down car icon that rotates according to heading degrees
 * and animates along GPS / GNSS / AI Dead Reckoning coordinates.
 */
object UberVehicleMarker {

    private var carDrawableCache: Drawable? = null

    fun updateVehicleMarker(
        mapView: MapView,
        position: GeoPoint,
        headingDegrees: Double
    ) {
        val context = mapView.context
        if (carDrawableCache == null) {
            carDrawableCache = createVehicleCarBitmap(context)
        }

        val existingMarker = mapView.overlays.filterIsInstance<Marker>().firstOrNull { it.id == "uber_vehicle_car_marker" }
        val carMarker = existingMarker ?: Marker(mapView).also { marker ->
            marker.id = "uber_vehicle_car_marker"
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.icon = carDrawableCache
            marker.title = "Vehicle Position"
            mapView.overlays.add(marker)
        }

        carMarker.position = position
        carMarker.rotation = headingDegrees.toFloat()
        mapView.invalidate()
    }

    fun updateVehicleHeading(
        mapView: MapView,
        headingDegrees: Double
    ) {
        val carMarker = mapView.overlays.filterIsInstance<Marker>().firstOrNull { it.id == "uber_vehicle_car_marker" }
        if (carMarker != null) {
            val currentRot = carMarker.rotation
            val targetRot = headingDegrees.toFloat()
            if (kotlin.math.abs(currentRot - targetRot) > 0.4f) {
                carMarker.rotation = targetRot
                mapView.postInvalidate()
            }
        }
    }

    private fun createVehicleCarBitmap(context: Context): Drawable {
        val size = 160
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Soft Blue Location Halo
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#263B82F6")
        canvas.drawCircle(cx, cy, 52f, paint)

        paint.color = Color.parseColor("#153B82F6")
        canvas.drawCircle(cx, cy, 64f, paint)

        // 2. Forward Radar / Navigation Field of View Beam
        val fovPath = Path().apply {
            moveTo(cx - 10f, cy - 20f)
            lineTo(cx - 45f, 4f)
            quadTo(cx, 0f, cx + 45f, 4f)
            lineTo(cx + 10f, cy - 20f)
            close()
        }
        val fovShader = android.graphics.LinearGradient(
            cx, cy - 20f, cx, 0f,
            Color.parseColor("#553B82F6"),
            Color.parseColor("#083B82F6"),
            android.graphics.Shader.TileMode.CLAMP
        )
        val fovPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = fovShader
            style = Paint.Style.FILL
        }
        canvas.drawPath(fovPath, fovPaint)

        // 3. Car Shadow
        paint.shader = null
        paint.color = Color.parseColor("#44000000")
        canvas.drawRoundRect(cx - 18f, cy - 28f, cx + 18f, cy + 34f, 14f, 14f, paint)

        // 4. Car Body (Dark Slate Blue)
        paint.color = Color.parseColor("#0F172A")
        canvas.drawRoundRect(cx - 16f, cy - 30f, cx + 16f, cy + 32f, 12f, 12f, paint)

        // 5. Car Roof (Vibrant Blue)
        paint.color = Color.parseColor("#2563EB")
        canvas.drawRoundRect(cx - 12f, cy - 12f, cx + 12f, cy + 18f, 8f, 8f, paint)

        // 6. Windshield (Front Light Cyan/Blue)
        paint.color = Color.parseColor("#93C5FD")
        val frontGlass = Path().apply {
            moveTo(cx - 11f, cy - 13f)
            lineTo(cx + 11f, cy - 13f)
            lineTo(cx + 9f, cy - 5f)
            lineTo(cx - 9f, cy - 5f)
            close()
        }
        canvas.drawPath(frontGlass, paint)

        // Rear glass
        val rearGlass = Path().apply {
            moveTo(cx - 10f, cy + 19f)
            lineTo(cx + 10f, cy + 19f)
            lineTo(cx + 8f, cy + 13f)
            lineTo(cx - 8f, cy + 13f)
            close()
        }
        canvas.drawPath(rearGlass, paint)

        // 7. Headlights (Bright Cyan / White-Blue)
        paint.color = Color.parseColor("#38BDF8")
        canvas.drawCircle(cx - 11f, cy - 27f, 3.5f, paint)
        canvas.drawCircle(cx + 11f, cy - 27f, 3.5f, paint)

        // 8. Taillights (Red)
        paint.color = Color.parseColor("#EF4444")
        canvas.drawRoundRect(cx - 14f, cy + 29f, cx - 8f, cy + 32f, 2f, 2f, paint)
        canvas.drawRoundRect(cx + 8f, cy + 29f, cx + 14f, cy + 32f, 2f, 2f, paint)

        return BitmapDrawable(context.resources, bitmap)
    }
}

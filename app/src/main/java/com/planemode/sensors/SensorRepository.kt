package com.planemode.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.planemode.model.SensorAttitude
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt

data class SensorAvailability(val name: String, val present: Boolean, val vendor: String? = null)

/**
 * Device-orientation estimates from Android sensor fusion (rotation vector).
 * These describe the PHONE, not the aircraft, unless the phone is rigidly aligned and calibrated.
 * Listeners are registered only while someone collects [attitude].
 */
@Singleton
class SensorRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val prefs = context.getSharedPreferences("hud_calibration", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val gravitySensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val pressure = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)

    fun availability(): List<SensorAvailability> = listOf(
        "Accelerometer" to Sensor.TYPE_ACCELEROMETER, "Gyroscope" to Sensor.TYPE_GYROSCOPE,
        "Magnetometer" to Sensor.TYPE_MAGNETIC_FIELD, "Rotation vector" to Sensor.TYPE_ROTATION_VECTOR,
        "Gravity" to Sensor.TYPE_GRAVITY, "Barometer" to Sensor.TYPE_PRESSURE,
    ).map { (n, t) -> sm.getDefaultSensor(t).let { SensorAvailability(n, it != null, it?.vendor) } }

    // Reference "down" (unit gravity in device coordinates) and forward axis defining level attitude.
    @Volatile private var refDown: FloatArray? = loadRef()
    @Volatile private var lastDown = floatArrayOf(0f, -1f, 0f)
    @Volatile private var pressureOffsetM = prefs.getFloat("pressure_offset", 0f)

    val isCalibrated get() = prefs.contains("g0x")

    private fun loadRef(): FloatArray? = if (prefs.contains("g0x"))
        floatArrayOf(prefs.getFloat("g0x", 0f), prefs.getFloat("g0y", 0f), prefs.getFloat("g0z", -1f)) else null

    /** Treat the phone's current orientation as aircraft-level. */
    fun calibrateLevel() {
        val g = lastDown.copyOf()
        refDown = g
        prefs.edit().putFloat("g0x", g[0]).putFloat("g0y", g[1]).putFloat("g0z", g[2]).apply()
    }

    fun clearCalibration() {
        refDown = null
        prefs.edit().remove("g0x").remove("g0y").remove("g0z").apply()
    }

    /** Align the phone-derived pressure altitude with a known altitude (e.g. GPS on the ground). */
    fun calibratePressureAltitude(knownAltitudeM: Double, currentPressureHpa: Float) {
        val raw = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, currentPressureHpa)
        pressureOffsetM = (knownAltitudeM - raw).toFloat()
        prefs.edit().putFloat("pressure_offset", pressureOffsetM).apply()
    }

    val attitude: StateFlow<SensorAttitude> = callbackFlow {
        if (rotation == null && gravitySensor == null) {
            trySend(SensorAttitude(available = false)); awaitClose { }; return@callbackFlow
        }
        val rm = FloatArray(9); val orient = FloatArray(3)
        var pitchF = 0f; var rollF = 0f; var azF: Float? = null
        var hpa: Float? = null
        var initialized = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_PRESSURE -> { hpa = e.values[0]; return }
                    Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rm, e.values)
                        // World "up" expressed in device coordinates is the 3rd row of R.
                        val down = floatArrayOf(-rm[6], -rm[7], -rm[8])
                        lastDown = down
                        if (e.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                            SensorManager.getOrientation(rm, orient)
                            val az = ((Math.toDegrees(orient[0].toDouble()) + 360) % 360).toFloat()
                            azF = azF?.let { prev -> var d = az - prev; if (d > 180) d -= 360; if (d < -180) d += 360; (prev + 0.15f * d + 360) % 360 } ?: az
                        }
                    }
                    else -> { // gravity/accelerometer fallback when no rotation vector
                        if (rotation != null) return
                        val n = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).coerceAtLeast(1e-3f)
                        lastDown = floatArrayOf(-e.values[0] / n, -e.values[1] / n, -e.values[2] / n)
                    }
                }
                val (p, r) = computeAttitude(lastDown, refDown ?: canonicalRef(lastDown))
                if (!initialized) { pitchF = p; rollF = r; initialized = true }
                pitchF += 0.12f * (p - pitchF); rollF += 0.12f * (r - rollF)
                val pAlt = hpa?.let { SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, it) + pressureOffsetM }
                trySend(SensorAttitude(true, pitchF, rollF, azF, refDown != null, hpa, pAlt))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        rotation?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        if (rotation == null) gravitySensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        pressure?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        awaitClose { sm.unregisterListener(listener) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(2_000), SensorAttitude())

    companion object {
        /** Level reference for the closest canonical posture (flat, portrait-upright, landscape). */
        fun canonicalRef(down: FloatArray): FloatArray {
            val ax = abs(down[0]); val ay = abs(down[1]); val az = abs(down[2])
            return when {
                az >= ax && az >= ay -> floatArrayOf(0f, 0f, if (down[2] < 0) -1f else 1f)
                ay >= ax -> floatArrayOf(0f, if (down[1] < 0) -1f else 1f, 0f)
                else -> floatArrayOf(if (down[0] < 0) -1f else 1f, 0f, 0f)
            }
        }

        /**
         * Pitch/roll of current gravity [down] relative to reference [ref] (both unit vectors in device axes).
         * Forward = device +Y when the phone lies flat, otherwise out of the back camera (-Z).
         */
        fun computeAttitude(down: FloatArray, ref: FloatArray): Pair<Float, Float> {
            val flat = abs(ref[2]) > 0.7f
            val cand = if (flat) floatArrayOf(0f, 1f, 0f) else floatArrayOf(0f, 0f, -1f)
            val dot = cand[0] * ref[0] + cand[1] * ref[1] + cand[2] * ref[2]
            val f = norm(floatArrayOf(cand[0] - dot * ref[0], cand[1] - dot * ref[1], cand[2] - dot * ref[2]))
            val up = floatArrayOf(-ref[0], -ref[1], -ref[2])
            val r = norm(cross(f, up))
            val gf = (down[0] * f[0] + down[1] * f[1] + down[2] * f[2]).coerceIn(-1f, 1f)
            val gr = (down[0] * r[0] + down[1] * r[1] + down[2] * r[2]).coerceIn(-1f, 1f)
            val pitch = -Math.toDegrees(asin(gf.toDouble())).toFloat()
            val roll = Math.toDegrees(asin(gr.toDouble())).toFloat()
            return pitch to roll
        }

        private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        private fun norm(v: FloatArray): FloatArray { val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-6f); return floatArrayOf(v[0] / n, v[1] / n, v[2] / n) }
    }
}

package com.example.wro_prometheus

import org.opencv.core.Scalar
import android.content.Context
import android.util.Log
import kotlin.math.*

object ColorConfig {

    enum class LightPreset {
        DARK, STANDARD, BRIGHT, CUSTOM
    }

    // Presets para color ROJO
    private val redPresets = mapOf(
        LightPreset.DARK to mapOf(
            "lowerRed1" to Scalar(0.0, 60.0, 30.0),
            "upperRed1" to Scalar(8.0, 255.0, 200.0),
            "lowerRed2" to Scalar(170.0, 100.0, 40.0),
            "upperRed2" to Scalar(180.0, 255.0, 200.0)
        ),
        LightPreset.STANDARD to mapOf(
            "lowerRed1" to Scalar(0.0, 40.0, 40.0),
            "upperRed1" to Scalar(8.0, 255.0, 255.0),
            "lowerRed2" to Scalar(170.0, 80.0, 60.0),
            "upperRed2" to Scalar(180.0, 255.0, 255.0)
        ),
        LightPreset.BRIGHT to mapOf(
            "lowerRed1" to Scalar(0.0, 20.0, 80.0),
            "upperRed1" to Scalar(8.0, 255.0, 255.0),
            "lowerRed2" to Scalar(170.0, 40.0, 100.0),
            "upperRed2" to Scalar(180.0, 255.0, 255.0)
        )
    )

    // Presets para color VERDE
    private val greenPresets = mapOf(
        LightPreset.DARK to mapOf(
            "lowerGreen" to Scalar(35.0, 50.0, 10.0),
            "upperGreen" to Scalar(90.0, 255.0, 200.0)
        ),
        LightPreset.STANDARD to mapOf(
            "lowerGreen" to Scalar(35.0, 25.0, 15.0),
            "upperGreen" to Scalar(90.0, 255.0, 255.0)
        ),
        LightPreset.BRIGHT to mapOf(
            "lowerGreen" to Scalar(35.0, 15.0, 50.0),
            "upperGreen" to Scalar(90.0, 255.0, 255.0)
        )
    )

    // Presets para color MAGENTA
    // Magenta en OpenCV HSV: Hue 140-170° (equivale a 280-340° en HSV estándar / 2)
    // S alto porque el magenta es un color muy saturado
    // V variable según iluminación
    private val magentaPresets = mapOf(
        LightPreset.DARK to mapOf(
            "lowerMagenta" to Scalar(138.0, 60.0, 20.0),
            "upperMagenta" to Scalar(168.0, 255.0, 180.0)
        ),
        LightPreset.STANDARD to mapOf(
            "lowerMagenta" to Scalar(138.0, 40.0, 40.0),
            "upperMagenta" to Scalar(168.0, 255.0, 255.0)
        ),
        LightPreset.BRIGHT to mapOf(
            "lowerMagenta" to Scalar(138.0, 20.0, 80.0),
            "upperMagenta" to Scalar(168.0, 255.0, 255.0)
        )
    )

    // Variables actuales
    private var currentRedPreset = LightPreset.STANDARD
    private var currentGreenPreset = LightPreset.STANDARD

    // RANGOS PERSONALIZADOS (calibrados por el usuario capturando muestras en la pista)
    // Se inicializan con los valores STANDARD para que nunca queden nulos/vacíos.
    private var customRedRange: Map<String, Scalar> = redPresets[LightPreset.STANDARD]!!
    private var customGreenRange: Map<String, Scalar> = greenPresets[LightPreset.STANDARD]!!

    // RANGO DE MAGENTA — calibrado por muestras igual que rojo y verde
    private var customMagentaRange: Map<String, Scalar> = magentaPresets[LightPreset.BRIGHT]!!
    private var currentMagentaPreset = LightPreset.BRIGHT


    private var magentaHueCenter: Double = 155.0  // Hue central calibrado

    // Variables de acceso directo para magenta
    var lowerMagenta: Scalar = customMagentaRange["lowerMagenta"]!!
        private set
    var upperMagenta: Scalar = customMagentaRange["upperMagenta"]!!
        private set

    // Si el magenta está calibrado, el límite superior de la banda 2 del rojo
    // se recorta automáticamente para no invadir el territorio del magenta.
    // Sin calibración el rojo usa su rango completo.
    var magentaCalibrated: Boolean = false
        private set


    // CACHE DE VALORES ACTUALES - OPTIMIZACIÓN CRÍTICA
    private var cachedRedRanges = redPresets[LightPreset.STANDARD]!!
    private var cachedGreenRanges = greenPresets[LightPreset.STANDARD]!!

    // ACCESO ULTRA RÁPIDO A VALORES INDIVIDUALES
    var lowerRed1: Scalar = cachedRedRanges["lowerRed1"]!!
        private set
    var upperRed1: Scalar = cachedRedRanges["upperRed1"]!!
        private set
    var lowerRed2: Scalar = cachedRedRanges["lowerRed2"]!!
        private set
    var upperRed2: Scalar = cachedRedRanges["upperRed2"]!!
        private set
    var lowerGreen: Scalar = cachedGreenRanges["lowerGreen"]!!
        private set
    var upperGreen: Scalar = cachedGreenRanges["upperGreen"]!!
        private set

    // Función para actualizar cache

    private fun updateCache() {
        cachedRedRanges = if (currentRedPreset == LightPreset.CUSTOM) customRedRange else redPresets[currentRedPreset]!!
        cachedGreenRanges = if (currentGreenPreset == LightPreset.CUSTOM) customGreenRange else greenPresets[currentGreenPreset]!!
        val cachedMagentaRanges = if (currentMagentaPreset == LightPreset.CUSTOM) customMagentaRange else magentaPresets[currentMagentaPreset]!!

        lowerRed1 = cachedRedRanges["lowerRed1"]!!
        upperRed1 = cachedRedRanges["upperRed1"]!!
        lowerRed2 = cachedRedRanges["lowerRed2"]!!
        upperRed2 = cachedRedRanges["upperRed2"]!!
        lowerGreen = cachedGreenRanges["lowerGreen"]!!
        upperGreen = cachedGreenRanges["upperGreen"]!!
        lowerMagenta = cachedMagentaRanges["lowerMagenta"]!!
        upperMagenta = cachedMagentaRanges["upperMagenta"]!!

// El magenta y la Banda 2 del rojo son zonas ADYACENTES del círculo de Hue
// (magenta ~140-168°, rojo banda2 ~170-180°, el extremo real del espectro).
// Para evitar solapamiento hay que subir el límite INFERIOR de la banda 2
// del rojo por encima del límite SUPERIOR del magenta — nunca tocar el
// límite superior del rojo (180°), que es donde realmente vive el rojo puro.
        if (magentaCalibrated) {
            val magentaHigh = upperMagenta.`val`[0]
            val gap = 2.0
            val redBand2Floor = (magentaHigh + gap).coerceAtMost(179.0)
            if (lowerRed2.`val`[0] < redBand2Floor) {
                lowerRed2 = Scalar(redBand2Floor, lowerRed2.`val`[1], lowerRed2.`val`[2])
                Log.d("ColorConfig", "Banda 2 rojo ajustada: H desde ${redBand2Floor}° (magenta hasta $magentaHigh°)")
            }
        }
    }


    // Setters optimizados que actualizan cache
    fun setRedPreset(preset: LightPreset) {
        if (currentRedPreset != preset) {
            currentRedPreset = preset
            updateCache()
        }
    }

    fun setGreenPreset(preset: LightPreset) {
        if (currentGreenPreset != preset) {
            currentGreenPreset = preset
            updateCache()
        }
    }


    fun setMagentaPreset(preset: LightPreset) {
        if (currentMagentaPreset != preset) {
            currentMagentaPreset = preset
            if (preset != LightPreset.CUSTOM) {
                magentaCalibrated = false  // al volver a preset, desactivar recorte del rojo
            }
            updateCache()
        }
    }

    // Getters para obtener preset actual
    fun getCurrentRedPreset(): LightPreset = currentRedPreset
    fun getCurrentGreenPreset(): LightPreset = currentGreenPreset

    // Función para obtener nombre legible del preset
    fun getPresetName(preset: LightPreset): String {
        return when (preset) {
            LightPreset.DARK -> "Oscuro"
            LightPreset.STANDARD -> "Estándar"
            LightPreset.BRIGHT -> "Claro"
            LightPreset.CUSTOM -> "Calibrado (muestras)"
        }
    }

    // =============================================================================
    // CALIBRACIÓN POR MUESTRAS - PROMEDIO DE HUE A LO LARGO DE LA PISTA
    // =============================================================================

    /**
     * Promedio circular de un conjunto de valores de Hue (0-180 en OpenCV).
     * Un promedio aritmético normal falla cuando el rojo está repartido entre
     * valores cercanos a 0° y cercanos a 180° (son el mismo color, extremos del círculo).
     */
    fun circularMeanHue(huesDegrees: List<Double>): Double {
        if (huesDegrees.isEmpty()) return 0.0
        val periodDegrees = 180.0
        val toRad = 2 * Math.PI / periodDegrees
        var sumSin = 0.0
        var sumCos = 0.0
        for (h in huesDegrees) {
            sumSin += sin(h * toRad)
            sumCos += cos(h * toRad)
        }
        var meanDeg = atan2(sumSin, sumCos) / toRad
        if (meanDeg < 0) meanDeg += periodDegrees
        return meanDeg
    }

    /**
     * A partir de un Hue central y una tolerancia, devuelve uno o dos rangos (lower, upper).
     * Devuelve dos rangos cuando el rango se "sale" de [0,180] (caso típico del rojo).
     */
    fun hueRangeFromCenter(center: Double, tolerance: Double): List<Pair<Double, Double>> {
        val low = center - tolerance
        val high = center + tolerance
        return when {
            low < 0 -> listOf(0.0 to high, (180.0 + low) to 180.0)
            high > 180 -> listOf(low to 180.0, 0.0 to (high - 180.0))
            else -> listOf(low to high)
        }
    }

    /**
     * Aplica un Hue calibrado para el ROJO, conservando los límites de Saturación/Valor
     * del preset STANDARD (esos dependen de la iluminación general, no del Hue real).
     */
    private fun applyCustomRedHue(band1: Pair<Double, Double>, band2: Pair<Double, Double>) {
        val base = redPresets[LightPreset.STANDARD]!!
        val l1 = base["lowerRed1"]!!
        val u1 = base["upperRed1"]!!
        val l2 = base["lowerRed2"]!!
        val u2 = base["upperRed2"]!!

        customRedRange = mapOf(
            "lowerRed1" to Scalar(band1.first, l1.`val`[1], l1.`val`[2]),
            "upperRed1" to Scalar(band1.second, u1.`val`[1], u1.`val`[2]),
            "lowerRed2" to Scalar(band2.first, l2.`val`[1], l2.`val`[2]),
            "upperRed2" to Scalar(band2.second, u2.`val`[1], u2.`val`[2])
        )
        currentRedPreset = LightPreset.CUSTOM
        updateCache()
    }

    /** Igual que applyCustomRedHue pero para el VERDE (un solo rango, no hay wraparound). */
    private fun applyCustomGreenHue(band: Pair<Double, Double>) {
        val base = greenPresets[LightPreset.STANDARD]!!
        val l = base["lowerGreen"]!!
        val u = base["upperGreen"]!!

        customGreenRange = mapOf(
            "lowerGreen" to Scalar(band.first, l.`val`[1], l.`val`[2]),
            "upperGreen" to Scalar(band.second, u.`val`[1], u.`val`[2])
        )
        currentGreenPreset = LightPreset.CUSTOM
        updateCache()
    }

    /**
     * Desviación estándar circular de los valores Hue (mide dispersión entre muestras).
     * Un valor alto indica variación real de iluminación entre tramos (contraluz vs luz directa),
     * lo que requiere una tolerancia mayor para cubrir todos los casos calibrados.
     */
    fun circularStdDevHue(huesDegrees: List<Double>): Double {
        if (huesDegrees.size < 2) return 0.0
        val periodDegrees = 180.0
        val toRad = 2 * Math.PI / periodDegrees
        var sumSin = 0.0; var sumCos = 0.0
        for (h in huesDegrees) { sumSin += sin(h * toRad); sumCos += cos(h * toRad) }
        val R = sqrt(sumSin * sumSin + sumCos * sumCos) / huesDegrees.size.toDouble()
        return sqrt(-2.0 * ln(R.coerceAtLeast(1e-9))) / toRad
    }

    /**
     * Calibra el ROJO a partir de las muestras capturadas (ideal 8: 4 por sentido).
     * La tolerancia se expande automáticamente según la dispersión circular de las
     * muestras para cubrir tanto luz directa como contraluz.
     * Devuelve Pair(hue_central, tolerancia_usada) para mostrar en la UI.
     */
    fun calibrateRedFromSamples(
        hueSamples: List<Double>,
        toleranceDegrees: Double = 10.0
    ): Pair<Double, Double> {
        val center = circularMeanHue(hueSamples)
        val stdDev = circularStdDevHue(hueSamples)
        val adaptiveTolerance = (toleranceDegrees + stdDev).coerceAtMost(25.0)
        val ranges = hueRangeFromCenter(center, adaptiveTolerance)
        val band1 = ranges.firstOrNull { (it.first + it.second) / 2.0 < 90.0 } ?: (0.0 to 0.0)
        val band2 = ranges.firstOrNull { (it.first + it.second) / 2.0 >= 90.0 } ?: (180.0 to 180.0)
        applyCustomRedHue(band1, band2)
        return Pair(center, adaptiveTolerance)
    }

    /** Igual que calibrateRedFromSamples pero para VERDE. */
    fun calibrateGreenFromSamples(
        hueSamples: List<Double>,
        toleranceDegrees: Double = 15.0
    ): Pair<Double, Double> {
        val center = circularMeanHue(hueSamples)
        val stdDev = circularStdDevHue(hueSamples)
        val adaptiveTolerance = (toleranceDegrees + stdDev).coerceAtMost(28.0)
        val band = hueRangeFromCenter(center, adaptiveTolerance).first()
        applyCustomGreenHue(band)
        return Pair(center, adaptiveTolerance)
    }

    private fun scalarToString(s: Scalar): String = "${s.`val`[0]},${s.`val`[1]},${s.`val`[2]}"

    private fun stringToScalar(str: String): Scalar {
        val parts = str.split(",").map { it.toDouble() }
        return Scalar(parts[0], parts[1], parts[2])
    }

    fun savePresets(context: Context) {
        val prefs = context.getSharedPreferences("color_presets", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("red_preset", currentRedPreset.name)
            putString("green_preset", currentGreenPreset.name)
            putString("magenta_preset", currentMagentaPreset.name)

            if (currentRedPreset == LightPreset.CUSTOM) {
                putString("custom_lowerRed1", scalarToString(customRedRange["lowerRed1"]!!))
                putString("custom_upperRed1", scalarToString(customRedRange["upperRed1"]!!))
                putString("custom_lowerRed2", scalarToString(customRedRange["lowerRed2"]!!))
                putString("custom_upperRed2", scalarToString(customRedRange["upperRed2"]!!))
            }
            if (currentGreenPreset == LightPreset.CUSTOM) {
                putString("custom_lowerGreen", scalarToString(customGreenRange["lowerGreen"]!!))
                putString("custom_upperGreen", scalarToString(customGreenRange["upperGreen"]!!))
            }
            apply()
        }
    }
    fun loadPresets(context: Context) {
        val prefs = context.getSharedPreferences("color_presets", Context.MODE_PRIVATE)

        val redPresetName     = prefs.getString("red_preset",     LightPreset.STANDARD.name)
        val greenPresetName   = prefs.getString("green_preset",   LightPreset.STANDARD.name)

        val magentaPresetName = prefs.getString("magenta_preset", LightPreset.BRIGHT.name)

        try {
            val redPreset     = LightPreset.valueOf(redPresetName     ?: LightPreset.STANDARD.name)
            val greenPreset   = LightPreset.valueOf(greenPresetName   ?: LightPreset.STANDARD.name)
            val magentaPreset = LightPreset.valueOf(magentaPresetName ?: LightPreset.STANDARD.name)

            if (redPreset == LightPreset.CUSTOM) {
                val l1 = prefs.getString("custom_lowerRed1", null)
                val u1 = prefs.getString("custom_upperRed1", null)
                val l2 = prefs.getString("custom_lowerRed2", null)
                val u2 = prefs.getString("custom_upperRed2", null)
                if (l1 != null && u1 != null && l2 != null && u2 != null) {
                    customRedRange = mapOf(
                        "lowerRed1" to stringToScalar(l1),
                        "upperRed1" to stringToScalar(u1),
                        "lowerRed2" to stringToScalar(l2),
                        "upperRed2" to stringToScalar(u2)
                    )
                }
            }
            if (greenPreset == LightPreset.CUSTOM) {
                val lg = prefs.getString("custom_lowerGreen", null)
                val ug = prefs.getString("custom_upperGreen", null)
                if (lg != null && ug != null) {
                    customGreenRange = mapOf(
                        "lowerGreen" to stringToScalar(lg),
                        "upperGreen" to stringToScalar(ug)
                    )
                }
            }

            setRedPreset(redPreset)
            setGreenPreset(greenPreset)
            // Para magenta usamos STANDARD como activo aunque no sea CUSTOM,
            // el calibrado real se carga por loadMagentaPreset()
            currentMagentaPreset = if (magentaPreset == LightPreset.CUSTOM) LightPreset.BRIGHT else magentaPreset
            updateCache()

            Log.d("ColorConfig", "Presets cargados: Rojo=$redPreset, Verde=$greenPreset, Magenta=$magentaPreset")
        } catch (e: Exception) {
            Log.w("ColorConfig", "Error cargando presets, usando valores por defecto")
            setRedPreset(LightPreset.STANDARD)
            setGreenPreset(LightPreset.STANDARD)
        }
    }


    fun getCurrentMagentaPreset(): LightPreset = currentMagentaPreset
    fun isMagentaCalibrated(): Boolean = magentaCalibrated

    /**
     * Calibra el MAGENTA a partir de muestras de Hue capturadas en la pista.
     * Tras calibrar, recorta automáticamente la banda 2 del rojo para evitar solapamiento.
     * Devuelve Pair(hue_central, tolerancia_usada).
     */
    fun calibrateMagentaFromSamples(
        hueSamples: List<Double>,
        toleranceDegrees: Double = 12.0
    ): Pair<Double, Double> {
        val center = circularMeanHue(hueSamples)
        val stdDev = circularStdDevHue(hueSamples)
        val adaptiveTolerance = (toleranceDegrees + stdDev).coerceAtMost(20.0)
        magentaHueCenter = center

        val low = (center - adaptiveTolerance).coerceAtLeast(130.0)
        val high = (center + adaptiveTolerance).coerceAtMost(180.0)

        val base = mapOf(
            "lowerMagenta" to Scalar(low,  40.0, 40.0),
            "upperMagenta" to Scalar(high, 255.0, 255.0)
        )
        customMagentaRange = base
        currentMagentaPreset = LightPreset.CUSTOM
        magentaCalibrated = true
        updateCache()  // recalcula lowerRed2/upperRed2 con el nuevo límite
        Log.d("ColorConfig", "Magenta calibrado: Hue=${"%.1f".format(center)}° ± ${"%.1f".format(adaptiveTolerance)}° → H ${"%.1f".format(low)}–${"%.1f".format(high)}°")
        return Pair(center, adaptiveTolerance)
    }

    fun saveMagentaPreset(context: Context) {
        val prefs = context.getSharedPreferences("color_presets", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("magenta_calibrated", magentaCalibrated)
            putFloat("magenta_lower", lowerMagenta.`val`[0].toFloat())
            putFloat("magenta_upper", upperMagenta.`val`[0].toFloat())
            putFloat("magenta_center", magentaHueCenter.toFloat())
            apply()
        }
    }

    fun loadMagentaPreset(context: Context) {
        val prefs = context.getSharedPreferences("color_presets", Context.MODE_PRIVATE)
        magentaCalibrated = prefs.getBoolean("magenta_calibrated", false)
        if (magentaCalibrated) {
            val low  = prefs.getFloat("magenta_lower", 140f).toDouble()
            val high = prefs.getFloat("magenta_upper", 170f).toDouble()
            magentaHueCenter = prefs.getFloat("magenta_center", 155f).toDouble()
            customMagentaRange = mapOf(
                "lowerMagenta" to Scalar(low,  40.0, 40.0),
                "upperMagenta" to Scalar(high, 255.0, 255.0)
            )
            currentMagentaPreset = LightPreset.CUSTOM
            updateCache()
            Log.d("ColorConfig", "Magenta cargado: H $low–$high°")
        }
    }


}
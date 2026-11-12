package com.example.wro_prometheus

import android.content.Context
import android.content.SharedPreferences

/**
 * Gestor de configuración global para la aplicación WRO Prometheus
 * Maneja todas las configuraciones ajustables de distancias y calibración
 */
object ConfigSettings {
    private const val PREFS_NAME = "wro_prometheus_config"


    // =============================================================================
    // DETECCIÓN DE ESTACIONAMIENTO
    // =============================================================================

    private const val KEY_WALL_DETECTION = "wall_detection_enabled"
    private const val KEY_PARKING_DETECTION = "parking_detection_enabled"
    private const val DEFAULT_WALL_DETECTION = true
    private const val DEFAULT_PARKING_DETECTION = true

    /** Detección de estacionamiento por pared negra (independiente del color magenta). */
    var wallDetectionEnabled: Boolean
        get() = prefs?.getBoolean(KEY_WALL_DETECTION, DEFAULT_WALL_DETECTION) ?: DEFAULT_WALL_DETECTION
        set(value) { prefs?.edit()?.putBoolean(KEY_WALL_DETECTION, value)?.apply() }

    /** Activación completa del MODO estacionamiento (comandos + lado). Apagado esto,
     *  el delimitador sigue sin poder confundirse con un pilar Rojo/Verde — solo se
     *  desactiva el comportamiento especial de estacionar. */
    var parkingDetectionEnabled: Boolean
        get() = prefs?.getBoolean(KEY_PARKING_DETECTION, DEFAULT_PARKING_DETECTION) ?: DEFAULT_PARKING_DETECTION
        set(value) { prefs?.edit()?.putBoolean(KEY_PARKING_DETECTION, value)?.apply() }

    // Keys para SharedPreferences
    private const val KEY_REFERENCE_AREA = "reference_area"
    private const val KEY_REFERENCE_DISTANCE = "reference_distance"
    private const val KEY_FRONT_MIN = "front_distance_min"
    private const val KEY_FRONT_MAX = "front_distance_max"
    private const val KEY_MIDDLE_MIN = "middle_distance_min"
    private const val KEY_MIDDLE_MAX = "middle_distance_max"
    private const val KEY_BACK_MIN = "back_distance_min"
    private const val KEY_BACK_MAX = "back_distance_max"

    // Valores por defecto (los originales del código)
    private const val DEFAULT_REFERENCE_AREA = 19000.0
    private const val DEFAULT_REFERENCE_DISTANCE = 50.0
    private const val DEFAULT_FRONT_MIN = 50
    private const val DEFAULT_FRONT_MAX = 80
    private const val DEFAULT_MIDDLE_MIN = 100
    private const val DEFAULT_MIDDLE_MAX = 130
    private const val DEFAULT_BACK_MIN = 140
    private const val DEFAULT_BACK_MAX = 210

    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Configuración de calibración de distancia
    var referenceArea: Double
        get() = prefs?.getFloat(KEY_REFERENCE_AREA, DEFAULT_REFERENCE_AREA.toFloat())?.toDouble() ?: DEFAULT_REFERENCE_AREA
        set(value) { prefs?.edit()?.putFloat(KEY_REFERENCE_AREA, value.toFloat())?.apply() }

    var referenceDistance: Double
        get() = prefs?.getFloat(KEY_REFERENCE_DISTANCE, DEFAULT_REFERENCE_DISTANCE.toFloat())?.toDouble() ?: DEFAULT_REFERENCE_DISTANCE
        set(value) { prefs?.edit()?.putFloat(KEY_REFERENCE_DISTANCE, value.toFloat())?.apply() }

    // Configuración de zonas de distancia
    var frontDistanceMin: Int
        get() = prefs?.getInt(KEY_FRONT_MIN, DEFAULT_FRONT_MIN) ?: DEFAULT_FRONT_MIN
        set(value) { prefs?.edit()?.putInt(KEY_FRONT_MIN, value)?.apply() }

    var frontDistanceMax: Int
        get() = prefs?.getInt(KEY_FRONT_MAX, DEFAULT_FRONT_MAX) ?: DEFAULT_FRONT_MAX
        set(value) { prefs?.edit()?.putInt(KEY_FRONT_MAX, value)?.apply() }

    var middleDistanceMin: Int
        get() = prefs?.getInt(KEY_MIDDLE_MIN, DEFAULT_MIDDLE_MIN) ?: DEFAULT_MIDDLE_MIN
        set(value) { prefs?.edit()?.putInt(KEY_MIDDLE_MIN, value)?.apply() }

    var middleDistanceMax: Int
        get() = prefs?.getInt(KEY_MIDDLE_MAX, DEFAULT_MIDDLE_MAX) ?: DEFAULT_MIDDLE_MAX
        set(value) { prefs?.edit()?.putInt(KEY_MIDDLE_MAX, value)?.apply() }

    var backDistanceMin: Int
        get() = prefs?.getInt(KEY_BACK_MIN, DEFAULT_BACK_MIN) ?: DEFAULT_BACK_MIN
        set(value) { prefs?.edit()?.putInt(KEY_BACK_MIN, value)?.apply() }

    var backDistanceMax: Int
        get() = prefs?.getInt(KEY_BACK_MAX, DEFAULT_BACK_MAX) ?: DEFAULT_BACK_MAX
        set(value) { prefs?.edit()?.putInt(KEY_BACK_MAX, value)?.apply() }

    // Función para resetear a valores por defecto
    fun resetToDefaults() {
        referenceArea = DEFAULT_REFERENCE_AREA
        referenceDistance = DEFAULT_REFERENCE_DISTANCE
        frontDistanceMin = DEFAULT_FRONT_MIN
        frontDistanceMax = DEFAULT_FRONT_MAX
        middleDistanceMin = DEFAULT_MIDDLE_MIN
        middleDistanceMax = DEFAULT_MIDDLE_MAX
        backDistanceMin = DEFAULT_BACK_MIN
        backDistanceMax = DEFAULT_BACK_MAX
    }

    // Función para validar configuraciones
    fun validateSettings(): Boolean {
        return frontDistanceMin < frontDistanceMax &&
                middleDistanceMin < middleDistanceMax &&
                backDistanceMin < backDistanceMax &&
                frontDistanceMax <= middleDistanceMin &&
                middleDistanceMax <= backDistanceMin &&
                referenceArea > 0 &&
                referenceDistance > 0
    }

    // Obtener resumen de configuración
    fun getConfigSummary(): String {
        return """
            === CONFIGURACIÓN ACTUAL ===
            Calibración de Distancia:
            • Área de referencia: ${referenceArea.toInt()}px
            • Distancia de referencia: ${referenceDistance.toInt()}cm
            
            Zonas de Distancia:
            • Adelante: ${frontDistanceMin}-${frontDistanceMax}cm
            • Medio: ${middleDistanceMin}-${middleDistanceMax}cm
            • Atrás: ${backDistanceMin}-${backDistanceMax}cm
            
            Configuración válida: ${if(validateSettings()) "✅" else "❌"}
        """.trimIndent()
    }

    // =============================================================================
// FILTRO ANTI-REFLEJO POR SATURACIÓN
// =============================================================================

    private const val KEY_REFLECTION_FILTER = "reflection_filter_enabled"
    private const val DEFAULT_REFLECTION_FILTER = true

    var reflectionFilterEnabled: Boolean
        get() = prefs?.getBoolean(KEY_REFLECTION_FILTER, DEFAULT_REFLECTION_FILTER) ?: DEFAULT_REFLECTION_FILTER
        set(value) { prefs?.edit()?.putBoolean(KEY_REFLECTION_FILTER, value)?.apply() }

    // Umbral mínimo de saturación interna (0-255) para considerar un contorno como objeto
    // real y no un reflejo. Configurable por color porque el verde oscuro tiene una
    // saturación intrínseca más baja que el rojo.
    private const val KEY_MIN_SAT_RED = "min_internal_saturation_red"
    private const val KEY_MIN_SAT_GREEN = "min_internal_saturation_green"
    private const val DEFAULT_MIN_SAT_RED = 55
    private const val DEFAULT_MIN_SAT_GREEN = 38

    var minInternalSaturationRed: Int
        get() = prefs?.getInt(KEY_MIN_SAT_RED, DEFAULT_MIN_SAT_RED) ?: DEFAULT_MIN_SAT_RED
        set(value) { prefs?.edit()?.putInt(KEY_MIN_SAT_RED, value)?.apply() }

    var minInternalSaturationGreen: Int
        get() = prefs?.getInt(KEY_MIN_SAT_GREEN, DEFAULT_MIN_SAT_GREEN) ?: DEFAULT_MIN_SAT_GREEN
        set(value) { prefs?.edit()?.putInt(KEY_MIN_SAT_GREEN, value)?.apply() }

    // =============================================================================
    // LINTERNA
    // =============================================================================

    private const val KEY_TORCH = "torch_enabled"

    /** Último estado de la linterna — se restaura al reiniciar la cámara. */
    var torchEnabled: Boolean
        get() = prefs?.getBoolean(KEY_TORCH, false) ?: false
        set(value) { prefs?.edit()?.putBoolean(KEY_TORCH, value)?.apply() }

}
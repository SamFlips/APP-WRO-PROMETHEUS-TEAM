package com.example.wro_prometheus

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.widget.*
import androidx.core.content.ContextCompat

/**
 * Gestor de diálogos de configuración para WRO Prometheus
 * Maneja todos los diálogos de configuración de la aplicación
 */
class SettingsDialogManager(private val context: Context) {

    /**
     * Muestra el diálogo principal de configuración con opciones
     */
    fun showMainSettingsDialog(onComplete: () -> Unit) {
        val filterStatus = if (ConfigSettings.reflectionFilterEnabled) "ACTIVO ✅" else "INACTIVO ❌"
        val wallStatus = if (ConfigSettings.wallDetectionEnabled) "ACTIVA ✅" else "INACTIVA ❌"
        val parkingStatus = if (ConfigSettings.parkingDetectionEnabled) "ACTIVA ✅" else "INACTIVA ❌"
        val mainActivity = MainActivity.getInstance()
        val torchStatus = when {
            mainActivity?.isTorchAvailable() != true -> "no disponible"
            mainActivity.isTorchEnabled() -> "ENCENDIDA 🔦"
            else -> "apagada"
        }
        val options = arrayOf(
            "🎨 Configuración de Colores",
            "🎯 Calibración por Muestras (Hue)",
            "📏 Calibración de Distancia",
            "📍 Zonas de Distancia",
            "🔍 Filtro Anti-Reflejo: $filterStatus",
            "⬛ Detección Pared Negra: $wallStatus",
            "🟣 Modo Estacionamiento: $parkingStatus",
            "🔦 Linterna: $torchStatus",
            "ℹ️ Ver Configuración Actual",
            "📤 Exportar / Compartir Configuración",
            "📥 Importar Configuración"
        )

        AlertDialog.Builder(context)
            .setTitle("⚙️ Configuración del Sistema")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showColorPresetsDialog(onComplete)
                    1 -> showColorCalibrationDialog(onComplete)
                    2 -> showDistanceCalibrationDialog(onComplete)
                    3 -> showDistanceZonesDialog(onComplete)
                    4 -> showReflectionFilterDialog(onComplete)
                    5 -> toggleWallDetection(onComplete)
                    6 -> toggleParkingDetection(onComplete)
                    7 -> showTorchDialog(onComplete)
                    8 -> showCurrentConfigDialog()
                    9 -> mainActivity?.exportConfiguration()
                        ?: Toast.makeText(context, "❌ No se pudo acceder a la app", Toast.LENGTH_SHORT).show()
                    10 -> mainActivity?.importConfiguration()
                        ?: Toast.makeText(context, "❌ No se pudo acceder a la app", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Toggle rápido, sin sub-diálogo — pensado para cambiar en el momento, en pista. */
    private fun toggleWallDetection(onComplete: () -> Unit) {
        val newValue = !ConfigSettings.wallDetectionEnabled
        ConfigSettings.wallDetectionEnabled = newValue
        val msg = if (newValue) "⬛✅ Detección de pared negra ACTIVADA" else "⬛❌ Detección de pared negra DESACTIVADA"
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        onComplete()
    }

    /** Toggle rápido para el modo estacionamiento completo (comandos de estacionar).
     *  La exclusión del delimitador como pilar Rojo/Verde NUNCA se apaga con esto. */
    private fun toggleParkingDetection(onComplete: () -> Unit) {
        val newValue = !ConfigSettings.parkingDetectionEnabled
        ConfigSettings.parkingDetectionEnabled = newValue
        val msg = if (newValue) "🟣✅ Modo Estacionamiento ACTIVADO" else "🟣❌ Modo Estacionamiento DESACTIVADO (el delimitador sigue sin detectarse como pilar)"
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        onComplete()
    }

    /**
     * Muestra diálogo de calibración de distancia
     */
    private fun showDistanceCalibrationDialog(onComplete: () -> Unit) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        // Título
        val titleText = TextView(context).apply {
            text = "📏 Calibración de Distancia"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 30)
        }
        layout.addView(titleText)

        // Descripción
        val descText = TextView(context).apply {
            text = "Para calibrar:\n1. Coloca un objeto a una distancia conocida\n2. Ajusta el área observada\n3. Ingresa la distancia real"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            setPadding(0, 0, 0, 20)
        }
        layout.addView(descText)

        // Campo para área de referencia
        val areaLabel = TextView(context).apply {
            text = "Área de referencia (píxeles):"
            setPadding(0, 0, 0, 5)
        }
        layout.addView(areaLabel)

        val areaInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(ConfigSettings.referenceArea.toInt().toString())
            setPadding(20, 15, 20, 15)
        }
        layout.addView(areaInput)

        // Campo para distancia de referencia
        val distLabel = TextView(context).apply {
            text = "Distancia de referencia (cm):"
            setPadding(0, 20, 0, 5)
        }
        layout.addView(distLabel)

        val distInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(ConfigSettings.referenceDistance.toInt().toString())
            setPadding(20, 15, 20, 15)
        }
        layout.addView(distInput)

        // Mostrar valores actuales
        val currentValues = TextView(context).apply {
            text = "Valores actuales:\nÁrea: ${ConfigSettings.referenceArea.toInt()}px\nDistancia: ${ConfigSettings.referenceDistance.toInt()}cm"
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            setPadding(0, 20, 0, 0)
        }
        layout.addView(currentValues)

        AlertDialog.Builder(context)
            .setView(layout)
            .setPositiveButton("Aplicar") { _, _ ->
                try {
                    val newArea = areaInput.text.toString().toDouble()
                    val newDistance = distInput.text.toString().toDouble()

                    if (newArea <= 0 || newDistance <= 0) {
                        Toast.makeText(context, "❌ Los valores deben ser mayores que 0", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }

                    ConfigSettings.referenceArea = newArea
                    ConfigSettings.referenceDistance = newDistance

                    Toast.makeText(context, "✅ Calibración actualizada", Toast.LENGTH_SHORT).show()
                    onComplete()
                } catch (e: NumberFormatException) {
                    Toast.makeText(context, "❌ Error: Ingresa números válidos", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Resetear") { _, _ ->
                ConfigSettings.referenceArea = 19000.0
                ConfigSettings.referenceDistance = 50.0
                Toast.makeText(context, "🔄 Calibración reseteada a valores por defecto", Toast.LENGTH_SHORT).show()
                onComplete()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Muestra diálogo de configuración de zonas de distancia
     */
    private fun showDistanceZonesDialog(onComplete: () -> Unit) {
        val scrollView = ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        // Título
        val titleText = TextView(context).apply {
            text = "📍 Configuración de Zonas de Distancia"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        }
        layout.addView(titleText)

        // Descripción
        val descText = TextView(context).apply {
            text = "Define los rangos de distancia para cada zona de detección."
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            setPadding(0, 0, 0, 20)
        }
        layout.addView(descText)

        // Función auxiliar para crear sección de zona
        fun createZoneSection(title: String, minValue: Int, maxValue: Int): Pair<EditText, EditText> {
            val sectionTitle = TextView(context).apply {
                text = title
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 10, 0, 10)
            }
            layout.addView(sectionTitle)

            val rangeLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val minInput = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(minValue.toString())
                hint = "Mín"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 10
                }
                setPadding(15, 10, 15, 10)
            }

            val separator = TextView(context).apply {
                text = " - "
                setPadding(10, 10, 10, 10)
            }

            val maxInput = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(maxValue.toString())
                hint = "Máx"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 10
                }
                setPadding(15, 10, 15, 10)
            }

            rangeLayout.addView(minInput)
            rangeLayout.addView(separator)
            rangeLayout.addView(maxInput)
            layout.addView(rangeLayout)

            return Pair(minInput, maxInput)
        }

        // Crear secciones para cada zona
        val (frontMinInput, frontMaxInput) = createZoneSection(
            "🎯 Zona ADELANTE (cm):",
            ConfigSettings.frontDistanceMin,
            ConfigSettings.frontDistanceMax
        )

        val (middleMinInput, middleMaxInput) = createZoneSection(
            "🎯 Zona MEDIO (cm):",
            ConfigSettings.middleDistanceMin,
            ConfigSettings.middleDistanceMax
        )

        val (backMinInput, backMaxInput) = createZoneSection(
            "🎯 Zona ATRÁS (cm):",
            ConfigSettings.backDistanceMin,
            ConfigSettings.backDistanceMax
        )

        // Información actual
        val currentInfo = TextView(context).apply {
            text = """
                Configuración actual:
                Adelante: ${ConfigSettings.frontDistanceMin}-${ConfigSettings.frontDistanceMax}cm
                Medio: ${ConfigSettings.middleDistanceMin}-${ConfigSettings.middleDistanceMax}cm
                Atrás: ${ConfigSettings.backDistanceMin}-${ConfigSettings.backDistanceMax}cm
            """.trimIndent()
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            setPadding(0, 20, 0, 0)
        }
        layout.addView(currentInfo)

        scrollView.addView(layout)

        AlertDialog.Builder(context)
            .setView(scrollView)
            .setPositiveButton("Aplicar") { _, _ ->
                try {
                    val frontMin = frontMinInput.text.toString().toInt()
                    val frontMax = frontMaxInput.text.toString().toInt()
                    val middleMin = middleMinInput.text.toString().toInt()
                    val middleMax = middleMaxInput.text.toString().toInt()
                    val backMin = backMinInput.text.toString().toInt()
                    val backMax = backMaxInput.text.toString().toInt()

                    // Validaciones
                    if (frontMin >= frontMax || middleMin >= middleMax || backMin >= backMax) {
                        Toast.makeText(context, "❌ Error: Los valores mínimos deben ser menores que los máximos", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }

                    if (frontMax > middleMin || middleMax > backMin) {
                        Toast.makeText(context, "❌ Error: Las zonas no pueden solaparse", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }

                    // Aplicar cambios
                    ConfigSettings.frontDistanceMin = frontMin
                    ConfigSettings.frontDistanceMax = frontMax
                    ConfigSettings.middleDistanceMin = middleMin
                    ConfigSettings.middleDistanceMax = middleMax
                    ConfigSettings.backDistanceMin = backMin
                    ConfigSettings.backDistanceMax = backMax

                    Toast.makeText(context, "✅ Zonas de distancia actualizadas", Toast.LENGTH_SHORT).show()
                    onComplete()
                } catch (e: NumberFormatException) {
                    Toast.makeText(context, "❌ Error: Ingresa números válidos", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Resetear") { _, _ ->
                ConfigSettings.frontDistanceMin = 50
                ConfigSettings.frontDistanceMax = 80
                ConfigSettings.middleDistanceMin = 100
                ConfigSettings.middleDistanceMax = 130
                ConfigSettings.backDistanceMin = 140
                ConfigSettings.backDistanceMax = 180
                Toast.makeText(context, "🔄 Zonas reseteadas a valores por defecto", Toast.LENGTH_SHORT).show()
                onComplete()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Muestra diálogo con la configuración actual
     */
    private fun showTorchDialog(onComplete: () -> Unit) {
        val mainActivity = MainActivity.getInstance()
        if (mainActivity == null || !mainActivity.isTorchAvailable()) {
            Toast.makeText(context, "❌ Linterna no disponible en este dispositivo", Toast.LENGTH_LONG).show()
            return
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val titleText = TextView(context).apply {
            text = "🔦 Control de Linterna"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        }
        layout.addView(titleText)

        val descText = TextView(context).apply {
            text = "Activa la linterna para mejorar la detección en condiciones de poca " +
                    "luz o contraluz severo. El estado se guarda y se restaura al reiniciar."
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            setPadding(0, 0, 0, 30)
        }
        layout.addView(descText)

        val toggleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val toggleLabel = TextView(context).apply {
            text = "Linterna:"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val toggle = Switch(context).apply { isChecked = mainActivity.isTorchEnabled() }
        toggleRow.addView(toggleLabel)
        toggleRow.addView(toggle)
        layout.addView(toggleRow)

        val statusText = TextView(context).apply {
            text = if (toggle.isChecked) "🔦 Linterna ENCENDIDA" else "⚫ Linterna apagada"
            textSize = 13f
            setPadding(0, 20, 0, 0)
            setTextColor(
                if (toggle.isChecked)
                    ContextCompat.getColor(context, android.R.color.holo_orange_dark)
                else
                    ContextCompat.getColor(context, android.R.color.darker_gray)
            )
        }
        layout.addView(statusText)

        toggle.setOnCheckedChangeListener { _, isChecked ->
            mainActivity.setTorch(isChecked)
            statusText.text = if (isChecked) "🔦 Linterna ENCENDIDA" else "⚫ Linterna apagada"
            statusText.setTextColor(
                if (isChecked)
                    ContextCompat.getColor(context, android.R.color.holo_orange_dark)
                else
                    ContextCompat.getColor(context, android.R.color.darker_gray)
            )
            onComplete()
        }

        AlertDialog.Builder(context)
            .setTitle("🔦 Linterna")
            .setView(layout)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /**
     * Muestra diálogo con la configuración actual incluyendo rangos HSV calibrados
     */
    private fun showCurrentConfigDialog() {
        val scrollView = ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 30)
        }

        val configText = TextView(context).apply {
            text = ConfigSettings.getConfigSummary()
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        layout.addView(configText)

        val divider = TextView(context).apply {
            text = "\n─────────────────────────"
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }
        layout.addView(divider)

        val hsvText = TextView(context).apply {
            text = buildHsvRangesSummary()
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        layout.addView(hsvText)

        scrollView.addView(layout)

        AlertDialog.Builder(context)
            .setTitle("📋 Configuración Actual")
            .setView(scrollView)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Resetear Todo") { _, _ ->
                AlertDialog.Builder(context)
                    .setTitle("⚠️ Confirmar Reset")
                    .setMessage("¿Resetear TODA la configuración a valores por defecto?")
                    .setPositiveButton("Sí, resetear") { _, _ ->
                        ConfigSettings.resetToDefaults()
                        Toast.makeText(context, "🔄 Toda la configuración reseteada", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .show()
    }

    private fun buildHsvRangesSummary(): String {
        val redPreset   = ColorConfig.getCurrentRedPreset()
        val greenPreset = ColorConfig.getCurrentGreenPreset()

        val r1Lo = ColorConfig.lowerRed1
        val r1Hi = ColorConfig.upperRed1
        val r2Lo = ColorConfig.lowerRed2
        val r2Hi = ColorConfig.upperRed2
        val gLo  = ColorConfig.lowerGreen
        val gHi  = ColorConfig.upperGreen
        val mLo  = ColorConfig.lowerMagenta
        val mHi  = ColorConfig.upperMagenta

        val greenCenter   = (gLo.`val`[0] + gHi.`val`[0]) / 2.0
        val magentaCenter = (mLo.`val`[0] + mHi.`val`[0]) / 2.0

        return buildString {
            append("=== RANGOS HSV ACTIVOS ===\n\n")

            append("🔴 ROJO — ${ColorConfig.getPresetName(redPreset)}\n")
            append("  Banda 1  H: ${"%.1f".format(r1Lo.`val`[0])}–${"%.1f".format(r1Hi.`val`[0])}°\n")
            append("           S: ${"%.0f".format(r1Lo.`val`[1])}–${"%.0f".format(r1Hi.`val`[1])}\n")
            append("           V: ${"%.0f".format(r1Lo.`val`[2])}–${"%.0f".format(r1Hi.`val`[2])}\n")
            append("  Banda 2  H: ${"%.1f".format(r2Lo.`val`[0])}–${"%.1f".format(r2Hi.`val`[0])}°")
            if (ColorConfig.isMagentaCalibrated()) append(" ✂️ recortada por magenta")
            append("\n")
            append("           S: ${"%.0f".format(r2Lo.`val`[1])}–${"%.0f".format(r2Hi.`val`[1])}\n")
            append("           V: ${"%.0f".format(r2Lo.`val`[2])}–${"%.0f".format(r2Hi.`val`[2])}\n")
            if (redPreset == ColorConfig.LightPreset.CUSTOM) {
                val center = (r1Lo.`val`[0] + r1Hi.`val`[0]) / 2.0
                val tol    = r1Hi.`val`[0] - r1Lo.`val`[0]
                append("  ↳ Centro ≈ ${"%.1f".format(center)}°  Tol ≈ ${"%.1f".format(tol)}°\n")
            }

            append("\n🟢 VERDE — ${ColorConfig.getPresetName(greenPreset)}\n")
            append("  H: ${"%.1f".format(gLo.`val`[0])}–${"%.1f".format(gHi.`val`[0])}°")
            append("  (centro ≈ ${"%.1f".format(greenCenter)}°)\n")
            append("  S: ${"%.0f".format(gLo.`val`[1])}–${"%.0f".format(gHi.`val`[1])}\n")
            append("  V: ${"%.0f".format(gLo.`val`[2])}–${"%.0f".format(gHi.`val`[2])}\n")

            append("\n🟣 MAGENTA — ")
            if (ColorConfig.isMagentaCalibrated()) {
                append("Calibrado\n")
                append("  H: ${"%.1f".format(mLo.`val`[0])}–${"%.1f".format(mHi.`val`[0])}°")
                append("  (centro ≈ ${"%.1f".format(magentaCenter)}°)\n")
                append("  S: ${"%.0f".format(mLo.`val`[1])}–${"%.0f".format(mHi.`val`[1])}\n")
                append("  V: ${"%.0f".format(mLo.`val`[2])}–${"%.0f".format(mHi.`val`[2])}\n")
            } else {
                append("No calibrado (inactivo)\n")
            }
        }
    }

    /**
     * Ventana de calibración por muestras (8 muestras: 4 por sentido).
     *
     * Al abrirse activa ColorAnalyzer.calibrationMode = true, lo que hace que el
     * analizador use rangos HSV muy amplios (rojo 0-15°/160-180°, verde 25-100°,
     * S y V desde 10) y desactiva todos los filtros geométricos y anti-reflejo.
     * Esto garantiza que incluso variantes muy oscuras o con contraluz severo sean
     * detectadas y su Hue pueda ser capturado como muestra.
     *
     * La preview no se tapa: el diálogo es translúcido y compacto (ocupa solo la
     * mitad inferior de la pantalla) dejando visible la imagen de la cámara con
     * los contornos detectados en la mitad superior.
     */
    private fun showColorCalibrationDialog(onComplete: () -> Unit) {
        val requiredSamples = 8
        val redSamples = mutableListOf<Double>()
        val greenSamples = mutableListOf<Double>()
        val magentaSamples = mutableListOf<Double>()

        val trampLabels = listOf(
            "Tramo 1 →", "Tramo 2 →", "Tramo 3 →", "Tramo 4 →",
            "Tramo 5 ←", "Tramo 6 ←", "Tramo 7 ←", "Tramo 8 ←"
        )

        // Activar modo calibración: rangos amplios + sin filtros
        ColorAnalyzer.calibrationMode = true

        // ── Layout compacto que ocupa la mitad inferior ──────────────────────
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 20, 32, 16)
        }

        // Indicador de modo calibración (banner naranja visible)
        val bannerText = TextView(context).apply {
            text = "🟠 MODO CALIBRACIÓN ACTIVO — rangos amplios + filtros"
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#CC6600"))
            setPadding(16, 10, 16, 10)
        }
        layout.addView(bannerText)

        // Selector de color
        val colorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 14, 0, 6)
        }
        val colorLabel = TextView(context).apply {
            text = "Color:"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 16 }
        }
        val colorGroup = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val radioRed     = RadioButton(context).apply { text = "🔴 Rojo" }
        val radioGreen   = RadioButton(context).apply { text = "🟢 Verde" }
        val radioMagenta = RadioButton(context).apply { text = "🟣 Magenta" }
        colorGroup.addView(radioRed)
        colorGroup.addView(radioGreen)
        colorGroup.addView(radioMagenta)
        radioRed.isChecked = true

        colorRow.addView(colorLabel)
        colorRow.addView(colorGroup)
        layout.addView(colorRow)

        // Estado y tramo actual (una sola línea)
        val statusText = TextView(context).apply {
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 6, 0, 2)
        }
        layout.addView(statusText)

        val trampText = TextView(context).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }
        layout.addView(trampText)

        // Última muestra capturada (Hue + Sat + área)
        val lastSampleText = TextView(context).apply {
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            setPadding(0, 4, 0, 8)
        }
        layout.addView(lastSampleText)

        fun currentCount() = when {
            radioRed.isChecked     -> redSamples.size
            radioGreen.isChecked   -> greenSamples.size
            else                   -> magentaSamples.size
        }

        fun refreshStatus() {
            val rDone = redSamples.size >= requiredSamples
            val gDone = greenSamples.size >= requiredSamples
            val mDone = magentaSamples.size >= requiredSamples
            statusText.text = "🔴 ${redSamples.size}/$requiredSamples ${if(rDone)"✅" else ""}  " +
                    "🟢 ${greenSamples.size}/$requiredSamples ${if(gDone)"✅" else ""}  " +
                    "🟣 ${magentaSamples.size}/$requiredSamples ${if(mDone)"✅" else ""}"
            val cnt = currentCount()
            trampText.text = if (cnt < requiredSamples)
                "📍 Próxima: ${trampLabels[cnt]}"
            else
                "✅ Muestras completas para este color"
        }

        refreshStatus()
        colorGroup.setOnCheckedChangeListener { _, _ -> refreshStatus() }

        // Botones en fila
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
        }
        val captureBtn = Button(context).apply {
            text = "📸 Capturar"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = 8 }
        }
        val resetBtn = Button(context).apply {
            text = "🔄 Reiniciar"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnRow.addView(captureBtn)
        btnRow.addView(resetBtn)
        layout.addView(btnRow)

// REEMPLAZAR el bloque captureBtn.setOnClickListener:
        captureBtn.setOnClickListener {
            val isMagenta = radioMagenta.isChecked
            val isRed = radioRed.isChecked
            val expectedLabel = when {
                isRed      -> "Rojo"
                isMagenta  -> "Magenta"
                else       -> "Verde"
            }
            val sample = ColorAnalyzer.lastPrimaryHsvSample

            if (sample == null || sample.colorLabel != expectedLabel) {
                Toast.makeText(
                    context,
                    "⚠️ No se detecta $expectedLabel — asegúrate de que el objeto llena bien el encuadre.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val targetList = when {
                isRed     -> redSamples
                isMagenta -> magentaSamples
                else      -> greenSamples
            }
            if (targetList.size >= requiredSamples) {
                Toast.makeText(context, "Ya tienes $requiredSamples muestras. Reinicia si quieres recapturar.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val trampLabel = trampLabels[targetList.size]
            targetList.add(sample.hue)
            lastSampleText.text = "✅ $trampLabel  H=${"%.1f".format(sample.hue)}°  " +
                    "S=${"%.0f".format(sample.saturation)}  área=${sample.area.toInt()}px"
            refreshStatus()
        }

        resetBtn.setOnClickListener {
            redSamples.clear()
            greenSamples.clear()
            magentaSamples.clear()
            lastSampleText.text = ""
            refreshStatus()
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setView(layout)
            .setPositiveButton("💾 Aplicar") { _, _ ->
                ColorAnalyzer.calibrationMode = false

                var applied = false

                if (redSamples.isNotEmpty()) {
                    val (center, tolerance) = ColorConfig.calibrateRedFromSamples(redSamples)
                    val partial = if (redSamples.size < requiredSamples) " (parcial ${redSamples.size}/$requiredSamples)" else ""
                    Toast.makeText(context,
                        "✅ Rojo calibrado$partial\nHue: ${"%.1f".format(center)}°  Tol: ${"%.1f".format(tolerance)}°",
                        Toast.LENGTH_LONG).show()
                    applied = true
                }

                if (greenSamples.isNotEmpty()) {
                    val (center, tolerance) = ColorConfig.calibrateGreenFromSamples(greenSamples)
                    val partial = if (greenSamples.size < requiredSamples) " (parcial ${greenSamples.size}/$requiredSamples)" else ""
                    Toast.makeText(context,
                        "✅ Verde calibrado$partial\nHue: ${"%.1f".format(center)}°  Tol: ${"%.1f".format(tolerance)}°",
                        Toast.LENGTH_LONG).show()
                    applied = true
                }

                if (magentaSamples.isNotEmpty()) {
                    val (center, tolerance) = ColorConfig.calibrateMagentaFromSamples(magentaSamples)
                    val partial = if (magentaSamples.size < requiredSamples) " (parcial ${magentaSamples.size}/$requiredSamples)" else ""
                    Toast.makeText(context,
                        "✅ Magenta calibrado$partial\nHue: ${"%.1f".format(center)}°  Tol: ${"%.1f".format(tolerance)}°\n⚙️ Banda 2 del rojo recortada automáticamente",
                        Toast.LENGTH_LONG).show()
                    ColorConfig.saveMagentaPreset(context)
                    applied = true
                }

                if (applied) {
                    ColorConfig.savePresets(context)
                    onComplete()
                } else {
                    Toast.makeText(context, "❌ Captura al menos 1 muestra antes de aplicar.", Toast.LENGTH_LONG).show()
                }
            }

            .setNegativeButton("Cancelar") { _, _ ->
                ColorAnalyzer.calibrationMode = false   // desactivar también al cancelar
            }
            .setOnCancelListener {
                ColorAnalyzer.calibrationMode = false
            }
            .create()

        // Hacer el diálogo translúcido y posicionado en la mitad inferior
        // para que la preview de la cámara siga visible arriba
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val params = attributes
            params.gravity = android.view.Gravity.BOTTOM
            params.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
            params.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            params.dimAmount = 0.3f   // atenuación leve: cámara sigue visible
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = params
        }

        dialog.show()
    }

    /**
     * Muestra diálogo para activar/desactivar el filtro anti-reflejo por saturación
     */
    private fun showReflectionFilterDialog(onComplete: () -> Unit) {
        val isCurrentlyEnabled = ConfigSettings.reflectionFilterEnabled

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val titleText = TextView(context).apply {
            text = "🔍 Filtro Anti-Reflejo"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        }
        layout.addView(titleText)

        val descText = TextView(context).apply {
            text = "Analiza la saturación de color interna de cada contorno detectado.\n\n" +
                    "Los reflejos en superficies brillantes pierden saturación al mezclarse " +
                    "con el color de la pista, por lo que este filtro los descarta automáticamente.\n\n" +
                    "⚠️ Si la iluminación es muy baja, puede reducir detecciones válidas."
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            setPadding(0, 0, 0, 30)
        }
        layout.addView(descText)

        val toggleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val toggleLabel = TextView(context).apply {
            text = "Estado del filtro:"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val toggle = Switch(context).apply {
            isChecked = isCurrentlyEnabled
            textOn = "Activo"
            textOff = "Inactivo"
        }

        toggleRow.addView(toggleLabel)
        toggleRow.addView(toggle)
        layout.addView(toggleRow)

        // Indicador de estado visual
        val statusText = TextView(context).apply {
            text = if (isCurrentlyEnabled) "✅ El filtro está ACTIVO" else "❌ El filtro está INACTIVO"
            textSize = 13f
            setPadding(0, 20, 0, 0)
            setTextColor(
                if (isCurrentlyEnabled)
                    ContextCompat.getColor(context, android.R.color.holo_green_dark)
                else
                    ContextCompat.getColor(context, android.R.color.holo_red_dark)
            )
        }
        layout.addView(statusText)

        // Actualizar el indicador mientras el usuario mueve el toggle
        toggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                statusText.text = "✅ El filtro está ACTIVO"
                statusText.setTextColor(ContextCompat.getColor(context, android.R.color.holo_green_dark))
            } else {
                statusText.text = "❌ El filtro está INACTIVO"
                statusText.setTextColor(ContextCompat.getColor(context, android.R.color.holo_red_dark))
            }
        }

// Umbrales de saturación interna por color (0-255). Más alto = más estricto
        // (rechaza más reflejos, pero puede empezar a rechazar también objetos reales
        // si la iluminación es baja). El verde oscuro suele necesitar un umbral menor que el rojo.
        val thresholdsTitle = TextView(context).apply {
            text = "Umbral de saturación interna (0-255):"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 30, 0, 10)
        }
        layout.addView(thresholdsTitle)

        val redThresholdLabel = TextView(context).apply { text = "🔴 Rojo:"; setPadding(0, 10, 0, 5) }
        layout.addView(redThresholdLabel)

        val redThresholdInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(ConfigSettings.minInternalSaturationRed.toString())
            setPadding(20, 15, 20, 15)
        }
        layout.addView(redThresholdInput)

        val greenThresholdLabel = TextView(context).apply { text = "🟢 Verde:"; setPadding(0, 15, 0, 5) }
        layout.addView(greenThresholdLabel)

        val greenThresholdInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(ConfigSettings.minInternalSaturationGreen.toString())
            setPadding(20, 15, 20, 15)
        }
        layout.addView(greenThresholdInput)

        val thresholdHint = TextView(context).apply {
            text = "💡 Para calibrar: activa debugMode en ColorAnalyzer, compara la " +
                    "\"Saturación interna\" de un objeto real contra la de un reflejo en el " +
                    "Logcat, y pon el umbral a medio camino entre ambos valores."
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            setPadding(0, 15, 0, 0)
        }
        layout.addView(thresholdHint)

        AlertDialog.Builder(context)
            .setView(layout)
            .setPositiveButton("Aplicar") { _, _ ->
                val newValue = toggle.isChecked
                ConfigSettings.reflectionFilterEnabled = newValue

                try {
                    val newRedThreshold = redThresholdInput.text.toString().toInt()
                    val newGreenThreshold = greenThresholdInput.text.toString().toInt()

                    if (newRedThreshold !in 0..255 || newGreenThreshold !in 0..255) {
                        Toast.makeText(context, "❌ Los umbrales deben estar entre 0 y 255", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }

                    ConfigSettings.minInternalSaturationRed = newRedThreshold
                    ConfigSettings.minInternalSaturationGreen = newGreenThreshold

                    val msg = if (newValue) "✅ Filtro Anti-Reflejo activado" else "❌ Filtro Anti-Reflejo desactivado"
                    Toast.makeText(context, "$msg\nRojo: $newRedThreshold | Verde: $newGreenThreshold", Toast.LENGTH_SHORT).show()
                    onComplete()
                } catch (e: NumberFormatException) {
                    Toast.makeText(context, "❌ Error: Ingresa números válidos", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }


    /**
     * Muestra diálogo de configuración de colores (reutiliza la funcionalidad existente)
     */
    private fun showColorPresetsDialog(onComplete: () -> Unit) {
        val colorDialog = ColorPresetDialog(context) {
            onComplete()
        }
        colorDialog.show()
    }
}
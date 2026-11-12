package com.example.wro_prometheus

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import android.graphics.BitmapFactory
import android.graphics.Matrix
import kotlin.math.*

/**
 * Analizador de colores que detecta objetos de co lores específicos (rojo, verde, magenta)
 * usando OpenCV y técnicas avanzadas de procesamiento de imágenes.
 *
 * Características principales:
 * - Ecualización de histograma para normalizar la iluminación
 * - Filtros geométricos para detectar formas rectangulares
 * - Suavizado de contornos con doble pasada
 * - Detección de picos triangulares para rechazar formas orgánicas
 *
 * @param callback Función que recibe los resultados del análisis con parámetros:
 * - String: Descripción del color detectado
 * - Bitmap?: Imagen procesada con contornos dibujados
 * - Double: Área del objeto detectado
 * - Float: Coordenada X del centro del objeto
 * - Float: Coordenada Y del centro del objeto
 * - Int: Ancho de la imagen procesada
 * - Int: Alto de la imagen procesada
 */
class ColorAnalyzer(private val callback: (String, Bitmap?, DualObjectDetection) -> Unit) : ImageAnalysis.Analyzer {

    companion object {
        /**
         * Clase de datos para almacenar información de posición del objeto detectado
         * Incluye coordenadas relativas y absolutas, área y color
         */
        data class ObjectPosition(
            val x: Float,           // Posición X relativa (0.0 - 1.0)
            val y: Float,           // Posición Y relativa (0.0 - 1.0)
            val area: Double,       // Área en píxeles
            val color: String,      // Color detectado
            val centerX: Float,     // Coordenada X absoluta del centro
            val centerY: Float,     // Coordenada Y absoluta del centro
            val imageWidth: Int,    // Ancho de la imagen de referencia
            val imageHeight: Int    // Alto de la imagen de referencia
        )

        // Variable estática para almacenar la última posición detectada
        var lastObjectPosition = ObjectPosition(0f, 0f, 0.0, "", 0f, 0f, 0, 0)

        data class DualObjectDetection(
            val primaryObject: ObjectPosition?,    // Objeto más cercano (determina comando)
            val secondaryObject: ObjectPosition?   // Objeto más lejano (solo información)
        )

        // Variable estática para detección dual
        var lastDualDetection = DualObjectDetection(null, null)

        /**
         * Muestra de color promedio (HSV) del interior del último objeto primario detectado.
         * La usa la ventana de calibración por muestras para promediar el Hue real de los
         * objetos en distintos puntos de la pista (compensa cambios de iluminación/balance de blancos).
         */
        data class HsvSample(
            val hue: Double,
            val saturation: Double,
            val value: Double,
            val colorLabel: String,
            val area: Double
        )

        var lastPrimaryHsvSample: HsvSample? = null

        data class RejectedInfo(val color: String, val reason: String, val area: Double)
        val lastRejectedObjects = mutableListOf<RejectedInfo>()


        // =============================================================================
        // MODO CALIBRACIÓN — rangos HSV ampliados para detectar hasta variantes extremas
        // =============================================================================
        // Cuando está activo, el analizador ignora los rangos calibrados/preset de
        // ColorConfig y usa estos rangos máximos. Esto permite capturar muestras de
        // objetos que el rango actual NO detectaría (rojo muy oscuro, verde muy oscuro,
        // objetos con contraluz severo). Se activa desde el diálogo de calibración y
        // se desactiva automáticamente al cerrarlo.
        var calibrationMode = false

        // Rojo: cubre 0-15° y 160-180° con S y V muy permisivos (desde 15)
        val calibrationLowerRed1 = org.opencv.core.Scalar(0.0,  15.0, 15.0)
        val calibrationUpperRed1 = org.opencv.core.Scalar(15.0, 255.0, 255.0)
        val calibrationLowerRed2 = org.opencv.core.Scalar(160.0, 15.0, 15.0)
        val calibrationUpperRed2 = org.opencv.core.Scalar(180.0, 255.0, 255.0)

        // Verde: cubre 25-100° con S y V muy permisivos (desde 10)
        val calibrationLowerGreen = org.opencv.core.Scalar(25.0, 10.0, 10.0)
        val calibrationUpperGreen = org.opencv.core.Scalar(100.0, 255.0, 255.0)

        // NUEVA FUNCIÓN PARA CALCULAR DISTANCIA USANDO LAS CONSTANTES1 DE MAINACTIVITY
        /**
         * Calcula la distancia usando la MISMA referencia que CommandManager
         * (ConfigSettings.referenceArea/referenceDistance), para que el valor mostrado en
         * pantalla y el usado para decidir el comando coincidan siempre, incluso si el
         * usuario recalibra la distancia desde Configuración.
         */
        fun calculateDistance(area: Double): Int {
            if (area <= 0.0) return MainActivity.MAX_DISTANCE
            val calculatedDistance = kotlin.math.sqrt(ConfigSettings.referenceArea / area) * ConfigSettings.referenceDistance
            return calculatedDistance.toInt().coerceIn(MainActivity.MIN_DISTANCE, MainActivity.MAX_DISTANCE)
        }

        /**
         * Igual que calculateDistance pero SIN recortar (coerceIn) al máximo configurado.
         * Se usa para decidir si un contorno está realmente fuera del rango útil de la
         * pista y debe descartarse del todo, en vez de aparecer "pegado" al máximo (180cm)
         * y colarse como si fuera un objeto válido en el límite de la zona Atrás.
         */
        private fun estimateRawDistance(area: Double): Double {
            if (area <= 0.0) return Double.MAX_VALUE
            return kotlin.math.sqrt(ConfigSettings.referenceArea / area) * ConfigSettings.referenceDistance
        }


    }

    // =============================================================================
    // PARÁMETROS DE ECUALIZACIÓN DE HISTOGRAMA
    // =============================================================================

    /** Habilita la ecualización de histograma para normalizar la iluminación */
    private val enableHistogramEqualization = true

    /** Habilita CLAHE (Contrast Limited Adaptive Histogram Equalization) */
    private val enableCLAHE = true

    /** Límite de contraste para CLAHE - valor conservador para evitar sobreecualización */
    private val claheClipLimit = 1.2

    /** Tamaño de la cuadrícula para CLAHE - 8x8 es bueno para objetos medianos */
    private val claheTileGridSize = Size(8.0, 8.0)

    // =============================================================================
    // RANGOS DE COLORES EN ESPACIO HSV
    // =============================================================================

    // ROJO: Se define en dos rangos debido a que el rojo está en los extremos del espectro H

    /** Rango HSV para tonos rojos bajos (0-10 grados)
    private val lowerRed1 = Scalar(0.0, 40.0, 40.0)
    private val upperRed1 = Scalar(8.0, 255.0, 255.0)

    /** Rango HSV para tonos rojos altos (170-180 grados) */
    // Se sube el límite inferior de 160 a 170 para no tocar los tonos magenta/rosa.
    private val lowerRed2 = Scalar(170.0, 80.0, 60.0)
    private val upperRed2 = Scalar(180.0, 255.0, 255.0)

    // /** Rango HSV para color magenta (145-170 grados) */
    // private val lowerMagenta = Scalar(145.0, 70.0, 60.0)
    // private val upperMagenta = Scalar(169.0, 255.0, 255.0)

    /** Rango HSV para color verde */
    private val lowerGreen = Scalar(35.0, 25.0, 15.0) // H: 30°, S: 16%, V: 10%
    private val upperGreen = Scalar(90.0, 255.0, 255.0) // H: 90°, S: 100%, V: 100% */

    // =============================================================================
    // PARÁMETROS GENERALES DE DETECCIÓN
    // =============================================================================

    /** Área mínima en píxeles que debe tener un contorno para ser considerado válido */
    private val minContourArea = 280.0


    /** Cuántas veces más grande es el delimitador magenta que un pilar rojo/verde
     *  a la MISMA distancia (medido físicamente: ver Figura 13). Se usa para (a)
     *  descartar por área objetos rojos/verdes que "gotean" hacia la máscara
     *  magenta por solapamiento de Hue, y (b) corregir el cálculo de distancia
     *  específico para magenta (ver calculateMagentaDistance más abajo). */
    private val magentaAreaRatio = 4.0


    /** Relación de aspecto máxima (ancho/alto) que puede tener un pilar Rojo/Verde real.
     *  Por encima de esto es demasiado "ancho" para ser un pilar de frente y se reclasifica
     *  como Estacionamiento antes de aplicar el filtro de forma normal. */
    private val maxPillarAspectRatio = 1.2


    /** Rectangularidad mínima (área_contorno / área_bounding_box) que debe tener un
     *  candidato a "pared" para contar como tal. Una franja diagonal delgada (como la
     *  cinta naranja de referencia en su parte en sombra) llena muy poco de su bounding
     *  box -> rectangularidad baja, así se descarta aunque sea oscura y toque el borde.
     *  CALIBRAR IN SITU con los logs nuevos: mirá el valor real de la pared vs el de
     *  falsos positivos como la cinta, y ajustá este número entre ambos. */
    private val minWallRectangularity = 0.55


    /** Área mínima específica para contornos magenta. Se usa un factor algo menor
     *  a magentaAreaRatio (3.0 en vez de 4.0) para dejar margen ante ángulos de
     *  cámara oblicuos, donde el área proyectada del delimitador se reduce un
     *  poco — pero sigue muy por encima del área típica de un pilar real, así
     *  que un pilar rojo/verde jamás la va a superar. */
    private val minMagentaContourArea = minContourArea * 3.0



    /** Área máxima que puede llegar a tener un pilar Rojo/Verde REAL en cámara —
     *  calculada a partir de la distancia mínima físicamente posible (el borde de
     *  la zona "Adelante"; más cerca que eso, el auto ya debería haber reaccionado).
     *  Cualquier contorno Rojo/Verde por encima de este valor es IMPOSIBLE que sea
     *  un pilar real — es el estacionamiento (u otro objeto grande) detectado con
     *  un color parecido al del pilar. Se recalcula en vivo por si el usuario
     *  cambia la calibración de distancia desde Configuración. */
    private val maxPlausibleRedGreenArea: Double
        get() {
            val minPlausibleDistance = ConfigSettings.frontDistanceMin.toDouble().coerceAtLeast(1.0)
            return ConfigSettings.referenceArea * (ConfigSettings.referenceDistance / minPlausibleDistance).let { it * it }
        }



    /** Área mínima para que un contorno magenta ACTIVE el modo estacionamiento.
     *  Es mayor a minMagentaContourArea a propósito: un delimitador real visto
     *  DE FRENTE desde el tramo anterior mucha menos
     *  área que el mismo delimitador visto DE COSTADO ya en su propio tramo
     *  aunque ambos sean el objeto real y pasen el filtro de forma,
     *  solo el segundo debe activar el modo.
     *  */

    private val minMagentaActivationArea = 8000.0





    /** Valor (brillo) máximo para considerar un píxel parte de la "pared negra" del
     *  estacionamiento — independiente del Hue, porque en competencia la pared es
     *  negra sin importar el tono. CALIBRAR IN SITU: en la pista real, revisá el
     *  texto de diagnóstico en pantalla apuntando a la pared negra y ajustá este
     *  valor según lo que reporte. */
    private val wallMaxValue = 70.0

    /** Saturación máxima para "pared negra" — permite algo de margen ante reflejos
     *  o variaciones de tono en el material negro, sin confundirse con colores
     *  saturados (rojo/verde/magenta real). */
    private val wallMaxSaturation = 90.0

    /** Área mínima para que la mancha negra cuente como "pared", no ruido/sombra. */
    private val wallMinContourArea = 5000.0

    /** Qué tan cerca del borde de la pantalla tiene que llegar la mancha negra
     *  para considerar que "toca" ese lado. 0.05 = 5% del ancho del ROI. */
    private val wallEdgeTouchMargin = 0.05f




    /** Umbral de área para reclasificar un contorno Rojo/Verde como "estacionamiento
     *  por forma" — respaldo para cuando el color del delimitador es demasiado
     *  parecido a un pilar y la detección por Hue falla. Reutiliza un criterio de
     *  tamaño similar al del canal Magenta; ajustalo si hace falta. */
    private val parkingShapeAreaThreshold = 2200.0

    /** Qué tan pegado al borde extremo de la pantalla debe estar un contorno para
     *  considerarlo "en la pared" (estacionamiento) en vez de "en el carril" (pilar
     *  normal). 0.18 = 18% desde cada borde. Es más estricto que el split L/R normal
     *  (0.60) a propósito — el delimitador está pegado a la pared, no solo del lado
     *  izquierdo/derecho del carril. */
    private val parkingEdgeZone = 0.18f



        /** Habilita mensajes de depuración en el log */
    private val debugMode = true

    /** Porcentaje de la imagen que se usa como ROI (Región de Interés) */
    private val roiPercentage = 0.8

    /** Región de interés calculada dinámicamente */
    private var roi: Rect? = null

    // =============================================================================
    // PARÁMETROS PARA FILTRADO DE FORMAS RECTANGULARES
    // =============================================================================

    /** Solidez mínima (área_contorno / área_casco_convexo) para formas regulares */
    private val minSolidity = 0.4

    /** Relación de aspecto máxima (ancho/alto) permitida */
    private val maxAspectRatio = 4.0

    /** Relación de aspecto mínima (ancho/alto) permitida */
    private val minAspectRatio = 0.25

    /** Extensión mínima (área_contorno / área_rectángulo_envolvente) */
    private val minExtent = 0.30

    /** Ratio máximo perímetro²/área para filtrar formas complejas */
    private val maxPerimeterAreaRatio = 30.0

    /** Habilita el filtrado básico de formas */
    private val enableShapeFiltering = true


    // =============================================================================
// PARÁMETROS DE FORMA ESPECÍFICOS PARA EL DELIMITADOR MAGENTA
// =============================================================================
// El delimitador de estacionamiento es una placa plana y delgada, no un pilar
// compacto. Visto en perspectiva (cara superior delgada + cara frontal) genera
// contornos con menor convexidad y mayor perímetro relativo que un pilar, así
// que necesita sus propios umbrales — más permisivos — en vez de reusar los
// pensados para Rojo/Verde.

    private val minSolidityMagenta = 0.55
    private val minConvexityMagenta = 0.65
    private val maxConvexityDefectsMagenta = 6
    private val minRectangularityMagenta = 0.35
    private val maxPerimeterAreaRatioMagenta = 45.0
    private val minAspectRatioMagenta = 0.15   // permite verse muy angosto en ciertos ángulos
    private val maxAspectRatioMagenta = 8.0    // permite verse muy alargado (placa larga y baja)
    private val minExtentMagenta = 0.3

    // =============================================================================
    // PARÁMETROS PARA FILTRADO AVANZADO
    // =============================================================================

    /** Rectangularidad mínima (área_contorno / área_rectángulo_envolvente) */
    private val minRectangularity = 0.35

    /** Número máximo de defectos de convexidad permitidos */
    private val maxConvexityDefects = 3

    /** Convexidad mínima (área_contorno / área_casco_convexo) */
    private val minConvexity = 0.85

    /** Habilita filtros geométricos avanzados */
    private val enableAdvancedFiltering = true

    // =============================================================================
    // PARÁMETROS PARA SUAVIZADO DE CONTORNOS
    // =============================================================================

    /** Habilita el suavizado de contornos usando aproximación poligonal */
    private val enableContourSmoothing = true

    /** Factor epsilon para aproximación poligonal (más bajo = más suave) */
    private val smoothingEpsilon = 0.008

    /** Habilita doble pasada de suavizado para mejor resultado */
    private val enableDoubleSmoothing = true

    // =============================================================================
    // PARÁMETROS PARA DETECCIÓN DE PICOS
    // =============================================================================

    /** Habilita detección de ángulos agudos (picos triangulares) */
    private val enableSpikeDetection = true

    /** Umbral de ángulo máximo en grados para considerar un pico */
    private val maxAngleThreshold = 45.0


    // =============================================================================
// PARÁMETROS PARA FILTRADO DE REFLEJOS POR SATURACIÓN
// =============================================================================

    /** Saturación mínima promedio (canal S en HSV 0-255) que debe tener el área interna del objeto ROJO.
     *  Configurable desde Configuración > Filtro Anti-Reflejo. */
    private val minInternalSaturationRed: Double
        get() = ConfigSettings.minInternalSaturationRed.toDouble()

    /** Saturación mínima promedio para el área interna del objeto VERDE.
     *  Configurable desde Configuración > Filtro Anti-Reflejo. */
    private val minInternalSaturationGreen: Double
        get() = ConfigSettings.minInternalSaturationGreen.toDouble()

    /** Habilita el filtro anti-reflejo por análisis de saturación interna */
    private val enableSaturationReflectionFilter: Boolean
        get() = ConfigSettings.reflectionFilterEnabled


    /**
     * Función principal de análisis que procesa cada frame de la cámara
     * Realiza todo el pipeline de procesamiento de imágenes
     */
    override fun analyze(image: ImageProxy) {
        if (debugMode) Log.d("ColorAnalyzer", "Analizando imagen: ${image.width}x${image.height}")


        // Variables para almacenar resultados del análisis
        var outputBitmap: Bitmap? = null
        var detectedArea: Double = 0.0
        var centerX: Float = 0f
        var centerY: Float = 0f
        var dualDetection = DualObjectDetection(null, null)

        try {
            // =============================================================================
            // 1. CONVERSIÓN DE FORMATO DE IMAGEN
            // =============================================================================

            val bitmap = imageProxyToBitmap(image)
            if (bitmap == null) {
                Log.e("ColorAnalyzer", "Error al convertir ImageProxy a Bitmap")
                callback("Error al procesar la imagen", null, DualObjectDetection(null, null))
                return
            }

            // =============================================================================
            // 2. CONFIGURACIÓN DE REGIÓN DE INTERÉS (ROI)
            // =============================================================================

            // Calcular ROI centrada si no existe
            if (roi == null) {
                val roiWidth = (image.width * roiPercentage).toInt()
                val roiHeight = (image.height * roiPercentage).toInt()
                val roiLeft = (image.width - roiWidth) / 2
                val roiTop = (image.height - roiHeight) / 2
                roi = Rect(roiLeft, roiTop, roiLeft + roiWidth, roiTop + roiHeight)
            }

            // Crear copia del bitmap para visualización
            outputBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)

            // =============================================================================
            // 3. CONVERSIÓN A FORMATO OPENCV
            // =============================================================================

            val matOriginal = Mat()
            Utils.bitmapToMat(bitmap, matOriginal)

            if (roi != null) {
                // Extraer región de interés
                val openCVRect = org.opencv.core.Rect(roi!!.left, roi!!.top, roi!!.width(), roi!!.height())
                val roiMat = Mat(matOriginal, openCVRect)

                // =============================================================================
                // 4. ECUALIZACIÓN DE HISTOGRAMA PARA NORMALIZAR ILUMINACIÓN
                // =============================================================================

                val matHSV = Mat()
                Imgproc.cvtColor(roiMat, matHSV, Imgproc.COLOR_RGB2HSV)

                // Aplicar ecualización al canal V (brillo) para normalizar la iluminación
                val equalizedHSV = if (enableHistogramEqualization) {
                    applyHistogramEqualizationToV(matHSV)
                } else {
                    matHSV.clone() // Usar copia para mantener consistencia
                }

                if (debugMode) {
                    Log.d("ColorAnalyzer", "Ecualización de histograma aplicada al canal V")
                }

                // Liberar imagen HSV original si se creó una nueva
                if (enableHistogramEqualization) {
                    matHSV.release()
                }

                // =============================================================================
                // 5. CREACIÓN DE MÁSCARAS PARA CADA COLOR
                // =============================================================================
                val maskRed1 = Mat()
                val maskRed2 = Mat()
                val maskRed = Mat()
                val maskGreen = Mat()
                val maskMagenta = Mat()
                val maskWall = Mat()

                Core.inRange(equalizedHSV, ColorConfig.lowerRed1, ColorConfig.upperRed1, maskRed1)
                Core.inRange(equalizedHSV, ColorConfig.lowerRed2, ColorConfig.upperRed2, maskRed2)
                Core.inRange(equalizedHSV, ColorConfig.lowerGreen, ColorConfig.upperGreen, maskGreen)
                Core.inRange(equalizedHSV, ColorConfig.lowerMagenta, ColorConfig.upperMagenta, maskMagenta)

// Máscara de "pared negra" — independiente del Hue, solo mira brillo/saturación.
                Core.inRange(equalizedHSV, Scalar(0.0, 0.0, 0.0), Scalar(180.0, wallMaxSaturation, wallMaxValue), maskWall)

                Core.bitwise_or(maskRed1, maskRed2, maskRed)

                // =============================================================================
                // 6. OPERACIONES MORFOLÓGICAS PARA LIMPIAR MÁSCARAS
                // =============================================================================

                // Kernel rectangular para operaciones morfológicas
                // OPEN usa un kernel más grande para romper los "puentes" finos que conectan
                // el objeto con un reflejo/sombra adyacente del mismo color.
                // CLOSE usa un kernel más chico para no volver a unir lo que OPEN separó.
                val kernelOpen = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
                val kernelClose = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))

                // Aplicar apertura (erosión + dilatación) para eliminar ruido y separar reflejos pegados
                // Seguida de cierre (dilatación + erosión) para llenar huecos internos pequeños
                Imgproc.morphologyEx(maskRed, maskRed, Imgproc.MORPH_OPEN, kernelOpen)
                Imgproc.morphologyEx(maskRed, maskRed, Imgproc.MORPH_CLOSE, kernelClose)

                Imgproc.morphologyEx(maskGreen, maskGreen, Imgproc.MORPH_OPEN, kernelOpen)
                Imgproc.morphologyEx(maskGreen, maskGreen, Imgproc.MORPH_CLOSE, kernelClose)

                if (!maskMagenta.empty()) {
                    Imgproc.morphologyEx(maskMagenta, maskMagenta, Imgproc.MORPH_OPEN, kernelOpen)
                    Imgproc.morphologyEx(maskMagenta, maskMagenta, Imgproc.MORPH_CLOSE, kernelClose)
                }

                Imgproc.morphologyEx(maskWall, maskWall, Imgproc.MORPH_OPEN, kernelOpen)
                Imgproc.morphologyEx(maskWall, maskWall, Imgproc.MORPH_CLOSE, kernelClose)


                // Imgproc.morphologyEx(maskMagenta, maskMagenta, Imgproc.MORPH_OPEN, kernel)
                // Imgproc.morphologyEx(maskMagenta, maskMagenta, Imgproc.MORPH_CLOSE, kernel)

                // =============================================================================
                // 7. DETECCIÓN DE CONTORNOS
                // =============================================================================

                val contoursRed = ArrayList<MatOfPoint>()
                val contoursGreen = ArrayList<MatOfPoint>()
                val contoursMagenta = ArrayList<MatOfPoint>()
                val contoursWall = ArrayList<MatOfPoint>()   // ← NUEVO


                Imgproc.findContours(maskRed, contoursRed, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                Imgproc.findContours(maskGreen, contoursGreen, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                if (!maskMagenta.empty()) {
                    Imgproc.findContours(maskMagenta, contoursMagenta, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                }
                Imgproc.findContours(maskWall, contoursWall, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

                if (debugMode) {
                    Log.d("ColorAnalyzer", "Contornos encontrados - Rojos: ${contoursRed.size}, Verdes: ${contoursGreen.size}, Magentas: ${contoursMagenta.size}")
                }

                // =============================================================================
                // 8. SUAVIZADO DE CONTORNOS
                // =============================================================================

                // Aplicar suavizado avanzado a los contornos si está habilitado
                val smoothedRedContours = if (enableContourSmoothing) {
                    contoursRed.map { smoothContourAdvanced(it) }
                } else {
                    contoursRed
                }

                val smoothedGreenContours = if (enableContourSmoothing) {
                    contoursGreen.map { smoothContourAdvanced(it) }
                } else {
                    contoursGreen
                }

                // val smoothedMagentaContours = if (enableContourSmoothing) {
                //     contoursMagenta.map { smoothContourAdvanced(it) }
                // } else {
                //     contoursMagenta
                // }

                // =============================================================================
                // 9. FILTRADO GEOMÉTRICO DE CONTORNOS
                // =============================================================================

                // Aplicar filtros para validar formas rectangulares
                lastRejectedObjects.clear()
                // Separar ANTES de aplicar el filtro estricto de "forma de pilar" — el
// aspect ratio del boundingRect es válido sin importar qué tan irregular
// sea el contorno por dentro (no depende de solidez/convexidad como
// isRegularShape, que puede rechazar el estacionamiento antes de llegar a
// medir su forma real).
                val wideRedContours = mutableListOf<MatOfPoint>()

                val validRedContours = smoothedRedContours.filter { contour ->
                    val area = Imgproc.contourArea(contour)
                    if (area <= minContourArea) {
                        if (debugMode) Log.d("ColorAnalyzer", "Contorno rojo rechazado por área pequeña: $area < $minContourArea")
                        lastRejectedObjects.add(RejectedInfo("Rojo", "área pequeña (${area.toInt()}px)", area))
                        return@filter false
                    }

                    val boundingRect = Imgproc.boundingRect(contour)
                    val aspectRatio = boundingRect.width.toDouble() / boundingRect.height.toDouble()

                    if (aspectRatio > maxPillarAspectRatio) {
                        if (debugMode) {
                            Log.d("ColorAnalyzer", "🔴➡️🟣 Rojo ancho detectado ANTES del filtro de pilar: aspect=${"%.2f".format(aspectRatio)} > máx=$maxPillarAspectRatio (área=${area.toInt()}px) — va como Estacionamiento")
                        }
                        wideRedContours.add(contour)
                        return@filter false
                    }

                    if (calibrationMode) {
                        isRegularShape(contour, area) && isRectangularShape(contour, area)
                    } else if (enableShapeFiltering) {
                        val basicCheck = isRegularShape(contour, area)
                        val advancedCheck = if (enableAdvancedFiltering) isRectangularShape(contour, area) else true
                        val reflectionCheck = if (enableSaturationReflectionFilter) hasRealObjectSaturation(contour, equalizedHSV, minInternalSaturationRed, "Rojo") else true
                        val passed = basicCheck && advancedCheck && reflectionCheck
                        if (!passed) {
                            val reason = when {
                                !basicCheck -> "forma irregular"
                                !advancedCheck -> "no rectangular"
                                !reflectionCheck -> "reflejo (sat. baja)"
                                else -> "rechazado"
                            }
                            lastRejectedObjects.add(RejectedInfo("Rojo", reason, area))
                        }
                        passed
                    } else {
                        val passed = if (enableSaturationReflectionFilter) hasRealObjectSaturation(contour, equalizedHSV, minInternalSaturationRed, "Rojo") else true
                        if (!passed) lastRejectedObjects.add(RejectedInfo("Rojo", "reflejo (sat. baja)", area))
                        passed
                    }
                }



                val validGreenContours = smoothedGreenContours.filter { contour ->
                    val area = Imgproc.contourArea(contour)
                    if (area > minContourArea) {
                        if (calibrationMode) {
                            isRegularShape(contour, area) && isRectangularShape(contour, area)
                        } else if (enableShapeFiltering) {
                            val basicCheck = isRegularShape(contour, area)
                            val advancedCheck = if (enableAdvancedFiltering) isRectangularShape(contour, area) else true
                            val reflectionCheck = if (enableSaturationReflectionFilter) hasRealObjectSaturation(contour, equalizedHSV, minInternalSaturationGreen, "Verde") else true
                            val passed = basicCheck && advancedCheck && reflectionCheck
                            if (!passed) {
                                val reason = when {
                                    !basicCheck -> "forma irregular"
                                    !advancedCheck -> "no rectangular"
                                    !reflectionCheck -> "reflejo (sat. baja)"
                                    else -> "rechazado"
                                }
                                lastRejectedObjects.add(RejectedInfo("Verde", reason, area))
                            }
                            passed
                        } else {
                            val passed = if (enableSaturationReflectionFilter) hasRealObjectSaturation(contour, equalizedHSV, minInternalSaturationGreen, "Verde") else true
                            if (!passed) lastRejectedObjects.add(RejectedInfo("Verde", "reflejo (sat. baja)", area))
                            passed
                        }
                    } else {
                        if (debugMode) Log.d("ColorAnalyzer", "Contorno verde rechazado por área pequeña: $area < $minContourArea")
                        lastRejectedObjects.add(RejectedInfo("Verde", "área pequeña (${area.toInt()}px)", area))
                        false
                    }
                }



                val validMagentaContours = if (true) {
                    val smoothedMagentaContours = if (enableContourSmoothing) {
                        contoursMagenta.map { smoothContourAdvanced(it) }
                    } else {
                        contoursMagenta
                    }
                    smoothedMagentaContours.filter { contour ->
                        val area = Imgproc.contourArea(contour)
                        if (area > minMagentaContourArea) {
                            if (calibrationMode) {
                                isRegularShapeMagenta(contour, area) && isRectangularShapeMagenta(contour, area)
                            } else if (enableShapeFiltering) {
                                val basicCheck = isRegularShapeMagenta(contour, area)
                                val advancedCheck = if (enableAdvancedFiltering) isRectangularShapeMagenta(contour, area) else true
                                val reflectionCheck = if (enableSaturationReflectionFilter) hasRealObjectSaturation(contour, equalizedHSV, minInternalSaturationRed, "Magenta") else true
                                val passed = basicCheck && advancedCheck && reflectionCheck
                                if (!passed) {
                                    val reason = when {
                                        !basicCheck -> "forma irregular"
                                        !advancedCheck -> "no rectangular"
                                        !reflectionCheck -> "reflejo (sat. baja)"
                                        else -> "rechazado"
                                    }
                                    lastRejectedObjects.add(RejectedInfo("Magenta", reason, area))
                                }
                                passed
                            } else {
                                val passed = if (enableSaturationReflectionFilter) hasRealObjectSaturation(contour, equalizedHSV, minInternalSaturationRed, "Magenta") else true
                                if (!passed) lastRejectedObjects.add(RejectedInfo("Magenta", "reflejo (sat. baja)", area))
                                passed
                            }
                        } else {
                            lastRejectedObjects.add(RejectedInfo("Magenta", "área pequeña (${area.toInt()}px)", area))
                            false
                        }
                    }
                } else emptyList()

                if (debugMode) {
                    Log.d("ColorAnalyzer", "Contornos válidos después del filtrado - Rojos: ${validRedContours.size}, Verdes: ${validGreenContours.size}, Magentas: ${validMagentaContours.size}")
                }


                if (debugMode) {
                    // Log.d("ColorAnalyzer", "Contornos válidos después del filtrado - Rojos: ${validRedContours.size}, Verdes: ${validGreenContours.size}, Magentas: ${validMagentaContours.size}")
                    Log.d("ColorAnalyzer", "Contornos válidos después del filtrado - Rojos: ${validRedContours.size}, Verdes: ${validGreenContours.size}")
                }

                // =============================================================================
                // 10. SELECCIÓN DE LOS DOS OBJETOS MÁS GRANDES (TODOS LOS COLORES)
                // =============================================================================

                // Crear lista de todos los contornos válidos con su información
                data class ContourCandidate(
                    val contour: MatOfPoint,
                    val color: String,
                    val area: Double,
                    val qualityScore: Double,
                    val estimatedDistance: Double
                )

                val allValidContours = mutableListOf<ContourCandidate>()

                validRedContours.forEach { contour ->
                    val area = Imgproc.contourArea(contour)
                    val estimatedDistance = estimateRawDistance(area)
                    if (estimatedDistance <= ConfigSettings.backDistanceMax) {
                        val score = computeRealObjectScore(contour, equalizedHSV)

                        if (area > maxPlausibleRedGreenArea) {
                            if (debugMode) {
                                Log.d("ColorAnalyzer", "🔴➡️🟣 Rojo reclasificado a Estacionamiento: área=${area.toInt()}px > máx plausible=${maxPlausibleRedGreenArea.toInt()}px")
                            }
                            allValidContours.add(ContourCandidate(contour, "Magenta", area, score, estimatedDistance))
                        } else {
                            allValidContours.add(ContourCandidate(contour, "Rojo", area, score, estimatedDistance))
                        }
                    } else if (debugMode) {
                        Log.d("ColorAnalyzer", "Contorno Rojo descartado: fuera de rango (${estimatedDistance.toInt()}cm > máx ${ConfigSettings.backDistanceMax}cm)")
                    }
                }


                wideRedContours.forEach { contour ->
                    val area = Imgproc.contourArea(contour)
                    val estimatedDistance = estimateRawDistance(area)
                    val satOk = if (enableSaturationReflectionFilter) hasRealObjectSaturation(contour, equalizedHSV, minInternalSaturationRed, "Estacionamiento(ancho)") else true
                    if (satOk && estimatedDistance <= ConfigSettings.backDistanceMax) {
                        val score = computeRealObjectScore(contour, equalizedHSV)
                        allValidContours.add(ContourCandidate(contour, "Magenta", area, score, estimatedDistance))
                    } else if (debugMode) {
                        Log.d("ColorAnalyzer", "Rojo ancho descartado: sat=$satOk, dist=${estimatedDistance.toInt()}cm")
                    }
                }


// Bloque de Verde — volver a esta versión simple, SIN reclasificación:
                validGreenContours.forEach { contour ->
                    val area = Imgproc.contourArea(contour)
                    val estimatedDistance = estimateRawDistance(area)
                    if (estimatedDistance <= ConfigSettings.backDistanceMax) {
                        val score = computeRealObjectScore(contour, equalizedHSV)
                        allValidContours.add(ContourCandidate(contour, "Verde", area, score, estimatedDistance))
                    } else if (debugMode) {
                        Log.d("ColorAnalyzer", "Contorno Verde descartado: fuera de rango (${estimatedDistance.toInt()}cm > máx ${ConfigSettings.backDistanceMax}cm)")
                    }
                }


                validMagentaContours.forEach { contour ->
                    val area = Imgproc.contourArea(contour)
                    val estimatedDistance = estimateRawDistance(area)
                    if (estimatedDistance <= ConfigSettings.backDistanceMax) {
                        val score = computeRealObjectScore(contour, equalizedHSV)
                        allValidContours.add(ContourCandidate(contour, "Magenta", area, score, estimatedDistance))
                    } else if (debugMode) {
                        Log.d("ColorAnalyzer", "Contorno Magenta descartado: fuera de rango (${estimatedDistance.toInt()}cm > máx ${ConfigSettings.backDistanceMax}cm)")
                    }
                }

                val sortedContours = allValidContours.sortedWith(
                    compareBy<ContourCandidate> { it.estimatedDistance }
                        .thenByDescending { it.qualityScore }
                )

                if (debugMode) {
                    Log.d("ColorAnalyzer", "Total contornos válidos encontrados: ${sortedContours.size}")
                    sortedContours.forEachIndexed { index, c ->
                        Log.d("ColorAnalyzer", "Objeto $index: Color=${c.color}, Área=${c.area.toInt()}, Dist=${c.estimatedDistance.toInt()}cm, Score=${"%.3f".format(c.qualityScore)}")
                    }
                }

                val magentaCandidateNearby = sortedContours
                    .filter { it.color == "Magenta" }
                    .maxByOrNull { it.area }

                val magentaVisible = magentaCandidateNearby != null && magentaCandidateNearby.area >= minMagentaActivationArea

// Detección de la pared negra del estacionamiento — señal INDEPENDIENTE del
// color del delimitador, no puede confundirse con un pilar Rojo/Verde real
// porque nunca mira Hue, solo brillo/saturación bajos pegados al borde.
data class WallCandidateInfo(
    val contour: MatOfPoint,
    val area: Double,
    val boundingRect: org.opencv.core.Rect,
    val rectangularity: Double,
    val aspectRatio: Double,
    val touchesBottom: Boolean,
    val touchesLeft: Boolean,
    val touchesRight: Boolean
)

                // Evaluar TODOS los candidatos oscuros (no solo el más grande) para
                // poder loguear por qué se aceptan o descartan.
                // NUEVO: si el switch está apagado, ni se calcula — lista vacía directo.
                val wallCandidatesInfo = if (ConfigSettings.wallDetectionEnabled) {
                    contoursWall
                        .map { c -> Pair(c, Imgproc.contourArea(c)) }
                        .filter { it.second >= wallMinContourArea }
                        .map { (contour, area) ->
                            val br = Imgproc.boundingRect(contour)
                            val rectangularity = area / (br.width * br.height).toDouble()
                            val aspectRatio = br.width.toDouble() / br.height.toDouble()
                            val touchesBottom = (br.y + br.height) >= roiMat.height() * (1f - wallEdgeTouchMargin)
                            val touchesLeft = br.x <= roiMat.width() * wallEdgeTouchMargin
                            val touchesRight = (br.x + br.width) >= roiMat.width() * (1f - wallEdgeTouchMargin)
                            WallCandidateInfo(contour, area, br, rectangularity, aspectRatio, touchesBottom, touchesLeft, touchesRight)
                        }
                        .sortedByDescending { it.area }
                } else {
                    emptyList()
                }

                if (debugMode) {
                    wallCandidatesInfo.forEachIndexed { i, w ->
                        Log.d("ColorAnalyzer", "⬛ Candidato pared #$i: área=${w.area.toInt()}px, rect=${"%.2f".format(w.rectangularity)} (mín=$minWallRectangularity), aspect=${"%.2f".format(w.aspectRatio)}, tocaAbajo=${w.touchesBottom}, tocaIzq=${w.touchesLeft}, tocaDer=${w.touchesRight}")
                    }
                }

                // Ahora sí exige forma de pared: rectangular Y apoyada en el piso.
                val wallCandidate = wallCandidatesInfo.firstOrNull {
                    it.rectangularity >= minWallRectangularity && it.touchesBottom
                }

                var wallSide: String? = null
                var wallDebugText = ""

                if (wallCandidate != null) {
                    wallSide = when {
                        wallCandidate.touchesLeft && !wallCandidate.touchesRight -> "L"
                        wallCandidate.touchesRight && !wallCandidate.touchesLeft -> "R"
                        else -> null
                    }
                    wallDebugText = "⬛✅ Pared: área=${wallCandidate.area.toInt()}px, rect=${"%.2f".format(wallCandidate.rectangularity)}, aspect=${"%.2f".format(wallCandidate.aspectRatio)}, lado=${wallSide ?: "?"}"
                    if (debugMode) Log.d("ColorAnalyzer", wallDebugText)
                } else if (debugMode && wallCandidatesInfo.isNotEmpty()) {
                    val top = wallCandidatesInfo.first()
                    wallDebugText = "⬛❌ Pared descartada: área=${top.area.toInt()}px, rect=${"%.2f".format(top.rectangularity)} (mín $minWallRectangularity), tocaAbajo=${top.touchesBottom}"
                    Log.d("ColorAnalyzer", wallDebugText)
                }

// Texto de diagnóstico visible en pantalla (magenta + pared)
                var magentaDebugText = ""

                if (magentaCandidateNearby != null && !magentaVisible) {
                    magentaDebugText = "🟣⚠️ Magenta LEJOS (ignorado): área=${magentaCandidateNearby.area.toInt()}px (mín=${minMagentaActivationArea.toInt()}px)"
                    if (debugMode) Log.d("ColorAnalyzer", magentaDebugText)
                }

// El modo se activa por CUALQUIERA de las dos señales — color O pared negra.
                // El switch general apaga TODA la activación (comandos de estacionar),
                // sin importar lo que digan magentaVisible o wallSide.
                val parkingActive = ConfigSettings.parkingDetectionEnabled && (magentaVisible || (wallSide != null))


                if (parkingActive) {
                    CommandManager.activateParkingMode()
                    val activationSource = if (wallSide != null) "Pared" else "Magenta"
                    magentaDebugText = "🟣✅ Estacionamiento ACTIVO (fuente: $activationSource)" +
                            (if (wallDebugText.isNotEmpty()) "\n$wallDebugText" else "") +
                            (if (magentaVisible) "\n🟣 Magenta área=${magentaCandidateNearby!!.area.toInt()}px" else "")

                    if (CommandManager.parkingSide == null) {
                        val side = wallSide ?: run {
                            // Sin lectura de pared confiable, usar el lado calculado por color magenta
                            val m = Imgproc.moments(magentaCandidateNearby!!.contour)
                            val mx = (m.m10 / m.m00).toFloat()
                            val my = (m.m01 / m.m00).toFloat()
                            CommandManager.calculateOrientation(mx, my, roiMat.width(), roiMat.height())
                        }
                        CommandManager.setParkingSide(side)
                    }
                } else {
                    CommandManager.deactivateParkingMode()

                    if (debugMode && wallDebugText.isNotEmpty()) {
                        magentaDebugText = wallDebugText
                    }

                }


                //los objetos "target" para primario/secundario son solo Rojo/Verde.
                // El magenta ya cumplió su función (activar el modo) y no debe competir
                // por el slot de primario/secundario ni robarle el comando a un pilar real.
// En modo calibración no hay pilares compitiendo por el slot primario — el
// usuario apunta un solo color a la vez, así que el Magenta SÍ debe poder
// ser primario para que se capture su muestra de Hue (lastPrimaryHsvSample).
// En operación normal (pista), el Magenta sigue excluido para no robarle
// el comando a un pilar rojo/verde real.
                val targetContours = if (calibrationMode) {
                    sortedContours
                } else {
                    sortedContours.filter { it.color != "Magenta" }
                }

                // =============================================================================
                // 11. PROCESAMIENTO DE HASTA DOS OBJETOS DETECTADOS
                // =============================================================================

                var primaryObject: ObjectPosition? = null
                var secondaryObject: ObjectPosition? = null
                var detectedColor = "No se detecta color significativo"

                // Procesar el objeto primario (más grande/cercano)
                if (targetContours.isNotEmpty()) {
                    val primaryContour = targetContours[0].contour
                    val primaryColorStr = targetContours[0].color
                    val primaryArea = targetContours[0].area

                    // Guardar muestra HSV promedio para la ventana de calibración por muestras
                    val (sampleH, sampleS, sampleV) = computeContourMeanHsv(primaryContour, equalizedHSV)
                    lastPrimaryHsvSample = HsvSample(sampleH, sampleS, sampleV, primaryColorStr, primaryArea)

                    val primaryMoments = Imgproc.moments(primaryContour)
                    val primaryCenterX = (primaryMoments.m10 / primaryMoments.m00).toFloat()
                    val primaryCenterY = (primaryMoments.m01 / primaryMoments.m00).toFloat()

                    // CORRECCIÓN: Usar CommandManager como única fuente de verdad para la orientación.
                    val primaryOrientation = CommandManager.calculateOrientation(
                        primaryCenterX,
                        primaryCenterY,
                        roiMat.width(),
                        roiMat.height()
                    )

                    primaryObject = ObjectPosition(
                        (primaryCenterX / roiMat.width()).toFloat(),
                        (primaryCenterY / roiMat.height()).toFloat(),
                        primaryArea,
                        primaryColorStr,
                        primaryCenterX,
                        primaryCenterY,
                        roiMat.width(),
                        roiMat.height()
                    )

                    detectedColor = "$primaryColorStr detectado (Principal) - Pos: $primaryOrientation"
                    detectedArea = primaryArea
                    centerX = primaryCenterX
                    centerY = primaryCenterY

                    // Dibujar contorno primario en Naranja
                    Imgproc.drawContours(roiMat, listOf(primaryContour), -1, Scalar(255.0, 164.0, 32.0), 3)

                    // Dibujar información del objeto primario
                    drawObjectInfo(roiMat, primaryContour, primaryColorStr, primaryArea, "P", Scalar(255.0, 164.0, 32.0))
                }


                // Procesar el objeto secundario (segundo más grande)
                if (targetContours.size >= 2) {
                    val secondaryContour = targetContours[1].contour
                    val secondaryColorStr = targetContours[1].color
                    val secondaryArea = targetContours[1].area

                    val secondaryMoments = Imgproc.moments(secondaryContour)
                    val secondaryCenterX = (secondaryMoments.m10 / secondaryMoments.m00).toFloat()
                    val secondaryCenterY = (secondaryMoments.m01 / secondaryMoments.m00).toFloat()

                    // CORRECCIÓN: Usar CommandManager también para el objeto secundario.
                    val secondaryOrientation = CommandManager.calculateOrientation(
                        secondaryCenterX,
                        secondaryCenterY,
                        roiMat.width(),
                        roiMat.height()
                    )

                    secondaryObject = ObjectPosition(
                        (secondaryCenterX / roiMat.width()).toFloat(),
                        (secondaryCenterY / roiMat.height()).toFloat(),
                        secondaryArea,
                        secondaryColorStr,
                        secondaryCenterX,
                        secondaryCenterY,
                        roiMat.width(),
                        roiMat.height()
                    )

                    detectedColor += " + $secondaryColorStr (Secundario) - Pos: $secondaryOrientation"

                    // Dibujar contorno secundario en CYAN
                    Imgproc.drawContours(roiMat, listOf(secondaryContour), -1, Scalar(0.0, 183.0, 235.0), 2)

                    // Dibujar información del objeto secundario
                    drawObjectInfo(roiMat, secondaryContour, secondaryColorStr, secondaryArea, "S", Scalar(0.0, 183.0, 235.0))

                }

                if (magentaDebugText.isNotEmpty()) {
                    detectedColor = "$magentaDebugText\n$detectedColor"
                }


                // Actualizar detección dual global
                dualDetection = DualObjectDetection(primaryObject, secondaryObject)
                lastDualDetection = dualDetection

                // =============================================================================
                // 12. PREPARACIÓN DE IMAGEN DE SALIDA
                // =============================================================================

                // Convertir Mat procesado a Bitmap
                val roiBitmap = Bitmap.createBitmap(roiMat.cols(), roiMat.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(roiMat, roiBitmap)

                // Rotar imagen 90 grados para orientación correcta
                val matrix = Matrix()
                matrix.postRotate(90f)
                val rotatedBitmap = Bitmap.createBitmap(roiBitmap, 0, 0, roiBitmap.width, roiBitmap.height, matrix, true)

                // Enviar resultados a través del callback
                callback(detectedColor, rotatedBitmap, dualDetection)

                // =============================================================================
                // 13. LIBERACIÓN DE MEMORIA
                // =============================================================================

                // Liberar todas las matrices para evitar memory leaks
                equalizedHSV.release()
                maskRed1.release()
                maskRed2.release()
                maskRed.release()
                maskGreen.release()
                maskMagenta.release()
                maskWall.release()
                kernelOpen.release()
                kernelClose.release()
                roiMat.release()
            } else {
                callback("Error: ROI es nulo", null, DualObjectDetection(null, null))
            }

            matOriginal.release()

        } catch (e: Exception) {
            Log.e("ColorAnalyzer", "Error en el análisis: ${e.message}", e)
            callback("Error: ${e.message}", null, DualObjectDetection(null, null))
        } finally {
            image.close()
        }
    }

    /**
     * Aplica ecualización de histograma al canal V (brillo) de una imagen HSV
     * para normalizar la iluminación y mejorar la detección de colores.
     *
     * Utiliza CLAHE (Contrast Limited Adaptive Histogram Equalization) para
     * evitar sobreecualización y mantener detalles locales.
     *
     * @param matHSV Imagen en formato HSV
     * @return Nueva imagen HSV con el canal V ecualizado
     */
    private fun applyHistogramEqualizationToV(matHSV: Mat): Mat {
        try {
            // Separar los canales H, S, V
            val hsvChannels = ArrayList<Mat>()
            Core.split(matHSV, hsvChannels)

            if (hsvChannels.size != 3) {
                Log.e("ColorAnalyzer", "Error: La imagen HSV no tiene 3 canales")
                return matHSV.clone()
            }

            val channelV = hsvChannels[2] // Canal V (brillo)
            val equalizedV = Mat()

            if (enableCLAHE) {
                // Usar CLAHE para ecualización adaptativa con límite de contraste
                val clahe = Imgproc.createCLAHE(claheClipLimit, claheTileGridSize)
                clahe.apply(channelV, equalizedV)
            } else {
                // Usar ecualización estándar de histograma
                Imgproc.equalizeHist(channelV, equalizedV)
            }

            // Recombinar canales: H y S originales + V ecualizado
            val finalChannels = ArrayList<Mat>()
            finalChannels.add(hsvChannels[0]) // Canal H original
            finalChannels.add(hsvChannels[1]) // Canal S original
            finalChannels.add(equalizedV)     // Canal V ecualizado

            val equalizedHSV = Mat()
            Core.merge(finalChannels, equalizedHSV)

            channelV.release() // Liberar canal V original

            if (debugMode) {
                Log.d("ColorAnalyzer", "✅ Ecualización completada exitosamente")
            }

            return equalizedHSV

        } catch (e: Exception) {
            Log.e("ColorAnalyzer", "Error en ecualización de histograma: ${e.message}", e)
            return matHSV.clone()
        }
    }

    /**
     * Suaviza un contorno usando aproximación poligonal con doble pasada
     * para eliminar irregularidades mientras preserva la forma general.
     *
     * Primera pasada: Suavizado agresivo para eliminar ruido
     * Segunda pasada: Suavizado fino para preservar detalles importantes
     *
     * @param contour Contorno original a suavizar
     * @return Contorno suavizado
     */
    /**
     * FUNCIÓN DE SUAVIZADO AVANZADO DE CONTORNOS
     * Aplica un algoritmo de suavizado en dos pasadas para reducir el ruido
     * y simplificar la forma del contorno manteniendo sus características principales
     */
    private fun smoothContourAdvanced(contour: MatOfPoint): MatOfPoint {
        try {
            // Convertir a formato de punto flotante para mayor precisión en los cálculos
            val contour2f = MatOfPoint2f()
            contour.convertTo(contour2f, CvType.CV_32FC2)

            // PRIMERA PASADA: Suavizado agresivo
            // Elimina detalles finos y ruido usando un epsilon más grande
            val smoothed1 = MatOfPoint2f()
            val epsilon1 = smoothingEpsilon * Imgproc.arcLength(contour2f, true)
            Imgproc.approxPolyDP(contour2f, smoothed1, epsilon1, true)

            // SEGUNDA PASADA: Suavizado fino (solo si está habilitado)
            // Refina el contorno con un epsilon menor para preservar detalles importantes
            val finalSmoothed = if (enableDoubleSmoothing && smoothed1.total() > 4) {
                val smoothed2 = MatOfPoint2f()
                val epsilon2 = (smoothingEpsilon * 0.5) * Imgproc.arcLength(smoothed1, true)
                Imgproc.approxPolyDP(smoothed1, smoothed2, epsilon2, true)
                smoothed2
            } else {
                smoothed1
            }

            // Convertir de vuelta a formato entero para compatibilidad con OpenCV
            val result = MatOfPoint()
            finalSmoothed.convertTo(result, CvType.CV_32S)

            // Liberar memoria de matrices temporales para evitar memory leaks
            contour2f.release()
            smoothed1.release()
            if (enableDoubleSmoothing) {
                finalSmoothed.release()
            }

            return result
        } catch (e: Exception) {
            Log.w("ColorAnalyzer", "Error suavizando contorno: ${e.message}")
            return contour // Retorna el contorno original si hay error
        }
    }

    /**
     * DETECTOR ESPECIALIZADO PARA FORMAS RECTANGULARES
     * Implementa múltiples filtros geométricos para identificar formas rectangulares
     * y rechazar formas orgánicas como hojas o formas irregulares
     */
    private fun isRectangularShape(contour: MatOfPoint, area: Double): Boolean {
        try {
            // 1. CALCULAR RECTANGULARIDAD (qué tan parecido a un rectángulo)
            // Compara el área del contorno con el área de su bounding rectangle
            val boundingRect = Imgproc.boundingRect(contour)
            val boundingRectArea = boundingRect.width * boundingRect.height
            val rectangularity = area / boundingRectArea.toDouble()

            if (debugMode) {
                Log.d("ColorAnalyzer", "Rectangularidad: $rectangularity (mín: $minRectangularity)")
            }

            // Si la forma llena muy poco su bounding rectangle, probablemente no es rectangular
            if (rectangularity < minRectangularity) {
                if (debugMode) Log.d("ColorAnalyzer", "❌ Rechazado por baja rectangularidad: $rectangularity")
                return false
            }

            // 2. VERIFICAR CONVEXIDAD (formas orgánicas tienden a ser menos convexas)
            // Calcula el hull convexo y compara su área con el área original
            val hull = MatOfInt()
            Imgproc.convexHull(contour, hull)

            val hullPoints = hull.toArray()
            val contourPoints = contour.toArray()
            val hullContour = MatOfPoint()

            // Construir el contorno del hull convexo
            val hullPointsList = mutableListOf<Point>()
            for (index in hullPoints) {
                if (index < contourPoints.size) {
                    hullPointsList.add(contourPoints[index])
                }
            }

            hullContour.fromArray(*hullPointsList.toTypedArray())
            val hullArea = Imgproc.contourArea(hullContour)
            val convexity = if (hullArea > 0) area / hullArea else 0.0

            if (debugMode) {
                Log.d("ColorAnalyzer", "Convexidad: $convexity (mín: $minConvexity)")
            }

            // Las formas muy cóncavas (con entrantes) son rechazadas
            if (convexity < minConvexity) {
                if (debugMode) Log.d("ColorAnalyzer", "❌ Rechazado por baja convexidad: $convexity")
                hull.release()
                hullContour.release()
                return false
            }

            // 3. CONTAR DEFECTOS DE CONVEXIDAD (hojas tienen muchos)
            // Los defectos de convexidad indican "entrantes" en la forma
            try {
                val defects = MatOfInt4()
                Imgproc.convexityDefects(contour, hull, defects)
                val numDefects = defects.rows()

                if (debugMode) {
                    Log.d("ColorAnalyzer", "Defectos de convexidad: $numDefects (máx: $maxConvexityDefects)")
                }

                // Muchos defectos = forma muy irregular (como una hoja dentada)
                if (numDefects > maxConvexityDefects) {
                    if (debugMode) Log.d("ColorAnalyzer", "❌ Rechazado por muchos defectos: $numDefects")
                    hull.release()
                    hullContour.release()
                    defects.release()
                    return false
                }
                defects.release()
            } catch (e: Exception) {
                // Algunos contornos pueden no tener defectos calculables
                if (debugMode) Log.w("ColorAnalyzer", "No se pudieron calcular defectos de convexidad: ${e.message}")
            }

            // 4. VERIFICAR ÁNGULOS EN LOS VÉRTICES (detectar picos triangulares)
            // Busca ángulos muy agudos que indican formas puntiagudas como hojas
            if (enableSpikeDetection) {
                val hasSpikes = detectSharpAngles(contour)
                if (hasSpikes) {
                    if (debugMode) Log.d("ColorAnalyzer", "❌ Rechazado por picos triangulares detectados")
                    hull.release()
                    hullContour.release()
                    return false
                }
            }

            // 5. VERIFICAR NÚMERO DE VÉRTICES DESPUÉS DEL SUAVIZADO
            // Las formas rectangulares deben tener entre 4-12 vértices aproximadamente
            val contour2f = MatOfPoint2f(*contour.toArray())
            val epsilon = 0.02 * Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, epsilon, true)

            val vertices = approx.toArray().size

            if (debugMode) {
                Log.d("ColorAnalyzer", "Vértices finales: $vertices")
            }

            // Muy pocos vértices = demasiado simple, muchos = demasiado complejo
            if (vertices < 4 || vertices > 12) {
                if (debugMode) Log.d("ColorAnalyzer", "❌ Rechazado por número de vértices: $vertices")
                hull.release()
                hullContour.release()
                contour2f.release()
                approx.release()
                return false
            }

            // Liberar memoria de todas las matrices temporales
            hull.release()
            hullContour.release()
            contour2f.release()
            approx.release()

            if (debugMode) {
                Log.d("ColorAnalyzer", "✅ Forma RECTANGULAR aceptada - Todos los filtros avanzados pasados")
            }

            return true

        } catch (e: Exception) {
            Log.e("ColorAnalyzer", "Error en filtro rectangular: ${e.message}")
            return false
        }
    }

    /**
     * DETECTOR DE PICOS TRIANGULARES EN CONTORNOS
     * Analiza los ángulos entre puntos consecutivos del contorno
     * para detectar formas puntiagudas características de hojas u objetos orgánicos
     */
    private fun detectSharpAngles(contour: MatOfPoint): Boolean {
        try {
            val points = contour.toArray()
            if (points.size < 3) return false // Necesitamos al menos 3 puntos para calcular ángulos

            var sharpAnglesCount = 0
            val minPoints = minOf(points.size, 20) // Verificar máximo 20 puntos para eficiencia

            // Recorrer puntos consecutivos para calcular ángulos
            for (i in 0 until minPoints) {
                val p1 = points[i]                           // Punto anterior
                val p2 = points[(i + 1) % points.size]      // Punto central (vértice)
                val p3 = points[(i + 2) % points.size]      // Punto siguiente

                // Calcular vectores desde el punto central hacia los otros dos
                val v1x = p1.x - p2.x
                val v1y = p1.y - p2.y
                val v2x = p3.x - p2.x
                val v2y = p3.y - p2.y

                // Calcular ángulo entre vectores usando producto punto
                val dot = v1x * v2x + v1y * v2y              // Producto punto
                val mag1 = sqrt(v1x * v1x + v1y * v1y)       // Magnitud del vector 1
                val mag2 = sqrt(v2x * v2x + v2y * v2y)       // Magnitud del vector 2

                if (mag1 > 0 && mag2 > 0) {
                    val cosAngle = dot / (mag1 * mag2)
                    // Convertir de radianes a grados y asegurar rango válido para acos
                    val angle = Math.toDegrees(acos(cosAngle.coerceIn(-1.0, 1.0)))

                    // Si el ángulo es muy agudo (pico triangular)
                    if (angle < maxAngleThreshold) {
                        sharpAnglesCount++
                        // Más de 2 ángulos agudos indica una forma muy irregular
                        if (sharpAnglesCount > 2) {
                            if (debugMode) {
                                Log.d("ColorAnalyzer", "Pico detectado: ángulo = ${angle.toInt()}°")
                            }
                            return true
                        }
                    }
                }
            }

            return false // No se encontraron suficientes ángulos agudos
        } catch (e: Exception) {
            Log.w("ColorAnalyzer", "Error detectando picos: ${e.message}")
            return false
        }
    }

    /**
     * FILTRO PRINCIPAL DE FORMAS REGULARES
     * Implementa múltiples métricas geométricas para determinar
     * si una forma es suficientemente regular para ser considerada válida
     */
    private fun isRegularShape(contour: MatOfPoint, area: Double): Boolean {
        try {
            // 1. CALCULAR SOLIDEZ (Solidity)
            // Mide qué tan "sólida" es la forma comparando con su hull convexo
            val hull = MatOfInt()
            Imgproc.convexHull(contour, hull)

            val hullPoints = hull.toArray()
            val contourPoints = contour.toArray()
            val hullContour = MatOfPoint()

            // Construir el contorno del hull convexo
            val hullPointsList = mutableListOf<Point>()
            for (index in hullPoints) {
                if (index < contourPoints.size) {
                    hullPointsList.add(contourPoints[index])
                }
            }

            hullContour.fromArray(*hullPointsList.toTypedArray())
            val hullArea = Imgproc.contourArea(hullContour)

            // Solidez = área_original / área_hull_convexo
            val solidity = if (hullArea > 0) area / hullArea else 0.0

            if (debugMode) {
                Log.d("ColorAnalyzer", "Solidez: $solidity (mín: $minSolidity)")
            }

            // Formas con baja solidez tienen muchas concavidades
            if (solidity < minSolidity) {
                if (debugMode) Log.d("ColorAnalyzer", "Rechazado por baja solidez: $solidity")
                return false
            }

            // 2. CALCULAR ASPECT RATIO (Relación de aspecto)
            // Evalúa si la forma es demasiado alargada o demasiado plana
            val boundingRect = Imgproc.boundingRect(contour)
            val aspectRatio = boundingRect.width.toDouble() / boundingRect.height.toDouble()

            if (debugMode) {
                Log.d("ColorAnalyzer", "Aspect Ratio: $aspectRatio (rango: $minAspectRatio - $maxAspectRatio)")
            }

            // Rechazar formas excesivamente alargadas (como ramas) o muy planas
            if (aspectRatio < minAspectRatio || aspectRatio > maxAspectRatio) {
                if (debugMode) Log.d("ColorAnalyzer", "Rechazado por aspect ratio: $aspectRatio")
                return false
            }

            // 3. CALCULAR EXTENT (Extensión)
            // Mide qué porcentaje del bounding rectangle está ocupado por la forma
            val boundingRectArea = boundingRect.width * boundingRect.height
            val extent = area / boundingRectArea.toDouble()

            if (debugMode) {
                Log.d("ColorAnalyzer", "Extent: $extent (mín: $minExtent)")
            }

            // Formas que ocupan muy poco de su bounding rectangle son irregulares
            if (extent < minExtent) {
                if (debugMode) Log.d("ColorAnalyzer", "Rechazado por baja extensión: $extent")
                return false
            }

            // 4. CALCULAR RATIO PERÍMETRO²/ÁREA (Complejidad de forma)
            // Formas complejas tienen perímetros largos relativos a su área
            val perimeter = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
            val perimeterAreaRatio = (perimeter * perimeter) / area

            if (debugMode) {
                Log.d("ColorAnalyzer", "Perímetro²/Área: $perimeterAreaRatio (máx: $maxPerimeterAreaRatio)")
            }

            // Un ratio alto indica una forma muy irregular (como hojas dentadas)
            if (perimeterAreaRatio > maxPerimeterAreaRatio) {
                if (debugMode) Log.d("ColorAnalyzer", "Rechazado por alta complejidad: $perimeterAreaRatio")
                return false
            }

            // Liberar memoria de matrices temporales
            hull.release()
            hullContour.release()

            if (debugMode) {
                Log.d("ColorAnalyzer", "✓ Forma básica ACEPTADA")
            }

            return true

        } catch (e: Exception) {
            Log.e("ColorAnalyzer", "Error en filtro de forma: ${e.message}")
            return false
        }
    }



    /**
     * Versión relajada de isRegularShape específica para el delimitador magenta
     * (placa plana, no pilar compacto).
     */
    private fun isRegularShapeMagenta(contour: MatOfPoint, area: Double): Boolean {
        try {
            val hull = MatOfInt()
            Imgproc.convexHull(contour, hull)
            val hullPoints = hull.toArray()
            val contourPoints = contour.toArray()
            val hullContour = MatOfPoint()
            val hullPointsList = mutableListOf<Point>()
            for (index in hullPoints) {
                if (index < contourPoints.size) hullPointsList.add(contourPoints[index])
            }
            hullContour.fromArray(*hullPointsList.toTypedArray())
            val hullArea = Imgproc.contourArea(hullContour)
            val solidity = if (hullArea > 0) area / hullArea else 0.0

            if (debugMode) Log.d("ColorAnalyzer", "[Magenta] Solidez: $solidity (mín: $minSolidityMagenta)")
            if (solidity < minSolidityMagenta) {
                hull.release(); hullContour.release()
                return false
            }

            val boundingRect = Imgproc.boundingRect(contour)
            val aspectRatio = boundingRect.width.toDouble() / boundingRect.height.toDouble()
            if (debugMode) Log.d("ColorAnalyzer", "[Magenta] Aspect Ratio: $aspectRatio (rango: $minAspectRatioMagenta - $maxAspectRatioMagenta)")
            if (aspectRatio < minAspectRatioMagenta || aspectRatio > maxAspectRatioMagenta) {
                hull.release(); hullContour.release()
                return false
            }

            val boundingRectArea = boundingRect.width * boundingRect.height
            val extent = area / boundingRectArea.toDouble()
            if (debugMode) Log.d("ColorAnalyzer", "[Magenta] Extent: $extent (mín: $minExtentMagenta)")
            if (extent < minExtentMagenta) {
                hull.release(); hullContour.release()
                return false
            }

            val perimeter = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
            val perimeterAreaRatio = (perimeter * perimeter) / area
            if (debugMode) Log.d("ColorAnalyzer", "[Magenta] Perímetro²/Área: $perimeterAreaRatio (máx: $maxPerimeterAreaRatioMagenta)")
            if (perimeterAreaRatio > maxPerimeterAreaRatioMagenta) {
                hull.release(); hullContour.release()
                return false
            }

            hull.release(); hullContour.release()
            return true
        } catch (e: Exception) {
            Log.e("ColorAnalyzer", "Error en filtro de forma magenta: ${e.message}")
            return false
        }
    }

    /**
     * Versión relajada de isRectangularShape específica para el delimitador magenta.
     * Se omite la detección de picos triangulares (pensada para hojas orgánicas,
     * no aplica a una placa) y se relajan convexidad/defectos.
     */
    private fun isRectangularShapeMagenta(contour: MatOfPoint, area: Double): Boolean {
        try {
            val boundingRect = Imgproc.boundingRect(contour)
            val boundingRectArea = boundingRect.width * boundingRect.height
            val rectangularity = area / boundingRectArea.toDouble()
            if (debugMode) Log.d("ColorAnalyzer", "[Magenta] Rectangularidad: $rectangularity (mín: $minRectangularityMagenta)")
            if (rectangularity < minRectangularityMagenta) return false

            val hull = MatOfInt()
            Imgproc.convexHull(contour, hull)
            val hullPoints = hull.toArray()
            val contourPoints = contour.toArray()
            val hullContour = MatOfPoint()
            val hullPointsList = mutableListOf<Point>()
            for (index in hullPoints) {
                if (index < contourPoints.size) hullPointsList.add(contourPoints[index])
            }
            hullContour.fromArray(*hullPointsList.toTypedArray())
            val hullArea = Imgproc.contourArea(hullContour)
            val convexity = if (hullArea > 0) area / hullArea else 0.0
            if (debugMode) Log.d("ColorAnalyzer", "[Magenta] Convexidad: $convexity (mín: $minConvexityMagenta)")
            if (convexity < minConvexityMagenta) {
                hull.release(); hullContour.release()
                return false
            }

            try {
                val defects = MatOfInt4()
                Imgproc.convexityDefects(contour, hull, defects)
                val numDefects = defects.rows()
                if (debugMode) Log.d("ColorAnalyzer", "[Magenta] Defectos de convexidad: $numDefects (máx: $maxConvexityDefectsMagenta)")
                if (numDefects > maxConvexityDefectsMagenta) {
                    hull.release(); hullContour.release(); defects.release()
                    return false
                }
                defects.release()
            } catch (e: Exception) {
                if (debugMode) Log.w("ColorAnalyzer", "[Magenta] No se pudieron calcular defectos: ${e.message}")
            }

            hull.release()
            hullContour.release()
            if (debugMode) Log.d("ColorAnalyzer", "✅ [Magenta] Forma aceptada con filtro específico")
            return true
        } catch (e: Exception) {
            Log.e("ColorAnalyzer", "Error en filtro rectangular magenta: ${e.message}")
            return false
        }


    }

    /** true si el centro X normalizado (0.0-1.0) está dentro de la franja extrema
     *  de cualquiera de los dos bordes de la pantalla — usado para reclasificar
     *  un contorno Rojo/Verde como estacionamiento cuando el color es ambiguo. */
    private fun isAtExtremeEdge(centerX: Float, imageWidth: Int): Boolean {
        val normalizedX = centerX / imageWidth.toFloat()
        return normalizedX <= parkingEdgeZone || normalizedX >= (1f - parkingEdgeZone)
    }


    /**
     * CONVERTIDOR DE IMAGEPROXY A BITMAP
     * Convierte la imagen capturada por la cámara (formato YUV) a Bitmap
     * para su procesamiento con OpenCV y análisis de colores
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        // Obtener los planos de color YUV de la imagen
        val planes = imageProxy.planes
        val yBuffer = planes[0].buffer  // Luminancia (brillo)
        val uBuffer = planes[1].buffer  // Crominancia U (componente azul-amarillo)
        val vBuffer = planes[2].buffer  // Crominancia V (componente rojo-verde)

        // Obtener tamaños de cada plano
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        // Crear array para almacenar todos los datos en formato NV21
        val nv21 = ByteArray(ySize + uSize + vSize)

        // Copiar datos de cada plano al array NV21
        yBuffer.get(nv21, 0, ySize)           // Copiar Y (luminancia)
        vBuffer.get(nv21, ySize, vSize)       // Copiar V (crominancia)
        uBuffer.get(nv21, ySize + vSize, uSize) // Copiar U (crominancia)

        // Crear imagen YUV y comprimirla a JPEG
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val imageBytes = out.toByteArray()

        // Decodificar bytes JPEG a Bitmap
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    /**
     * Dibuja información detallada del objeto detectado junto a su contorno
     */
    private fun drawObjectInfo(
        mat: Mat,
        contour: MatOfPoint,
        color: String,
        area: Double,
        prefix: String,
        textColor: Scalar
    ) {
        try {
            // Calcular rectángulo envolvente
            val boundingRect = Imgproc.boundingRect(contour)

            // USAR LA FUNCIÓN CENTRALIZADA PARA CALCULAR DISTANCIA
            val distance = calculateDistance(area)

            // CORRECCIÓN: Usar CommandManager para una lógica consistente.
            val centerX = (boundingRect.x + boundingRect.width / 2).toFloat()
            val centerY = (boundingRect.y + boundingRect.height / 2).toFloat()
            val orientation = CommandManager.calculateOrientation(centerX, centerY, mat.width(), mat.height())

            // Preparar textos de información
            val texts = listOf(
                "$prefix: $color",
                "Dist: ${distance}cm",
                "Pos: $orientation",
                "Area: ${area.toInt()}"
            )

            // Configuración de texto
            val fontScale = 0.5
            val thickness = 1
            val padding = 5

            // Calcular posición del texto (a la derecha del objeto)
            val textX = (boundingRect.x + boundingRect.width + padding).toDouble()
            var textY = (boundingRect.y + 20).toDouble()

            // Ajustar si el texto se sale de la imagen
            val adjustedTextX = if (textX + 100 > mat.width()) {
                (boundingRect.x - 100).toDouble().coerceAtLeast(5.0)
            } else {
                textX
            }

            // Dibujar cada línea de texto
            texts.forEachIndexed { index, text ->
                val currentY = textY + (index * 15)

                // Fondo negro semi-transparente para legibilidad
                val textSize = Imgproc.getTextSize(text, Imgproc.FONT_HERSHEY_SIMPLEX, fontScale, thickness, null)
                Imgproc.rectangle(
                    mat,
                    Point(adjustedTextX - 2, currentY - textSize.height - 2),
                    Point(adjustedTextX + textSize.width + 2, currentY + 2),
                    Scalar(0.0, 0.0, 0.0),
                    -1
                )

                // Texto en color correspondiente
                Imgproc.putText(
                    mat,
                    text,
                    Point(adjustedTextX, currentY),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    fontScale,
                    textColor,
                    thickness
                )
            }

            // Dibujar rectángulo envolvente
            Imgproc.rectangle(mat, boundingRect.tl(), boundingRect.br(), textColor, 1)

            // Dibujar punto central
            val center = Point(
                (boundingRect.x + boundingRect.width / 2).toDouble(),
                (boundingRect.y + boundingRect.height / 2).toDouble()
            )
            Imgproc.circle(mat, center, 3, textColor, -1)

        } catch (e: Exception) {
            Log.w("ColorAnalyzer", "Error dibujando información del objeto: ${e.message}")
        }
    }


    /**
     * FILTRO ANTI-REFLEJO POR SATURACIÓN INTERNA
     *
     * Analiza la saturación promedio de los píxeles DENTRO del contorno en la imagen HSV.
     * Los reflejos en superficies brillantes pierden saturación: el color se mezcla con
     * el blanco/gris de la pista, produciendo un S promedio más bajo que el objeto real.
     *
     * @param contour     Contorno a evaluar
     * @param hsvMat      Imagen completa en formato HSV (para muestrear píxeles internos)
     * @return true si la saturación interna es suficiente (objeto real), false si es reflejo
     */
    /**
     * Calcula el promedio de Hue, Saturación y Valor (canal por canal) dentro de un contorno.
     * Se usa para alimentar la ventana de calibración por muestras (promedio de Hue real
     * del objeto en distintos puntos de la pista).
     */
    private fun computeContourMeanHsv(contour: MatOfPoint, hsvMat: Mat): Triple<Double, Double, Double> {
        val mask = Mat.zeros(hsvMat.size(), CvType.CV_8UC1)
        Imgproc.drawContours(mask, listOf(contour), -1, Scalar(255.0), -1)
        val meanHsv = Core.mean(hsvMat, mask)
        mask.release()
        return Triple(meanHsv.`val`[0], meanHsv.`val`[1], meanHsv.`val`[2])
    }

    private fun hasRealObjectSaturation(contour: MatOfPoint, hsvMat: Mat, threshold: Double, colorLabel: String = ""): Boolean {
        return try {
            // Crear máscara del tamaño del ROI, toda en negro
            val mask = Mat.zeros(hsvMat.size(), CvType.CV_8UC1)

            // Pintar en blanco solo el interior del contorno
            Imgproc.drawContours(mask, listOf(contour), -1, Scalar(255.0), -1)

            // Separar canales HSV para acceder solo al canal S
            val channels = ArrayList<Mat>()
            Core.split(hsvMat, channels)
            val saturationChannel = channels[1] // Canal S

            // Calcular media de saturación SOLO dentro de la máscara del contorno
            val meanSat = Core.mean(saturationChannel, mask)
            val avgSaturation = meanSat.`val`[0]

            // Liberar memoria
            mask.release()
            channels.forEach { it.release() }

            if (debugMode) {
                Log.d("ColorAnalyzer", "[$colorLabel] Saturación interna promedio: ${"%.1f".format(avgSaturation)} (mín: $threshold)")
            }

            if (avgSaturation < threshold) {
                if (debugMode) Log.d("ColorAnalyzer", "❌ [$colorLabel] Rechazado como reflejo: saturación baja (${"%.1f".format(avgSaturation)})")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w("ColorAnalyzer", "Error en filtro saturación: ${e.message}")
            true // en caso de error, no rechazar
        }
    }

    private fun computeRealObjectScore(contour: MatOfPoint, hsvMat: Mat): Double {
        return try {
            val area = Imgproc.contourArea(contour)
            if (area <= 0) return 0.0

            val boundingRect = Imgproc.boundingRect(contour)
            val bboxArea = (boundingRect.width * boundingRect.height).toDouble()
            val solidity = if (bboxArea > 0) (area / bboxArea).coerceIn(0.0, 1.0) else 0.0

            val mask = Mat.zeros(hsvMat.size(), CvType.CV_8UC1)
            Imgproc.drawContours(mask, listOf(contour), -1, Scalar(255.0), -1)
            val channels = ArrayList<Mat>()
            Core.split(hsvMat, channels)
            val meanSat = Core.mean(channels[1], mask).`val`[0]
            val satScore = (meanSat / 255.0).coerceIn(0.0, 1.0)

            mask.release()
            channels.forEach { it.release() }

            val score = (solidity * 0.6) + (satScore * 0.4)

            if (debugMode) {
                Log.d("ColorAnalyzer", "Score calidad: ${"%.3f".format(score)} " +
                        "(solidez=${"%.3f".format(solidity)}, sat=${"%.1f".format(meanSat)})")
            }

            score
        } catch (e: Exception) {
            Log.w("ColorAnalyzer", "Error calculando score: ${e.message}")
            0.5
        }
    }

    private fun computeShapeDiagnostics(contour: MatOfPoint, area: Double): String {
        return try {
            val boundingRect = Imgproc.boundingRect(contour)
            val aspectRatio = boundingRect.width.toDouble() / boundingRect.height.toDouble()
            val boundingRectArea = boundingRect.width * boundingRect.height
            val rectangularity = area / boundingRectArea.toDouble()

            val hull = MatOfInt()
            Imgproc.convexHull(contour, hull)
            val hullPoints = hull.toArray()
            val contourPoints = contour.toArray()
            val hullContour = MatOfPoint()
            val hullPointsList = mutableListOf<Point>()
            for (index in hullPoints) {
                if (index < contourPoints.size) hullPointsList.add(contourPoints[index])
            }
            hullContour.fromArray(*hullPointsList.toTypedArray())
            val hullArea = Imgproc.contourArea(hullContour)
            val convexity = if (hullArea > 0) area / hullArea else 0.0

            var numDefects = -1
            try {
                val defects = MatOfInt4()
                Imgproc.convexityDefects(contour, hull, defects)
                numDefects = defects.rows()
                defects.release()
            } catch (e: Exception) { /* algunos contornos no tienen defectos calculables */ }

            val contour2f = MatOfPoint2f(*contour.toArray())
            val epsilon = 0.02 * Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, epsilon, true)
            val vertices = approx.toArray().size

            hull.release(); hullContour.release(); contour2f.release(); approx.release()

            "rect=${"%.2f".format(rectangularity)} conv=${"%.2f".format(convexity)} def=$numDefects vert=$vertices asp=${"%.2f".format(aspectRatio)}"
        } catch (e: Exception) {
            "diag error: ${e.message}"
        }
    }

}
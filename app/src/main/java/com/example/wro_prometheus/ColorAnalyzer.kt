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
 * Analizador de colores que detecta objetos de colores específicos (rojo, verde, magenta)
 * usando OpenCV y técnicas avanzadas de procesamiento de imágenes.
 *
 * Características principales:
 * - Ecualización de histograma para normalizar la iluminación
 * - Filtros geométricos para detectar formas rectangulares
 * - Suavizado de contornos con doble pasada
 * - Detección de picos triangulares para rechazar formas orgánicas
 *
 * @param callback Función que recibe los resultados del análisis con parámetros:
 *                 - String: Descripción del color detectado
 *                 - Bitmap?: Imagen procesada con contornos dibujados
 *                 - Double: Área del objeto detectado
 *                 - Float: Coordenada X del centro del objeto
 *                 - Float: Coordenada Y del centro del objeto
 *                 - Int: Ancho de la imagen procesada
 *                 - Int: Alto de la imagen procesada
 */
class ColorAnalyzer(private val callback: (String, Bitmap?, Double, Float, Float, Int, Int) -> Unit) : ImageAnalysis.Analyzer {

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
    }

    // =============================================================================
    // PARÁMETROS DE ECUALIZACIÓN DE HISTOGRAMA
    // =============================================================================

    /** Habilita la ecualización de histograma para normalizar la iluminación */
    private val enableHistogramEqualization = true

    /** Habilita CLAHE (Contrast Limited Adaptive Histogram Equalization) */
    private val enableCLAHE = true

    /** Límite de contraste para CLAHE - valor conservador para evitar sobreecualización */
    private val claheClipLimit = 2.0

    /** Tamaño de la cuadrícula para CLAHE - 8x8 es bueno para objetos medianos */
    private val claheTileGridSize = Size(8.0, 8.0)

    // =============================================================================
    // RANGOS DE COLORES EN ESPACIO HSV
    // =============================================================================

    // ROJO: Se define en dos rangos debido a que el rojo está en los extremos del espectro H

    /** Rango HSV para tonos rojos bajos (0-10 grados en el círculo cromático) */
    private val lowerRed1 = Scalar(0.0, 100.0, 80.0)    // H: 0°, S: 39%, V: 31%
    private val upperRed1 = Scalar(10.0, 255.0, 255.0)  // H: 10°, S: 100%, V: 100%

    /** Rango HSV para tonos rojos altos (172-180 grados en el círculo cromático) */
    private val lowerRed2 = Scalar(172.0, 100.0, 80.0)  // H: 172°, S: 39%, V: 31%
    private val upperRed2 = Scalar(180.0, 255.0, 255.0) // H: 180°, S: 100%, V: 100%

    /** Rango HSV para color magenta (145-170 grados) */
    private val lowerMagenta = Scalar(145.0, 90.0, 60.0)  // H: 145°, S: 35%, V: 24%
    private val upperMagenta = Scalar(170.0, 255.0, 255.0) // H: 170°, S: 100%, V: 100%

    /** Rango HSV para color verde - AMPLIADO para detectar tonalidades oscuras (30-90 grados) */
    private val lowerGreen = Scalar(30.0, 40.0, 25.0)   // H: 30°, S: 16%, V: 10% - Incluye verdes muy oscuros
    private val upperGreen = Scalar(90.0, 255.0, 255.0) // H: 90°, S: 100%, V: 100% - Mantiene verdes brillantes

    // =============================================================================
    // PARÁMETROS GENERALES DE DETECCIÓN
    // =============================================================================

    /** Área mínima en píxeles que debe tener un contorno para ser considerado válido */
    private val minContourArea = 800.0

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
    private val minSolidity = 0.5

    /** Relación de aspecto máxima (ancho/alto) permitida */
    private val maxAspectRatio = 4.0

    /** Relación de aspecto mínima (ancho/alto) permitida */
    private val minAspectRatio = 0.25

    /** Extensión mínima (área_contorno / área_rectángulo_envolvente) */
    private val minExtent = 0.4

    /** Ratio máximo perímetro²/área para filtrar formas complejas */
    private val maxPerimeterAreaRatio = 20.0

    /** Habilita el filtrado básico de formas */
    private val enableShapeFiltering = true

    // =============================================================================
    // PARÁMETROS PARA FILTRADO AVANZADO
    // =============================================================================

    /** Rectangularidad mínima (área_contorno / área_rectángulo_envolvente) */
    private val minRectangularity = 0.6

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

        try {
            // =============================================================================
            // 1. CONVERSIÓN DE FORMATO DE IMAGEN
            // =============================================================================

            val bitmap = imageProxyToBitmap(image)
            if (bitmap == null) {
                Log.e("ColorAnalyzer", "Error al convertir ImageProxy a Bitmap")
                callback("Error al procesar la imagen", null, 0.0, 0f, 0f, 0, 0)
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

                val maskRed1 = Mat()      // Máscara para rojo bajo
                val maskRed2 = Mat()      // Máscara para rojo alto
                val maskRed = Mat()       // Máscara combinada para rojo
                val maskGreen = Mat()     // Máscara para verde
                val maskMagenta = Mat()   // Máscara para magenta

                // Crear máscaras binarias usando rangos HSV
                Core.inRange(equalizedHSV, lowerRed1, upperRed1, maskRed1)
                Core.inRange(equalizedHSV, lowerRed2, upperRed2, maskRed2)
                Core.inRange(equalizedHSV, lowerGreen, upperGreen, maskGreen)
                Core.inRange(equalizedHSV, lowerMagenta, upperMagenta, maskMagenta)

                // Combinar las dos máscaras de rojo
                Core.bitwise_or(maskRed1, maskRed2, maskRed)

                // =============================================================================
                // 6. OPERACIONES MORFOLÓGICAS PARA LIMPIAR MÁSCARAS
                // =============================================================================

                // Kernel rectangular para operaciones morfológicas
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))

                // Aplicar apertura (erosión + dilatación) para eliminar ruido
                // Seguida de cierre (dilatación + erosión) para llenar huecos
                Imgproc.morphologyEx(maskRed, maskRed, Imgproc.MORPH_OPEN, kernel)
                Imgproc.morphologyEx(maskRed, maskRed, Imgproc.MORPH_CLOSE, kernel)

                Imgproc.morphologyEx(maskGreen, maskGreen, Imgproc.MORPH_OPEN, kernel)
                Imgproc.morphologyEx(maskGreen, maskGreen, Imgproc.MORPH_CLOSE, kernel)

                Imgproc.morphologyEx(maskMagenta, maskMagenta, Imgproc.MORPH_OPEN, kernel)
                Imgproc.morphologyEx(maskMagenta, maskMagenta, Imgproc.MORPH_CLOSE, kernel)

                // =============================================================================
                // 7. DETECCIÓN DE CONTORNOS
                // =============================================================================

                val contoursRed = ArrayList<MatOfPoint>()
                val contoursGreen = ArrayList<MatOfPoint>()
                val contoursMagenta = ArrayList<MatOfPoint>()

                // Encontrar contornos externos en cada máscara
                Imgproc.findContours(maskRed, contoursRed, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                Imgproc.findContours(maskGreen, contoursGreen, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                Imgproc.findContours(maskMagenta, contoursMagenta, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

                if (debugMode) {
                    Log.d("ColorAnalyzer", "Contornos encontrados - Rojos: ${contoursRed.size}, Verdes: ${contoursGreen.size}, Magentas: ${contoursMagenta.size}")
                }

                // =============================================================================
                // 8. SUAVIZADO DE CONTORNOS
                // =============================================================================

                var detectedColor = "No se detecta color significativo"
                val contourColor = Scalar(0.0, 255.0, 0.0) // Verde para dibujar contornos

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

                val smoothedMagentaContours = if (enableContourSmoothing) {
                    contoursMagenta.map { smoothContourAdvanced(it) }
                } else {
                    contoursMagenta
                }

                // =============================================================================
                // 9. FILTRADO GEOMÉTRICO DE CONTORNOS
                // =============================================================================

                // Aplicar filtros para validar formas rectangulares
                val validRedContours = smoothedRedContours.filter { contour ->
                    val area = Imgproc.contourArea(contour)
                    if (area > minContourArea) {
                        if (enableShapeFiltering) {
                            val basicCheck = isRegularShape(contour, area)
                            val advancedCheck = if (enableAdvancedFiltering) {
                                isRectangularShape(contour, area)
                            } else {
                                true
                            }
                            basicCheck && advancedCheck
                        } else {
                            true
                        }
                    } else {
                        if (debugMode) {
                            Log.d("ColorAnalyzer", "Contorno rojo rechazado por área pequeña: $area < $minContourArea")
                        }
                        false
                    }
                }

                val validGreenContours = smoothedGreenContours.filter { contour ->
                    val area = Imgproc.contourArea(contour)
                    if (area > minContourArea) {
                        if (enableShapeFiltering) {
                            val basicCheck = isRegularShape(contour, area)
                            val advancedCheck = if (enableAdvancedFiltering) {
                                isRectangularShape(contour, area)
                            } else {
                                true
                            }
                            basicCheck && advancedCheck
                        } else {
                            true
                        }
                    } else {
                        if (debugMode) {
                            Log.d("ColorAnalyzer", "Contorno verde rechazado por área pequeña: $area < $minContourArea")
                        }
                        false
                    }
                }

                val validMagentaContours = smoothedMagentaContours.filter { contour ->
                    val area = Imgproc.contourArea(contour)
                    if (area > minContourArea) {
                        if (enableShapeFiltering) {
                            val basicCheck = isRegularShape(contour, area)
                            val advancedCheck = if (enableAdvancedFiltering) {
                                isRectangularShape(contour, area)
                            } else {
                                true
                            }
                            basicCheck && advancedCheck
                        } else {
                            true
                        }
                    } else {
                        if (debugMode) {
                            Log.d("ColorAnalyzer", "Contorno magenta rechazado por área pequeña: $area < $minContourArea")
                        }
                        false
                    }
                }

                if (debugMode) {
                    Log.d("ColorAnalyzer", "Contornos válidos después del filtrado - Rojos: ${validRedContours.size}, Verdes: ${validGreenContours.size}, Magentas: ${validMagentaContours.size}")
                }

                // =============================================================================
                // 10. SELECCIÓN DEL OBJETO MÁS GRANDE POR COLOR
                // =============================================================================

                val largestRedContour = validRedContours.maxByOrNull { Imgproc.contourArea(it) }
                val largestGreenContour = validGreenContours.maxByOrNull { Imgproc.contourArea(it) }
                val largestMagentaContour = validMagentaContours.maxByOrNull { Imgproc.contourArea(it) }

                val redArea = largestRedContour?.let { Imgproc.contourArea(it) } ?: 0.0
                val greenArea = largestGreenContour?.let { Imgproc.contourArea(it) } ?: 0.0
                val magentaArea = largestMagentaContour?.let { Imgproc.contourArea(it) } ?: 0.0

                if (debugMode) {
                    Log.d("ColorAnalyzer", "Área roja: $redArea, Área verde: $greenArea, Área magenta: $magentaArea")
                }

                // =============================================================================
                // 11. DETERMINACIÓN DEL COLOR PREDOMINANTE Y CÁLCULO DE POSICIÓN
                // =============================================================================

                if (redArea > 0 || greenArea > 0 || magentaArea > 0) {
                    if (redArea >= greenArea && redArea >= magentaArea) {
                        // ROJO es el color predominante
                        detectedColor = "Rojo detectado"
                        detectedArea = redArea

                        largestRedContour?.let {
                            // Calcular centro de masa usando momentos
                            val moments = Imgproc.moments(it)
                            centerX = (moments.m10 / moments.m00).toFloat()
                            centerY = (moments.m01 / moments.m00).toFloat()

                            // Actualizar posición global del objeto
                            lastObjectPosition = ObjectPosition(
                                (centerX / roiMat.width()).toFloat(),
                                (centerY / roiMat.height()).toFloat(),
                                redArea,
                                "Rojo",
                                centerX,
                                centerY,
                                roiMat.width(),
                                roiMat.height()
                            )

                            // Dibujar contorno del objeto detectado
                            Imgproc.drawContours(roiMat, listOf(it), -1, contourColor, 2)

                            if (debugMode) {
                                // Dibujar centro y rectángulo envolvente
                                Imgproc.circle(roiMat, Point(centerX.toDouble(), centerY.toDouble()), 10, Scalar(255.0, 0.0, 0.0), -1)
                                val boundingRect = Imgproc.boundingRect(it)
                                Imgproc.rectangle(roiMat, boundingRect.tl(), boundingRect.br(), Scalar(255.0, 255.0, 0.0), 2)
                            }
                        }
                    } else if (greenArea >= magentaArea) {
                        // VERDE es el color predominante
                        detectedColor = "Verde detectado"
                        detectedArea = greenArea

                        largestGreenContour?.let {
                            val moments = Imgproc.moments(it)
                            centerX = (moments.m10 / moments.m00).toFloat()
                            centerY = (moments.m01 / moments.m00).toFloat()

                            lastObjectPosition = ObjectPosition(
                                (centerX / roiMat.width()).toFloat(),
                                (centerY / roiMat.height()).toFloat(),
                                greenArea,
                                "Verde",
                                centerX,
                                centerY,
                                roiMat.width(),
                                roiMat.height()
                            )

                            Imgproc.drawContours(roiMat, listOf(it), -1, contourColor, 2)

                            if (debugMode) {
                                Imgproc.circle(roiMat, Point(centerX.toDouble(), centerY.toDouble()), 10, Scalar(0.0, 255.0, 0.0), -1)
                                val boundingRect = Imgproc.boundingRect(it)
                                Imgproc.rectangle(roiMat, boundingRect.tl(), boundingRect.br(), Scalar(255.0, 255.0, 0.0), 2)
                            }
                        }
                    } else {
                        // MAGENTA es el color predominante
                        detectedColor = "Magenta detectado"
                        detectedArea = magentaArea

                        largestMagentaContour?.let {
                            val moments = Imgproc.moments(it)
                            centerX = (moments.m10 / moments.m00).toFloat()
                            centerY = (moments.m01 / moments.m00).toFloat()

                            lastObjectPosition = ObjectPosition(
                                (centerX / roiMat.width()).toFloat(),
                                (centerY / roiMat.height()).toFloat(),
                                magentaArea,
                                "Magenta",
                                centerX,
                                centerY,
                                roiMat.width(),
                                roiMat.height()
                            )

                            Imgproc.drawContours(roiMat, listOf(it), -1, contourColor, 2)

                            if (debugMode) {
                                Imgproc.circle(roiMat, Point(centerX.toDouble(), centerY.toDouble()), 10, Scalar(255.0, 0.0, 255.0), -1)
                                val boundingRect = Imgproc.boundingRect(it)
                                Imgproc.rectangle(roiMat, boundingRect.tl(), boundingRect.br(), Scalar(255.0, 255.0, 0.0), 2)
                            }
                        }
                    }
                }

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
                callback(detectedColor, rotatedBitmap, detectedArea, centerX, centerY, roiMat.width(), roiMat.height())

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
                kernel.release()
                roiMat.release()
            } else {
                callback("Error: ROI es nulo", null, 0.0, 0f, 0f, 0, 0)
            }

            matOriginal.release()

        } catch (e: Exception) {
            Log.e("ColorAnalyzer", "Error en el análisis: ${e.message}", e)
            callback("Error: ${e.message}", null, 0.0, 0f, 0f, 0, 0)
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

}
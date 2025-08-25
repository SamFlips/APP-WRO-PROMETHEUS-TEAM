package com.example.wro_prometheus
import android.util.Log

/**
 * SISTEMA DE COMANDOS WRO PROMETHEUS - VERSIÓN COMPLETA CON TODOS LOS CASOS DUALES
 */

class CommandManager {

    companion object {
        // =============================================================================
        // CONSTANTES DE DISTANCIA - CORREGIDAS PARA COINCIDIR CON MAINACTIVITY
        // =============================================================================

        /** Área de referencia del objeto a distancia conocida */
        private const val REFERENCE_AREA = 26000.0

        /** Distancia de referencia en centímetros */
        private const val REFERENCE_DISTANCE = 30.0

        /** Distancia mínima válida */
        private const val MIN_DISTANCE = 10

        /** Distancia máxima válida */
        private const val MAX_DISTANCE = 135

        /** Distancia mínima para objetos "adelante" */
        private const val FRONT_DISTANCE_MIN = 40

        /** Distancia máxima para objetos "adelante" */
        private const val FRONT_DISTANCE_MAX = 60

        /** Distancia mínima para objetos en el "medio" */
        private const val MIDDLE_DISTANCE_MIN = 80

        /** Distancia máxima para objetos en el "medio" */
        private const val MIDDLE_DISTANCE_MAX = 100

        /** Distancia mínima para objetos "atrás" */
        private const val BACK_DISTANCE_MIN = 110

        /** Distancia máxima para objetos "atrás" */
        private const val BACK_DISTANCE_MAX = 130

        /** Comando cuando no hay detección válida */
        private const val NO_DETECTION_COMMAND = "N"

        // =============================================================================
        // DEFINICIÓN DE CASOS DE COMANDO
        // =============================================================================

        enum class CommandCase(
            val code: String,
            val color: String,
            val position: String,
            val distanceMin: Int,
            val distanceMax: Int,
            val description: String,
            val objectCount: Int = 1
        ) {
            // CASOS DE UN SOLO OBJETO - ADELANTE (40-50cm)
            C01("C01", "Verde", "R", FRONT_DISTANCE_MIN, FRONT_DISTANCE_MAX, "Verde, Adelante Derecha", 1),
            C02("C02", "Rojo", "R", FRONT_DISTANCE_MIN, FRONT_DISTANCE_MAX, "Rojo, Adelante Derecha", 1),
            C07("C07", "Verde", "L", FRONT_DISTANCE_MIN, FRONT_DISTANCE_MAX, "Verde, Adelante Izquierda", 1),
            C08("C08", "Rojo", "L", FRONT_DISTANCE_MIN, FRONT_DISTANCE_MAX, "Rojo, Adelante Izquierda", 1),

            // CASOS DE UN SOLO OBJETO - MEDIO (70-90cm)
            C03("C03", "Verde", "R", MIDDLE_DISTANCE_MIN, MIDDLE_DISTANCE_MAX, "Verde, Medio Derecha", 1),
            C04("C04", "Rojo", "R", MIDDLE_DISTANCE_MIN, MIDDLE_DISTANCE_MAX, "Rojo, Medio Derecha", 1),
            C09("C09", "Verde", "L", MIDDLE_DISTANCE_MIN, MIDDLE_DISTANCE_MAX, "Verde, Medio Izquierda", 1),
            C10("C10", "Rojo", "L", MIDDLE_DISTANCE_MIN, MIDDLE_DISTANCE_MAX, "Rojo, Medio Izquierda", 1),

            // CASOS DE UN SOLO OBJETO - ATRÁS (110-140cm)
            C05("C05", "Verde", "R", BACK_DISTANCE_MIN, BACK_DISTANCE_MAX, "Verde, Atrás Derecha", 1),
            C06("C06", "Rojo", "R", BACK_DISTANCE_MIN, BACK_DISTANCE_MAX, "Rojo, Atrás Derecha", 1),
            C37("C37", "Verde", "L", BACK_DISTANCE_MIN, BACK_DISTANCE_MAX, "Verde, Atrás Izquierda", 1),
            C38("C38", "Rojo", "L", BACK_DISTANCE_MIN, BACK_DISTANCE_MAX, "Rojo, Atrás Izquierda", 1),

            // CASOS DE DOS OBJETOS - TODOS LOS CASOS DUALES
            C13("C13", "", "", 0, 0, "Verde Adelante Izq + Verde Atrás Der", 2),
            C14("C14", "", "", 0, 0, "Verde Adelante Izq + Rojo Atrás Der", 2),
            C15("C15", "", "", 0, 0, "Rojo Adelante Izq + Verde Atrás Der", 2),
            C18("C18", "", "", 0, 0, "Rojo Adelante Izq + Rojo Atrás Der", 2),
            C19("C19", "", "", 0, 0, "Verde Adelante Der + Verde Atrás Izq", 2),
            C20("C20", "", "", 0, 0, "Verde Adelante Der + Rojo Atrás Izq", 2),
            C21("C21", "", "", 0, 0, "Rojo Adelante Der + Verde Atrás Izq", 2),
            C24("C24", "", "", 0, 0, "Rojo Adelante Der + Rojo Atrás Izq", 2),
            C25("C25", "", "", 0, 0, "Verde Adelante Der + Verde Atrás Der", 2),
            C26("C26", "", "", 0, 0, "Verde Adelante Der + Rojo Atrás Der", 2),
            C27("C27", "", "", 0, 0, "Rojo Adelante Der + Verde Atrás Der", 2),
            C30("C30", "", "", 0, 0, "Rojo Adelante Der + Rojo Atrás Der", 2),
            C31("C31", "", "", 0, 0, "Verde Adelante Izq + Verde Atrás Izq", 2),
            C32("C32", "", "", 0, 0, "Verde Adelante Izq + Rojo Atrás Izq", 2),
            C33("C33", "", "", 0, 0, "Rojo Adelante Izq + Verde Atrás Izq", 2),
            C36("C36", "", "", 0, 0, "Rojo Adelante Izq + Rojo Atrás Izq", 2);

            fun matches(detectedColor: String, orientation: String, distance: Int): Boolean {
                return this.objectCount == 1 &&
                        this.color.equals(detectedColor, ignoreCase = true) &&
                        this.position == orientation &&
                        distance in this.distanceMin..this.distanceMax
            }
        }

        // =============================================================================
        // CLASE PARA DEFINIR CASOS DUALES
        // =============================================================================

        data class DualCase(
            val code: String,
            val primaryColor: String,
            val primaryPosition: String,
            val secondaryColor: String,
            val secondaryPosition: String,
            val description: String
        )

        private val dualCases = listOf(
            // Casos con primer objeto Adelante Izquierda
            DualCase("C13", "Verde", "L", "Verde", "R", "Verde Adelante Izq + Verde Atrás Der"),
            DualCase("C14", "Verde", "L", "Rojo", "R", "Verde Adelante Izq + Rojo Atrás Der"),
            DualCase("C15", "Rojo", "L", "Verde", "R", "Rojo Adelante Izq + Verde Atrás Der"),
            DualCase("C18", "Rojo", "L", "Rojo", "R", "Rojo Adelante Izq + Rojo Atrás Der"),
            DualCase("C31", "Verde", "L", "Verde", "L", "Verde Adelante Izq + Verde Atrás Izq"),
            DualCase("C32", "Verde", "L", "Rojo", "L", "Verde Adelante Izq + Rojo Atrás Izq"),
            DualCase("C33", "Rojo", "L", "Verde", "L", "Rojo Adelante Izq + Verde Atrás Izq"),
            DualCase("C36", "Rojo", "L", "Rojo", "L", "Rojo Adelante Izq + Rojo Atrás Izq"),

            // Casos con primer objeto Adelante Derecha
            DualCase("C19", "Verde", "R", "Verde", "L", "Verde Adelante Der + Verde Atrás Izq"),
            DualCase("C20", "Verde", "R", "Rojo", "L", "Verde Adelante Der + Rojo Atrás Izq"),
            DualCase("C21", "Rojo", "R", "Verde", "L", "Rojo Adelante Der + Verde Atrás Izq"),
            DualCase("C24", "Rojo", "R", "Rojo", "L", "Rojo Adelante Der + Rojo Atrás Izq"),
            DualCase("C25", "Verde", "R", "Verde", "R", "Verde Adelante Der + Verde Atrás Der"),
            DualCase("C26", "Verde", "R", "Rojo", "R", "Verde Adelante Der + Rojo Atrás Der"),
            DualCase("C27", "Rojo", "R", "Verde", "R", "Rojo Adelante Der + Verde Atrás Der"),
            DualCase("C30", "Rojo", "R", "Rojo", "R", "Rojo Adelante Der + Rojo Atrás Der")
        )

        // =============================================================================
        // FUNCIONES PRINCIPALES
        // =============================================================================

        /**
         * Determina el comando para un solo objeto basado en su color, orientación y distancia.
         * Busca una coincidencia en los casos predefinidos de un solo objeto.
         * @param color El color detectado del objeto.
         * @param orientation La orientación del objeto ('L' o 'R').
         * @param distance La distancia del objeto en centímetros.
         * @return El código de comando correspondiente o "N" si no hay coincidencia.
         */
        fun determineCommand(color: String, orientation: String, distance: Int): String {
            val matchingCase = CommandCase.values().find { case ->
                case.matches(color, orientation, distance)
            }
            return matchingCase?.code ?: NO_DETECTION_COMMAND
        }

        /**
         * Determina el comando cuando se detectan dos objetos.
         * Prioriza los casos duales si se cumplen las condiciones de distancia (adelante-atrás).
         * Si las condiciones no se cumplen, o no hay un caso dual que coincida,
         * regresa al comando del objeto primario.
         * @param primaryColor Color del objeto primario.
         * @param primaryOrientation Orientación del objeto primario.
         * @param primaryDistance Distancia del objeto primario.
         * @param secondaryColor Color del objeto secundario.
         * @param secondaryOrientation Orientación del objeto secundario.
         * @param secondaryDistance Distancia del objeto secundario.
         * @return Un par de cadenas: el código de comando y una descripción.
         */
        fun determineDualObjectCommand(
            primaryColor: String, primaryOrientation: String, primaryDistance: Int,
            secondaryColor: String, secondaryOrientation: String, secondaryDistance: Int
        ): Pair<String, String> {

            Log.d("CommandManager", "=== ANALIZANDO COMANDO DUAL ===")
            Log.d("CommandManager", "Primario: $primaryColor, $primaryOrientation, ${primaryDistance}cm")
            Log.d("CommandManager", "Secundario: $secondaryColor, $secondaryOrientation, ${secondaryDistance}cm")

            // Verificar que el primer objeto esté en zona ADELANTE y el segundo en zona ATRÁS
            val isPrimaryFront = primaryDistance in FRONT_DISTANCE_MIN..FRONT_DISTANCE_MAX
            val isSecondaryBack = secondaryDistance in BACK_DISTANCE_MIN..BACK_DISTANCE_MAX

            if (!isPrimaryFront) {
                Log.d("CommandManager", "⚠️ Objeto primario no está en zona ADELANTE (${primaryDistance}cm)")
            }

            if (!isSecondaryBack) {
                Log.d("CommandManager", "⚠️ Objeto secundario no está en zona ATRÁS (${secondaryDistance}cm)")
            }

            if (!isPrimaryFront || !isSecondaryBack) {
                val (primaryCommand, primaryInfo) = determineCommandSafe(primaryColor, primaryOrientation, primaryDistance)
                Log.d("CommandManager", "❌ Distancias no válidas para caso dual - usando primario: $primaryCommand")
                return Pair(primaryCommand, "Distancias no válidas - Usando: $primaryInfo")
            }

            // Buscar caso dual que coincida
            val matchingDualCase = dualCases.find { case ->
                case.primaryColor.equals(primaryColor, ignoreCase = true) &&
                        case.primaryPosition == primaryOrientation &&
                        case.secondaryColor.equals(secondaryColor, ignoreCase = true) &&
                        case.secondaryPosition == secondaryOrientation
            }

            if (matchingDualCase != null) {
                Log.d("CommandManager", "✅ CASO DUAL ${matchingDualCase.code} DETECTADO!")
                return Pair(matchingDualCase.code, matchingDualCase.description)
            }

            // Si no hay caso dual válido, usar comando del objeto primario
            val (primaryCommand, primaryInfo) = determineCommandSafe(primaryColor, primaryOrientation, primaryDistance)
            Log.d("CommandManager", "⚠️ No hay comando dual válido - usando primario: $primaryCommand")

            return Pair(primaryCommand, "Dual no válido - Usando: $primaryInfo")
        }

        /**
         * Verifica si una distancia está dentro del rango válido para un caso de un solo objeto.
         * @param distance La distancia a verificar.
         * @return `true` si la distancia es válida, de lo contrario `false`.
         */
        fun isValidDistance(distance: Int): Boolean {
            return CommandCase.values().any { case ->
                case.objectCount == 1 && distance in case.distanceMin..case.distanceMax
            }
        }

        /**
         * Obtiene la descripción de un comando a partir de su código.
         * @param commandCode El código del comando (ej. "C01", "C13").
         * @return La descripción del comando, o `null` si el código no existe.
         */
        fun getCommandInfo(commandCode: String): String? {
            // Buscar en casos individuales
            val singleCase = CommandCase.values().find { it.code == commandCode }
            if (singleCase != null) {
                return singleCase.description
            }

            // Buscar en casos duales
            val dualCase = dualCases.find { it.code == commandCode }
            return dualCase?.description
        }

        /**
         * Genera un string con la información de todos los casos de comando.
         * @return Un string formateado con la descripción de todos los comandos.
         */
        fun getAllCasesInfo(): String {
            val singleCases = CommandCase.values()
                .filter { it.objectCount == 1 }
                .joinToString("\n") { case ->
                    "${case.code}: ${case.description} (${case.distanceMin}-${case.distanceMax}cm)"
                }

            val dualCasesInfo = dualCases.joinToString("\n") { case ->
                "${case.code}: ${case.description}"
            }

            return "=== CASOS INDIVIDUALES ===\n$singleCases\n\n=== CASOS DUALES ===\n$dualCasesInfo"
        }

        /**
         * Genera un string con la información de los casos de comando duales.
         * @return Un string formateado con la descripción de los comandos duales.
         */
        fun getDualCasesInfo(): String {
            return dualCases.joinToString("\n") { case ->
                "${case.code}: ${case.description}"
            }
        }

        /**
         * Determina el comando de forma segura, validando los datos de entrada
         * y retornando un par de (código, info) para una gestión más robusta.
         * @param color Color del objeto.
         * @param orientation Orientación del objeto ('L' o 'R').
         * @param distance Distancia del objeto.
         * @return Un par que contiene el código del comando y su descripción o un mensaje de error.
         */
        fun determineCommandSafe(color: String, orientation: String, distance: Int): Pair<String, String> {
            val normalizedColor = normalizeColor(color)

            if (!isValidOrientation(orientation)) {
                return Pair(NO_DETECTION_COMMAND, "Orientación inválida: $orientation")
            }

            if (distance < 10 || distance > 200) {
                return Pair(NO_DETECTION_COMMAND, "Distancia fuera de rango: ${distance}cm")
            }

            val command = determineCommand(normalizedColor, orientation, distance)
            val info = if (command != NO_DETECTION_COMMAND) {
                getCommandInfo(command) ?: "Comando desconocido"
            } else {
                "No hay comando para: $normalizedColor, $orientation, ${distance}cm"
            }

            return Pair(command, info)
        }

        /**
         * Calcula la distancia de un objeto en centímetros basándose en su área
         * detectada y una distancia de referencia.
         * @param area El área del objeto detectado en píxeles.
         * @return La distancia calculada en centímetros, limitada a un rango válido.
         */
        fun calculateDistance(area: Double): Int {
            val calculatedDistance = kotlin.math.sqrt(REFERENCE_AREA / area) * REFERENCE_DISTANCE
            return calculatedDistance.toInt().coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        }

        /**
         * Calcula la orientación de un objeto ('L' o 'R') basándose en su
         * posición central dentro de una imagen.
         * @param centerX La coordenada X del centro del objeto.
         * @param centerY La coordenada Y del centro del objeto (no utilizada en la lógica actual).
         * @param imageWidth El ancho de la imagen.
         * @param imageHeight El alto de la imagen (no utilizado en la lógica actual).
         * @return La orientación calculada ('L' o 'R').
         */
        fun calculateOrientation(centerX: Float, centerY: Float, imageWidth: Int, imageHeight: Int): String {
            val normalizedX = centerX / imageWidth.toFloat()
            return when {
                normalizedX < 0.60f -> "L"
                else -> "R"
            }
        }

        // =============================================================================
        // FUNCIONES DE UTILIDAD ADICIONALES
        // =============================================================================

        /**
         * Agrupa los casos de un solo objeto por zona de distancia (Adelante, Medio, Atrás).
         * @return Un mapa donde la clave es el nombre de la zona y el valor es una lista de CommandCase.
         */
        fun getCommandsByZone(): Map<String, List<CommandCase>> {
            return CommandCase.values().filter { it.objectCount == 1 }.groupBy { case ->
                when {
                    case.distanceMin == FRONT_DISTANCE_MIN -> "Adelante"
                    case.distanceMin == MIDDLE_DISTANCE_MIN -> "Medio"
                    case.distanceMin == BACK_DISTANCE_MIN -> "Atrás"
                    else -> "Otra"
                }
            }
        }

        /**
         * Agrupa los casos duales por su patrón de posición (ej. "R-L").
         * @return Un mapa donde la clave es el patrón y el valor es una lista de DualCase.
         */
        fun getDualCasesByPattern(): Map<String, List<DualCase>> {
            return dualCases.groupBy { case ->
                "${case.primaryPosition}-${case.secondaryPosition}"
            }
        }

        /**
         * Verifica si una distancia está en la zona de "adelante".
         * @param distance La distancia a verificar.
         * @return `true` si la distancia está en el rango de "adelante".
         */
        fun isFrontZone(distance: Int): Boolean {
            return distance in FRONT_DISTANCE_MIN..FRONT_DISTANCE_MAX
        }

        /**
         * Verifica si una distancia está en la zona de "medio".
         * @param distance La distancia a verificar.
         * @return `true` si la distancia está en el rango de "medio".
         */
        fun isMiddleZone(distance: Int): Boolean {
            return distance in MIDDLE_DISTANCE_MIN..MIDDLE_DISTANCE_MAX
        }

        /**
         * Verifica si una distancia está en la zona de "atrás".
         * @param distance La distancia a verificar.
         * @return `true` si la distancia está en el rango de "atrás".
         */
        fun isBackZone(distance: Int): Boolean {
            return distance in BACK_DISTANCE_MIN..BACK_DISTANCE_MAX
        }

        /**
         * Verifica si la configuración de distancias de dos objetos es válida para un caso dual.
         * Un caso dual válido requiere que el objeto primario esté en la zona "adelante"
         * y el secundario en la zona "atrás".
         * @param primaryDistance Distancia del objeto primario.
         * @param secondaryDistance Distancia del objeto secundario.
         * @return `true` si la configuración es válida, de lo contrario `false`.
         */
        fun isValidDualConfiguration(
            primaryDistance: Int,
            secondaryDistance: Int
        ): Boolean {
            return isFrontZone(primaryDistance) && isBackZone(secondaryDistance)
        }

        // =============================================================================
        // FUNCIONES DE UTILIDAD PRIVADAS
        // =============================================================================

        /**
         * Normaliza una cadena de color para que coincida con los nombres de color
         * predefinidos en el sistema (ej. "Rojo", "Verde").
         * @param color La cadena de color a normalizar.
         * @return La cadena de color normalizada.
         */
        private fun normalizeColor(color: String): String {
            return when (color.lowercase().trim()) {
                "rojo", "red" -> "Rojo"
                "verde", "green" -> "Verde"
                "magenta", "purple" -> "Magenta"
                else -> color
            }
        }

        /**
         * Verifica si una cadena de orientación es válida.
         * @param orientation La cadena de orientación a verificar.
         * @return `true` si es "L" o "R", de lo contrario `false`.
         */
        private fun isValidOrientation(orientation: String): Boolean {
            return orientation in listOf("L", "R")
        }
    }
}
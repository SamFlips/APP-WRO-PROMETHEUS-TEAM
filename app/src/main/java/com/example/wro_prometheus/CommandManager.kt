package com.example.wro_prometheus
import android.util.Log

/**7
 * SISTEMA DE COMANDOS WRO PROMETHEUS - VERSIÓN COMPLETA CON TODOS LOS CASOS DUALES
 */

class CommandManager {

    companion object {

        // =============================================================================
        // CONSTANTES DE DISTANCIA - CORREGIDAS PARA COINCIDIR CON MAINACTIVITY
        // =============================================================================


        /** Distancia mínima válida */
        private const val MIN_DISTANCE = 10

        /** Distancia máxima válida */
        private const val MAX_DISTANCE = 200

        /** Área de referencia del objeto a distancia conocida */
        private val REFERENCE_AREA get() = ConfigSettings.referenceArea

        /** Distancia de referencia en centímetros */
        private val REFERENCE_DISTANCE get() = ConfigSettings.referenceDistance

        /** Distancia mínima para objetos "adelante" */
        private val FRONT_DISTANCE_MIN get() = ConfigSettings.frontDistanceMin

        /** Distancia máxima para objetos "adelante" */
        private val FRONT_DISTANCE_MAX get() = ConfigSettings.frontDistanceMax

        /** Distancia mínima para objetos en el "medio" */
        private val MIDDLE_DISTANCE_MIN get() = ConfigSettings.middleDistanceMin

        /** Distancia máxima para objetos en el "medio" */
        private val MIDDLE_DISTANCE_MAX get() = ConfigSettings.middleDistanceMax

        /** Distancia mínima para objetos "atrás" */
        private val BACK_DISTANCE_MIN get() = ConfigSettings.backDistanceMin

        /** Distancia máxima para objetos "atrás" */
        private val BACK_DISTANCE_MAX get() = ConfigSettings.backDistanceMax

        /** Comando cuando no hay detección válida */
        private const val NO_DETECTION_COMMAND = "N"



        // =============================================================================
        // MODO ESTACIONAMIENTO — activado cuando se detecta magenta
        // =============================================================================
        // Cuando hay magenta visible, la regla WRO obliga a que todas las señales
        // se desplacen al muro interior. En sentido estándar quedan todas a la
        // izquierda (L), en sentido inverso todas a la derecha (R).
        // El sistema detecta automáticamente cuál aplica según los objetos visibles.

        var parkingModeActive: Boolean = false
            private set
        var parkingSide: String? = null
            private set

        fun setParkingSide(side: String) {
            if ((side == "L" || side == "R") && parkingSide != side) {
                parkingSide = side
                Log.d("CommandManager", "🟣 Lado de estacionamiento fijado: $side (muro interior)")
            }
        }

        fun activateParkingMode() {
            if (!parkingModeActive) {
                parkingModeActive = true
                Log.d("CommandManager", "🟣 MODO ESTACIONAMIENTO ACTIVADO")
            }
        }

        fun deactivateParkingMode() {
            if (parkingModeActive) {
                parkingModeActive = false
                parkingSide = null   // ← nuevo: se vuelve a determinar en el próximo tramo
                Log.d("CommandManager", "⬜ MODO ESTACIONAMIENTO DESACTIVADO")
            }
        }

        // Casos individuales en modo estacionamiento — SOLO lado L (sentido estándar)
        private val parkingSingleCasesL = listOf(
            Triple("C07E", "Verde",  "L"),   // Verde Adelante Izquierda
            Triple("C08E", "Rojo",   "L"),   // Rojo Adelante Izquierda
            Triple("C09E", "Verde",  "L"),   // Verde Medio Izquierda
            Triple("C10E", "Rojo",   "L"),   // Rojo Medio Izquierda
            Triple("C37E", "Verde",  "L"),   // Verde Atrás Izquierda
            Triple("C38E", "Rojo",   "L")    // Rojo Atrás Izquierda
        )

        // Casos individuales en modo estacionamiento — SOLO lado R (sentido inverso)
        private val parkingSingleCasesR = listOf(
            Triple("C01E", "Verde",  "R"),   // Verde Adelante Derecha
            Triple("C02E", "Rojo",   "R"),   // Rojo Adelante Derecha
            Triple("C03E", "Verde",  "R"),   // Verde Medio Derecha
            Triple("C04E", "Rojo",   "R"),   // Rojo Medio Derecha
            Triple("C05E", "Verde",  "R"),   // Verde Atrás Derecha
            Triple("C06E", "Rojo",   "R")    // Rojo Atrás Derecha
        )

        // Casos duales en modo estacionamiento — L+L (sentido estándar)
        private val parkingDualCasesLL = listOf(
            DualCase("C31E", "Verde", "L", "Verde", "L", "Verde Adelante Izq + Verde Atrás Izq"),
            DualCase("C32E", "Verde", "L", "Rojo",  "L", "Verde Adelante Izq + Rojo Atrás Izq"),
            DualCase("C33E", "Rojo",  "L", "Verde", "L", "Rojo Adelante Izq + Verde Atrás Izq"),
            DualCase("C36E", "Rojo",  "L", "Rojo",  "L", "Rojo Adelante Izq + Rojo Atrás Izq")
        )

        // Casos duales en modo estacionamiento — R+R (sentido inverso)
        private val parkingDualCasesRR = listOf(
            DualCase("C25E", "Verde", "R", "Verde", "R", "Verde Adelante Der + Verde Atrás Der"),
            DualCase("C26E", "Verde", "R", "Rojo",  "R", "Verde Adelante Der + Rojo Atrás Der"),
            DualCase("C27E", "Rojo",  "R", "Verde", "R", "Rojo Adelante Der + Verde Atrás Der"),
            DualCase("C30E", "Rojo",  "R", "Rojo",  "R", "Rojo Adelante Der + Rojo Atrás Der")
        )


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
         */
        fun determineCommand(color: String, orientation: String, distance: Int): String {
            val matchingCase = CommandCase.values().find { case ->
                case.matches(color, orientation, distance)
            }
            return matchingCase?.code ?: NO_DETECTION_COMMAND
        }

        /**
         * Determina el comando cuando se detectan dos objetos.
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
         * Determina el comando en MODO ESTACIONAMIENTO para un solo objeto.
         * Solo acepta el lado que corresponde según la orientación del objeto:
         * si está a la izquierda → usa tabla L, si está a la derecha → usa tabla R.
         * Esto refleja la regla WRO: todos los objetos se desplazan al muro interior.
         */
        fun determineParkingCommand(
            color: String,
            orientation: String,
            distance: Int
        ): Pair<String, String> {
            val normalizedColor = normalizeColor(color)

            // El pilar SIEMPRE está en el lado opuesto al muro del estacionamiento
            // (regla WRO). Se fuerza por descarte en vez de confiar en la posición
            // en pantalla del pilar, que falla si el auto está torcido.
            val effectiveOrientation = when (parkingSide) {
                "L" -> "R"
                "R" -> "L"
                else -> orientation  // fallback si por algo no se fijó el lado todavía
            }

            val table = if (effectiveOrientation == "L") parkingSingleCasesL else parkingSingleCasesR

            // Determinar zona de distancia
            val zone = when {
                distance in FRONT_DISTANCE_MIN..FRONT_DISTANCE_MAX -> "Adelante"
                distance in MIDDLE_DISTANCE_MIN..MIDDLE_DISTANCE_MAX -> "Medio"
                distance in BACK_DISTANCE_MIN..BACK_DISTANCE_MAX -> "Atrás"
                else -> null
            }

            if (zone == null) {
                Log.d("CommandManager", "🟣E Distancia fuera de rango: ${distance}cm")
                return Pair(NO_DETECTION_COMMAND, "Distancia inválida en modo estacionamiento")
            }

            val match = table.find { (_, c, o) ->
                c.equals(normalizedColor, ignoreCase = true) && o == effectiveOrientation
            }

            return if (match != null) {
                Log.d("CommandManager", "🟣E COMANDO ESTACIONAMIENTO SIMPLE: ${match.first}")
                Pair(match.first, "$normalizedColor $zone $effectiveOrientation — Estacionamiento")
            } else {
                Log.d("CommandManager", "🟣E Sin comando simple para $normalizedColor $effectiveOrientation")
                Pair(NO_DETECTION_COMMAND, "Sin caso de estacionamiento para: $normalizedColor $effectiveOrientation")
            }
        }
        /**
         * Determina el comando en MODO ESTACIONAMIENTO para dos objetos.
         * Solo acepta combinaciones L+L o R+R. Si los objetos están en lados
         * distintos (mixto), cae al comando simple del objeto primario.
         */
        fun determineParkingDualCommand(
            primaryColor: String, primaryOrientation: String, primaryDistance: Int,
            secondaryColor: String, secondaryOrientation: String, secondaryDistance: Int
        ): Pair<String, String> {
            val pColor = normalizeColor(primaryColor)
            val sColor = normalizeColor(secondaryColor)

            // ← ESTA declaración es la que falta agregar acá:
            val effectiveOrientation = when (parkingSide) {
                "L" -> "R"
                "R" -> "L"
                else -> primaryOrientation
            }

            Log.d("CommandManager", "🟣E DUAL ESTACIONAMIENTO: $pColor $effectiveOrientation + $sColor $effectiveOrientation")

            // Verificar distancias válidas para caso dual
            val isPrimaryFront  = primaryDistance   in FRONT_DISTANCE_MIN..FRONT_DISTANCE_MAX
            val isSecondaryBack = secondaryDistance in BACK_DISTANCE_MIN..BACK_DISTANCE_MAX

            if (!isPrimaryFront || !isSecondaryBack) {
                Log.d("CommandManager", "🟣E Distancias no válidas para dual — cayendo a simple")
                return determineParkingCommand(primaryColor, primaryOrientation, primaryDistance)
            }

            // Seleccionar tabla según combinación de orientaciones
            val table = if (effectiveOrientation == "L") parkingDualCasesLL else parkingDualCasesRR

            val match = table.find { case ->
                case.primaryColor.equals(pColor, ignoreCase = true) &&
                        case.primaryPosition == effectiveOrientation &&
                        case.secondaryColor.equals(sColor, ignoreCase = true) &&
                        case.secondaryPosition == effectiveOrientation
            }

            return if (match != null) {
                Log.d("CommandManager", "🟣E DUAL ESTACIONAMIENTO: ${match.code}")
                Pair(match.code, match.description)
            } else {
                Log.d("CommandManager", "🟣E Sin caso dual — cayendo a simple")
                determineParkingCommand(primaryColor, effectiveOrientation, primaryDistance)
            }
        }

        // Información de comandos E para getCommandInfo
        fun getParkingCommandInfo(commandCode: String): String? {
            val allSingle = parkingSingleCasesL + parkingSingleCasesR
            val singleMatch = allSingle.find { it.first == commandCode }
            if (singleMatch != null) return "${singleMatch.second} ${singleMatch.third} — Estacionamiento"

            val allDual = parkingDualCasesLL + parkingDualCasesRR
            return allDual.find { it.code == commandCode }?.description
        }


        /**
         * Calcula la distancia de un objeto en centímetros basándose en su área.
         */
        fun calculateDistance(area: Double): Int {
            val calculatedDistance = kotlin.math.sqrt(REFERENCE_AREA / area) * REFERENCE_DISTANCE
            return calculatedDistance.toInt().coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        }

        /**
         * Calcula la orientación de un objeto ('L' o 'R') basándose en su posición
         * relativa a la MISMA línea diagonal violeta que se dibuja en MainActivity.
         *
         * IMPORTANTE: Esta función debe ser IDÉNTICA al cálculo de la línea violeta.
         */
        fun calculateOrientation(centerX: Float, centerY: Float, imageWidth: Int, imageHeight: Int): String {
            val normalizedX = centerX / imageWidth.toFloat()
            return when {
                normalizedX < 0.60f -> "L"
                else -> "R"
            }
        }

        /**
         * Determina el comando de forma segura, validando los datos de entrada.
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

        // =============================================================================
        // FUNCIONES DE UTILIDAD
        // =============================================================================

        fun isValidDistance(distance: Int): Boolean {
            return CommandCase.values().any { case ->
                case.objectCount == 1 && distance in case.distanceMin..case.distanceMax
            }
        }

        fun getCommandInfo(commandCode: String): String? {
            // Buscar en casos normales
            val singleCase = CommandCase.values().find { it.code == commandCode }
            if (singleCase != null) return singleCase.description

            val dualCase = dualCases.find { it.code == commandCode }
            if (dualCase != null) return dualCase.description

            // Buscar en casos de estacionamiento
            return getParkingCommandInfo(commandCode)
        }

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

        fun getDualCasesInfo(): String {
            return dualCases.joinToString("\n") { case ->
                "${case.code}: ${case.description}"
            }
        }

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

        fun getDualCasesByPattern(): Map<String, List<DualCase>> {
            return dualCases.groupBy { case ->
                "${case.primaryPosition}-${case.secondaryPosition}"
            }
        }

        fun isFrontZone(distance: Int): Boolean {
            return distance in FRONT_DISTANCE_MIN..FRONT_DISTANCE_MAX
        }

        fun isMiddleZone(distance: Int): Boolean {
            return distance in MIDDLE_DISTANCE_MIN..MIDDLE_DISTANCE_MAX
        }

        fun isBackZone(distance: Int): Boolean {
            return distance in BACK_DISTANCE_MIN..BACK_DISTANCE_MAX
        }

        fun isValidDualConfiguration(
            primaryDistance: Int,
            secondaryDistance: Int
        ): Boolean {
            return isFrontZone(primaryDistance) && isBackZone(secondaryDistance)
        }

        // =============================================================================
        // FUNCIONES DE UTILIDAD PRIVADAS
        // =============================================================================

        private fun normalizeColor(color: String): String {
            return when (color.lowercase().trim()) {
                "rojo", "red" -> "Rojo"
                "verde", "green" -> "Verde"
                "magenta", "purple" -> "Magenta"
                else -> color
            }
        }

        private fun isValidOrientation(orientation: String): Boolean {
            return orientation in listOf("L", "R")
        }
    }
}
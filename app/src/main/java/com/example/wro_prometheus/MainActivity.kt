// ===================================================================================
// Proyecto: WRO Prometheus App
// Descripción: Aplicación de Android para la detección y reconocimiento de colores de los obstaculos y el estacionamiento de la competencia World Robot Olympiad (WRO) 2025.
// Categoria Futuros Ingenieros. Usando OpenCV y comunicacion serial USB con el arduino
//
// Desarrollado por:
// Equipo: [PROMETHEUS TEAM]
// Integrantes: [Samuel Pérez], [Trino Carrisales], [Leandro Padrón]
//
// Programador Principal:
// [Samuel Pérez]
//
// Fecha de Creación: 20/4/2025
// Versión: 5.0 - Implementacion del nuevo sistema de comandos por casos y doble deteccion de objetos.
//
// ===================================================================================
package com.example.wro_prometheus

import android.Manifest
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.AspectRatio
import android.util.Size
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import android.view.Window
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import com.example.wro_prometheus.ColorAnalyzer.Companion.DualObjectDetection
import com.example.wro_prometheus.ColorAnalyzer.Companion.ObjectPosition
import com.example.wro_prometheus.CommandManager  // <-- AGREGAR ESTA LÍNEA


// MainActivity es la actividad principal de la aplicación.
// Implementa UsbPermissionReceiver.UsbPermissionListener para manejar eventos de permiso USB.
class MainActivity : AppCompatActivity(), UsbPermissionReceiver.UsbPermissionListener {

    // Acción de intento para solicitar permisos USB.
    private val ACTION_USB_PERMISSION = "com.example.opencvserial.USB_PERMISSION"
    // Código de solicitud para permisos de cámara.
    private val CAMERA_PERMISSION_REQUEST_CODE = 100


    // Objeto anidado que contiene constantes estáticas para toda la clase.
    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val MIN_AREA_THRESHOLD = 800.0
        private const val MIN_COMMAND_INTERVAL = 50L
        private const val DETECTION_TIMEOUT = 300L
        private const val USB_WRITE_TIMEOUT = 500
        private const val USB_READ_TIMEOUT = 200
        private const val USB_SETUP_DELAY = 500L
        private const val USB_RECONNECT_DELAY = 2000L
        private const val MAX_WRITE_RETRIES = 2       // Máximo reintentos de escritura

        private const val INCOMING_DATA_BUFFER_SIZE = 2048

        // CONSTANTES PARA CÁLCULOS DE DISTANCIA
        // Área de referencia (en píxeles cuadrados) de un objeto a una distancia conocida.
        const val REFERENCE_AREA = 26000.0  // Área de referencia a 30cm
        // Distancia de referencia en centímetros a la que se obtuvo REFERENCE_AREA.
        const val REFERENCE_DISTANCE = 30.0 // Distancia de referencia en cm
        // Distancia mínima en centímetros que se puede calcular o enviar.
        const val MIN_DISTANCE = 10        // Distancia mínima en cm
        // Distancia máxima en centímetros que se puede calcular o enviar.
        const val MAX_DISTANCE = 150       // Distancia máxima en cm

        // PATRÓN SINGLETON - Variables estáticas para acceso global
        private var instance: MainActivity? = null

        // CONSTANTES PARA RESOLUCIÓN 9:16
        private const val TARGET_WIDTH = 720    // Ancho objetivo
        private const val TARGET_HEIGHT = 1280  // Alto objetivo (ratio 9:16)
        private val TARGET_RESOLUTION = Size(TARGET_WIDTH, TARGET_HEIGHT)

        // Variables para manejo de datos entrantes de Arduino
        private val incomingDataBuffer = StringBuilder()
        private var lastIncomingDataTime = 0L
        private var incomingDataProcessor: ExecutorService? = null

        // CONSTANTES ESPECÍFICAS PARA ESP32
        private const val ESP32_SETUP_DELAY = 2000L      // ESP32 necesita más tiempo para inicializar
        private const val ESP32_RESET_DELAY = 1500L      // Tiempo después del reset de ESP32
        private const val ESP32_DTR_RTS_DELAY = 200L     // Delay específico para señales ESP32

        // Baud rates comunes para ESP32
        private val ESP32_BAUD_RATES = arrayOf(115200, 921600, 460800, 230400)

        private val SERIAL_MONITOR_REQUEST_CODE = 1001

        private val isDetectionActive = AtomicBoolean(false)

        // Función para obtener la instancia actual de MainActivity
        fun getInstance(): MainActivity? = instance

    }

    // Variables de la clase para gestionar el hardware y la UI.
    private lateinit var usbManager: UsbManager // Administrador de USB del sistema.
    private var port: UsbSerialPort? = null // Puerto serial USB actual.
    private var connection: UsbDeviceConnection? = null // Conexión USB al dispositivo.
    private var pendingDriver: UsbSerialDriver? = null // Driver USB pendiente de permisos.
    private lateinit var usbReceiver: UsbPermissionReceiver // Receptor de permisos USB.
    private lateinit var tvStatus: TextView // TextView para mostrar el estado en la UI.
    private lateinit var imageView: ImageView // ImageView para mostrar el frame de la cámara.
    private lateinit var cameraExecutor: ExecutorService // Executor para tareas de la cámara.
    private var imageAnalyzer: ImageAnalysis? = null // Analizador de imagen para CameraX.

    // VARIABLES PARA DESPERTAR PANTALLA
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var powerManager: PowerManager

    // Variables optimizadas para máxima velocidad y seguridad de hilos.
    private val currentDetection = AtomicReference<String>("") // Almacena la última detección actual.
    private val lastSentCommand = AtomicReference<String>("") // Almacena el último comando enviado al Arduino.
    private val isConnected = AtomicBoolean(false) // Indica si hay una conexión USB activa.
    private val isReconnecting = AtomicBoolean(false) // Indica si se está en proceso de reconexión.
    private var lastCommandTime = 0L // Marca de tiempo del último comando enviado.
    private var lastDetectionTime = 0L // Marca de tiempo de la última detección de pilar.
    private var toast: Toast? = null // Objeto Toast para mostrar mensajes al usuario.

    // Sistema de cola de comandos para procesamiento ultra rápido.
    private val commandQueue = LinkedBlockingQueue<String>() // Cola de comandos a enviar al Arduino.
    private lateinit var commandProcessor: ExecutorService // Executor para procesar la cola de comandos.

    // Executor dedicado para USB con alta prioridad.
    private lateinit var usbExecutor: ExecutorService // Executor para operaciones USB.

    // Coroutine scope optimizado.
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob()) // Scope para corrutinas con manejo de errores.

    private var isSerialMonitorOpen = AtomicBoolean(false)

    private var lastReceivedMessage = ""
    private var lastMessageTime = 0L
    private val MESSAGE_DISPLAY_DURATION = 3000L // 3 segundos

// =============================================================================
// VARIABLES PARA FILTRO TEMPORAL DE DETECCIONES
// =============================================================================
    // Tiempo mínimo que debe mantenerse una detección antes de enviar comando
    private val DETECTION_STABILITY_TIME = 1000L // 1 segundo

    // Variables para seguimiento temporal de detecciones
    private var currentStableDetection = AtomicReference<String>("") // Detección que se está estabilizando
    private var detectionStartTime = AtomicReference<Long>(0L) // Momento cuando inició la detección actual
    private var isDetectionStable = AtomicBoolean(false) // Si la detección actual ya es estable
    private var lastStableCommand = AtomicReference<String>("") // Último comando estable enviado

    // Executor dedicado para el filtro temporal
    private lateinit var temporalFilterExecutor: ExecutorService


    // Metodo onCreate se llama cuando la actividad es creada.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- INICIO: MODO PANTALLA COMPLETA ---
        // 1. Oculta la barra de título original de la aplicación
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        // 2. Oculta la barra de notificaciones y hace que la app ocupe toda la pantalla
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        // --- FIN: MODO PANTALLA COMPLETA ---

        setContentView(R.layout.activity_main) // Establece el diseño de la interfaz de usuario.

        // ESTABLECER LA INSTANCIA SINGLETON
        instance = this

        // CONFIGURAR DESPERTAR DE PANTALLA
        setupWakeScreen()

        // Inicializa OpenCV. Muestra un Toast si falla.
        if (!OpenCVLoader.initDebug()) {
            showToastOnce("OpenCV no pudo inicializarse")
        }

        // Obtiene el servicio USB del sistema.
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        // CONFIGURAR POWER MANAGER
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Inicializa los elementos de la UI.
        tvStatus = findViewById(R.id.tvStatus)
        imageView = findViewById(R.id.imageView)

        // Executors optimizados para diferentes tareas, cada uno con su propio hilo y prioridad.
        cameraExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "CameraThread").apply {
                priority = Thread.MAX_PRIORITY // Alta prioridad para el hilo de la cámara.
            }
        }

        usbExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "USBThread").apply {
                priority = Thread.MAX_PRIORITY // Alta prioridad para el hilo USB.
            }
        }

        commandProcessor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "CommandProcessor").apply {
                priority = Thread.MAX_PRIORITY // Alta prioridad para el procesador de comandos.
            }
        }

        // Configura los listeners para los botones de la UI.
        findViewById<Button>(R.id.btnConnect).setOnClickListener { connectToUsbDevice() } // Botón para conectar al USB.
        findViewById<Button>(R.id.btnLedOff).setOnClickListener {
            if (isConnected.get()) {
                isSerialMonitorOpen.set(true)

                // Usar startActivityForResult en lugar de startActivity
                val intent = SerialMonitorActivity.createIntent(this)
                startActivityForResult(intent, SERIAL_MONITOR_REQUEST_CODE)

                showToastOnce("📊 Monitor abierto - Manteniendo conexión")
            } else {
                showToastOnce("❌ Conecta primero un dispositivo USB")
            }
        }

        // Configura el receptor de permisos USB.
        setupUsbReceiver()
        // Inicia el procesador de comandos.
        startCommandProcessor()
        // Inicia el monitor de detección (para enviar "no detección" si no hay actividad).
        startDetectionMonitor()

        startDetectionHealthMonitor()

        // Maneja cualquier intento USB que haya lanzado la actividad (ej. al conectar un dispositivo).
        handleUsbIntent(intent)

        // Verifica y solicita permisos de cámara si es necesario.
        if (allPermissionsGranted()) {
            startCamera() // Inicia la cámara si los permisos están concedidos.
        } else {
            // Solicita permisos de cámara si no están concedidos.
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, CAMERA_PERMISSION_REQUEST_CODE
            )
        }

        temporalFilterExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "TemporalFilterThread").apply {
                priority = Thread.NORM_PRIORITY
            }
        }

    }


    // Métodos públicos para acceder a los objetos USB desde SerialMonitorActivity
    fun getUsbPort(): UsbSerialPort? = port

    fun getUsbConnection(): UsbDeviceConnection? = connection

    fun isUsbConnected(): Boolean = isConnected.get()

    // Método mejorado para verificar estado de conexión
    fun getConnectionDetails(): String {
        return buildString {
            append("Puerto: ${if (port != null) "Disponible" else "Null"}")
            append(" | Abierto: ${port?.isOpen ?: false}")
            append(" | Conexión: ${if (connection != null) "Activa" else "Null"}")
            append(" | Estado: ${isConnected.get()}")
        }
    }

    // Método para obtener estadísticas de comandos
    fun getCommandStats(): Pair<String, Long> {
        return Pair(lastSentCommand.get(), lastCommandTime)
    }

    fun getDualDetectionInfo(): DualObjectDetection {
        return ColorAnalyzer.lastDualDetection
    }

    fun getPrimaryObjectInfo(): String {
        val detection = ColorAnalyzer.lastDualDetection
        return detection.primaryObject?.let { obj ->
            // CORRECCIÓN: Llamar a CommandManager directamente
            val distance = CommandManager.calculateDistance(obj.area)
            val orientation = CommandManager.calculateOrientation(obj.centerX, obj.centerY, obj.imageWidth, obj.imageHeight)
            "Color: ${obj.color}, Distancia: ${distance}cm, Orientación: $orientation, Área: ${obj.area.toInt()}px"
        } ?: "Sin objeto primario"
    }

    fun getSecondaryObjectInfo(): String {
        val detection = ColorAnalyzer.lastDualDetection
        return detection.secondaryObject?.let { obj ->
            // CORRECCIÓN: Llamar a CommandManager directamente
            val distance = CommandManager.calculateDistance(obj.area)
            val orientation = CommandManager.calculateOrientation(obj.centerX, obj.centerY, obj.imageWidth, obj.imageHeight)
            "Color: ${obj.color}, Distancia: ${distance}cm, Orientación: $orientation, Área: ${obj.area.toInt()}px"
        } ?: "Sin objeto secundario"
    }

    // Método mejorado para cerrar conexión de forma segura
    fun safeCloseConnection() {
        isConnected.set(false)

        Thread {
            try {
                port?.takeIf { it.isOpen }?.close()
                connection?.close()
            } catch (e: Exception) {
                // Ignorar errores al cerrar
            } finally {
                port = null
                connection = null
            }
        }.start()
    }

    fun onSerialMonitorClosed() {
        isSerialMonitorOpen.set(false)
    }

    private object ArduinoCommands {
        const val NO_DETECTION = "N"

        fun createPilarCommand(color: String, distance: Int, orientation: String): String {
            val (command, _) = CommandManager.determineCommandSafe(color, orientation, distance)
            return command
        }
    }

    // NUEVO MÉTODO PARA CONFIGURAR DESPERTAR DE PANTALLA
    private fun setupWakeScreen() {
        try {
            // Configurar la ventana para que se muestre sobre la pantalla de bloqueo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                )
            }
        } catch (e: Exception) {
            // Continuar sin estas funcionalidades si hay error
        }
    }

    // METODO PARA DESPERTAR LA PANTALLA
    private fun wakeUpScreen() {
        try {
            // Crear WakeLock si no existe
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                    "WROPrometheus:WakeLock"
                )
            }

            // Adquirir WakeLock por 10 segundos
            wakeLock?.acquire(10000L)

            showToastOnce("📱 Pantalla despertada - Arduino detectado")
        } catch (e: Exception) {
            showToastOnce("Error al despertar pantalla: ${e.message}")
        }
    }

    // Inicia el procesador de comandos en un hilo separado.
    private fun startCommandProcessor() {
        commandProcessor.execute {
            Log.d("CommandProcessor", "🚀 Procesador de comandos iniciado")

            while (!Thread.currentThread().isInterrupted) {
                try {
                    val command = commandQueue.take() // Bloquea hasta que haya un comando en la cola.

                    // LOG del comando que se va a procesar
                    Log.d("CommandProcessor", "📦 Procesando comando de la cola: $command")

                    sendCommandImmediate(command) // Envía el comando al Arduino.

                    // Pequeño delay para evitar saturar el puerto
                    Thread.sleep(10)

                } catch (e: InterruptedException) {
                    Log.d("CommandProcessor", "❌ Procesador de comandos interrumpido")
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    Log.e("CommandProcessor", "Error en procesador de comandos: ${e.message}", e)
                    // Continuar procesando otros comandos
                    Thread.sleep(50)
                }
            }

            Log.d("CommandProcessor", "🛑 Procesador de comandos terminado")
        }
    }

    // Monitorea la actividad de detección y envía un comando de "no detección" si hay un tiempo de espera.
    private fun startDetectionMonitor() {
        scope.launch {
            while (isActive) {
                delay(100)

                val currentTime = System.currentTimeMillis()

                // Solo enviar "N" cuando:
                // 1. Hay timeout desde la última detección real
                // 2. No se está procesando ningún comando en el filtro temporal
                // 3. No se está enviando "N" ya
                // 4. Está conectado y no hay monitor abierto
                if (currentTime - lastDetectionTime > DETECTION_TIMEOUT &&
                    !isDetectionStable.get() && // No hay comando estabilizándose
                    currentStableDetection.get().isEmpty() && // No hay detección en proceso
                    lastSentCommand.get() != ArduinoCommands.NO_DETECTION &&
                    isConnected.get() &&
                    !isSerialMonitorOpen.get()) {

                    Log.d("DetectionMonitor", "⏰ Timeout detectado - enviando N")

                    // Limpiar variables del filtro temporal
                    currentStableDetection.set("")
                    detectionStartTime.set(0L)
                    isDetectionStable.set(false)

                    queueCommand(ArduinoCommands.NO_DETECTION)
                }
            }
        }
    }

    // Se llama cuando la actividad recibe un nuevo Intent (ej. al conectar un dispositivo USB).
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleUsbIntent(intent) // Delega el manejo del Intent al metodo especifico.
    }

    // Maneja los Intents relacionados con eventos USB - MODIFICADO PARA DESPERTAR PANTALLA
    private fun handleUsbIntent(intent: Intent?) {
        when (intent?.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                // DESPERTAR PANTALLA CUANDO SE CONECTA DISPOSITIVO
                wakeUpScreen()

                // Obtener información del dispositivo conectado
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                val deviceName = when {
                    device != null && isESP32Device(device.vendorId, device.productId) -> "ESP32"
                    device != null -> "Arduino"
                    else -> "Dispositivo USB"
                }

                showToastOnce("📱 $deviceName conectado - Iniciando automáticamente...")

                // Pequeño delay para asegurar que el dispositivo esté listo
                scope.launch {
                    delay(if (deviceName == "ESP32") 2000 else 1000) // ESP32 necesita más tiempo

                    withContext(Dispatchers.Main) {
                        connectToUsbDevice()
                    }
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                val deviceName = when {
                    device != null && isESP32Device(device.vendorId, device.productId) -> "ESP32"
                    device != null -> "Arduino"
                    else -> "Dispositivo USB"
                }

                showToastOnce("📱 $deviceName desconectado")
                isConnected.set(false)

                // Limpiar la conexión
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            port?.takeIf { it.isOpen }?.close()
                            connection?.close()
                        } catch (e: Exception) {
                            // Ignorar errores al desconectar
                        } finally {
                            port = null
                            connection = null
                        }
                    }

                    withContext(Dispatchers.Main) {
                        updateStatusFast("❌ Desconectado")
                    }
                }
            }
        }
    }

    // Procesa la detección de un pilar con su color, área, centro y dimensiones de la imagen.
    private fun processPilarDetection(color: String, area: Double, centerX: Float, centerY: Float, imageWidth: Int, imageHeight: Int) {
        if (area < MIN_AREA_THRESHOLD) return

        try {
            // Usar las funciones locales que internamente usan CommandManager
            val distance = CommandManager.calculateDistance(area)
            val orientation = CommandManager.calculateOrientation(centerX, centerY, imageWidth, imageHeight)


            // Obtener comando del CommandManager
            val (command, commandInfo) = CommandManager.determineCommandSafe(color, orientation, distance)

            // Debug info mejorado
            Log.d("CommandSystem", "=== PROCESANDO DETECCIÓN ===")
            Log.d("CommandSystem", "Color: $color | Orientación: $orientation | Distancia: ${distance}cm")
            Log.d("CommandSystem", "Comando generado: $command | Info: $commandInfo")
            Log.d("CommandSystem", "Último enviado: ${lastSentCommand.get()}")

            // Actualizar detección actual SIEMPRE
            currentDetection.set(command)
            lastDetectionTime = System.currentTimeMillis()

            // Encolar comando si es diferente al último
            if (command != lastSentCommand.get()) {
                Log.d("CommandSystem", "✅ Encolando comando: $command (diferente al anterior)")
                queueCommand(command)
            } else {
                Log.d("CommandSystem", "⚠️ Comando ignorado - igual al anterior: $command")
            }

        } catch (e: Exception) {
            Log.e("CommandSystem", "❌ Error en processPilarDetection: ${e.message}", e)
        }
    }

    // Calcula la distancia de un objeto basándose en su área percibida.
    private fun calculateDistance(area: Double): Int {
        return CommandManager.calculateDistance(area)
    }


    // Encola un comando para ser enviado al Arduino, respetando un intervalo mínimo.
    private fun queueCommand(command: String) {
        val currentTime = System.currentTimeMillis()

        // Log de entrada
        Log.d("CommandQueue", "=== INTENTANDO ENCOLAR ===")
        Log.d("CommandQueue", "Comando: $command")
        Log.d("CommandQueue", "Último enviado: ${lastSentCommand.get()}")
        Log.d("CommandQueue", "Conectado: ${isConnected.get()}")
        Log.d("CommandQueue", "Monitor abierto: ${isSerialMonitorOpen.get()}")

        // Verificar intervalo mínimo
        if (currentTime - lastCommandTime < MIN_COMMAND_INTERVAL) {
            Log.d("CommandQueue", "❌ Intervalo mínimo no cumplido")
            return
        }

        // Solo verificar salud si NO está el monitor abierto
        if (!isSerialMonitorOpen.get() && !verifyConnectionHealth()) {
            Log.d("CommandQueue", "❌ Conexión no saludable - reintentando")
            reconnectDevice()
            return
        }

        if (!isConnected.get()) {
            Log.d("CommandQueue", "❌ No conectado")
            return
        }

        // Limpiar comandos duplicados de la cola
        val queueSize = commandQueue.size
        val iterator = commandQueue.iterator()
        var removedCount = 0
        while (iterator.hasNext()) {
            if (iterator.next() == command) {
                iterator.remove()
                removedCount++
            }
        }

        if (removedCount > 0) {
            Log.d("CommandQueue", "🧹 Removidos $removedCount comandos duplicados de la cola")
        }

        // Encolar el nuevo comando
        val offered = commandQueue.offer(command)

        if (offered) {
            Log.d("CommandQueue", "✅ Comando encolado exitosamente: $command (cola: ${commandQueue.size})")
        } else {
            Log.e("CommandQueue", "❌ Falló encolar comando: $command")
        }
    }

    // Envía un comando inmediatamente al puerto USB, manejando errores de conexión.
    private fun sendCommandImmediate(command: String) {
        if (!isConnected.get() || port == null) {
            Log.w("CommandSystem", "No se puede enviar $command - no conectado")
            return
        }

        var retryCount = 0
        var success = false
        val currentTime = System.currentTimeMillis()

        while (retryCount < MAX_WRITE_RETRIES && !success) {
            try {
                val currentPort = port
                val currentConnection = connection

                if (currentPort == null || currentConnection == null || !currentPort.isOpen) {
                    Log.w("CommandSystem", "Puerto no disponible para comando $command")
                    return
                }

                // Preparar comando con terminador
                val commandBytes = "${command}\n".toByteArray()

                // Escribir con manejo de errores más permisivo
                try {
                    currentPort.write(commandBytes, USB_WRITE_TIMEOUT)
                    success = true

                    // Actualizar variables de estado SOLO si fue exitoso
                    lastSentCommand.set(command)
                    lastCommandTime = currentTime

                    // LOG DE ÉXITO
                    Log.d("CommandSystem", "✅ Comando enviado exitosamente: $command")

                    // Actualizar UI solo si es necesario
                    if (!isSerialMonitorOpen.get()) {
                        runOnUiThread {
                            // Solo mostrar toast para comandos importantes (no "N")
                            if (command != "N") {
                                showToastOnce("📤 Enviado: $command")
                            }
                        }
                    }

                } catch (e: IOException) {
                    throw IOException("Error en escritura USB: ${e.message}")
                } catch (e: Exception) {
                    throw IOException("Error inesperado en escritura: ${e.message}")
                }

            } catch (e: IOException) {
                retryCount++
                Log.w("CommandSystem", "Intento $retryCount fallido para comando $command: ${e.message}")

                if (retryCount < MAX_WRITE_RETRIES) {
                    try {
                        Thread.sleep(100L)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                } else {
                    // Solo mostrar error después de múltiples fallos SI NO está el monitor abierto
                    Log.e("CommandSystem", "❌ Falló envío de $command después de $MAX_WRITE_RETRIES intentos")
                    if (!isSerialMonitorOpen.get()) {
                        runOnUiThread {
                            showToastOnce("USB Error persistente - Verificar conexión")
                        }
                    }
                    return
                }
            } catch (e: Exception) {
                Log.e("CommandSystem", "Error inesperado enviando $command: ${e.message}")
                if (!isSerialMonitorOpen.get()) {
                    runOnUiThread {
                        showToastOnce("Error inesperado: ${e.message}")
                    }
                }
                return
            }
        }
    }

    // Configura el BroadcastReceiver para escuchar los eventos de permiso USB.
    private fun setupUsbReceiver() {
        usbReceiver = UsbPermissionReceiver().apply { listener = this@MainActivity } // Crea una instancia y asigna el listener.
        val filter = IntentFilter(ACTION_USB_PERMISSION) // Crea un filtro para la acción de permiso USB.
        // Configura las flags para el registro del receiver, adaptándose a diferentes versiones de Android.
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        registerReceiver(usbReceiver, filter, receiverFlags) // Registra el receiver.
    }

    private fun connectToUsbDevice() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (availableDrivers.isEmpty()) {
            showToastOnce("❌ No hay dispositivos USB disponibles")
            return
        }

        // LISTA AMPLIADA de dispositivos compatibles incluyendo ESP32
        val targetDriver = availableDrivers.find { driver ->
            val device = driver.device
            val vendorId = device.vendorId
            val productId = device.productId

            // Lista ampliada de IDs conocidos
            val knownDevices = listOf(
                // Arduino tradicional
                Pair(0x1A86, 0x7523), // CH340 (Arduino clones)
                Pair(0x2341, 0x0043), // Arduino Uno
                Pair(0x2341, 0x0001), // Arduino Uno Rev3
                Pair(0x0403, 0x6001), // FTDI FT232

                // ESP32 específicos
                Pair(0x10C4, 0xEA60), // CP2102 (común en ESP32)
                Pair(0x1A86, 0x7523), // CH340 (ESP32 clones)
                Pair(0x0403, 0x6001), // FTDI (ESP32 DevKit)
                Pair(0x239A, 0x8038), // ESP32-S2
                Pair(0x303A, 0x1001), // ESP32-S3
                Pair(0x303A, 0x0002), // ESP32-C3

                // Chips seriales genéricos que usa ESP32
                Pair(0x10C4, 0xEA70), // CP2105 (dual port)
                Pair(0x067B, 0x2303), // PL2303
            )

            knownDevices.any { (vid, pid) ->
                vendorId == vid && productId == pid
            }
        } ?: availableDrivers[0] // Si no encuentra uno conocido, usa el primero

        val usbConnection = usbManager.openDevice(targetDriver.device)

        if (usbConnection == null) {
            pendingDriver = targetDriver
            val permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) },
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )

            showToastOnce("🔐 Solicitando permisos USB...")
            usbManager.requestPermission(targetDriver.device, permissionIntent)
            return
        }

        // DETECTAR si es ESP32 basado en los IDs
        val isESP32 = isESP32Device(targetDriver.device.vendorId, targetDriver.device.productId)
        if (isESP32) {
            showToastOnce("🚀 Conectando a ESP32...")
        } else {
            showToastOnce("🚀 Conectando a Arduino...")
        }

        initializeConnection(targetDriver, usbConnection, isESP32)
    }

    private fun isESP32Device(vendorId: Int, productId: Int): Boolean {
        val esp32Ids = listOf(
            Pair(0x10C4, 0xEA60), // CP2102
            Pair(0x239A, 0x8038), // ESP32-S2
            Pair(0x303A, 0x1001), // ESP32-S3
            Pair(0x303A, 0x0002), // ESP32-C3
            Pair(0x10C4, 0xEA70), // CP2105
        )

        return esp32Ids.any { (vid, pid) -> vendorId == vid && productId == pid }
    }

    // Intenta reconectar al dispositivo USB.
    private fun reconnectDevice() {
        if (!isReconnecting.compareAndSet(false, true)) return

        isConnected.set(false)

        usbExecutor.execute {
            try {
                // Cerrar conexiones existentes de forma más segura
                port?.let { currentPort ->
                    try {
                        if (currentPort.isOpen) {
                            currentPort.close()
                        }
                    } catch (e: Exception) {
                        // Ignorar errores al cerrar
                    }
                }

                connection?.let { currentConnection ->
                    try {
                        currentConnection.close()
                    } catch (e: Exception) {
                        // Ignorar errores al cerrar
                    }
                }

                port = null
                connection = null

                // Delay más largo para reconexión
                Thread.sleep(USB_RECONNECT_DELAY)

                runOnUiThread {
                    isReconnecting.set(false)
                    showToastOnce("🔄 Reintentando conexión USB...")
                    connectToUsbDevice()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    isReconnecting.set(false)
                    showToastOnce("Error en reconexión: ${e.message}")
                }
            }
        }
    }

    private fun verifyConnectionHealth(): Boolean {
        val currentPort = port
        val currentConnection = connection

        return when {
            currentPort == null || currentConnection == null -> false
            !currentPort.isOpen -> false
            !isConnected.get() -> false
            else -> {
                // Test rápido de escritura (opcional)
                try {
                    // Solo verificar el estado, no enviar datos
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    // Inicializa la conexión USB serial con el driver y la conexión dados.
    private fun initializeConnection(driver: UsbSerialDriver, usbConnection: UsbDeviceConnection, isESP32: Boolean = false) {
        usbExecutor.execute {
            try {
                // Cerrar conexiones previas
                port?.let { currentPort ->
                    if (currentPort.isOpen) {
                        try {
                            currentPort.close()
                            Thread.sleep(if (isESP32) 500 else 200)
                        } catch (e: Exception) {
                            // Ignorar errores al cerrar
                        }
                    }
                }
                connection?.close()

                port = driver.ports[0]
                connection = usbConnection

                val currentPort = port ?: throw IOException("No se pudo obtener el puerto del driver")

                // Abrir puerto
                currentPort.open(usbConnection)

                if (!currentPort.isOpen) {
                    throw IOException("El puerto no se abrió correctamente")
                }

                // CONFIGURACIÓN ESPECÍFICA PARA ESP32
                if (isESP32) {
                    initializeESP32(currentPort)
                } else {
                    initializeArduino(currentPort)
                }

                // Inicializar procesador de datos entrantes
                startIncomingDataProcessor()

                isConnected.set(true)
                lastSentCommand.set("")
                currentDetection.set("")

                runOnUiThread {
                    val deviceType = if (isESP32) "ESP32" else "Arduino"
                    showToastOnce("⚡ $deviceType conectado - 115200 bps")
                    updateStatusFast("✅ $deviceType conectado y verificado")
                }

            } catch (e: IOException) {
                isConnected.set(false)
                port = null
                connection?.close()
                connection = null

                runOnUiThread {
                    showToastOnce("Error conexión: ${e.message}")
                    updateStatusFast("❌ Error: ${e.message}")
                }
            } catch (e: Exception) {
                isConnected.set(false)
                port = null
                connection?.close()
                connection = null

                runOnUiThread {
                    showToastOnce("Error inesperado: ${e.message}")
                }
            }
        }
    }

    private fun initializeESP32(port: UsbSerialPort) {
        // Configuración específica para ESP32
        port.setParameters(
            115200,
            8,
            UsbSerialPort.STOPBITS_1,
            UsbSerialPort.PARITY_NONE
        )

        // ESP32 necesita un reset específico
        try {
            // Secuencia de reset para ESP32
            port.setDTR(false)
            port.setRTS(true)
            Thread.sleep(100)
            port.setRTS(false)
            Thread.sleep(50)
            port.setDTR(true)
            Thread.sleep(ESP32_DTR_RTS_DELAY)

            // Limpiar buffers después del reset
            Thread.sleep(ESP32_RESET_DELAY)

        } catch (e: Exception) {
            // Continuar si el control de flujo falla
        }

        // Limpiar buffers múltiples veces para ESP32
        repeat(5) {
            try {
                port.purgeHwBuffers(true, true)
                Thread.sleep(100)
            } catch (e: Exception) {
                // Ignorar si no es compatible
            }
        }

        // ESP32 necesita más tiempo para estar listo
        Thread.sleep(ESP32_SETUP_DELAY)
    }

    private fun initializeArduino(port: UsbSerialPort) {
        // Configuración para Arduino tradicional
        port.setParameters(
            115200,
            8,
            UsbSerialPort.STOPBITS_1,
            UsbSerialPort.PARITY_NONE
        )

        // Delay crítico más largo
        Thread.sleep(USB_SETUP_DELAY)

        // Configurar control de flujo
        try {
            port.setDTR(false)
            Thread.sleep(100)
            port.setRTS(false)
            Thread.sleep(100)
            port.setDTR(true)
            Thread.sleep(100)
            port.setRTS(true)
            Thread.sleep(100)
        } catch (e: Exception) {
            // No crítico si falla
        }

        // Limpiar buffers
        repeat(5) {
            try {
                port.purgeHwBuffers(true, true)
                Thread.sleep(50)
            } catch (e: Exception) {
                // Ignorar si no es compatible
            }
        }

        // Esperar que Arduino complete su reset/inicialización
        Thread.sleep(1000)
    }

    private fun startIncomingDataProcessor() {
        incomingDataProcessor?.shutdownNow()

        incomingDataProcessor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "IncomingDataProcessor").apply {
                priority = Thread.NORM_PRIORITY // Prioridad normal, no máxima
            }
        }

        incomingDataProcessor?.execute {
            val buffer = ByteArray(INCOMING_DATA_BUFFER_SIZE)

            while (isConnected.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val currentPort = port
                    if (currentPort == null || !currentPort.isOpen) {
                        Thread.sleep(100)
                        continue
                    }

                    // Leer datos con timeout más largo
                    val bytesRead = currentPort.read(buffer, USB_READ_TIMEOUT)

                    if (bytesRead > 0) {
                        val receivedData = String(buffer, 0, bytesRead)
                        processIncomingData(receivedData)
                        lastIncomingDataTime = System.currentTimeMillis()
                    } else {
                        // Sleep más largo cuando no hay datos
                        Thread.sleep(10)
                    }

                } catch (e: IOException) {
                    if (isConnected.get()) {
                        // Error de lectura - no reconectar inmediatamente
                        Thread.sleep(100)
                    }
                } catch (e: Exception) {
                    if (isConnected.get()) {
                        Thread.sleep(50)
                    }
                }
            }
        }
    }

    private fun processIncomingData(data: String) {
        try {
            // Agregar datos al buffer
            synchronized(incomingDataBuffer) {
                incomingDataBuffer.append(data)

                // Procesar líneas completas
                val content = incomingDataBuffer.toString()
                val lines = content.split('\n')

                // Mantener la última línea parcial
                incomingDataBuffer.clear()
                if (!content.endsWith('\n') && lines.isNotEmpty()) {
                    incomingDataBuffer.append(lines.last())
                }

                // Procesar líneas completas
                val completedLines = if (content.endsWith('\n')) lines else lines.dropLast(1)

                for (line in completedLines) {
                    val cleanLine = line.trim()
                    if (cleanLine.isNotEmpty()) {
                        // NUEVO: Guardar último mensaje con timestamp
                        lastReceivedMessage = cleanLine
                        lastMessageTime = System.currentTimeMillis()

                        // Mostrar inmediatamente en UI
                        runOnUiThread {
                            updateStatusWithArduinoMessage(cleanLine)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignorar errores de procesamiento
        }
    }

    private fun updateStatusWithArduinoMessage(message: String) {
        // Mostrar el mensaje de Arduino en el status
        val currentStatus = tvStatus.text.toString()

        // Si el status es muy largo, limpiarlo
        if (currentStatus.length > 500) {
            tvStatus.text = "Arduino: $message"
        } else {
            // Si ya hay un mensaje de Arduino, reemplazarlo
            val statusLines = currentStatus.split('\n').toMutableList()
            val arduinoLineIndex = statusLines.indexOfFirst { it.startsWith("Arduino:") }

            if (arduinoLineIndex >= 0) {
                statusLines[arduinoLineIndex] = "Arduino: $message"
            } else {
                statusLines.add("Arduino: $message")
            }

            tvStatus.text = statusLines.joinToString("\n")
        }
    }


    // Inicia la cámara y configura el ImageAnalysis para el procesamiento de frames.
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(windowManager.defaultDisplay.rotation)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetResolution(TARGET_RESOLUTION)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ColorAnalyzer { result, bitmap, dualDetection ->
                        // MARCAR que la detección está activa
                        isDetectionActive.set(true)

                        // Procesar solo el objeto primario para comandos (mantiene lógica existente)
                        val primaryObject = dualDetection.primaryObject
                        if (primaryObject != null && primaryObject.area > MIN_AREA_THRESHOLD) {
                            processDetectionImmediate(
                                result,
                                primaryObject.area,
                                primaryObject.centerX,
                                primaryObject.centerY,
                                primaryObject.imageWidth,
                                primaryObject.imageHeight
                            )
                        }

                        runOnUiThread {
                            updateUIOptimizedDual(result, bitmap, dualDetection)
                        }
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                val camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalyzer
                )

                if (camera.cameraInfo.hasFlashUnit()) {
                    camera.cameraControl.enableTorch(true)
                }

            } catch (exc: Exception) {
                showToastOnce("Error cámara: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startDetectionHealthMonitor() {
        scope.launch {
            while (isActive) {
                delay(5000) // Verificar cada 5 segundos

                if (isConnected.get() && !isDetectionActive.get()) {
                    // La detección se detuvo, intentar reactivar
                    withContext(Dispatchers.Main) {
                        if (imageAnalyzer == null) {
                            startCamera()
                            showToastOnce("🔄 Reactivando detección automáticamente")
                        }
                    }
                }

                // Reset flag
                isDetectionActive.set(false)
            }
        }
    }

    // Procesa la detección de inmediato, decidiendo qué comando de pilar enviar.

    private fun processDetectionImmediate(result: String, area: Double, centerX: Float, centerY: Float, imageWidth: Int, imageHeight: Int) {
        if (!isConnected.get() || area < MIN_AREA_THRESHOLD) {
            return
        }

        val dualDetection = ColorAnalyzer.lastDualDetection

        // PRIORIDAD ABSOLUTA: Si hay dos objetos, SOLO usar lógica dual
        if (dualDetection.primaryObject != null && dualDetection.secondaryObject != null) {
            Log.d("CommandSystem", "=== MODO DUAL ACTIVADO ===")
            Log.d("CommandSystem", "Detectados 2 objetos - Usando solo lógica dual")
            processDualObjectDetection(dualDetection)
            return // CRÍTICO: Salir aquí para evitar procesamiento individual
        }

        // Solo procesar objeto único si NO hay detección dual
        if (dualDetection.primaryObject != null) {
            Log.d("CommandSystem", "=== MODO SIMPLE ACTIVADO ===")
            Log.d("CommandSystem", "Detectado 1 objeto - Usando lógica simple")
            processSingleObjectDetection(dualDetection.primaryObject!!)
        }
    }


    private fun processSingleObjectDetection(objectPos: ObjectPosition) {
        try {
            val distance = CommandManager.calculateDistance(objectPos.area)
            val orientation = CommandManager.calculateOrientation(
                objectPos.centerX,
                objectPos.centerY,
                objectPos.imageWidth,
                objectPos.imageHeight
            )

            val (command, commandInfo) = CommandManager.determineCommandSafe(
                objectPos.color,
                orientation,
                distance
            )

            Log.d("SingleObject", "Objeto único: ${objectPos.color} | $orientation | ${distance}cm -> $command")

            // APLICAR FILTRO TEMPORAL
            processCommandWithTemporalFilter(command, "SIMPLE")

        } catch (e: Exception) {
            Log.e("SingleObject", "Error procesando objeto único: ${e.message}")
        }
    }

    private fun processDualObjectDetection(dualDetection: DualObjectDetection) {
        try {
            val primary = dualDetection.primaryObject!!
            val secondary = dualDetection.secondaryObject!!

            val primaryDistance = CommandManager.calculateDistance(primary.area)
            val primaryOrientation = CommandManager.calculateOrientation(
                primary.centerX, primary.centerY, primary.imageWidth, primary.imageHeight
            )

            val secondaryDistance = CommandManager.calculateDistance(secondary.area)
            val secondaryOrientation = CommandManager.calculateOrientation(
                secondary.centerX, secondary.centerY, secondary.imageWidth, secondary.imageHeight
            )

            val (command, commandInfo) = CommandManager.determineDualObjectCommand(
                primary.color, primaryOrientation, primaryDistance,
                secondary.color, secondaryOrientation, secondaryDistance
            )

            Log.d("DualObject", "=== COMANDO DUAL DETECTADO ===")
            Log.d("DualObject", "Primario: ${primary.color}($primaryOrientation,${primaryDistance}cm)")
            Log.d("DualObject", "Secundario: ${secondary.color}($secondaryOrientation,${secondaryDistance}cm)")
            Log.d("DualObject", "COMANDO FINAL: $command")
            Log.d("DualObject", "INFO: $commandInfo")

            // APLICAR FILTRO TEMPORAL
            processCommandWithTemporalFilter(command, "DUAL")

        } catch (e: Exception) {
            Log.e("DualObject", "Error procesando objetos duales: ${e.message}")
        }
    }



    /**
     * FILTRO TEMPORAL PARA ESTABILIZAR DETECCIONES
     * Solo envía comandos después de que se mantengan estables por DETECTION_STABILITY_TIME
     */
    private fun processCommandWithTemporalFilter(command: String, detectionType: String) {
        temporalFilterExecutor.execute {
            val currentTime = System.currentTimeMillis()
            val currentStableCmd = currentStableDetection.get()

            Log.d("TemporalFilter", "=== FILTRO TEMPORAL ===")
            Log.d("TemporalFilter", "Comando detectado: $command ($detectionType)")
            Log.d("TemporalFilter", "Comando estable actual: $currentStableCmd")

            // CASO 1: Es el mismo comando que ya se está estabilizando
            if (command == currentStableCmd) {
                val elapsedTime = currentTime - detectionStartTime.get()
                Log.d("TemporalFilter", "Comando consistente - Tiempo transcurrido: ${elapsedTime}ms")

                // Si ya pasó el tiempo de estabilidad y no se ha enviado aún
                if (elapsedTime >= DETECTION_STABILITY_TIME && !isDetectionStable.get()) {
                    isDetectionStable.set(true)

                    Log.d("TemporalFilter", "✅ COMANDO ESTABLE ALCANZADO: $command")

                    // Solo enviar si es diferente al último comando estable enviado
                    if (command != lastStableCommand.get()) {
                        lastStableCommand.set(command)
                        currentDetection.set(command)
                        lastDetectionTime = currentTime

                        Log.d("TemporalFilter", "🚀 ENVIANDO COMANDO ESTABLE: $command")
                        queueCommand(command)

                        runOnUiThread {
                            showToastOnce("✅ Comando estable: $command")
                        }
                    } else {
                        Log.d("TemporalFilter", "⚠️ Comando ya enviado previamente: $command")
                    }
                } else if (!isDetectionStable.get()) {
                    Log.d("TemporalFilter", "⏳ Esperando estabilidad... ${elapsedTime}ms / ${DETECTION_STABILITY_TIME}ms")
                }
            }
            // CASO 2: Es un comando diferente al que se está estabilizando
            else {
                Log.d("TemporalFilter", "🔄 NUEVO COMANDO DETECTADO: $command (anterior: $currentStableCmd)")

                // Reiniciar el proceso de estabilización
                currentStableDetection.set(command)
                detectionStartTime.set(currentTime)
                isDetectionStable.set(false)

                Log.d("TemporalFilter", "⏱️ Iniciando periodo de estabilización para: $command")

                runOnUiThread {
                    showToastOnce("⏳ Estabilizando: $command")
                }
            }
        }
    }

    // Muestra información de calibración (área y distancia calculada) en un Toast.
    private fun showCalibrationInfo(area: Double) {
        val distance = calculateDistance(area) // Calcula la distancia para la calibración.
        val debugText = "🔧 CALIBRACIÓN:\nÁrea actual: ${area.toInt()}\nDistancia calculada: ${distance}cm"

        runOnUiThread {
            showToastOnce(debugText) // Muestra el mensaje de calibración.
        }
    }

    // Actualiza la interfaz de usuario de forma optimizada.
    private fun updateUIOptimized(result: String, bitmap: Bitmap?, area: Double, distance: Int, orientation: String) {
        // Actualizar cada pocos frames para no sobrecargar la UI y mantener la fluidez.
        if (System.currentTimeMillis() % 3 == 0L) {
            val lastCmd = lastSentCommand.get()
            val currentTime = System.currentTimeMillis()

            // Construir status principal
            val mainStatusText = buildString {
                append("$result")
                if (area > MIN_AREA_THRESHOLD) {
                    append(" | Área: ${area.toInt()}")
                    append(" | Dist: ${distance}cm")
                    append(" | Pos: $orientation")
                }
                if (lastCmd.isNotEmpty()) {
                    append(" | Cmd: $lastCmd")
                }
            }

            //Preservar mensaje de Arduino si es reciente
            val statusText = if (currentTime - lastMessageTime < MESSAGE_DISPLAY_DURATION && lastReceivedMessage.isNotEmpty()) {
                "$mainStatusText\nArduino: $lastReceivedMessage"
            } else {
                mainStatusText
            }

            tvStatus.text = statusText
        }

        bitmap?.let {
            val bitmapWithLines = drawDivisionLines(it)
            imageView.setImageBitmap(bitmapWithLines)
        }
    }

    private fun startMessageCleanupTimer() {
        scope.launch {
            while (isActive) {
                delay(1000) // Verificar cada segundo

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastMessageTime > MESSAGE_DISPLAY_DURATION && lastReceivedMessage.isNotEmpty()) {
                    lastReceivedMessage = ""
                    // La UI se actualizará automáticamente en el próximo frame
                }
            }
        }
    }


    private fun drawDivisionLines(originalBitmap: Bitmap): Bitmap {
        val mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        // Configurar el pincel para la línea de división
        val paint = Paint().apply {
            color = Color.RED
            strokeWidth = 4f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val width = mutableBitmap.width.toFloat()
        val height = mutableBitmap.height.toFloat()

        // Dibuja una línea central HORIZONTAL para la nueva división
        val centerLineY = height * 0.60f
        canvas.drawLine(0f, centerLineY, width, centerLineY, paint)

        return mutableBitmap
    }

    // Actualiza rápidamente solo el texto de estado para los comandos enviados.
    private fun updateStatusFast(message: String) {
        tvStatus.append(" $message") // Añade el mensaje al texto existente.
    }

    // Muestra un Toast, cancelando el anterior si existía para evitar acumulación.
    private fun showToastOnce(message: String) {
        toast?.cancel() // Cancela cualquier Toast previo.
        toast = Toast.makeText(this, message, Toast.LENGTH_SHORT) // Crea un nuevo Toast.
        toast?.show() // Muestra el Toast.
    }

    // Verifica si todos los permisos requeridos han sido concedidos.
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    // Callback llamado cuando se concede el permiso USB.
    override fun onPermissionGranted() {
        pendingDriver?.let { driver ->
            usbManager.openDevice(driver.device)?.let { usbConnection ->
                initializeConnection(driver, usbConnection) // Inicializa la conexión con el driver y conexión.
            }
        }
        pendingDriver = null // Limpia el driver pendiente.
    }

    // Callback llamado cuando se deniega el permiso USB.
    override fun onPermissionDenied() {
        showToastOnce("Permiso USB denegado")
        pendingDriver = null // Limpia el driver pendiente.
    }

    // Callback para el resultado de la solicitud de permisos.
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                startCamera() // Si los permisos de cámara se conceden, inicia la cámara.
            } else {
                showToastOnce("Se requieren permisos de cámara.")
                finish() // Cierra la actividad si los permisos no son concedidos.
            }
        }
    }

    // Metodo onDestroy se llama cuando la actividad está a punto de ser destruida.
    override fun onDestroy() {
        super.onDestroy()

        // Limpiar instancia estática
        instance = null

        try {
            wakeLock?.release()
        } catch (e: Exception) {
            // Ignorar errores al liberar WakeLock
        }

        incomingDataProcessor?.shutdownNow()

        // Apagado ordenado de todos los Executors y scopes para liberar recursos.
        commandProcessor.shutdownNow() // Interrumpe y apaga el procesador de comandos.
        scope.cancel() // Cancela todas las corrutinas en el scope.
        cameraExecutor.shutdown() // Apaga el executor de la cámara.
        usbExecutor.shutdown() // Apaga el executor USB.
        temporalFilterExecutor.shutdownNow()

        isConnected.set(false) // Marca la conexión como inactiva.

        // Cierra el puerto y la conexión USB en un nuevo hilo para evitar bloqueos en el hilo principal.
        Thread {
            try {
                Thread.sleep(200) // Esperar antes de cerrar
                port?.takeIf { it.isOpen }?.close()
                Thread.sleep(100)
                connection?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()

        try {
            unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            // Ignora la excepción si el receiver ya había sido desregistrado
        }
    }
    override fun onResume() {
        super.onResume()

        // Verificar conexión si volvemos de otra actividad
        if (isConnected.get()) {
            // Asegurar que la cámara esté funcionando
            if (imageAnalyzer == null) {
                startCamera()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SERIAL_MONITOR_REQUEST_CODE) {
            // El monitor serial se cerró, marcar como cerrado
            isSerialMonitorOpen.set(false)

            // Verificar que la conexión siga activa
            if (isConnected.get()) {
                showToastOnce("🔄 Volviendo a detección principal")

                // Reactivar la cámara si es necesario
                if (imageAnalyzer == null) {
                    startCamera()
                }
            }
        }
    }

    private fun updateUIOptimizedDual(result: String, bitmap: Bitmap?, dualDetection: DualObjectDetection) {
        if (System.currentTimeMillis() % 3 == 0L) {
            val lastCmd = lastSentCommand.get()
            val currentTime = System.currentTimeMillis()

            val statusText = buildString {
                // VERIFICAR SI HAY DETECCIÓN DUAL PRIMERO
                if (dualDetection.primaryObject != null && dualDetection.secondaryObject != null) {
                    // MODO DUAL - Mostrar información del comando combinado
                    append("🔥 DETECCIÓN DUAL ACTIVA 🔥\n")

                    val primary = dualDetection.primaryObject!!
                    val secondary = dualDetection.secondaryObject!!

                    val primaryDistance = calculateDistance(primary.area)
                    val primaryOrientation = CommandManager.calculateOrientation(
                        primary.centerX, primary.centerY, primary.imageWidth, primary.imageHeight
                    )

                    val secondaryDistance = calculateDistance(secondary.area)
                    val secondaryOrientation = CommandManager.calculateOrientation(
                        secondary.centerX, secondary.centerY, secondary.imageWidth, secondary.imageHeight
                    )

                    // Obtener comando dual
                    val (dualCommand, dualCommandInfo) = CommandManager.determineDualObjectCommand(
                        primary.color, primaryOrientation, primaryDistance,
                        secondary.color, secondaryOrientation, secondaryDistance
                    )

                    append("COMANDO DUAL: $dualCommand\n")
                    append("INFO: $dualCommandInfo\n")
                    append("═══════════════════════════\n")
                    append("🎯 P: ${primary.color} | ${primaryDistance}cm | ${if(primaryOrientation == "L") "Izq" else "Der"}\n")
                    append("🔸 S: ${secondary.color} | ${secondaryDistance}cm | ${if(secondaryOrientation == "L") "Izq" else "Der"}")

                } else {
                    // MODO SIMPLE - Mostrar información individual
                    append(result)

                    dualDetection.primaryObject?.let { primary ->
                        val distance = calculateDistance(primary.area)
                        val orientation = CommandManager.calculateOrientation(
                            primary.centerX, primary.centerY, primary.imageWidth, primary.imageHeight
                        )
                        val (command, _) = CommandManager.determineCommandSafe(primary.color, orientation, distance)
                        val positionText = if(orientation == "L") "Izq" else if(orientation == "R") "Der" else "Centro"

                        append("\n🎯 ${primary.color} | ${distance}cm | $positionText")
                        if (command != "N") {
                            append(" | CMD: $command")
                        }
                    }
                }

                // Mostrar último comando enviado
                if (lastCmd.isNotEmpty()) {
                    append("\n🚀 ENVIADO: $lastCmd")
                }

                // Mostrar mensaje de Arduino si es reciente
                if (currentTime - lastMessageTime < MESSAGE_DISPLAY_DURATION && lastReceivedMessage.isNotEmpty()) {
                    append("\nArduino: $lastReceivedMessage")
                }

                // INFORMACIÓN DEL FILTRO TEMPORAL
                val currentStableCmd = currentStableDetection.get()
                if (currentStableCmd.isNotEmpty()) {
                    val elapsedTime = currentTime - detectionStartTime.get()
                    val progress = (elapsedTime * 100 / DETECTION_STABILITY_TIME).coerceAtMost(100)
                    append("\n⏳ Estabilizando: $currentStableCmd (${progress}%)")

                    if (isDetectionStable.get()) {
                        append(" ✅ ESTABLE")
                    } else {
                        append(" ⏱️ ${DETECTION_STABILITY_TIME - elapsedTime}ms restantes")
                    }
                }

            }

            tvStatus.text = statusText
        }

        bitmap?.let {
            val bitmapWithLines = drawDivisionLines(it)
            imageView.setImageBitmap(bitmapWithLines)
        }
    }

    /**
     * Obtiene información detallada del sistema de comandos para debugging
     */
    fun getCommandSystemInfo(): String {
        return buildString {
            append("=== SISTEMA DE COMANDOS ACTIVO ===\n")
            append("Último comando: ${lastSentCommand.get()}\n")
            append("Detección actual: ${currentDetection.get()}\n")
            append("Tiempo última detección: ${System.currentTimeMillis() - lastDetectionTime}ms ago\n")
            append("\n=== CASOS CONFIGURADOS ===\n")
            append(CommandManager.getAllCasesInfo())
        }
    }

    /**
     * Función mejorada para obtener estadísticas de detección
     */
    fun getEnhancedDetectionStats(): String {
        val detection = ColorAnalyzer.lastDualDetection
        return buildString {
            detection.primaryObject?.let { obj ->
                // CORRECCIÓN: Llamar a CommandManager directamente
                val distance = CommandManager.calculateDistance(obj.area)
                val orientation = CommandManager.calculateOrientation(obj.centerX, obj.centerY, obj.imageWidth, obj.imageHeight)
                val (command, commandInfo) = CommandManager.determineCommandSafe(obj.color, orientation, distance)

                append("🎯 OBJETO PRIMARIO:\n")
                append("Color: ${obj.color}\n")
                append("Posición: ${if(orientation == "L") "Izquierda" else "Derecha"}\n")
                append("Distancia: ${distance}cm\n")
                append("Comando: $command\n")
                append("Info: $commandInfo\n")
                append("Área: ${obj.area.toInt()}px\n\n")
            }

            detection.secondaryObject?.let { obj ->
                // CORRECCIÓN: Llamar a CommandManager directamente
                val distance = CommandManager.calculateDistance(obj.area)
                val orientation = CommandManager.calculateOrientation(obj.centerX, obj.centerY, obj.imageWidth, obj.imageHeight)
                val (command, commandInfo) = CommandManager.determineCommandSafe(obj.color, orientation, distance)

                append("🔸 OBJETO SECUNDARIO:\n")
                append("Color: ${obj.color}\n")
                append("Posición: ${if(orientation == "L") "Izquierda" else "Derecha"}\n")
                append("Distancia: ${distance}cm\n")
                append("Comando: $command\n")
                append("Info: $commandInfo\n")
                append("Área: ${obj.area.toInt()}px")
            }

            if (detection.primaryObject == null && detection.secondaryObject == null) {
                append("❌ No hay objetos detectados")
            }
        }
    }

    /**
     * Obtiene información detallada del estado del filtro temporal
     */
    fun getTemporalFilterInfo(): String {
        val currentTime = System.currentTimeMillis()
        val elapsedTime = currentTime - detectionStartTime.get()

        return buildString {
            append("=== FILTRO TEMPORAL DE DETECCIÓN ===\n")
            append("Tiempo de estabilidad requerido: ${DETECTION_STABILITY_TIME}ms\n")
            append("Comando estabilizándose: ${currentStableDetection.get().ifEmpty { "Ninguno" }}\n")
            append("Tiempo transcurrido: ${elapsedTime}ms\n")
            append("Es estable: ${isDetectionStable.get()}\n")
            append("Último comando estable: ${lastStableCommand.get().ifEmpty { "Ninguno" }}\n")
            append("Progreso: ${if (currentStableDetection.get().isNotEmpty())
                "${(elapsedTime * 100 / DETECTION_STABILITY_TIME).coerceAtMost(100)}%" else "0%"}")
        }
    }


}


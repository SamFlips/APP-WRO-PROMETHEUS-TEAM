// ===================================================================================
// Proyecto: WRO Prometheus App
// Descripción: Aplicación de Android para la detección y reconocimiento de colores de los obstaculos y el estacionamiento de la competencia World Robot Olympiad (WRO) 2025.
// Categoria Futuros Ingenieros. Usando OpenCV y comunicacion serial USB con el arduino
//
// Desarrollado por:
// Equipo: [PROMETHEUS TEAM]
// Integrantes: [Samuel Pérez], [Trino Carrisales], [Leandro Padrón]
//
// Fecha de Creación: 20/4/2025
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
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader // Importa la librería OpenCV para procesamiento de imagen
import com.hoho.android.usbserial.driver.UsbSerialDriver // Driver para comunicación serial USB
import com.hoho.android.usbserial.driver.UsbSerialPort // Puerto serial USB
import com.hoho.android.usbserial.driver.UsbSerialProber // Herramienta para buscar drivers USB
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.* // Para el manejo de corrutinas (programación asíncrona)
import java.util.concurrent.LinkedBlockingQueue // Cola de comandos para procesamiento
import java.util.concurrent.atomic.AtomicBoolean // Tipo atómico para booleanos (seguro para hilos)
import java.util.concurrent.atomic.AtomicReference // Tipo atómico para referencias de objetos (seguro para hilos)

// MainActivity es la actividad principal de la aplicación.
// Implementa UsbPermissionReceiver.UsbPermissionListener para manejar eventos de permiso USB.
class MainActivity : AppCompatActivity(), UsbPermissionReceiver.UsbPermissionListener {

    // Acción de intento para solicitar permisos USB.
    private val ACTION_USB_PERMISSION = "com.example.opencvserial.USB_PERMISSION"
    // Código de solicitud para permisos de cámara.
    private val CAMERA_PERMISSION_REQUEST_CODE = 100

    // Objeto anidado para definir comandos relacionados con Arduino.
    private object ArduinoCommands {
        // Comando para indicar que no hay detección de pilares.
        const val NO_DETECTION = "N"

        // Función para crear un comando completo para un pilar detectado.
        // Recibe color, distancia y orientación y devuelve una cadena formateada.
        fun createPilarCommand(color: String, distance: Int, orientation: String): String {
            // Asigna un código corto al color.
            val colorCode = when(color) {
                "Rojo" -> "R" // 'R' para Rojo (Red)
                "Verde" -> "G" // 'G' para Verde (Green)
                "Magenta" -> "E" // 'E' para Magenta ("E" de Estacionamiento)
                else -> "N" // 'N' si no se detecta un color conocido
            }
            // Formatea el comando como "COLOR_CODE,DISTANCIA,ORIENTACION".
            return "$colorCode,$distance,$orientation"
        }
    }

    // Objeto anidado que contiene constantes estáticas para toda la clase.
    private companion object {
        // Permisos de cámara requeridos por la aplicación.
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        // Umbral mínimo de área para considerar una detección de pilar válida.
        private const val MIN_AREA_THRESHOLD = 800.0
        // Intervalo mínimo en milisegundos entre el envío de comandos al Arduino para evitar sobrecarga.
        private const val MIN_COMMAND_INTERVAL = 10L
        // Tiempo de espera en milisegundos antes de enviar un comando de "no detección" si no hay actividad.
        private const val DETECTION_TIMEOUT = 200L
        // Tiempo de espera en milisegundos para operaciones de escritura USB.
        private const val USB_WRITE_TIMEOUT = 50
        // Tiempo de espera en milisegundos para operaciones de lectura USB.
        private const val USB_READ_TIMEOUT = 10

        // CONSTANTES PARA CÁLCULOS DE DISTANCIA
        // Área de referencia (en píxeles cuadrados) de un objeto a una distancia conocida.
        private const val REFERENCE_AREA = 11110.0  // Área de referencia a 30cm
        // Distancia de referencia en centímetros a la que se obtuvo REFERENCE_AREA.
        private const val REFERENCE_DISTANCE = 30.0 // Distancia de referencia en cm
        // Distancia mínima en centímetros que se puede calcular o enviar.
        private const val MIN_DISTANCE = 10        // Distancia mínima en cm
        // Distancia máxima en centímetros que se puede calcular o enviar.
        private const val MAX_DISTANCE = 100       // Distancia máxima en cm
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

    // NUEVAS VARIABLES PARA DESPERTAR PANTALLA
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

    // Metodo onCreate se llama cuando la actividad es creada.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Establece el diseño de la interfaz de usuario.

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
        findViewById<Button>(R.id.btnLedOn).setOnClickListener { queueCommand("1") } // Botón para encender LED (envía "1").
        findViewById<Button>(R.id.btnLedOff).setOnClickListener { queueCommand("0") } // Botón para apagar LED (envía "0").

        // Configura el receptor de permisos USB.
        setupUsbReceiver()
        // Inicia el procesador de comandos.
        startCommandProcessor()
        // Inicia el monitor de detección (para enviar "no detección" si no hay actividad).
        startDetectionMonitor()

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

    // NUEVO METODO PARA DESPERTAR LA PANTALLA
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
            while (!Thread.currentThread().isInterrupted) { // Bucle infinito hasta que el hilo sea interrumpido.
                try {
                    val command = commandQueue.take() // Bloquea hasta que haya un comando en la cola.
                    sendCommandImmediate(command) // Envía el comando al Arduino.
                } catch (e: InterruptedException) {
                    // Si el hilo es interrumpido, sale del bucle.
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    // Captura cualquier otra excepción y continúa procesando.
                    // Esto evita que un error en un comando detenga todo el procesador.
                }
            }
        }
    }

    // Monitorea la actividad de detección y envía un comando de "no detección" si hay un tiempo de espera.
    private fun startDetectionMonitor() {
        scope.launch { // Lanza una corrutina en el scope definido.
            while (isActive) { // Bucle infinito mientras la corrutina esté activa.
                delay(50) // Espera 50 milisegundos antes de la siguiente verificación.

                val currentTime = System.currentTimeMillis() // Tiempo actual.
                // Si el tiempo desde la última detección excede el timeout
                // Y el último comando enviado no es "no detección"
                // Y la conexión USB está activa
                if (currentTime - lastDetectionTime > DETECTION_TIMEOUT &&
                    lastSentCommand.get() != ArduinoCommands.NO_DETECTION &&
                    isConnected.get()) {

                    queueCommand(ArduinoCommands.NO_DETECTION) // Encola el comando de "no detección".
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
            // Cuando se conecta un dispositivo USB.
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                // DESPERTAR PANTALLA CUANDO SE CONECTA ARDUINO
                wakeUpScreen()

                showToastOnce("📱 Arduino conectado - Iniciando automáticamente...")

                // Pequeño delay para asegurar que el dispositivo esté listo.
                scope.launch { // Lanza una corrutina.
                    delay(1000) // Espera 1 segundo.

                    // Intentar conectar automáticamente en el hilo principal.
                    withContext(Dispatchers.Main) {
                        connectToUsbDevice()
                    }
                }
            }
            // Cuando se desconecta un dispositivo USB.
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                showToastOnce("📱 Arduino desconectado")
                isConnected.set(false) // Marca la conexión como inactiva.

                // Limpiar la conexión en un hilo de IO.
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            port?.takeIf { it.isOpen }?.close() // Cierra el puerto si está abierto.
                            connection?.close() // Cierra la conexión.
                        } catch (e: Exception) {
                            // Ignorar errores al desconectar, ya que el dispositivo ya no está.
                        } finally {
                            port = null // Limpia la referencia al puerto.
                            connection = null // Limpia la referencia a la conexión.
                        }
                    }

                    // Actualiza el estado en la UI en el hilo principal.
                    withContext(Dispatchers.Main) {
                        updateStatusFast("❌ Desconectado")
                    }
                }
            }
        }
    }

    // Procesa la detección de un pilar con su color, área, centro y dimensiones de la imagen.
    private fun processPilarDetection(color: String, area: Double, centerX: Float, centerY: Float, imageWidth: Int, imageHeight: Int) {
        if (area < MIN_AREA_THRESHOLD) return // Ignora detecciones con área muy pequeña.

        // Calcular distancia basada en el área.
        val distance = calculateDistance(area)

        // Calcular orientación basada en la posición del centro.
        val orientation = calculateOrientation(centerX, centerY, imageWidth, imageHeight)

        // Crear comando completo.
        val command = ArduinoCommands.createPilarCommand(color, distance, orientation)

        currentDetection.set(command) // Actualiza la detección actual.
        lastDetectionTime = System.currentTimeMillis() // Actualiza el tiempo de la última detección.

        // Solo encola el comando si es diferente al último comando enviado.
        if (command != lastSentCommand.get()) {
            queueCommand(command)
        }
    }

    // Calcula la distancia de un objeto basándose en su área percibida.
    private fun calculateDistance(area: Double): Int {
        // Fórmula: distancia = sqrt(REFERENCE_AREA / area) * REFERENCE_DISTANCE
        // Esta es una fórmula empírica comúnmente usada para estimar distancia basada en el tamaño de un objeto en la imagen.
        val calculatedDistance = kotlin.math.sqrt(REFERENCE_AREA / area) * REFERENCE_DISTANCE

        // Limita la distancia calculada entre MIN_DISTANCE y MAX_DISTANCE.
        return calculatedDistance.toInt().coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    // Calcula la orientación del pilar (Izquierda, Centro, Derecha) basándose en su posición X.
    private fun calculateOrientation(centerX: Float, centerY: Float, imageWidth: Int, imageHeight: Int): String {
        // Como el teléfono está horizontal hacia la izquierda, ajustamos la lógica
        // centerX representa la posición horizontal en la imagen rotada.

        // Normaliza la posición X del centro (de 0 a 1).
        val normalizedX = centerX / imageWidth.toFloat()

        // Determina la orientación basándose en tercios de la imagen.
        return when {
            normalizedX < 0.33f -> "I"   // Tercio izquierdo
            normalizedX > 0.80f -> "D"  // Tercio derecho
            else -> "C"                // Tercio central
        }
    }

    // Encola un comando para ser enviado al Arduino, respetando un intervalo mínimo.
    private fun queueCommand(command: String) {
        val currentTime = System.currentTimeMillis()

        // Verificar intervalo mínimo antes de encolar.
        if (currentTime - lastCommandTime < MIN_COMMAND_INTERVAL) {
            return
        }

        // Limpiar cola si hay comandos pendientes del mismo tipo para evitar redundancia.
        val iterator = commandQueue.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() == command) {
                iterator.remove() // Elimina comandos duplicados.
            }
        }

        // Encolar el nuevo comando.
        commandQueue.offer(command)
    }

    // Envía un comando inmediatamente al puerto USB, manejando errores de conexión.
    private fun sendCommandImmediate(command: String) {
        // No envía si no está conectado o el puerto es nulo.
        if (!isConnected.get() || port == null) return

        try {
            val currentPort = port
            // Si el puerto no existe o no está abierto, intenta reconectar.
            if (currentPort == null || !currentPort.isOpen) {
                reconnectDevice()
                return
            }

            // Intentar limpiar el buffer de entrada del puerto (si es compatible).
            try {
                currentPort.purgeHwBuffers(true, true)
            } catch (e: Exception) {
                // Ignorar si el driver no soporta esta función.
            }

            // Enviar el comando completo más un terminador de línea.
            val commandBytes = "${command}\n".toByteArray()

            try {
                currentPort.write(commandBytes, USB_WRITE_TIMEOUT) // Escribe los bytes al puerto.
            } catch (e: IOException) {
                throw IOException("Error en escritura USB: ${e.message}") // Propaga el error de escritura.
            }

            // Forzar transmisión inmediata (si el driver lo soporta).
            try {
                // Usa reflexión para llamar al metodo "flush" si existe.
                if (currentPort.javaClass.methods.any { it.name == "flush" }) {
                    currentPort.javaClass.getMethod("flush").invoke(currentPort)
                }
            } catch (e: Exception) {
                // Ignorar si no es compatible o no se puede invocar.
            }

            // Actualizar estado de los comandos enviados y el tiempo.
            lastSentCommand.set(command)
            lastCommandTime = System.currentTimeMillis()

            // Actualización de la UI optimizada: solo si el comando no es "NO_DETECTION".
            if (command != ArduinoCommands.NO_DETECTION) {
                runOnUiThread {
                    updateStatusFast("→$command") // Muestra el comando enviado.
                }
            }

        } catch (e: IOException) {
            // Manejo de errores de IO: marca como desconectado y muestra un Toast.
            isConnected.set(false)
            runOnUiThread {
                showToastOnce("USB Error: ${e.message}")
            }
            reconnectDevice() // Intenta reconectar.
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

    // Intenta conectar a un dispositivo USB (Arduino).
    private fun connectToUsbDevice() {
        // Busca todos los drivers USB serial disponibles.
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (availableDrivers.isEmpty()) {
            showToastOnce("❌ No hay dispositivos USB disponibles")
            return
        }

        // Buscar específicamente Arduinos conocidos por sus Vendor ID y Product ID.
        val targetDriver = availableDrivers.find { driver ->
            val device = driver.device
            val vendorId = device.vendorId
            val productId = device.productId

            // Lista de IDs conocidos de Arduino y chips seriales comunes.
            val knownArduinos = listOf(
                Pair(0x1A86, 0x7523), // CH340
                Pair(0x2341, 0x0043), // Arduino Uno
                Pair(0x2341, 0x0001), // Arduino Uno Rev3
                Pair(0x0403, 0x6001), // FTDI FT232
            )

            knownArduinos.any { (vid, pid) ->
                vendorId == vid && productId == pid
            }
        } ?: availableDrivers[0] // Si no encuentra uno conocido, usa el primer driver disponible.

        // Intenta abrir la conexión al dispositivo USB.
        val usbConnection = usbManager.openDevice(targetDriver.device)

        if (usbConnection == null) {
            // Si no se pudo abrir la conexión (falta de permiso), solicita el permiso.
            pendingDriver = targetDriver // Guarda el driver para usarlo después de obtener el permiso.
            val permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) },
                // Flags para PendingIntent, dependiendo de la versión de Android.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )

            showToastOnce("🔐 Solicitando permisos USB...")
            usbManager.requestPermission(targetDriver.device, permissionIntent) // Solicita el permiso al usuario.
            return
        }

        showToastOnce("🚀 Conectando automáticamente...")
        initializeConnection(targetDriver, usbConnection) // Si tiene permisos, inicializa la conexión.
    }

    // Intenta reconectar al dispositivo USB.
    private fun reconnectDevice() {
        // Usa AtomicBoolean para asegurar que solo una reconexión se inicie a la vez.
        if (!isReconnecting.compareAndSet(false, true)) return

        isConnected.set(false) // Marca como desconectado.

        usbExecutor.execute { // Ejecuta en el hilo USB.
            try {
                port?.takeIf { it.isOpen }?.close() // Cierra el puerto si está abierto.
                connection?.close() // Cierra la conexión.
            } catch (e: IOException) {
                // Ignorar errores al cerrar.
            } finally {
                port = null
                connection = null
            }

            Thread.sleep(500) // Espera mínima antes de intentar reconectar.

            runOnUiThread {
                isReconnecting.set(false) // Resetea la bandera de reconexión.
                connectToUsbDevice() // Intenta conectar de nuevo.
            }
        }
    }


    // Inicializa la conexión USB serial con el driver y la conexión dados.
    private fun initializeConnection(driver: UsbSerialDriver, usbConnection: UsbDeviceConnection) {
        usbExecutor.execute { // Ejecuta en el hilo USB.
            try {
                // Asegura el cierre de cualquier conexión previa.
                port?.takeIf { it.isOpen }?.close()
                connection?.close()

                port = driver.ports[0] // Obtiene el primer puerto del driver.
                connection = usbConnection // Asigna la conexión.

                if (port?.isOpen == true) { // Si el puerto ya estaba abierto, ciérralo y espera.
                    port?.close()
                    Thread.sleep(100) // Reducido el tiempo de espera.
                }

                port?.open(usbConnection) // Abre el puerto serial.

                // CONFIGURACIÓN ULTRA OPTIMIZADA para mínima latencia.
                port?.setParameters(
                    115200,                    // Baud rate (velocidad de comunicación).
                    8,                         // Data bits.
                    UsbSerialPort.STOPBITS_1,  // Stop bits.
                    UsbSerialPort.PARITY_NONE  // Parity.
                )

                // OPTIMIZACIONES CRÍTICAS DE LATENCIA
                Thread.sleep(30) // Tiempo de espera mínimo necesario después de configurar parámetros.

                // Configurar control de flujo para máxima velocidad.
                port?.setDTR(true) // Data Terminal Ready.
                port?.setRTS(true) // Request To Send.

                // Configurar timeouts para mínima latencia (si el driver lo permite).
                try {
                    val portClass = port?.javaClass
                    // Usa reflexión para llamar a setReadTimeout y setWriteTimeout si existen.
                    portClass?.methods?.find { it.name == "setReadTimeout" }?.invoke(port, USB_READ_TIMEOUT)
                    portClass?.methods?.find { it.name == "setWriteTimeout" }?.invoke(port, USB_WRITE_TIMEOUT)
                } catch (e: Exception) {
                    // Ignorar si no es compatible con el driver actual.
                }

                // Limpiar buffers iniciales.
                try {
                    port?.purgeHwBuffers(true, true) // Limpia buffers de entrada y salida de hardware.
                } catch (e: Exception) {
                    // Ignorar si no es compatible.
                }

                // Enviar comando de inicialización al Arduino.
                Thread.sleep(50) // Espera mínima para que Arduino esté listo para recibir.

                try {
                    // Envía un byte 0x49 ('I' en ASCII) como comando de wake-up/reset.
                    port?.write(byteArrayOf(0x49), 50)
                } catch (e: Exception) {
                    // No crítico si falla, la comunicación principal debería funcionar igual.
                }

                isConnected.set(true) // Marca como conectado.
                lastSentCommand.set("") // Resetea el último comando enviado.
                currentDetection.set("") // Resetea la detección actual.

                runOnUiThread {
                    showToastOnce("⚡ Ultra Fast 115200") // Muestra un Toast de éxito.
                }

            } catch (e: IOException) {
                // Manejo de errores de conexión.
                isConnected.set(false)
                port = null
                connection = null

                runOnUiThread {
                    showToastOnce("Error: ${e.message}") // Muestra el mensaje de error.
                }
            }
        }
    }

    // Inicia la cámara y configura el ImageAnalysis para el procesamiento de frames.
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this) // Obtiene el proveedor de la cámara.

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // Descarta frames viejos si el procesamiento es lento.
                .setTargetRotation(windowManager.defaultDisplay.rotation) // Ajusta la rotación del output de la imagen.
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888) // Formato de imagen.
                .build()
                .also {
                    // Configura el analizador con un ColorAnalyzer personalizado.
                    it.setAnalyzer(cameraExecutor, ColorAnalyzer { result, bitmap, area, centerX, centerY, imageWidth, imageHeight ->
                        // CALCULAR DISTANCIA AQUÍ PARA MOSTRAR EN UI (solo para display, no para comando).
                        val distance = if (area > MIN_AREA_THRESHOLD) calculateDistance(area) else 0
                        val orientation = if (area > MIN_AREA_THRESHOLD) calculateOrientation(centerX, centerY, imageWidth, imageHeight) else ""

                        // Procesamiento inmediato de la detección con toda la información.
                        processDetectionImmediate(result, area, centerX, centerY, imageWidth, imageHeight)

                        // UI update CON DISTANCIA en el hilo principal.
                        runOnUiThread {
                            updateUIOptimized(result, bitmap, area, distance, orientation)
                        }
                    })
                }

            // Selecciona la cámara trasera por defecto.
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll() // Desvincula todos los casos de uso previos.

                // 1. Captura el objeto 'Camera' que devuelve bindToLifecycle.
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalyzer // Vincula el analizador de imagen al ciclo de vida.
                )

                // 2. Verifica si la cámara tiene flash y enciéndelo.
                if (camera.cameraInfo.hasFlashUnit()) {
                    camera.cameraControl.enableTorch(true) // Enciende la linterna (modo antorcha) para una mejor iluminación.
                }

            } catch (exc: Exception) {
                showToastOnce("Error cámara: ${exc.message}") // Muestra un Toast si hay un error al iniciar la cámara.
            }
        }, ContextCompat.getMainExecutor(this)) // Ejecuta el listener en el hilo principal.
    }

    // Procesa la detección de inmediato, decidiendo qué comando de pilar enviar.
    private fun processDetectionImmediate(result: String, area: Double, centerX: Float, centerY: Float, imageWidth: Int, imageHeight: Int) {
        // No procesa si no está conectado o el área es menor al umbral.
        if (!isConnected.get() || area < MIN_AREA_THRESHOLD) return

        // Recalcula distancia y orientación (redundante si ya se hizo, pero asegura valores actualizados).
        val distance = calculateDistance(area)
        val orientation = calculateOrientation(centerX, centerY, imageWidth, imageHeight)

        // Decide qué pilar se detectó y llama a la función de procesamiento específica.
        when (result) {
            "Rojo detectado" -> {
                processPilarDetection("Rojo", area, centerX, centerY, imageWidth, imageHeight)
            }
            "Verde detectado" -> {
                processPilarDetection("Verde", area, centerX, centerY, imageWidth, imageHeight)
            }
            "Magenta detectado" -> {
                processPilarDetection("Magenta", area, centerX, centerY, imageWidth, imageHeight)
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
        // Actualiza cada pocos frames para no sobrecargar la UI y mantener la fluidez.
        if (System.currentTimeMillis() % 3 == 0L) { // Actualiza aproximadamente cada 3 frames.
            val lastCmd = lastSentCommand.get() // Obtiene el último comando enviado.

            // Construye la cadena de texto para el estado.
            val statusText = buildString {
                append("$result") // Resultado de la detección.
                if (area > MIN_AREA_THRESHOLD) { // Si hay una detección válida.
                    append(" | Área: ${area.toInt()}")
                    append(" | Dist: ${distance}cm")
                    append(" | Pos: $orientation")
                }
                if (lastCmd.isNotEmpty()) { // Si se ha enviado un comando.
                    append(" | Cmd: $lastCmd")
                }
            }

            tvStatus.text = statusText // Actualiza el TextView de estado.
        }

        bitmap?.let {
            imageView.setImageBitmap(it) // Muestra el frame de la cámara en el ImageView.
        }
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

        try {
            wakeLock?.release()
        } catch (e: Exception) {
            // Ignorar errores al liberar WakeLock
        }

        // Apagado ordenado de todos los Executors y scopes para liberar recursos.
        commandProcessor.shutdownNow() // Interrumpe y apaga el procesador de comandos.
        scope.cancel() // Cancela todas las corrutinas en el scope.
        cameraExecutor.shutdown() // Apaga el executor de la cámara.
        usbExecutor.shutdown() // Apaga el executor USB.

        isConnected.set(false) // Marca la conexión como inactiva.

        // Cierra el puerto y la conexión USB en un nuevo hilo para evitar bloqueos en el hilo principal.
        Thread {
            try {
                port?.takeIf { it.isOpen }?.close()
                connection?.close()
            } catch (e: Exception) {
                e.printStackTrace() // Imprime el stack trace si hay un error al cerrar.
            }
        }.start()

        try {
            unregisterReceiver(usbReceiver) // Desregistra el receptor USB.
        } catch (e: IllegalArgumentException) {
            // Ignora la excepción si el receiver ya había sido desregistrado.
        }
    }
}
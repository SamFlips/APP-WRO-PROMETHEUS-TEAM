// ===================================================================================
// Proyecto: WRO Prometheus App - Monitor Serial
// Descripción: Actividad para monitorear la comunicación serial con Arduino
//
// Desarrollado por: Equipo PROMETHEUS TEAM
// Versión: 1.3 - Corregido autoscroll y optimizaciones
// ===================================================================================

package com.example.wro_prometheus

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDeviceConnection
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.*
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.view.Window
import android.view.WindowManager

class SerialMonitorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SerialMonitor"
        private const val USB_READ_TIMEOUT = 200
        private const val USB_WRITE_TIMEOUT = 500
        private const val MAX_BUFFER_SIZE = 50000
        private const val BAUD_RATE = 115200 // Velocidad de comunicación serial
        private const val MAX_LINES_DISPLAY = 1000 // Límite de líneas para evitar sobrecarga de memoria
        private const val READ_BUFFER_SIZE = 2048 // Tamaño del buffer de lectura USB

        // Función estática para crear un Intent que inicia esta actividad
        fun createIntent(context: Context): Intent {
            return Intent(context, SerialMonitorActivity::class.java)
        }
    }

    // Variables de UI (enlace a los elementos del layout XML)
    private lateinit var tvSerialOutput: TextView
    private lateinit var btnClear: Button
    private lateinit var btnClose: Button
    private lateinit var switchAutoScroll: Switch
    private lateinit var tvConnectionStatus: TextView
    private lateinit var scrollViewOutput: ScrollView

    // Variables para la comunicación serial USB
    private var port: UsbSerialPort? = null
    private var connection: UsbDeviceConnection? = null
    private val isMonitoring = AtomicBoolean(false) // Flag atómico para controlar el bucle de lectura
    private val isDestroyed = AtomicBoolean(false) // Flag atómico para el estado de destrucción de la actividad

    // Buffers y contadores thread-safe para manejar los datos
    private val serialLines = Collections.synchronizedList(mutableListOf<String>()) // Lista sincronizada para las líneas de salida
    private val messageCounter = AtomicInteger(0) // Contador de mensajes

    // Variables de control para el autoscroll
    private var autoScrollEnabled = true
    private var shouldScrollToBottom = false // Flag para indicar que se debe hacer scroll

    // Coroutine Scope para manejar tareas asíncronas con gestión de errores
    private val monitorScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, exception ->
            Log.e(TAG, "Error no capturado en corrutina: ${exception.message}", exception)
        }
    )
    private var readingJob: Job? = null // Corrutina para la lectura de datos
    private var uiUpdateJob: Job? = null // Corrutina para la actualización de la UI

    // Formato de tiempo para los timestamps
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // Buffer para almacenar datos incompletos (por ejemplo, una línea que no ha terminado con \n)
    private val partialDataBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- INICIO: MODO PANTALLA COMPLETA ---
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        // --- FIN: MODO PANTALLA COMPLETA ---

        setContentView(R.layout.activity_serial_monitor)

        Log.d(TAG, "SerialMonitorActivity iniciada")

        initializeViews() // Inicializa los elementos de la interfaz de usuario
        setupEventListeners() // Configura los listeners de los botones y switches

        // Intenta la conexión en una corrutina para no bloquear el hilo principal
        monitorScope.launch {
            attemptConnection()
        }
    }

    private fun initializeViews() {
        try {
            tvSerialOutput = findViewById(R.id.tvSerialOutput)
            btnClear = findViewById(R.id.btnClear)
            btnClose = findViewById(R.id.btnClose)
            switchAutoScroll = findViewById(R.id.switchAutoScroll)
            tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
            scrollViewOutput = findViewById(R.id.scrollViewOutput)

            // Configura el TextView para permitir scroll
            tvSerialOutput.movementMethod = ScrollingMovementMethod()

            // Configura el estado inicial del switch
            switchAutoScroll.isChecked = true
            autoScrollEnabled = true

            updateConnectionStatus("Inicializando...", false)
            Log.d(TAG, "Vistas inicializadas correctamente")
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando vistas: ${e.message}", e)
            safeShowToast("Error inicializando interfaz")
        }
    }

    private fun setupEventListeners() {
        try {
            // Listener para el botón de limpiar
            btnClear.setOnClickListener {
                clearOutput()
            }

            // Listener para el botón de cerrar
            btnClose.setOnClickListener {
                setResult(RESULT_OK)
                finish()
            }

            // Listener para el switch de autoscroll
            switchAutoScroll.setOnCheckedChangeListener { _, isChecked ->
                autoScrollEnabled = isChecked
                Log.d(TAG, "Autoscroll ${if (isChecked) "activado" else "desactivado"}")

                // Si se activa, hacer scroll inmediato al final
                if (isChecked) {
                    scrollToBottom()
                }
            }

            Log.d(TAG, "Event listeners configurados")
        } catch (e: Exception) {
            Log.e(TAG, "Error configurando listeners: ${e.message}", e)
        }
    }

    private suspend fun attemptConnection() {
        // Obtiene la instancia de MainActivity para acceder al puerto USB
        val mainActivity = MainActivity.getInstance()
        if (mainActivity == null) {
            withContext(Dispatchers.Main) {
                appendToOutput("ERROR: No se pudo obtener instancia de MainActivity", true)
            }
            return
        }

        port = mainActivity.getUsbPort()
        connection = mainActivity.getUsbConnection()

        if (port == null) {
            withContext(Dispatchers.Main) {
                appendToOutput("ERROR: El puerto es null", true)
            }
            return
        }

        if (!port!!.isOpen) {
            withContext(Dispatchers.Main) {
                appendToOutput("ERROR: El puerto no está abierto", true)
            }
            return
        }

        // Si la conexión es exitosa, se actualiza la UI y se inicia el monitoreo
        withContext(Dispatchers.Main) {
            configureSerialPort()
            startMonitoring()
            tvConnectionStatus.text = "Conectado - $BAUD_RATE bps (Monitor)"
            appendToOutput("=== Monitor Serial Iniciado ===", true)
            appendToOutput("Prendelo Pinguino y mas naa", true)
            appendToOutput("Configuración: $BAUD_RATE bps, 8N1", true)
            appendToOutput("Modo solo lectura activo", true)
            appendToOutput("Esperando datos del Microcontrolador...", true)
        }
    }

    private fun configureSerialPort() {
        try {
            port?.let { p ->
                Log.d(TAG, "Puerto ya configurado por MainActivity")
                appendToOutput("Usando configuración existente del puerto", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando configuración: ${e.message}", e)
            appendToOutput("ERROR verificando configuración: ${e.message}", true)
        }
    }

    private fun startMonitoring() {
        // Usa un flag atómico para asegurar que el monitoreo solo inicie una vez
        if (isMonitoring.compareAndSet(false, true)) {
            Log.d(TAG, "Iniciando monitoreo serial robusto")
            startReadingData() // Inicia la corrutina de lectura
            startUIUpdates() // Inicia la corrutina de actualización de UI
            startConnectionHealthCheck() // Inicia la corrutina de verificación de salud
        }
    }

    private fun stopMonitoring() {
        Log.d(TAG, "Deteniendo monitoreo serial")
        isMonitoring.set(false)

        // Cancela los jobs de las corrutinas
        readingJob?.cancel()
        uiUpdateJob?.cancel()

        // Espera de forma no bloqueante a que las corrutinas terminen
        runBlocking {
            withTimeoutOrNull(500) {
                readingJob?.join()
                uiUpdateJob?.join()
            }
        }
    }

    private fun startReadingData() {
        readingJob = monitorScope.launch {
            Log.d(TAG, "Hilo de lectura iniciado para alta velocidad")
            val buffer = ByteArray(READ_BUFFER_SIZE)

            while (isMonitoring.get() && isActive && !isDestroyed.get()) {
                try {
                    val currentPort = port
                    if (currentPort == null || !currentPort.isOpen) {
                        Log.w(TAG, "Puerto no disponible en lectura")
                        delay(1000)
                        continue
                    }

                    // Lee datos del puerto USB con un timeout
                    val bytesRead = currentPort.read(buffer, USB_READ_TIMEOUT)
                    if (bytesRead > 0) {
                        val receivedData = String(buffer, 0, bytesRead)
                        processReceivedData(receivedData) // Procesa los datos leídos
                    } else {
                        delay(10) // Pequeña pausa para evitar un bucle de CPU intensivo
                    }
                } catch (e: IOException) {
                    // El timeout es una excepción esperada y se ignora
                    if (isMonitoring.get() && !isDestroyed.get()) {
                        Log.w(TAG, "Timeout de lectura USB (normal): ${e.message}")
                        delay(50)
                    }
                } catch (e: Exception) {
                    // Maneja cualquier otra excepción de lectura
                    if (isMonitoring.get() && !isDestroyed.get()) {
                        Log.e(TAG, "Error general en lectura: ${e.message}", e)

                        withContext(Dispatchers.Main) {
                            appendToOutput("ERROR DE LECTURA: ${e.message}", true)
                            updateConnectionStatus("Error de conexión", false)
                        }
                        delay(1000)
                    }
                }
            }
            Log.d(TAG, "Hilo de lectura terminado")
        }
    }

    private fun processReceivedData(data: String) {
        try {
            // Agrega los datos al buffer de datos parciales
            partialDataBuffer.append(data)

            // Separa los datos por líneas completas
            val fullData = partialDataBuffer.toString()
            val lines = fullData.split('\n')

            // Mantiene la última línea si está incompleta
            partialDataBuffer.clear()
            if (!fullData.endsWith('\n') && lines.isNotEmpty()) {
                partialDataBuffer.append(lines.last())
            }

            // Procesa solo las líneas completas
            val completedLines = if (fullData.endsWith('\n')) lines else lines.dropLast(1)

            for (line in completedLines) {
                val cleanLine = line.trim()
                if (cleanLine.isNotEmpty()) {
                    val timestamp = timeFormat.format(Date())
                    val formattedLine = "[$timestamp] RX: $cleanLine"

                    // Agrega la línea a la lista de forma segura (sincronizada)
                    synchronized(serialLines) {
                        serialLines.add(formattedLine)
                        // Limita el tamaño de la lista
                        while (serialLines.size > MAX_LINES_DISPLAY) {
                            serialLines.removeAt(0)
                        }
                    }
                    messageCounter.incrementAndGet()

                    // Marca la necesidad de hacer scroll
                    if (autoScrollEnabled) {
                        shouldScrollToBottom = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando datos recibidos: ${e.message}", e)
        }
    }

    private fun startUIUpdates() {
        uiUpdateJob = monitorScope.launch {
            Log.d(TAG, "Hilo de actualización UI iniciado")

            while (isMonitoring.get() && isActive && !isDestroyed.get()) {
                try {
                    // Cambia al hilo principal para actualizar la interfaz
                    withContext(Dispatchers.Main) {
                        updateUI()
                    }
                    // Pequeña pausa para evitar sobrecarga de la UI
                    delay(100)
                } catch (e: Exception) {
                    if (!isDestroyed.get()) {
                        Log.e(TAG, "Error actualizando UI: ${e.message}", e)
                    }
                    delay(500)
                }
            }
            Log.d(TAG, "Hilo de actualización UI terminado")
        }
    }

    private fun updateUI() {
        try {
            if (isDestroyed.get()) return

            // Actualiza el TextView con las últimas líneas
            synchronized(serialLines) {
                if (serialLines.isNotEmpty()) {
                    val displayText = serialLines.takeLast(MAX_LINES_DISPLAY).joinToString("\n")
                    // Solo actualiza si el texto realmente ha cambiado
                    if (tvSerialOutput.text.toString() != displayText) {
                        tvSerialOutput.text = displayText

                        // Realiza el autoscroll si está habilitado y es necesario
                        if (shouldScrollToBottom && autoScrollEnabled) {
                            scrollToBottom()
                            shouldScrollToBottom = false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en updateUI: ${e.message}", e)
        }
    }

    // Función para hacer scroll al final del ScrollView
    private fun scrollToBottom() {
        try {
            if (isDestroyed.get()) return

            // Usa post para asegurar que la vista esté en el layout antes de hacer scroll
            scrollViewOutput.post {
                scrollViewOutput.fullScroll(ScrollView.FOCUS_DOWN)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en scrollToBottom: ${e.message}", e)
        }
    }

    // Corrutina para verificar el estado de la conexión periódicamente
    private fun startConnectionHealthCheck() {
        monitorScope.launch {
            while (isMonitoring.get() && isActive && !isDestroyed.get()) {
                delay(10000) // Verificar cada 10 segundos

                try {
                    val mainActivity = MainActivity.getInstance()
                    val mainActivityConnected = mainActivity?.isUsbConnected() ?: false
                    val localPortOpen = port?.isOpen ?: false

                    if (isMonitoring.get() && (!mainActivityConnected || !localPortOpen)) {
                        Log.w(TAG, "Health check falló - MainActivity: $mainActivityConnected, Puerto: $localPortOpen")

                        withContext(Dispatchers.Main) {
                            appendToOutput("WARN: Verificando estado de conexión...", true)
                            updateConnectionStatus("Verificando conexión", false)
                        }

                        // Intenta una lectura de prueba para confirmar si la conexión está perdida
                        try {
                            val testBuffer = ByteArray(1)
                            port?.read(testBuffer, 50)
                            // Si la lectura no falla, se considera conectado
                            withContext(Dispatchers.Main) {
                                updateConnectionStatus("Conectado - $BAUD_RATE bps", true)
                            }
                        } catch (e: Exception) {
                            // Si la lectura falla, la conexión está perdida
                            withContext(Dispatchers.Main) {
                                appendToOutput("ERROR: Conexión perdida", true)
                                updateConnectionStatus("Conexión perdida", false)
                                stopMonitoring()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error en health check: ${e.message}")
                }
            }
        }
    }

    private fun appendToOutput(text: String, isSystemMessage: Boolean) {
        try {
            if (isDestroyed.get()) return

            val timestamp = timeFormat.format(Date())
            val fullText = if (isSystemMessage) {
                "[SYS] $text"
            } else {
                "[$timestamp] $text"
            }

            // Agrega la línea de forma segura
            synchronized(serialLines) {
                serialLines.add(fullText)
                while (serialLines.size > MAX_LINES_DISPLAY) {
                    serialLines.removeAt(0)
                }
            }

            // Marca para scroll si está habilitado
            if (autoScrollEnabled) {
                shouldScrollToBottom = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error agregando texto al output: ${e.message}", e)
        }
    }

    private fun clearOutput() {
        try {
            // Limpia los buffers de datos de forma segura
            synchronized(serialLines) {
                serialLines.clear()
            }
            messageCounter.set(0)
            partialDataBuffer.clear()

            // Actualiza la UI en el hilo principal
            runOnUiThread {
                tvSerialOutput.text = ""
                appendToOutput("=== Buffer limpiado ===", true)
            }
            Log.d(TAG, "Buffer limpiado")
        } catch (e: Exception) {
            Log.e(TAG, "Error limpiando output: ${e.message}", e)
        }
    }

    private fun updateConnectionStatus(status: String, isConnected: Boolean) {
        try {
            if (isDestroyed.get()) return

            tvConnectionStatus.text = "Estado: $status"

            // Cambia el color del fondo según el estado de la conexión
            tvConnectionStatus.setBackgroundColor(
                if (isConnected) {
                    resources.getColor(android.R.color.holo_green_dark, theme)
                } else {
                    resources.getColor(android.R.color.holo_red_dark, theme)
                }
            )

            Log.d(TAG, "Estado actualizado: $status (conectado: $isConnected)")
        } catch (e: Exception) {
            Log.e(TAG, "Error actualizando estado: ${e.message}", e)
        }
    }

    // Muestra un Toast de forma segura
    private fun safeShowToast(message: String) {
        try {
            if (!isDestroyed.get()) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error mostrando Toast: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "SerialMonitorActivity destruyéndose")
        isDestroyed.set(true)

        setResult(RESULT_OK)
        super.onDestroy()

        stopMonitoring() // Detiene las corrutinas de monitoreo
        monitorScope.cancel() // Cancela el scope para liberar todos los jobs

        // Notifica a MainActivity que el monitor fue cerrado
        MainActivity.getInstance()?.onSerialMonitorClosed()

        // Libera las referencias a los objetos USB para evitar fugas de memoria
        port = null
        connection = null

        synchronized(serialLines) {
            serialLines.clear()
        }
        partialDataBuffer.clear()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "SerialMonitorActivity pausada")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "SerialMonitorActivity reanudada")

        // Si la actividad se reanuda y la conexión USB se ha perdido, detiene el monitoreo
        if (isMonitoring.get()) {
            val mainActivity = MainActivity.getInstance()
            if (mainActivity?.isUsbConnected() != true) {
                Log.w(TAG, "Conexión perdida durante pausa")
                runOnUiThread {
                    updateConnectionStatus("Conexión perdida", false)
                    stopMonitoring()
                }
            }
        }
    }
}
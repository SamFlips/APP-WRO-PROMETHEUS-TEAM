package com.example.wro_prometheus

import android.content.Context
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.util.Log

class AudioManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: AudioManager? = null

        fun getInstance(context: Context): AudioManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AudioManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var isAudioEnabled = false

    // Control de comandos reproducidos para evitar duplicados
    private var lastPlayedCommand = ""
    private var lastPlayTime = 0L
    private val REPLAY_COOLDOWN = 2000L // 2 segundos mínimo entre reproducciones del mismo comando

    // Mapa de comandos a recursos de audio
    private val commandSounds = mapOf(
       // Esquives simples
        "C01" to R.raw.yippee,
        "C08" to R.raw.yippee,
        "C02" to R.raw.yippee,
        "C07" to R.raw.yippee,
        "C03" to R.raw.ezsound,
        "C04" to R.raw.ezsound,
        "C09" to R.raw.ezsound,
        "C10" to R.raw.ezsound,
        "C05" to R.raw.thateasy,
        "C06" to R.raw.thateasy,
        "c37" to R.raw.thateasy,
        "c38" to R.raw.thateasy,

        // Esquives Dobles
        "C14" to R.raw.tokyo,
        "C15" to R.raw.tokyo,
        "C21" to R.raw.tokyo,
        "C20" to R.raw.okleltsgo,
        "C26" to R.raw.maxverstappen,
        "C27" to R.raw.maxverstappen,
        "C32" to R.raw.maxverstappen,
        "C33" to R.raw.maxverstappen,
        "C13" to R.raw.rizzz,
        "C18" to R.raw.rizzz,
        "C19" to R.raw.rizzz,
        "C24" to R.raw.rizzz,
        "C25" to R.raw.sadmeow,
        "C30" to R.raw.sadmeow,
        "C31" to R.raw.okleltsgo,
        "C36" to R.raw.okleltsgo,






        // aqui puedes agregar más comandos fácilmente:
        // "C15" to R.raw.c15_sound,
        // "C01" to R.raw.c01_sound,
        // etc.
    )

    //Reproducir sonido solo si es un comando diferente o ha pasado suficiente tiempo
    fun playCommandSoundOnce(command: String) {
        if (!isAudioEnabled) {
            Log.d("AudioManager", "Audio deshabilitado")
            return
        }

        val currentTime = System.currentTimeMillis()

        // Verificar si es el mismo comando y no ha pasado suficiente tiempo
        if (command == lastPlayedCommand && (currentTime - lastPlayTime) < REPLAY_COOLDOWN) {
            Log.d("AudioManager", "Sonido ignorado - comando repetido demasiado rápido: $command")
            return
        }

        val soundResourceId = commandSounds[command]
        if (soundResourceId == null) {
            Log.d("AudioManager", "No hay sonido configurado para comando: $command")
            return
        }

        Log.d("AudioManager", "Reproduciendo sonido ÚNICO para comando: $command")

        // Actualizar control de reproducción
        lastPlayedCommand = command
        lastPlayTime = currentTime

        try {
            // Liberar MediaPlayer anterior
            mediaPlayer?.release()

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )

                setOnPreparedListener { mp ->
                    Log.d("AudioManager", "Iniciando reproducción ÚNICA de $command")
                    mp.start()
                }

                setOnCompletionListener { mp ->
                    Log.d("AudioManager", "Reproducción completada para $command")
                    mp.release()
                    this@AudioManager.mediaPlayer = null
                }

                setOnErrorListener { mp, what, extra ->
                    Log.e("AudioManager", "Error reproduciendo $command: what=$what, extra=$extra")
                    mp.release()
                    this@AudioManager.mediaPlayer = null
                    true
                }

                val uri = android.net.Uri.parse("android.resource://${context.packageName}/${soundResourceId}")
                setDataSource(context, uri)
                prepareAsync()
            }

        } catch (e: Exception) {
            Log.e("AudioManager", "Error configurando audio para $command: ${e.message}")
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    // MANTENER función original para compatibilidad (pero marcar como deprecated)
    @Deprecated("Usar playCommandSoundOnce para evitar duplicados")
    fun playCommandSound(command: String) {
        playCommandSoundOnce(command)
    }

    fun setAudioEnabled(enabled: Boolean) {
        isAudioEnabled = enabled
        Log.d("AudioManager", "Audio ${if (enabled) "habilitado" else "deshabilitado"}")
    }

    // Resetear control de reproducción (útil para debugging)
    fun resetPlaybackControl() {
        lastPlayedCommand = ""
        lastPlayTime = 0L
        Log.d("AudioManager", "Control de reproducción reseteado")
    }

    fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
        resetPlaybackControl()
    }
}
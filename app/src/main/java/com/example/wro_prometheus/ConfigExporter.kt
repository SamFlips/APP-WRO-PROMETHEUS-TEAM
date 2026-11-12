package com.example.wro_prometheus

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Exporta/importa TODA la configuración calibrable de la app (zonas de distancia,
 * calibración de distancia por área de referencia, filtro anti-reflejo, presets de
 * iluminación y rangos HSV calibrados por muestras de Rojo/Verde/Magenta) como un
 * único archivo JSON, para compartir la calibración entre teléfonos sin tener que
 * recalibrar todo desde cero en cada dispositivo.
 */
object ConfigExporter {

    private const val PREFS_CONFIG_SETTINGS = "wro_prometheus_config"
    private const val PREFS_COLOR_PRESETS = "color_presets"

    fun exportToJson(context: Context): String {
        val root = JSONObject()
        root.put("app", "WRO Prometheus")
        root.put("exportVersion", 1)
        root.put("exportedAt", System.currentTimeMillis())
        root.put(PREFS_CONFIG_SETTINGS, prefsToJson(context, PREFS_CONFIG_SETTINGS))
        root.put(PREFS_COLOR_PRESETS, prefsToJson(context, PREFS_COLOR_PRESETS))
        return root.toString(2)
    }

    fun importFromJson(context: Context, jsonString: String): Boolean {
        return try {
            val root = JSONObject(jsonString)
            root.optJSONObject(PREFS_CONFIG_SETTINGS)?.let { jsonToPrefs(context, PREFS_CONFIG_SETTINGS, it) }
            root.optJSONObject(PREFS_COLOR_PRESETS)?.let { jsonToPrefs(context, PREFS_COLOR_PRESETS, it) }

            // ConfigSettings lee directo de SharedPreferences en cada get, así que
            // ya queda actualizado. ColorConfig cachea los rangos en memoria, así
            // que hay que forzar la recarga para que aplique sin reiniciar la app.
            ColorConfig.loadPresets(context)
            ColorConfig.loadMagentaPreset(context)

            Log.d("ConfigExporter", "Configuración importada correctamente")
            true
        } catch (e: Exception) {
            Log.e("ConfigExporter", "Error importando configuración: ${e.message}", e)
            false
        }
    }

    // -------------------------------------------------------------------
    // SharedPreferences <-> JSON, preservando el tipo real de cada valor
    // -------------------------------------------------------------------

    private fun prefsToJson(context: Context, prefsName: String): JSONObject {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val obj = JSONObject()
        for ((key, value) in prefs.all) {
            val entry = JSONObject()
            when (value) {
                is Boolean -> { entry.put("type", "boolean"); entry.put("value", value) }
                is Int     -> { entry.put("type", "int");     entry.put("value", value) }
                is Long    -> { entry.put("type", "long");    entry.put("value", value) }
                is Float   -> { entry.put("type", "float");   entry.put("value", value.toDouble()) }
                is String  -> { entry.put("type", "string");  entry.put("value", value) }
                else -> continue
            }
            obj.put(key, entry)
        }
        return obj
    }

    private fun jsonToPrefs(context: Context, prefsName: String, json: JSONObject) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().apply {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = json.getJSONObject(key)
                when (entry.getString("type")) {
                    "boolean" -> putBoolean(key, entry.getBoolean("value"))
                    "int"     -> putInt(key, entry.getInt("value"))
                    "long"    -> putLong(key, entry.getLong("value"))
                    "float"   -> putFloat(key, entry.getDouble("value").toFloat())
                    "string"  -> putString(key, entry.getString("value"))
                }
            }
            apply()
        }
    }

    // -------------------------------------------------------------------
    // Compartir / recibir el archivo .json entre teléfonos
    // -------------------------------------------------------------------

    /** Guarda el JSON en un archivo temporal y arma un Intent para compartirlo
     *  por cualquier app (WhatsApp, Drive, Bluetooth, correo, etc.). */
    fun buildShareIntent(context: Context, json: String): Intent {
        val fileName = "wro_prometheus_config_${System.currentTimeMillis()}.json"
        val file = File(context.cacheDir, fileName)
        file.writeText(json)

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Lee el contenido de texto del archivo elegido al importar (con ACTION_OPEN_DOCUMENT). */
    fun readTextFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }
        } catch (e: Exception) {
            Log.e("ConfigExporter", "Error leyendo archivo: ${e.message}", e)
            null
        }
    }
}
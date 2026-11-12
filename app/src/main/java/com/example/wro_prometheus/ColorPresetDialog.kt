package com.example.wro_prometheus

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog

class ColorPresetDialog(
    context: Context,
    private val onPresetChanged: () -> Unit
) : Dialog(context) {

    private lateinit var redSpinner: Spinner
    private lateinit var greenSpinner: Spinner
    private lateinit var magentaSpinner: Spinner

    private val spinnerAdapter by lazy {
        val presetNames = ColorConfig.LightPreset.values().map {
            ColorConfig.getPresetName(it)
        }
        ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            presetNames
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_color_presets)

        setupViews()
        setupSpinners()
        setupButtons()
    }

    private fun setupViews() {
        redSpinner     = findViewById(R.id.spinnerRedPreset)
        greenSpinner   = findViewById(R.id.spinnerGreenPreset)
        magentaSpinner = findViewById(R.id.spinnerMagentaPreset)

        setTitle("Configuración de Iluminación")
        setCancelable(true)
    }

    private fun setupSpinners() {
        redSpinner.adapter     = spinnerAdapter
        greenSpinner.adapter   = spinnerAdapter
        magentaSpinner.adapter = spinnerAdapter

        redSpinner.setSelection(ColorConfig.getCurrentRedPreset().ordinal)
        greenSpinner.setSelection(ColorConfig.getCurrentGreenPreset().ordinal)
        magentaSpinner.setSelection(ColorConfig.getCurrentMagentaPreset().ordinal)
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnApply).setOnClickListener { applySettings() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { dismiss() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetToDefault() }
    }

    private fun applySettings() {
        val redPreset     = ColorConfig.LightPreset.values()[redSpinner.selectedItemPosition]
        val greenPreset   = ColorConfig.LightPreset.values()[greenSpinner.selectedItemPosition]
        val magentaPreset = ColorConfig.LightPreset.values()[magentaSpinner.selectedItemPosition]

        var changed = false
        if (ColorConfig.getCurrentRedPreset() != redPreset) {
            ColorConfig.setRedPreset(redPreset)
            changed = true
        }
        if (ColorConfig.getCurrentGreenPreset() != greenPreset) {
            ColorConfig.setGreenPreset(greenPreset)
            changed = true
        }
        if (ColorConfig.getCurrentMagentaPreset() != magentaPreset) {
            ColorConfig.setMagentaPreset(magentaPreset)
            changed = true
        }

        if (changed) {
            ColorConfig.savePresets(context)
            ColorConfig.saveMagentaPreset(context)
            onPresetChanged()
            Toast.makeText(
                context,
                "Configuración aplicada:\n" +
                        "Rojo: ${ColorConfig.getPresetName(redPreset)}\n" +
                        "Verde: ${ColorConfig.getPresetName(greenPreset)}\n" +
                        "Magenta: ${ColorConfig.getPresetName(magentaPreset)}",
                Toast.LENGTH_SHORT
            ).show()
        }
        dismiss()
    }

    private fun resetToDefault() {
        redSpinner.setSelection(ColorConfig.LightPreset.STANDARD.ordinal)
        greenSpinner.setSelection(ColorConfig.LightPreset.STANDARD.ordinal)
        magentaSpinner.setSelection(ColorConfig.LightPreset.BRIGHT.ordinal)
    }
}

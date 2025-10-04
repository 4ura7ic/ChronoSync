package com.chronosync.app

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.Toolbar

class SettingsActivity : AppCompatActivity() {

    private lateinit var customServerEditText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // --- NEW: Find our toolbar and set it as the action bar ---
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        // This line will now work because an action bar exists
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Get all view references
        val ntpServerSpinner: Spinner = findViewById(R.id.ntpServerSpinner)
        customServerEditText = findViewById(R.id.customServerEditText)
        val secondHandSpinner: Spinner = findViewById(R.id.secondHandSpinner)
        val versionTextView: TextView = findViewById(R.id.versionTextView)

        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)

        // --- All the existing spinner and version logic is unchanged ---
        val serverSpinnerAdapter = ArrayAdapter(this, R.layout.custom_spinner_item, MainActivity.PRESET_SERVERS); serverSpinnerAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item); ntpServerSpinner.adapter = serverSpinnerAdapter
        val savedServerPosition = prefs.getInt(MainActivity.KEY_SELECTED_SERVER_POSITION, 0); ntpServerSpinner.setSelection(savedServerPosition)
        val savedCustomServer = prefs.getString(MainActivity.KEY_CUSTOM_SERVER, ""); customServerEditText.setText(savedCustomServer)
        if (MainActivity.PRESET_SERVERS[savedServerPosition] == "Custom...") { customServerEditText.visibility = View.VISIBLE } else { customServerEditText.visibility = View.GONE }
        ntpServerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { prefs.edit().putInt(MainActivity.KEY_SELECTED_SERVER_POSITION, position).apply(); if (MainActivity.PRESET_SERVERS[position] == "Custom...") { customServerEditText.visibility = View.VISIBLE } else { customServerEditText.visibility = View.GONE } }; override fun onNothingSelected(parent: AdapterView<*>?) {} }
        val styleSpinnerAdapter = ArrayAdapter(this, R.layout.custom_spinner_item, MainActivity.SECOND_HAND_STYLES); styleSpinnerAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item); secondHandSpinner.adapter = styleSpinnerAdapter
        val savedStylePosition = prefs.getInt(MainActivity.KEY_SECOND_HAND_STYLE, 0); secondHandSpinner.setSelection(savedStylePosition)
        secondHandSpinner.onItemSelectedListener = object: AdapterView.OnItemSelectedListener { override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) { prefs.edit().putInt(MainActivity.KEY_SECOND_HAND_STYLE, position).apply() }; override fun onNothingSelected(p0: AdapterView<*>?) {} }
        try { val versionName = BuildConfig.VERSION_NAME; versionTextView.text = getString(R.string.version_label, versionName) } catch (e: Exception) { e.printStackTrace(); versionTextView.text = "Version N/A" }
    }

    // --- NEW: This handles the click on the back arrow ---
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onPause() {
        super.onPause()
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(MainActivity.KEY_CUSTOM_SERVER, customServerEditText.text.toString().trim()).apply()
    }
}
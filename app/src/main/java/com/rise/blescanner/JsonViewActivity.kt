package com.rise.blescanner

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.rise.blescanner.databinding.ActivityCsvViewBinding
import org.json.JSONObject
import java.io.File
import androidx.core.graphics.toColorInt

class JsonViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCsvViewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCsvViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        val jsonPath = intent.getStringExtra("json_path")
        if (jsonPath.isNullOrEmpty()) {
            Toast.makeText(this, "JSON file path is empty", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val file = File(jsonPath)
        if (!file.exists()) {
            Toast.makeText(this, "JSON file does not exist", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.txtTitle.text = file.name

        try {
            val lines = file.readLines()
            if (lines.isNotEmpty()) {
                renderJson(lines)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderJson(lines: List<String>) {
        // Collect all possible keys from the first few rows to build header
        val allKeys = mutableSetOf<String>()
        val jsonObjects = mutableListOf<JSONObject>()
        
        for (i in 0 until minOf(lines.size, 100)) {
            if (lines[i].isBlank()) continue
            try {
                val obj = JSONObject(lines[i])
                jsonObjects.add(obj)
                obj.keys().forEach { key ->
                    if (key != "ble_rssis") {
                        allKeys.add(key)
                    }
                }
                // Handle ble_rssis specifically to flatten them if possible
                obj.optJSONObject("ble_rssis")?.let { bleRssis ->
                    bleRssis.keys().forEach { mac ->
                        allKeys.add("BLE_$mac")
                    }
                }
            } catch (_: Exception) {}
        }

        val sortedKeys = allKeys.sorted().toList()
        val headerRow = createRow(sortedKeys, isHeader = true)
        binding.tableLayout.addView(headerRow)

        for (obj in jsonObjects) {
            val values = sortedKeys.map { key ->
                if (key.startsWith("BLE_")) {
                    val mac = key.substring(4)
                    obj.optJSONObject("ble_rssis")?.optString(mac) ?: ""
                } else {
                    obj.optString(key, "")
                }
            }
            val dataRow = createRow(values, isHeader = false)
            binding.tableLayout.addView(dataRow)
        }

        if (lines.size > 100) {
            Toast.makeText(this, "File is large; displaying first 100 rows only.", Toast.LENGTH_LONG).show()
        }
    }

    private fun createRow(columns: List<String>, isHeader: Boolean): TableRow {
        val tableRow = TableRow(this)
        val paddingDp = 8
        val scale = resources.displayMetrics.density
        val paddingPx = (paddingDp * scale + 0.5f).toInt()

        for (columnText in columns) {
            val textView = TextView(this).apply {
                text = columnText
                paddingPx.let { setPadding(it, it, it, it) }
                gravity = Gravity.CENTER
                textSize = 14f
                minWidth = (100 * scale + 0.5f).toInt()
                if (isHeader) {
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setBackgroundColor("#4CAF50".toColorInt())
                } else {
                    setTextColor(Color.BLACK)
                    setBackgroundColor("#F5FBF4".toColorInt())
                }
            }
            tableRow.addView(textView)
        }
        return tableRow
    }
}
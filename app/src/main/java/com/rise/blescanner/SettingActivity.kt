package com.rise.blescanner

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.rise.blescanner.databinding.ActivitySettingBinding
import com.rise.blescanner.store.ConfigStore

@SuppressLint("SetTextI18n")
class SettingActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingBinding
    private lateinit var configStore: ConfigStore

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        configStore = ConfigStore(this)

        loadCurrentConfigurations()
        setupClickListeners()
    }

    private fun loadCurrentConfigurations() {
        binding.etTrackedMacs.setText(configStore.getTrackedMacs())
        binding.switchRecordAccel.isChecked = configStore.getRecordAccel()
        binding.switchRecordGyro.isChecked = configStore.getRecordGyro()
        binding.switchShowVisualizer.isChecked = configStore.getShowVisualizer()
    }

    private fun setupClickListeners() {
        binding.switchRecordAccel.setOnCheckedChangeListener { _, isChecked ->
            configStore.saveRecordAccel(isChecked)
        }

        binding.switchRecordGyro.setOnCheckedChangeListener { _, isChecked ->
            configStore.saveRecordGyro(isChecked)
        }

        binding.switchShowVisualizer.setOnCheckedChangeListener { _, isChecked ->
            configStore.saveShowVisualizer(isChecked)
        }

        binding.btnBack.setOnClickListener {
            saveAllConfigurations()
            finish()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                saveAllConfigurations()
                finish()
            }
        })
    }

    private fun saveAllConfigurations() {
        val macs = binding.etTrackedMacs.text.toString()
        configStore.saveTrackedMacs(macs)
    }
}
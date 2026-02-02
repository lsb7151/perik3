package com.coremotion.perik3

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.coremotion.perik3.databinding.ActivityMainBinding
import com.coremotion.perik3.ui.MeasurementFragment

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportFragmentManager.beginTransaction()
            .replace(binding.container.id, MeasurementFragment())
            .commit()
    }
}
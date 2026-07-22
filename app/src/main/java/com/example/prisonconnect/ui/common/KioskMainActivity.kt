package com.example.prisonconnect.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.example.prisonconnect.R
import com.example.prisonconnect.databinding.ActivityKioskMainBinding
import com.example.prisonconnect.ui.auth.LoginFragment

class KioskMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKioskMainBinding
    private val TAG = "KioskMain"

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach {
            Log.d(TAG, "Permission ${it.key} granted: ${it.value}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityKioskMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestPermissions()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Setup OnBackPressedDispatcher
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
                if (currentFragment is LoginFragment) {
                    finishAffinity() // Exit the app completely
                } else {
                    // Otherwise, go back to login (which will show PIN if user is logged in)
//                    navigateToFragment(LoginFragment(), false)
                    finishAffinity() // Exit the app completely
                }
            }
        })

        if (savedInstanceState == null) {
            navigateToFragment(LoginFragment(), false)
        }
    }

    fun navigateToFragment(fragment: Fragment, addToBackStack: Boolean = true) {
        val transaction = supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
        
        if (addToBackStack) {
            transaction.addToBackStack(null)
        }
        
        transaction.commit()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Log.d(TAG, "Requesting missing permissions: $missingPermissions")
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            Log.d(TAG, "All permissions already granted")
        }
    }
}

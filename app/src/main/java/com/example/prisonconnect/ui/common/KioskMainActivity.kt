package com.example.prisonconnect.ui.common

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.example.prisonconnect.R
import com.example.prisonconnect.databinding.ActivityKioskMainBinding
import com.example.prisonconnect.ui.auth.LoginFragment

class KioskMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKioskMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityKioskMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                    navigateToFragment(LoginFragment(), false)
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
}

package com.example.prisonconnect.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.Context
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.prisonconnect.databinding.FragmentLoginBinding
import com.example.prisonconnect.domain.model.User
import com.example.prisonconnect.data.remote.DbService
import com.example.prisonconnect.ui.dashboard.DashboardFragment
import com.example.prisonconnect.ui.common.KioskMainActivity
import kotlinx.coroutines.launch
import java.security.MessageDigest

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private var enteredPin = ""
    private var verifiedUserId: String? = null
    private var serverPinHash: String? = null
    private var userFullName: String? = null
    private enum class EntryMode { PRISONER_ID, PIN }
    private var mode = EntryMode.PRISONER_ID

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Check if user is already logged in (secure session persistence)
        val sharedPref = requireActivity().getSharedPreferences("PrisonPrefs", Context.MODE_PRIVATE)
        val savedUserId = sharedPref.getString("logged_in_user", null)
        
        if (savedUserId != null) {
            // User is already logged in, go directly to PIN entry
            lifecycleScope.launch {
                try {
                    val user: User? = DbService.getDocument(table = "users", id = savedUserId)
                    if (user != null && user.account_status == "active") {
                        verifiedUserId = savedUserId
                        userFullName = user.full_name.ifEmpty { "Inmate" }
                        serverPinHash = user.pin_hash
                        
                        mode = EntryMode.PIN
                        enteredPin = ""
                        updatePinDisplay()
                        binding.tvTitle.text = "Enter PIN"
                        binding.tvWelcome.text = "Welcome, $userFullName"
                        binding.tvWelcome.visibility = View.VISIBLE
                        binding.btnChangePrisoner.visibility = View.VISIBLE
                        binding.llPinContainer.visibility = View.VISIBLE
                        binding.glButtons.visibility = View.VISIBLE
                    } else {
                        // User not found or inactive, show prisoner ID screen
                        showPrisonerIdScreen()
                    }
                } catch (e: Exception) {
                    // If there's an error, show prisoner ID screen
                    showPrisonerIdScreen()
                }
            }
        } else {
            // Show prisoner ID screen
            showPrisonerIdScreen()
        }

        setupPinPad()
        updatePinDisplay()

        binding.btnChangePrisoner.setOnClickListener { resetToPrisonerId() }
    }

    private fun showPrisonerIdScreen() {
        binding.llPinContainer.visibility = View.VISIBLE
        binding.glButtons.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE
        binding.tvWelcome.visibility = View.GONE
        binding.btnChangePrisoner.visibility = View.GONE
        binding.tvTitle.text = "Enter Prisoner ID"
    }

    private fun resetToPrisonerId() {
        // Clear the saved user session
        val sharedPref = requireActivity().getSharedPreferences("PrisonPrefs", Context.MODE_PRIVATE)
        sharedPref.edit().remove("logged_in_user").apply()
        
        verifiedUserId = null
        serverPinHash = null
        userFullName = null
        enteredPin = ""
        updatePinDisplay()
        mode = EntryMode.PRISONER_ID
        binding.tvTitle.text = "Enter Prisoner ID"
        binding.tvWelcome.visibility = View.GONE
        binding.btnChangePrisoner.visibility = View.GONE

        binding.llPinContainer.visibility = View.VISIBLE
        binding.glButtons.visibility = View.VISIBLE
    }

    private fun setupPinPad() {
        val buttons = listOf(
            binding.btn0, binding.btn1, binding.btn2, binding.btn3,
            binding.btn4, binding.btn5, binding.btn6, binding.btn7,
            binding.btn8, binding.btn9
        )

        buttons.forEach { button ->
            button.setOnClickListener {
                if (enteredPin.length < 6) {
                    enteredPin += button.text
                    updatePinDisplay()
                    if (enteredPin.length == 6) {
                        onSixDigitsEntered(enteredPin)
                    }
                }
            }
        }

        binding.btnClear.setOnClickListener {
            enteredPin = ""
            updatePinDisplay()
        }

        binding.btnDelete.setOnClickListener {
            if (enteredPin.isNotEmpty()) {
                enteredPin = enteredPin.substring(0, enteredPin.length - 1)
                updatePinDisplay()
            }
        }
    }

    private fun onSixDigitsEntered(digits: String) {
        when (mode) {
            EntryMode.PRISONER_ID -> verifyPrisonerId(digits)
            EntryMode.PIN -> verifyPin(digits)
        }
    }

    private fun verifyPrisonerId(id: String) {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val users: List<User> = DbService.queryDocuments(
                    table = "users",
                    field = "prisoner_id",
                    value = id
                )
                binding.progressBar.visibility = View.GONE

                if (users.isNotEmpty()) {
                    val user = users[0]
                    verifiedUserId = user.id
                    userFullName = user.full_name.ifEmpty { "Inmate" }
                    serverPinHash = user.pin_hash

                    // Switch to PIN entry mode
                    mode = EntryMode.PIN
                    enteredPin = ""
                    updatePinDisplay()
                    binding.tvTitle.text = "Enter PIN"
                    binding.tvWelcome.text = "Welcome, $userFullName"
                    binding.tvWelcome.visibility = View.VISIBLE
                    binding.btnChangePrisoner.visibility = View.VISIBLE
                    binding.tvWelcome.alpha = 0f
                    binding.tvWelcome.animate().alpha(1f).setDuration(350).start()
                } else {
                    Toast.makeText(context, "Invalid Prisoner ID", Toast.LENGTH_SHORT).show()
                    enteredPin = ""
                    updatePinDisplay()
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(context, "Connection Error", Toast.LENGTH_SHORT).show()
                enteredPin = ""
                updatePinDisplay()
            }
        }
    }

    private fun updatePinDisplay() {
        val pinBoxes = listOf(
            binding.tvPinBox1,
            binding.tvPinBox2,
            binding.tvPinBox3,
            binding.tvPinBox4,
            binding.tvPinBox5,
            binding.tvPinBox6
        )

        for (i in pinBoxes.indices) {
            if (i < enteredPin.length) {
                pinBoxes[i].text = "●"
                pinBoxes[i].isPressed = true
            } else {
                pinBoxes[i].text = ""
                pinBoxes[i].isPressed = false
            }
        }
    }

    private fun verifyPin(pin: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.glButtons.visibility = View.INVISIBLE

        val pinHash = hashSha256(pin)

        // If we have the server hash from the ID step, compare locally
        if (serverPinHash != null) {
            if (pinHash == serverPinHash) {
                val uid = verifiedUserId
                if (uid == null) {
                    binding.progressBar.visibility = View.GONE
                    binding.glButtons.visibility = View.VISIBLE
                    Toast.makeText(context, "Internal error", Toast.LENGTH_SHORT).show()
                    return
                }

                lifecycleScope.launch {
                    try {
                        val user: User? = DbService.getDocument(table = "users", id = uid)
                        binding.progressBar.visibility = View.GONE
                        binding.glButtons.visibility = View.VISIBLE

                        if (user != null && user.account_status == "active") {
                            val sharedPref = requireActivity().getSharedPreferences("PrisonPrefs", Context.MODE_PRIVATE)
                            sharedPref.edit().putString("logged_in_user", uid).apply()

                            val fragment = DashboardFragment().apply {
                                arguments = Bundle().apply { putString("user_id", uid) }
                            }
                            (activity as? KioskMainActivity)?.navigateToFragment(fragment)
                        } else {
                            Toast.makeText(context, "Account inactive", Toast.LENGTH_SHORT).show()
                            enteredPin = ""
                            updatePinDisplay()
                        }
                    } catch (e: Exception) {
                        binding.progressBar.visibility = View.GONE
                        binding.glButtons.visibility = View.VISIBLE
                        Toast.makeText(context, "Connection Error", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                binding.progressBar.visibility = View.GONE
                binding.glButtons.visibility = View.VISIBLE
                Toast.makeText(context, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                enteredPin = ""
                updatePinDisplay()
            }
            return
        }

        // Fallback: query by pin_hash
        lifecycleScope.launch {
            try {
                val users: List<User> = DbService.queryDocuments(
                    table = "users",
                    field = "pin_hash",
                    value = pinHash
                )
                binding.progressBar.visibility = View.GONE
                binding.glButtons.visibility = View.VISIBLE

                if (users.isNotEmpty()) {
                    val user = users[0]
                    if (user.account_status == "active") {
                        val uid = user.id
                        val sharedPref = requireActivity().getSharedPreferences("PrisonPrefs", Context.MODE_PRIVATE)
                        sharedPref.edit().putString("logged_in_user", uid).apply()

                        val fragment = DashboardFragment().apply {
                            arguments = Bundle().apply { putString("user_id", uid) }
                        }
                        (activity as? KioskMainActivity)?.navigateToFragment(fragment)
                    } else {
                        Toast.makeText(context, "Account inactive or invalid PIN", Toast.LENGTH_SHORT).show()
                        enteredPin = ""
                        updatePinDisplay()
                    }
                } else {
                    Toast.makeText(context, "Invalid PIN", Toast.LENGTH_SHORT).show()
                    enteredPin = ""
                    updatePinDisplay()
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.glButtons.visibility = View.VISIBLE
                Toast.makeText(context, "Connection Error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hashSha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
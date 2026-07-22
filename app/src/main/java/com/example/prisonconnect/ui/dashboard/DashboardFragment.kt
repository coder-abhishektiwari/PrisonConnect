package com.example.prisonconnect.ui.dashboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.example.prisonconnect.R
import com.example.prisonconnect.databinding.FragmentDashboardBinding
import com.example.prisonconnect.domain.model.Contact
import com.example.prisonconnect.domain.model.User
import com.example.prisonconnect.ui.auth.LoginFragment
import com.example.prisonconnect.ui.call.AudioCallActivity
import com.example.prisonconnect.ui.call.VideoCallActivity
import com.example.prisonconnect.ui.common.KioskMainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import android.widget.ArrayAdapter
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.example.prisonconnect.databinding.DialogDialerBinding
import com.example.prisonconnect.databinding.DialogSmsProviderSelectorBinding
import com.example.prisonconnect.config.SmsConfig
import com.example.prisonconnect.config.SmsMode
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.ContextCompat

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by viewModels()
    private var userId: String? = null
    private lateinit var contactAdapter: ContactAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userId = arguments?.getString("user_id")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        observeViewModel()

        userId?.let { viewModel.loadData(it) }

        binding.btnLogout.setOnClickListener {
            (activity as? KioskMainActivity)?.navigateToFragment(LoginFragment(), false)
        }

        binding.fabDialer.setOnClickListener {
            showDialerDialog()
        }

        binding.btnToggleMessaging.setOnClickListener {
            showSmsProviderSelector()
        }
    }

    private fun setupRecyclerView() {
        contactAdapter = ContactAdapter(emptyList(),
            onAudioCall = { contact -> launchCall(contact.phone_number, "AUDIO", contact.full_name) },
            onVideoCall = { contact -> launchCall(contact.phone_number, "VIDEO", contact.full_name) }
        )
        binding.rvContacts.apply {
            val columns = resources.getInteger(R.integer.contact_columns)
            layoutManager = GridLayoutManager(context, columns)
            adapter = contactAdapter
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.user.collect { user ->
                        user?.let { updateBalanceUI(it) }
                    }
                }
                launch {
                    viewModel.contacts.collect { contacts ->
                        updateContactsUI(contacts)
                    }
                }
                launch {
                    viewModel.isLoading.collect { isLoading ->
                        binding.pbBalance.isIndeterminate = isLoading
                    }
                }
            }
        }
    }

    private fun updateContactsUI(contacts: List<Contact>) {
        if (contacts.isEmpty()) {
            binding.rvContacts.visibility = View.GONE
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.tvEmptyState.text = "No contacts added for you"
        } else {
            binding.tvEmptyState.visibility = View.GONE
            binding.rvContacts.visibility = View.VISIBLE
            contactAdapter.updateContacts(contacts)
        }
    }

    private fun updateBalanceUI(user: User) {
        binding.tvInmateName.text = user.full_name
        binding.tvBalanceValue.text = formatBalance(user.balance_remaining_seconds)

        val maxSeconds = 36000L // 10 hours scale
        binding.pbBalance.max = maxSeconds.toInt()
        binding.pbBalance.progress = user.balance_remaining_seconds.toInt().coerceIn(0, maxSeconds.toInt())
    }

    private fun formatBalance(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%02d:%02d:%02d Hours", h, m, s)
    }

    private fun launchCall(phone: String, type: String, contactName: String = "") {
        val user = viewModel.user.value ?: return
        val intent = if (type == "VIDEO") {
            Intent(requireContext(), VideoCallActivity::class.java)
        } else {
            Intent(requireContext(), AudioCallActivity::class.java)
        }

        intent.apply {
            putExtra("room_id", "ROOM_${System.currentTimeMillis()}")
            putExtra("user_id", user.id)
            putExtra("phone_number", phone)
            putExtra("inmate_name", user.full_name)
            putExtra("jail_name", user.jail_name)
            putExtra("initial_balance", user.balance_remaining_seconds)
            putExtra("contact_name", contactName)
        }
        startActivity(intent)
    }

    private fun showDialerDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val dialogBinding = DialogDialerBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        // ... (rest of the existing dialer code)
    }

    private fun showSmsProviderSelector() {
        val dialogView = DialogSmsProviderSelectorBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.Theme_PrisonConnect_Dialog)
            .setView(dialogView.root)
            .create()

        val sharedPref = requireActivity().getSharedPreferences("PrisonPrefs", Context.MODE_PRIVATE)
        val savedMode = sharedPref.getString("active_sms_provider", SmsConfig.SMS_MODE.name)
        var selectedMode = try { SmsMode.valueOf(savedMode ?: "") } catch(e: Exception) { SmsConfig.SMS_MODE }

        fun updateUi() {
            val highlightColor = ContextCompat.getColor(requireContext(), R.color.primary)
            val normalColor = android.graphics.Color.TRANSPARENT
            
            dialogView.cardTwilio.strokeColor = if (selectedMode == SmsMode.TWILIO) highlightColor else normalColor
            dialogView.cardDevice.strokeColor = if (selectedMode == SmsMode.DEVICE) highlightColor else normalColor
            
            dialogView.cardTwilio.alpha = if (selectedMode == SmsMode.TWILIO) 1.0f else 0.6f
            dialogView.cardDevice.alpha = if (selectedMode == SmsMode.DEVICE) 1.0f else 0.6f
        }

        updateUi()

        dialogView.cardTwilio.setOnClickListener {
            selectedMode = SmsMode.TWILIO
            updateUi()
        }

        dialogView.cardDevice.setOnClickListener {
            selectedMode = SmsMode.DEVICE
            updateUi()
        }

        dialogView.btnSave.setOnClickListener {
            sharedPref.edit().putString("active_sms_provider", selectedMode.name).apply()
            Toast.makeText(context, "SMS provider updated to $selectedMode", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun validatePhone(phone: String?): Boolean {
        return if (phone.isNullOrBlank() || phone.length < 10) {
            Toast.makeText(context, "Please enter a valid 10-digit number", Toast.LENGTH_SHORT).show()
            false
        } else true
    }

    private fun setupListeners() {}

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
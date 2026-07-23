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

        // Initialize SMS icon based on current mode
        updateSmsIcon()

        binding.btnLogout.setOnClickListener {
            (activity as? KioskMainActivity)?.navigateToFragment(LoginFragment(), false)
        }

        binding.fabDialer.setOnClickListener {
            showDialerDialog()
        }

        binding.btnToggleMessaging.setOnClickListener {
            showSmsProviderSelector()
        }

        checkFirstTimeLaunch()
    }

    private fun checkFirstTimeLaunch() {
        val sharedPref = requireActivity().getSharedPreferences("PrisonPrefs", Context.MODE_PRIVATE)
        val isFirstTime = sharedPref.getBoolean("is_first_dashboard_launch", true)

        if (isFirstTime) {
            binding.guideOverlay.visibility = View.VISIBLE
            binding.btnCloseGuide.setOnClickListener {
                binding.guideOverlay.visibility = View.GONE
                sharedPref.edit().putBoolean("is_first_dashboard_launch", false).apply()
            }
        }
    }

    private fun updateSmsIcon() {
        val sharedPref = requireActivity().getSharedPreferences("PrisonPrefs", Context.MODE_PRIVATE)
        val savedMode = sharedPref.getString("active_sms_provider", SmsConfig.SMS_MODE.name)
        val mode = try {
            SmsMode.valueOf(savedMode ?: SmsConfig.SMS_MODE.name)
        } catch (e: Exception) {
            SmsConfig.SMS_MODE
        }

        val (iconRes, iconTint) = when (mode) {
            SmsMode.TWILIO -> R.drawable.ic_cloud_sms to R.color.danger
            SmsMode.DEVICE -> R.drawable.ic_sim_card to R.color.sms
            SmsMode.LOG -> R.drawable.ic_copy_share to R.color.primary
        }

        binding.btnToggleMessaging.setIconResource(iconRes)
        binding.btnToggleMessaging.setIconTintResource(iconTint)
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
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return buildString {
            if (hours > 0) append("$hours hr ")
            if (minutes > 0 || hours > 0) append("$minutes min ")
            append("$secs sec")
        }
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
            putExtra("inmate_name", user.full_name)
            putExtra("phone_number", phone)
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

        val codes = listOf("+91", "+1", "+44", "+971", "+966")
        val adapter = ArrayAdapter(requireContext(), R.layout.dropdown_item, codes)
        dialogBinding.actCountryCode.setAdapter(adapter)

        dialogBinding.btnAudioCall.setOnClickListener {
            val phone = dialogBinding.etPhoneNumber.text?.toString()
            val code = dialogBinding.actCountryCode.text.toString()
            if (validatePhone(phone)) {
                dialog.dismiss()
                launchCall("$code$phone", "AUDIO")
            }
        }

        dialogBinding.btnVideoCall.setOnClickListener {
            val phone = dialogBinding.etPhoneNumber.text?.toString()
            val code = dialogBinding.actCountryCode.text.toString()
            if (validatePhone(phone)) {
                dialog.dismiss()
                launchCall("$code$phone", "VIDEO")
            }
        }
        dialog.show()
    }

    private fun showSmsProviderSelector() {
        val dialogBinding = DialogSmsProviderSelectorBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.Theme_SmsProvider_Dialog)
            .setView(dialogBinding.root)
            .create()

        val sharedPref = requireActivity().getSharedPreferences("PrisonPrefs", Context.MODE_PRIVATE)
        val savedMode = sharedPref.getString("active_sms_provider", SmsConfig.SMS_MODE.name)
        var selectedMode = try {
            SmsMode.valueOf(savedMode ?: SmsConfig.SMS_MODE.name)
        } catch (e: Exception) {
            SmsConfig.SMS_MODE
        }

        fun updateUi() {
            val highlightColor = ContextCompat.getColor(requireContext(), R.color.brand_primary)
            val normalColor = android.graphics.Color.TRANSPARENT
            
            dialogBinding.cardTwilio.strokeColor = if (selectedMode == SmsMode.TWILIO) highlightColor else normalColor
            dialogBinding.cardDevice.strokeColor = if (selectedMode == SmsMode.DEVICE) highlightColor else normalColor
            dialogBinding.cardCopy.strokeColor = if (selectedMode == SmsMode.LOG) highlightColor else normalColor
            
            dialogBinding.cardTwilio.alpha = if (selectedMode == SmsMode.TWILIO) 1.0f else 0.6f
            dialogBinding.cardDevice.alpha = if (selectedMode == SmsMode.DEVICE) 1.0f else 0.6f
            dialogBinding.cardCopy.alpha = if (selectedMode == SmsMode.LOG) 1.0f else 0.6f
        }

        updateUi()

        dialogBinding.cardTwilio.setOnClickListener {
            selectedMode = SmsMode.TWILIO
            updateUi()
        }

        dialogBinding.cardDevice.setOnClickListener {
            selectedMode = SmsMode.DEVICE
            updateUi()
        }

        dialogBinding.cardCopy.setOnClickListener {
            selectedMode = SmsMode.LOG
            updateUi()
        }

        dialogBinding.btnSave.setOnClickListener {
            sharedPref.edit().putString("active_sms_provider", selectedMode.name).apply()
            Toast.makeText(context, "SMS provider updated to $selectedMode", Toast.LENGTH_SHORT).show()
            updateSmsIcon()
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
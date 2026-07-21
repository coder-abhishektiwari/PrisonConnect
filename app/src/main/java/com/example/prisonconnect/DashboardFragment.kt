package com.example.prisonconnect

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.prisonconnect.databinding.FragmentDashboardBinding
import com.example.prisonconnect.model.Contact
import com.example.prisonconnect.model.User
import com.example.prisonconnect.repository.DbService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import android.widget.ArrayAdapter
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.example.prisonconnect.databinding.DialogDialerBinding

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private var userId: String? = null
    private var balancePollJob: Job? = null
    private var inmateName: String = "Inmate"
    private var jailName: String = "jail"

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
        fetchInmateData()
        fetchContacts()
        startBalancePolling()

        val binding = _binding
        if (binding != null) {
            // Logout button - just shows PIN screen (kiosk is fixed to one prisoner)
            binding.btnLogout.setOnClickListener {
                balancePollJob?.cancel()
                // Go to LoginFragment which will show PIN screen (not prisoner ID)
                (activity as? KioskMainActivity)?.navigateToFragment(LoginFragment(), false)
            }

            binding.fabDialer.setOnClickListener {
                showDialerDialog()
            }
        }
    }

    private fun showDialerDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val dialogBinding = DialogDialerBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        // Setup Country Code Picker
        val codes = listOf("+91", "+1", "+44", "+971", "+966")
        val adapter = ArrayAdapter(requireContext(), R.layout.dropdown_item, codes)
        dialogBinding.actCountryCode.setAdapter(adapter)

        dialogBinding.btnAudioCall.setOnClickListener {
            val phone = dialogBinding.etPhoneNumber.text?.toString()
            val code = dialogBinding.actCountryCode.text.toString()
            if (validatePhone(phone)) {
                dialog.dismiss()
                navigateToManualCall("$code$phone", "AUDIO")
            }
        }

        dialogBinding.btnVideoCall.setOnClickListener {
            val phone = dialogBinding.etPhoneNumber.text?.toString()
            val code = dialogBinding.actCountryCode.text.toString()
            if (validatePhone(phone)) {
                dialog.dismiss()
                navigateToManualCall("$code$phone", "VIDEO")
            }
        }

        dialog.show()
    }

    private fun validatePhone(phone: String?): Boolean {
        return if (phone.isNullOrBlank() || phone.length < 10) {
            Toast.makeText(context, "Please enter a valid 10-digit number", Toast.LENGTH_SHORT).show()
            false
        } else true
    }

    private fun navigateToManualCall(fullPhone: String, type: String) {
        val bundle = Bundle().apply {
            putString("call_type", type)
            putString("user_id", userId)
            putString("phone_number", fullPhone)
            putString("inmate_name", inmateName)
            putString("jail_name", jailName)
        }
        val fragment = CallRoomFragment().apply {
            arguments = bundle
        }
        (activity as? KioskMainActivity)?.navigateToFragment(fragment)
    }

    private fun setupRecyclerView() {
        val binding = _binding ?: return
        contactAdapter = ContactAdapter(emptyList(),
            onAudioCall = { contact -> navigateToCall(contact, "AUDIO") },
            onVideoCall = { contact -> navigateToCall(contact, "VIDEO") }
        )
        binding.rvContacts.apply {
            val columns = resources.getInteger(R.integer.contact_columns)
            layoutManager = GridLayoutManager(context, columns)
            adapter = contactAdapter
        }
    }

    private fun setupListeners() {
        // No-op: polling replaces snapshot listener
    }

    private fun startBalancePolling() {
        val id = userId ?: return
        balancePollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                try {
                    val user: User? = DbService.getDocument(table = "users", id = id)
                    if (user != null && _binding != null) {
                        updateBalanceUI(user)
                    }
                } catch (_: Exception) {
                    // Silently retry on next poll
                }
                delay(3000L) // Poll every 3 seconds
            }
        }
    }

    private fun fetchInmateData() {
        val binding = _binding ?: return
        val currentBinding = binding
        userId?.let { id ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val user: User? = DbService.getDocument(table = "users", id = id)
                    if (_binding != null && user != null) {
                        inmateName = user.full_name
                        jailName = user.jail_name
                        currentBinding.tvInmateName.text = user.full_name
                    }
                } catch (_: Exception) {
                    if (_binding != null) {
                        currentBinding.tvInmateName.text = "Inmate"
                    }
                }
            }
        }
    }

    private fun fetchContacts() {
        val binding = _binding
        if (binding == null) return
        
        userId?.let { id ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val contacts: List<Contact> = DbService.queryDocuments(
                        table = "contacts",
                        field = "associated_inmate_id",
                        value = id
                    )

                    if (contacts.isEmpty()) {
                        binding.rvContacts.visibility = View.GONE
                        binding.tvEmptyState.visibility = View.VISIBLE
                        binding.tvEmptyState.text = "No contacts added for you"
                    } else {
                        binding.tvEmptyState.visibility = View.GONE
                        binding.rvContacts.visibility = View.VISIBLE
                        contactAdapter.updateContacts(contacts)
                    }
                } catch (e: Exception) {
                    if (_binding != null) {
                        binding.rvContacts.visibility = View.GONE
                        binding.tvEmptyState.visibility = View.VISIBLE
                        binding.tvEmptyState.text = "Failed to load contacts. Please try again."
                    }
                }
            }
        } ?: run {
            if (_binding != null) {
                binding.rvContacts.visibility = View.GONE
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.tvEmptyState.text = "No contacts added for you"
            }
        }
    }

    private fun updateBalanceUI(user: User) {
        val binding = _binding ?: return
        val seconds = user.balance_remaining_seconds
        binding.tvBalanceValue.text = formatBalance(seconds)

        // Progress bar: Use a larger scale, e.g., 24 hours (36000 seconds)
        // or dynamic based on initial balance. For now, 10 hours.
        val maxSeconds = 36000L
        binding.pbBalance.max = maxSeconds.toInt()
        binding.pbBalance.progress = seconds.toInt().coerceIn(0, maxSeconds.toInt())
        
        Log.d("Dashboard abhishek", "Balance UI updated: $seconds seconds")
    }

    private fun formatBalance(seconds: Long): String {
        val remainingSeconds = seconds % 60
        val totalMinutes = seconds / 60
        val remainingMinutes = totalMinutes % 60
        val hours = totalMinutes / 60

        return String.format("%02d:%02d:%02d Hours", hours, remainingMinutes, remainingSeconds)
    }

    private fun navigateToCall(contact: Contact, type: String) {
        if (userId == null) {
            Toast.makeText(context, "Error: User ID not found", Toast.LENGTH_SHORT).show()
            return
        }
        
        val bundle = Bundle().apply {
            putString("contact_id", contact.contact_id)
            putString("call_type", type)
            putString("user_id", userId)
            putString("phone_number", contact.phone_number)
            putString("inmate_name", inmateName) // Prisoner Name from users table
            putString("jail_name", jailName)     // Jail Name from users table
        }
        val fragment = CallRoomFragment().apply {
            arguments = bundle
        }
        (activity as? KioskMainActivity)?.navigateToFragment(fragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        balancePollJob?.cancel()
        _binding = null
    }
}
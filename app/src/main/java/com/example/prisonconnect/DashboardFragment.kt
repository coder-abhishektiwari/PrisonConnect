package com.example.prisonconnect

import android.os.Bundle
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

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private var userId: String? = null
    private var balancePollJob: Job? = null

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

        binding.btnLogout.setOnClickListener {
            balancePollJob?.cancel()
            (activity as? KioskMainActivity)?.navigateToFragment(LoginFragment(), false)
        }
    }

    private fun setupRecyclerView() {
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
        balancePollJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    val user: User? = DbService.getDocument(table = "users", id = id)
                    if (user != null) {
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
        userId?.let { id ->
            lifecycleScope.launch {
                try {
                    val user: User? = DbService.getDocument(table = "users", id = id)
                    binding.tvInmateName.text = user?.full_name ?: "Inmate"
                } catch (_: Exception) {
                    binding.tvInmateName.text = "Inmate"
                }
            }
        }
    }

    private fun fetchContacts() {
        userId?.let { id ->
            lifecycleScope.launch {
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
                    binding.rvContacts.visibility = View.GONE
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.tvEmptyState.text = "Failed to load contacts. Please try again."
                }
            }
        } ?: run {
            binding.rvContacts.visibility = View.GONE
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.tvEmptyState.text = "No contacts added for you"
        }
    }

    private fun updateBalanceUI(user: User) {
        val seconds = user.balance_remaining_seconds
        binding.tvBalanceValue.text = formatBalance(seconds)

        // Progress bar: assume 30 mins max (1800 seconds)
        val maxSeconds = 1800L
        binding.pbBalance.max = maxSeconds.toInt()
        binding.pbBalance.progress = seconds.toInt().coerceAtMost(maxSeconds.toInt())
    }

    private fun formatBalance(seconds: Long): String {
        val remainingSeconds = seconds % 60
        val totalMinutes = seconds / 60
        val remainingMinutes = totalMinutes % 60
        val hours = totalMinutes / 60

        return String.format("%02d:%02d:%02d Hours", hours, remainingMinutes, remainingSeconds)
    }

    private fun navigateToCall(contact: Contact, type: String) {
        val bundle = Bundle().apply {
            putString("contact_id", contact.contact_id)
            putString("call_type", type)
            putString("user_id", userId)
            putString("phone_number", contact.phone_number)
            putString("inmate_name", contact.full_name ?: "Inmate")
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
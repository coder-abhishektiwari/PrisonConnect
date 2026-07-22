package com.example.prisonconnect.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.prisonconnect.domain.model.Contact
import com.example.prisonconnect.domain.model.User
import com.example.prisonconnect.domain.repository.ContactRepository
import com.example.prisonconnect.data.repository.SupabaseContactRepository
import com.example.prisonconnect.domain.repository.UserRepository
import com.example.prisonconnect.data.repository.SupabaseUserRepository
import com.example.prisonconnect.util.Logger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for the Dashboard screen.
 *
 * Manages:
 * - User data observation (name, balance)
 * - Contacts list observation
 * - Loading state
 */
class DashboardViewModel : ViewModel() {

    private val logger = Logger("DashboardViewModel")

    private val userRepository: UserRepository = SupabaseUserRepository()
    private val contactRepository: ContactRepository = SupabaseContactRepository()

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Loads user data and contacts for the given user ID.
     * Observes both streams concurrently.
     *
     * @param userId The user's UUID
     */
    fun loadData(userId: String) {
        _isLoading.value = true
        logger.d("Loading data for user: $userId")
        viewModelScope.launch {
            launch {
                userRepository.observeUser(userId)
                    .onEach { userData ->
                        _user.value = userData
                    }
                    .catch { throwable ->
                        logger.e("Error observing user", throwable)
                    }
                    .collect()
            }
            launch {
                contactRepository.observeContacts(userId)
                    .onEach { contactList ->
                        _contacts.value = contactList
                        _isLoading.value = false
                    }
                    .catch { throwable ->
                        logger.e("Error observing contacts", throwable)
                        _isLoading.value = false
                    }
                    .collect()
            }
        }
    }
}
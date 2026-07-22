package com.example.prisonconnect.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.prisonconnect.R
import com.example.prisonconnect.databinding.ItemContactBinding
import com.example.prisonconnect.domain.model.Contact

/**
 * RecyclerView adapter for displaying inmate contacts.
 *
 * Each contact shows name, phone number, and relationship type.
 * Audio and video call buttons trigger the provided callbacks,
 * but are blocked for FACILITY_EMERGENCY contacts.
 *
 * @property contacts The list of contacts to display
 * @property onAudioCall Callback invoked when audio call button is pressed
 * @property onVideoCall Callback invoked when video call button is pressed
 */
class ContactAdapter(
    private var contacts: List<Contact>,
    private val onAudioCall: (Contact) -> Unit,
    private val onVideoCall: (Contact) -> Unit
) : RecyclerView.Adapter<ContactAdapter.ContactViewHolder>() {

    companion object {
        private const val BLOCKED_RELATIONSHIP = "FACILITY_EMERGENCY"
        private const val CALL_BLOCKED_MESSAGE = "[ CALL BLOCKED ]"
    }

    class ContactViewHolder(val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val binding = ItemContactBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ContactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = contacts[position]
        holder.binding.apply {
            tvContactName.text = contact.full_name
            tvPhoneNumber.text = contact.phone_number
            tvRelationship.text = contact.relationship_type

            ivAudioCall.setOnClickListener {
                if (isCallBlocked(contact)) {
                    Toast.makeText(
                        holder.itemView.context,
                        CALL_BLOCKED_MESSAGE,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    onAudioCall(contact)
                }
            }

            ivVideoCall.setOnClickListener {
                if (isCallBlocked(contact)) {
                    Toast.makeText(
                        holder.itemView.context,
                        CALL_BLOCKED_MESSAGE,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    onVideoCall(contact)
                }
            }
        }
    }

    override fun getItemCount(): Int = contacts.size

    /**
     * Updates the contact list and refreshes the display.
     *
     * @param newContacts The new list of contacts to display
     */
    fun updateContacts(newContacts: List<Contact>) {
        contacts = newContacts
        notifyDataSetChanged()
    }

    /**
     * Checks if calls to this contact are blocked.
     *
     * @param contact The contact to check
     * @return true if the contact is a facility emergency contact
     */
    private fun isCallBlocked(contact: Contact): Boolean {
        return contact.relationship_type == BLOCKED_RELATIONSHIP
    }
}
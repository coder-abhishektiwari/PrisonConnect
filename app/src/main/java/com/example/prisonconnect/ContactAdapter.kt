package com.example.prisonconnect

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.prisonconnect.databinding.ItemContactBinding
import com.example.prisonconnect.model.Contact

class ContactAdapter(
    private var contacts: List<Contact>,
    private val onAudioCall: (Contact) -> Unit,
    private val onVideoCall: (Contact) -> Unit
) : RecyclerView.Adapter<ContactAdapter.ContactViewHolder>() {

    class ContactViewHolder(val binding: ItemContactBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val binding = ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ContactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = contacts[position]
        holder.binding.apply {
            tvContactName.text = contact.full_name
            tvPhoneNumber.text = contact.phone_number
            tvRelationship.text = contact.relationship_type

            ivAudioCall.setOnClickListener {
                if (contact.relationship_type == "FACILITY_EMERGENCY") {
                    Toast.makeText(holder.itemView.context, "[ CALL BLOCKED ]", Toast.LENGTH_SHORT).show()
                } else {
                    onAudioCall(contact)
                }
            }

            ivVideoCall.setOnClickListener {
                if (contact.relationship_type == "FACILITY_EMERGENCY") {
                    Toast.makeText(holder.itemView.context, "[ CALL BLOCKED ]", Toast.LENGTH_SHORT).show()
                } else {
                    onVideoCall(contact)
                }
            }
        }
    }

    override fun getItemCount(): Int = contacts.size

    fun updateContacts(newContacts: List<Contact>) {
        contacts = newContacts
        notifyDataSetChanged()
    }
}

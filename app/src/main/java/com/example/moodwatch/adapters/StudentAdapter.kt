package com.example.moodwatch.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.moodwatch.R
import com.example.moodwatch.student.Student
import com.google.android.material.card.MaterialCardView

class StudentAdapter(
    private val onItemClick: (Student) -> Unit
) : ListAdapter<Student, StudentAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_student, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.bind(item) { onItemClick(item) }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.name)
        private val subtitle: TextView = itemView.findViewById(R.id.subtitle)
        private val card: MaterialCardView = itemView as MaterialCardView

        fun bind(item: Student, onRowClick: () -> Unit) {
            name.text = item.stud_name.ifBlank { item.stud_id }
            if (item.stud_bday.isNotBlank()) {
                subtitle.visibility = View.VISIBLE
                subtitle.text = "Birthday: ${item.stud_bday}"
            } else {
                subtitle.visibility = View.GONE
                subtitle.text = ""
            }
            card.setOnClickListener { onRowClick() }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Student>() {
        override fun areItemsTheSame(oldItem: Student, newItem: Student) =
            oldItem.stud_id == newItem.stud_id

        override fun areContentsTheSame(oldItem: Student, newItem: Student) =
            oldItem == newItem
    }
}

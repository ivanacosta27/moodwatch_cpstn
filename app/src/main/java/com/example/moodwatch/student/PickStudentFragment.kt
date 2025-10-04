package com.example.moodwatch.student

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import com.example.moodwatch.R
import com.example.moodwatch.SessionActivity
import com.example.moodwatch.adapters.StudentAdapter
import com.example.moodwatch.databinding.FragmentPickStudentBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class PickStudentFragment : Fragment() {

    private var _binding: FragmentPickStudentBinding? = null
    private val binding get() = _binding!!

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private lateinit var colRef: CollectionReference
    private var registration: ListenerRegistration? = null

    private lateinit var adapter: StudentAdapter
    private var fullList: List<Student> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // init Firestore collection only if logged in
        auth.currentUser?.uid?.let { uid ->
            colRef = FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("student") // users/{uid}/student
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPickStudentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // If user is not logged in, show message and disable actions
        if (auth.currentUser == null) {
            binding.progress.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
            binding.emptyView.text = "Please sign in to view your students."
            binding.btnAddStudent.isEnabled = false
            return
        }

        adapter = StudentAdapter(
            onItemClick = { student ->
                showStartSessionDialog(student)
            }
        )

        with(binding.recycler) {
            adapter = this@PickStudentFragment.adapter
            addItemDecoration(DividerItemDecoration(requireContext(), RecyclerView.VERTICAL))
            setHasFixedSize(true)
        }

        // Search by name or id
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                filterList(query.orEmpty()); return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                filterList(newText.orEmpty()); return true
            }
        })

        // Navigate to add-student screen
        binding.btnAddStudent.setOnClickListener {
            parentFragmentManager
                .beginTransaction()
                .replace(R.id.fragment_container, AddNewStudentFragment())
                .addToBackStack(null)
                .commit()
        }

        fetchStudents()
    }

    private fun filterList(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) fullList
        else fullList.filter {
            it.stud_name.lowercase().contains(q) || it.stud_id.lowercase().contains(q)
        }
        adapter.submitList(filtered)
        binding.emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showStartSessionDialog(student: Student) {
        val bdayLine = if (student.stud_bday.isNotBlank()) "\nBirthday: ${student.stud_bday}" else ""
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Start session")
            .setMessage("Start session with ${student.stud_name}?$bdayLine")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start") { _, _ ->
                val intent = Intent(requireContext(), SessionActivity::class.java).apply {
                    putExtra("stud_id", student.stud_id)
                    putExtra("stud_name", student.stud_name)
                    putExtra("stud_bday", student.stud_bday)
                }
                startActivity(intent)
            }
            .show()
    }

    private fun fetchStudents() {
        showLoading(true)

        if (!::colRef.isInitialized) {
            showLoading(false)
            binding.emptyView.visibility = View.VISIBLE
            binding.emptyView.text = "Please sign in to view your students."
            return
        }

        // Remove any old listener to avoid duplicates on re-entry
        registration?.remove()

        // Prefer server-side ordering
        registration = colRef.orderBy("stud_name")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    showLoading(false)
                    binding.emptyView.visibility = View.VISIBLE
                    binding.emptyView.text = "Failed to load students: ${e.message}"
                    return@addSnapshotListener
                }

                if (snapshots == null) {
                    showLoading(false)
                    binding.emptyView.visibility = View.VISIBLE
                    binding.emptyView.text = "No students yet"
                    return@addSnapshotListener
                }

                val items = snapshots.documents.mapNotNull { doc ->
                    val studName = doc.getString("stud_name") ?: ""
                    val studBday = doc.getString("stud_bday") ?: ""
                    val studId = doc.getString("stud_id") ?: doc.id
                    if (studName.isBlank() && studId.isBlank()) null
                    else Student(
                        stud_id = studId,
                        stud_name = studName.ifBlank { studId },
                        stud_bday = studBday
                    )
                }

                fullList = items
                adapter.submitList(items)
                showLoading(false)
                binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
    }

    private fun showLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        registration?.remove()
        _binding = null
    }
}

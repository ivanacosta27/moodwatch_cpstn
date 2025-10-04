package com.example.moodwatch.student

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.moodwatch.databinding.FragmentAddNewStudentBinding
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class AddNewStudentFragment : Fragment() {

    private var _binding: FragmentAddNewStudentBinding? = null
    private val binding get() = _binding!!

    private val auth by lazy { FirebaseAuth.getInstance() }
    private lateinit var studentsCol: CollectionReference
    private lateinit var sessionsCol: CollectionReference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth.currentUser?.uid?.let { uid ->
            val db = FirebaseFirestore.getInstance()
            studentsCol = db.collection("users").document(uid).collection("student")
            sessionsCol = db.collection("users").document(uid).collection("sessions")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddNewStudentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (auth.currentUser == null || !::studentsCol.isInitialized) {
            Toast.makeText(requireContext(), "Please sign in first.", Toast.LENGTH_LONG).show()
            return
        }

        // Date picker
        binding.tilStudBday.setEndIconOnClickListener { showDatePicker() }
        binding.etStudBday.setOnClickListener { showDatePicker() }

        // Add-only → save then go back to PickStudentFragment
        binding.btnAdd.setOnClickListener {
            saveStudent(startSessionAfterSave = false, navigateBackAfterSave = true)
        }
    }

    /** Saves the student; optional: start session and/or navigate back after save. */
    private fun saveStudent(
        startSessionAfterSave: Boolean,
        navigateBackAfterSave: Boolean
    ) {
        // clear errors
        binding.tilStudName.error = null
        binding.tilStudId.error = null

        val inputId = binding.etStudId.text?.toString()?.trim().orEmpty()
        val name = binding.etStudName.text?.toString()?.trim().orEmpty()
        val bday = binding.etStudBday.text?.toString()?.trim().orEmpty()

        if (name.isEmpty()) {
            binding.tilStudName.error = "Student name is required"
            return
        }

        setLoading(true)

        val docRef = if (inputId.isBlank()) studentsCol.document() else studentsCol.document(inputId)
        val resolvedId = inputId.ifBlank { docRef.id }

        val studentData = mapOf(
            "stud_id" to resolvedId,
            "stud_name" to name,
            "stud_bday" to bday
        )

        docRef.set(studentData)
            .addOnSuccessListener {
                if (startSessionAfterSave) {
                    // create a session record, then (optionally) navigate
                    createSession(resolvedId, name) {
                        setLoading(false)
                        Toast.makeText(requireContext(), "Session started with $name", Toast.LENGTH_SHORT).show()
                        // if you want to go back after starting, flip navigateBackAfterSave = true above
                    }
                } else {
                    setLoading(false)
                    Toast.makeText(requireContext(), "Student added", Toast.LENGTH_SHORT).show()
                    // ✅ Go back to PickStudentFragment
                    findNavController().popBackStack()
                }
            }
            .addOnFailureListener { e ->
                setLoading(false)
                if (inputId.isNotBlank()) binding.tilStudId.error = e.localizedMessage
                Toast.makeText(requireContext(), "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    /** Writes a simple session doc under users/{uid}/sessions/{autoId}. */
    private fun createSession(studId: String, studName: String, onDone: () -> Unit) {
        val doc = sessionsCol.document()
        val data = mapOf(
            "session_id" to doc.id,
            "stud_id" to studId,
            "stud_name" to studName,
            "started_at" to FieldValue.serverTimestamp(),
            "status" to "active"
        )
        doc.set(data)
            .addOnSuccessListener { onDone() }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Failed to start session: ${e.message}", Toast.LENGTH_LONG).show()
                onDone()
            }
    }

    private fun showDatePicker() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Select birthday")
            .build()
        picker.addOnPositiveButtonClickListener { utcMillis ->
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = utcMillis
            }
            binding.etStudBday.setText(sdf.format(cal.time))
        }
        picker.show(parentFragmentManager, "bday_picker")
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnAdd.isEnabled = !loading
        binding.etStudId.isEnabled = !loading
        binding.etStudName.isEnabled = !loading
        binding.etStudBday.isEnabled = !loading
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

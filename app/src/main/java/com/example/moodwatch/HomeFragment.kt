package com.example.moodwatch

import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.fragment.app.Fragment
import com.example.moodwatch.student.PickStudentFragment

class HomeFragment : Fragment(R.layout.fragment_home) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Button>(R.id.btnStartSession).setOnClickListener {
            parentFragmentManager
                .beginTransaction()
                .replace(R.id.fragment_container, PickStudentFragment())
                .addToBackStack(null)
                .commit()
        }
    }
}

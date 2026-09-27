package com.timepass.financialapp.ui.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.timepass.financialapp.BuildConfig
import com.timepass.financialapp.R
import com.timepass.financialapp.databinding.FragmentAboutBinding

/** Version and identity of this build. */
class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        binding.tvPackage.text = BuildConfig.APPLICATION_ID
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

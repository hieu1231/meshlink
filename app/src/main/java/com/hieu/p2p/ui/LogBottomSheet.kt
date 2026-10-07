package com.hieu.p2p.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.hieu.p2p.R
import com.hieu.p2p.databinding.BottomSheetLogBinding

/** Live protocol log: discoveries, route changes, sync rounds, rejected packets. */
class LogBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLogBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.collectWhileStarted(mesh.node.log) { lines ->
            binding.textLog.text = if (lines.isEmpty()) {
                getString(R.string.log_empty)
            } else {
                lines.joinToString("\n") { "${Formats.logTime(it.time)}  ${it.message}" }
            }
            binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/*
 * SPDX-FileCopyrightText: 2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chiller3.basicsync.R
import com.chiller3.basicsync.databinding.DialogFolderPickerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File

class FolderPickerDialogFragment : DialogFragment(), FolderPickerAdapter.Listener {
    companion object {
        val TAG: String = FolderPickerDialogFragment::class.java.simpleName

        private val TAG_NEW_FOLDER = "$TAG.new_folder"

        private const val ARG_PATH = "path"
        const val RESULT_SUCCESS = "success"
        const val RESULT_PATH = "path"

        fun newInstance(path: String?): FolderPickerDialogFragment =
            FolderPickerDialogFragment().apply {
                arguments = bundleOf(ARG_PATH to path)
            }
    }

    private var success: Boolean = false
    private val viewModel: FolderPickerViewModel by viewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val arguments = requireArguments()
        val context = requireContext()

        if (savedInstanceState == null) {
            val initialPath = arguments.getString(ARG_PATH)?.let { File(it) }
            viewModel.navigate(initialPath ?: FolderPickerViewModel.EXTERNAL_DIR)
        }

        val binding = DialogFolderPickerBinding.inflate(layoutInflater)

        val adapter = FolderPickerAdapter(this)
        binding.list.adapter = adapter
        binding.list.layoutManager = LinearLayoutManager(activity).apply {
            orientation = RecyclerView.VERTICAL
        }

        // Don't lose user state unless the user cancels intentionally.
        isCancelable = false

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                success = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.dialog_new_folder_title, null)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)

                // Set click handler manually because doing it via the alert dialog builder forces
                // the button to always dismiss the dialog.
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        NewFolderDialogFragment.newInstance(
                            context,
                            viewModel.state.value.shortCwd.toString(),
                        ).show(parentFragmentManager.beginTransaction(), TAG_NEW_FOLDER)
                    }
                }
            }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.state.collect {
                    dialog.setTitle(it.shortCwd.toString())

                    adapter.names = it.childDirs
                }
            }
        }

        setFragmentResultListener(TAG_NEW_FOLDER) { _, bundle: Bundle ->
            if (bundle.getBoolean(NewFolderDialogFragment.RESULT_SUCCESS)) {
                val name = bundle.getString(NewFolderDialogFragment.RESULT_INPUT)!!

                viewModel.mkdir(File(name))
            }
        }

        return dialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        setFragmentResult(tag!!, bundleOf(
            RESULT_SUCCESS to success,
            RESULT_PATH to viewModel.state.value.shortCwd.toString(),
        ))
    }

    override fun onNameSelected(position: Int, name: String) {
        viewModel.navigate(File(name))
    }
}

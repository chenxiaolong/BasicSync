/*
 * SPDX-FileCopyrightText: 2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.dialog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.chiller3.basicsync.databinding.DialogFolderPickerItemBinding

internal class FolderPickerAdapter(
    private val listener: Listener,
) : RecyclerView.Adapter<FolderPickerAdapter.CustomViewHolder?>() {
    interface Listener {
        fun onNameSelected(position: Int, name: String)
    }

    internal inner class CustomViewHolder(private val binding: DialogFolderPickerItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        var text: CharSequence
            get() = binding.root.text
            set(text) {
                binding.root.text = text
            }

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                listener.onNameSelected(position, names[position])
            }
        }
    }

    var names: List<String> = arrayListOf()
        set(newNames) {
            val diff = DiffUtil.calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize(): Int = field.size

                    override fun getNewListSize(): Int = newNames.size

                    override fun areItemsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int,
                    ): Boolean = areContentsTheSame(oldItemPosition, newItemPosition)

                    override fun areContentsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int,
                    ): Boolean = field[oldItemPosition] == newNames[newItemPosition]
                },
                true,
            )

            field = newNames

            diff.dispatchUpdatesTo(this)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CustomViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = DialogFolderPickerItemBinding.inflate(inflater, parent, false)

        return CustomViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CustomViewHolder, position: Int) {
        holder.text = names[position] + '/'
    }

    override fun getItemCount(): Int = names.size
}

package com.filecleaner.app.ui.messaging

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.filecleaner.app.R
import com.filecleaner.app.databinding.FragmentMessagingCleanerBinding
import com.filecleaner.app.databinding.ItemMessagingGroupBinding
import com.filecleaner.app.utils.MessagingMediaCleaner
import com.filecleaner.app.utils.UndoHelper
import com.filecleaner.app.viewmodel.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen for reviewing and cleaning media from messaging apps
 * (WhatsApp, Telegram, Signal, Viber).
 *
 * Shows grouped media categories sorted by size with one-tap cleanup.
 */
class MessagingCleanerFragment : Fragment() {

    private var _binding: FragmentMessagingCleanerBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    private var groups = listOf<MessagingMediaCleaner.MessagingMediaGroup>()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentMessagingCleanerBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.recyclerGroups.layoutManager = LinearLayoutManager(requireContext())

        loadGroups()
    }

    private fun loadGroups() {
        binding.progress.visibility = View.VISIBLE
        binding.recyclerGroups.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            groups = withContext(Dispatchers.IO) {
                MessagingMediaCleaner.scan()
            }

            if (_binding == null) return@launch
            binding.progress.visibility = View.GONE

            if (groups.isEmpty()) {
                binding.emptyState.visibility = View.VISIBLE
                binding.cardSummary.visibility = View.GONE
                return@launch
            }

            // Summary
            val totalSize = groups.sumOf { it.totalSize }
            val totalFiles = groups.sumOf { it.files.size }
            binding.tvSummary.text = getString(R.string.messaging_summary,
                UndoHelper.formatBytes(totalSize))
            binding.tvSummaryDetail.text = resources.getQuantityString(
                R.plurals.messaging_summary_detail, totalFiles, totalFiles,
                groups.map { it.appName }.distinct().size)
            binding.cardSummary.visibility = View.VISIBLE

            // List
            binding.recyclerGroups.visibility = View.VISIBLE
            binding.recyclerGroups.adapter = GroupAdapter(groups) { group ->
                showCleanConfirmDialog(group)
            }
        }
    }

    private fun showCleanConfirmDialog(group: MessagingMediaCleaner.MessagingMediaGroup) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.messaging_clean_title))
            .setMessage(getString(R.string.messaging_clean_confirm,
                group.files.size, group.appName, group.category,
                UndoHelper.formatBytes(group.totalSize)))
            .setPositiveButton(getString(R.string.ctx_delete)) { _, _ ->
                vm.deleteFiles(group.files)
                loadGroups() // Refresh
                Snackbar.make(binding.root,
                    getString(R.string.messaging_cleaned, UndoHelper.formatBytes(group.totalSize)),
                    Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Adapter ──

    private class GroupAdapter(
        private val groups: List<MessagingMediaCleaner.MessagingMediaGroup>,
        private val onClick: (MessagingMediaCleaner.MessagingMediaGroup) -> Unit
    ) : RecyclerView.Adapter<GroupAdapter.VH>() {

        class VH(val binding: ItemMessagingGroupBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemMessagingGroupBinding.inflate(
                LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val group = groups[position]
            val ctx = holder.itemView.context
            holder.binding.tvGroupTitle.text = ctx.getString(
                R.string.messaging_media_group, group.appName, group.category)
            holder.binding.tvGroupDetail.text = ctx.resources.getQuantityString(
                R.plurals.n_files, group.files.size, group.files.size)
            holder.binding.tvGroupSize.text = UndoHelper.formatBytes(group.totalSize)

            holder.binding.ivAppIcon.setImageResource(R.drawable.ic_messaging)

            holder.itemView.setOnClickListener { onClick(group) }
        }

        override fun getItemCount() = groups.size
    }
}

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
import com.filecleaner.app.R
import com.filecleaner.app.databinding.FragmentMessagingCleanerBinding
import com.filecleaner.app.databinding.ItemMessagingGroupBinding
import com.filecleaner.app.ui.common.SimpleListAdapter
import com.filecleaner.app.utils.cleanup.MessagingMediaCleaner
import com.filecleaner.app.utils.UndoHelper
import com.filecleaner.app.viewmodel.MainViewModel
import com.filecleaner.app.ui.common.RoundedDialogBuilder
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
                binding.emptyState.root.visibility = View.VISIBLE
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
            binding.recyclerGroups.adapter = SimpleListAdapter<MessagingMediaCleaner.MessagingMediaGroup, ItemMessagingGroupBinding>(
                inflate = { inflater, parent -> ItemMessagingGroupBinding.inflate(inflater, parent, false) },
                bind = { b, group ->
                    val ctx = b.root.context
                    b.tvGroupTitle.text = ctx.getString(R.string.messaging_media_group, group.appName, group.category)
                    b.tvGroupDetail.text = ctx.resources.getQuantityString(R.plurals.n_files, group.files.size, group.files.size)
                    b.tvGroupSize.text = UndoHelper.formatBytes(group.totalSize)
                    b.ivAppIcon.setImageResource(R.drawable.ic_messaging)
                    b.root.contentDescription = "${group.appName} ${group.category}, ${group.files.size} files, ${UndoHelper.formatBytes(group.totalSize)}"
                },
                onClick = { group -> showCleanConfirmDialog(group) }
            ).also { it.submitList(groups) }
        }
    }

    private fun showCleanConfirmDialog(group: MessagingMediaCleaner.MessagingMediaGroup) {
        val ctx = context ?: return
        RoundedDialogBuilder(ctx)
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

}

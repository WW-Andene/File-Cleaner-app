package com.filecleaner.app.ui.trash

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.filecleaner.app.R
import com.filecleaner.app.databinding.FragmentTrashBinding
import com.filecleaner.app.databinding.ItemFileBinding
import com.filecleaner.app.ui.common.SimpleListAdapter
import com.filecleaner.app.utils.TrashManager
import com.filecleaner.app.utils.UndoHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recycle bin browser — lets users view, restore, or permanently delete
 * files that were moved to trash via the undo system.
 */
class TrashFragment : Fragment() {

    private var _binding: FragmentTrashBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentTrashBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.recyclerTrash.layoutManager = LinearLayoutManager(requireContext())

        binding.btnEmptyTrash.setOnClickListener { confirmEmptyTrash() }

        loadTrash()
    }

    private fun loadTrash() {
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { TrashManager.listTrash() }

            if (_binding == null) return@launch

            if (entries.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.recyclerTrash.visibility = View.GONE
                binding.tvSummary.visibility = View.GONE
                binding.btnEmptyTrash.visibility = View.GONE
                return@launch
            }

            binding.tvEmpty.visibility = View.GONE
            binding.recyclerTrash.visibility = View.VISIBLE
            binding.btnEmptyTrash.visibility = View.VISIBLE

            val totalSize = entries.sumOf { it.item.size }
            binding.tvSummary.text = getString(R.string.trash_summary,
                entries.size, UndoHelper.formatBytes(totalSize))

            binding.recyclerTrash.adapter = SimpleListAdapter<TrashManager.TrashEntry, ItemFileBinding>(
                inflate = { inflater, parent -> ItemFileBinding.inflate(inflater, parent, false) },
                bind = { b, entry ->
                    b.tvFileName.text = entry.item.name
                    b.tvFileMeta?.text = "${UndoHelper.formatBytes(entry.item.size)} • ${
                        com.filecleaner.app.utils.DateFormatUtils.formatDateTime(entry.item.lastModified)
                    }"
                    b.ivFileIcon.setImageResource(
                        com.filecleaner.app.ui.adapters.FileItemUtils.iconForCategory(entry.item.category))
                    b.root.contentDescription = "${entry.item.name}, ${UndoHelper.formatBytes(entry.item.size)}"
                },
                onClick = { entry -> showTrashActions(entry) }
            ).also { it.submitList(entries) }
        }
    }

    private fun showTrashActions(entry: TrashManager.TrashEntry) {
        val ctx = context ?: return
        val options = mutableListOf<String>()
        if (entry.originalPath != null) {
            options.add(getString(R.string.trash_restore))
        }
        options.add(getString(R.string.trash_delete_permanent))

        MaterialAlertDialogBuilder(ctx)
            .setTitle(entry.item.name)
            .setItems(options.toTypedArray()) { _, which ->
                val isRestore = entry.originalPath != null && which == 0
                if (isRestore) {
                    val ok = TrashManager.restore(entry.item.path)
                    Snackbar.make(binding.root,
                        if (ok) getString(R.string.trash_restored) else "Restore failed",
                        Snackbar.LENGTH_SHORT).show()
                } else {
                    TrashManager.permanentlyDelete(entry.item.path)
                    Snackbar.make(binding.root,
                        getString(R.string.trash_deleted), Snackbar.LENGTH_SHORT).show()
                }
                loadTrash()
            }
            .show()
    }

    private fun confirmEmptyTrash() {
        val ctx = context ?: return
        val entries = TrashManager.listTrash()
        val totalSize = entries.sumOf { it.item.size }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.trash_empty_all))
            .setMessage(getString(R.string.trash_empty_confirm,
                entries.size, UndoHelper.formatBytes(totalSize)))
            .setPositiveButton(getString(R.string.ctx_delete)) { _, _ ->
                TrashManager.emptyTrash()
                loadTrash()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

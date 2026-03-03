package com.filecleaner.app.ui.optimize

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.filecleaner.app.R
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.databinding.FragmentOptimizeBinding
import com.filecleaner.app.utils.StorageOptimizer
import com.filecleaner.app.viewmodel.MainViewModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class OptimizeFragment : Fragment() {

    private var _binding: FragmentOptimizeBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()
    private var suggestions = listOf<StorageOptimizer.Suggestion>()

    @Suppress("DEPRECATION")
    private val storagePath: String by lazy {
        Environment.getExternalStorageDirectory().absolutePath
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentOptimizeBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        // D3-07: Run analysis off the main thread to prevent jank
        val allFiles = vm.filesByCategory.value?.values?.flatten() ?: emptyList()
        binding.recyclerSuggestions.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            suggestions = withContext(Dispatchers.IO) {
                StorageOptimizer.analyze(allFiles, storagePath)
            }

            if (suggestions.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.recyclerSuggestions.visibility = View.GONE
                binding.btnApply.isEnabled = false
                binding.selectionControls.visibility = View.GONE
            } else {
                binding.tvEmpty.visibility = View.GONE
                binding.recyclerSuggestions.visibility = View.VISIBLE
                binding.selectionControls.visibility = View.VISIBLE
            }

            val accepted = suggestions.count { it.accepted }
            binding.tvSummary.text = getString(R.string.optimize_summary_detail,
                suggestions.size, accepted)

            // Group suggestions by category and build a sectioned list
            val grouped = buildGroupedList(suggestions)
            binding.recyclerSuggestions.adapter = GroupedSuggestionAdapter(
                grouped, storagePath
            ) { updateSummary() }
        }

        binding.btnSelectAll.setOnClickListener {
            suggestions.forEach { it.accepted = true }
            binding.recyclerSuggestions.adapter?.notifyDataSetChanged()
            updateSummary()
        }

        binding.btnDeselectAll.setOnClickListener {
            suggestions.forEach { it.accepted = false }
            binding.recyclerSuggestions.adapter?.notifyDataSetChanged()
            updateSummary()
        }

        binding.btnApply.setOnClickListener { confirmApply() }

        vm.operationResult.observe(viewLifecycleOwner) { result ->
            Snackbar.make(binding.root, result.message, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun updateSummary() {
        val accepted = suggestions.count { it.accepted }
        binding.tvSummary.text = getString(R.string.optimize_summary_detail,
            suggestions.size, accepted)
        binding.btnApply.isEnabled = accepted > 0
    }

    private fun buildGroupedList(items: List<StorageOptimizer.Suggestion>): List<Any> {
        val result = mutableListOf<Any>()
        val grouped = items.groupBy { it.file.category }

        // Sort categories in a user-friendly order
        val order = listOf(
            FileCategory.IMAGE, FileCategory.VIDEO, FileCategory.AUDIO,
            FileCategory.DOCUMENT, FileCategory.APK, FileCategory.DOWNLOAD
        )
        for (cat in order) {
            val group = grouped[cat] ?: continue
            result.add(CategoryHeader(cat, group.size))
            result.addAll(group)
        }
        // Add any remaining categories not in the order list
        for ((cat, group) in grouped) {
            if (cat !in order) {
                result.add(CategoryHeader(cat, group.size))
                result.addAll(group)
            }
        }
        return result
    }

    private fun confirmApply() {
        val accepted = suggestions.filter { it.accepted }
        if (accepted.isEmpty()) {
            Snackbar.make(binding.root, getString(R.string.optimize_none_selected), Snackbar.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.optimize_confirm_title))
            .setMessage(resources.getQuantityString(R.plurals.optimize_confirm_message, accepted.size, accepted.size))
            .setPositiveButton(getString(R.string.move)) { _, _ ->
                for (suggestion in accepted) {
                    val targetDir = File(suggestion.suggestedPath).parent ?: continue
                    File(targetDir).mkdirs()
                    vm.moveFile(suggestion.currentPath, targetDir)
                }
                Snackbar.make(binding.root,
                    resources.getQuantityString(R.plurals.optimize_applied, accepted.size, accepted.size),
                    Snackbar.LENGTH_SHORT).show()
                findNavController().popBackStack()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Category section header data. */
    data class CategoryHeader(val category: FileCategory, val count: Int)

    /** Adapter with category headers and suggestion items. */
    class GroupedSuggestionAdapter(
        private val items: List<Any>,
        private val storagePath: String,
        private val onSelectionChanged: () -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_SUGGESTION = 1
        }

        class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tv_header_title)
            val count: TextView = view.findViewById(R.id.tv_header_count)
        }

        class SuggestionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.cb_accept)
            val filename: TextView = view.findViewById(R.id.tv_filename)
            val reason: TextView = view.findViewById(R.id.tv_reason)
            val movePath: TextView = view.findViewById(R.id.tv_move_path)
        }

        override fun getItemViewType(position: Int) =
            if (items[position] is CategoryHeader) TYPE_HEADER else TYPE_SUGGESTION

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderViewHolder(inflater.inflate(R.layout.item_optimize_header, parent, false))
            } else {
                SuggestionViewHolder(inflater.inflate(R.layout.item_optimize_suggestion, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is CategoryHeader -> {
                    val h = holder as HeaderViewHolder
                    val ctx = h.itemView.context
                    h.title.text = "${item.category.emoji} ${ctx.getString(item.category.displayNameRes)}"
                    h.count.text = ctx.resources.getQuantityString(R.plurals.n_files, item.count, item.count)
                }
                is StorageOptimizer.Suggestion -> {
                    val h = holder as SuggestionViewHolder
                    h.filename.text = item.file.name
                    h.reason.text = item.reason

                    val fromRelative = item.currentPath.removePrefix(storagePath)
                    val toRelative = item.suggestedPath.removePrefix(storagePath)
                    h.movePath.text = "$fromRelative \u2192 $toRelative"

                    h.checkbox.setOnCheckedChangeListener(null)
                    h.checkbox.isChecked = item.accepted
                    h.checkbox.setOnCheckedChangeListener { _, checked ->
                        item.accepted = checked
                        onSelectionChanged()
                    }
                }
            }
        }

        override fun getItemCount() = items.size
    }
}

package com.filecleaner.app.ui.folders

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.filecleaner.app.R
import com.filecleaner.app.databinding.FragmentFolderSizesBinding
import com.filecleaner.app.databinding.ItemFolderSizeBinding
import com.filecleaner.app.ui.common.SimpleListAdapter
import com.filecleaner.app.utils.UndoHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shows all top-level folders sorted by size with visual progress bars.
 * Helps users quickly identify which folders consume the most storage.
 */
class FolderSizeFragment : Fragment() {

    private var _binding: FragmentFolderSizesBinding? = null
    private val binding get() = _binding!!

    data class FolderInfo(val name: String, val path: String, val size: Long)

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentFolderSizesBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.recyclerFolders.layoutManager = LinearLayoutManager(requireContext())

        loadFolders()
    }

    @Suppress("DEPRECATION")
    private fun loadFolders() {
        binding.progress.visibility = View.VISIBLE
        binding.recyclerFolders.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val storage = Environment.getExternalStorageDirectory()
            val folders = withContext(Dispatchers.IO) {
                val dirs = storage.listFiles()?.filter { it.isDirectory } ?: emptyList()
                dirs.map { dir ->
                    FolderInfo(dir.name, dir.absolutePath, calculateSize(dir))
                }.sortedByDescending { it.size }
            }

            if (_binding == null) return@launch
            binding.progress.visibility = View.GONE
            binding.recyclerFolders.visibility = View.VISIBLE

            val maxSize = folders.maxOfOrNull { it.size } ?: 1L

            binding.recyclerFolders.adapter = SimpleListAdapter<FolderInfo, ItemFolderSizeBinding>(
                inflate = { inflater, parent -> ItemFolderSizeBinding.inflate(inflater, parent, false) },
                bind = { b, folder ->
                    b.tvFolderName.text = folder.name
                    b.tvFolderSize.text = UndoHelper.formatBytes(folder.size)
                    b.progressBar.max = 100
                    b.progressBar.progress = if (maxSize > 0) ((folder.size * 100) / maxSize).toInt() else 0
                    b.root.contentDescription = "${folder.name}, ${UndoHelper.formatBytes(folder.size)}"
                }
            ).also { it.submitList(folders) }
        }
    }

    private fun calculateSize(dir: File): Long {
        var size = 0L
        val queue = ArrayDeque<File>()
        queue.add(dir)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isFile) size += child.length()
                else if (child.isDirectory) queue.add(child)
            }
        }
        return size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

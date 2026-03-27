package com.filecleaner.app.ui.similar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.filecleaner.app.R
import com.filecleaner.app.data.FileItem
import com.filecleaner.app.databinding.FragmentSimilarPhotosBinding
import com.filecleaner.app.utils.cleanup.SimilarPhotoDetector
import com.filecleaner.app.utils.UndoHelper
import com.filecleaner.app.viewmodel.MainViewModel
import com.filecleaner.app.ui.common.RoundedDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Visual grid of similar photo groups detected via perceptual hashing.
 * Users can review groups and delete duplicates to free space.
 */
class SimilarPhotosFragment : Fragment() {

    private var _binding: FragmentSimilarPhotosBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    private var groups = listOf<SimilarPhotoDetector.SimilarGroup>()
    private val selectedPaths = mutableSetOf<String>()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSimilarPhotosBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.recyclerPhotos.layoutManager = GridLayoutManager(requireContext(), 3)

        binding.btnDeleteSelected.setOnClickListener {
            if (selectedPaths.isEmpty()) return@setOnClickListener
            val items = groups.flatMap { it.photos }.filter { it.path in selectedPaths }
            RoundedDialogBuilder(requireContext())
                .setTitle(getString(R.string.similar_delete_title))
                .setMessage(getString(R.string.similar_delete_confirm,
                    items.size, UndoHelper.formatBytes(items.sumOf { it.size })))
                .setPositiveButton(getString(R.string.ctx_delete)) { _, _ ->
                    vm.deleteFiles(items)
                    selectedPaths.clear()
                    scanForSimilar()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        scanForSimilar()
    }

    private fun scanForSimilar() {
        binding.progress.visibility = View.VISIBLE
        binding.recyclerPhotos.visibility = View.GONE
        binding.emptyContainer.visibility = View.GONE
        binding.selectionBar.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val files = vm.filesByCategory.value?.values?.flatten() ?: emptyList()
            groups = withContext(Dispatchers.IO) {
                SimilarPhotoDetector.findSimilarPhotos(files) { done, total ->
                    _binding?.tvProgress?.post {
                        _binding?.tvProgress?.text = getString(R.string.similar_scanning, done, total)
                    }
                }
            }

            if (_binding == null) return@launch
            binding.progress.visibility = View.GONE

            if (groups.isEmpty()) {
                binding.emptyContainer.visibility = View.VISIBLE
                binding.tvEmpty.text = getString(R.string.similar_none_found)
                return@launch
            }

            binding.tvSummary.text = getString(R.string.similar_found,
                groups.size, groups.sumOf { it.photos.size },
                UndoHelper.formatBytes(groups.sumOf { it.totalSize }))
            binding.tvSummary.visibility = View.VISIBLE
            binding.recyclerPhotos.visibility = View.VISIBLE
            binding.recyclerPhotos.adapter = PhotoGridAdapter()
        }
    }

    private data class GridItem(val photo: FileItem?, val groupHeader: String?)

    private inner class PhotoGridAdapter : RecyclerView.Adapter<PhotoGridAdapter.VH>() {

        private val items = mutableListOf<GridItem>()

        init {
            for ((idx, group) in groups.withIndex()) {
                items.add(GridItem(null, getString(R.string.similar_group_header,
                    idx + 1, group.photos.size, UndoHelper.formatBytes(group.totalSize))))
                for (photo in group.photos) {
                    items.add(GridItem(photo, null))
                }
            }
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view)

        override fun getItemViewType(position: Int) =
            if (items[position].groupHeader != null) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layout = if (viewType == 0) R.layout.item_similar_header else R.layout.item_similar_photo
            return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            if (item.groupHeader != null) {
                holder.itemView.findViewById<TextView>(R.id.tv_group_header).text = item.groupHeader
            } else if (item.photo != null) {
                val iv = holder.itemView.findViewById<ImageView>(R.id.iv_photo)
                val check = holder.itemView.findViewById<View>(R.id.check_overlay)
                Glide.with(this@SimilarPhotosFragment)
                    .load(File(item.photo.path))
                    .centerCrop()
                    .into(iv)

                val isSelected = item.photo.path in selectedPaths
                check.visibility = if (isSelected) View.VISIBLE else View.GONE
                holder.itemView.alpha = if (isSelected) 0.6f else 1f

                holder.itemView.setOnClickListener {
                    if (item.photo.path in selectedPaths) {
                        selectedPaths.remove(item.photo.path)
                    } else {
                        selectedPaths.add(item.photo.path)
                    }
                    notifyItemChanged(position)
                    updateSelectionBar()
                }

                holder.itemView.contentDescription = "${item.photo.name}, ${UndoHelper.formatBytes(item.photo.size)}"
            }
        }

        override fun getItemCount() = items.size
    }

    private fun updateSelectionBar() {
        if (selectedPaths.isEmpty()) {
            binding.selectionBar.visibility = View.GONE
        } else {
            binding.selectionBar.visibility = View.VISIBLE
            val items = groups.flatMap { it.photos }.filter { it.path in selectedPaths }
            binding.tvSelectedCount.text = getString(R.string.similar_selected,
                items.size, UndoHelper.formatBytes(items.sumOf { it.size }))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

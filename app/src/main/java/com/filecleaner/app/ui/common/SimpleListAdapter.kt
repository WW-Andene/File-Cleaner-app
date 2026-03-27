package com.filecleaner.app.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding

/**
 * Generic single-type list adapter that eliminates boilerplate for simple
 * RecyclerView lists with a single item type and click handler.
 *
 * Usage:
 * ```
 * val adapter = SimpleListAdapter<MyItem, ItemMyBinding>(
 *     inflate = { inflater, parent -> ItemMyBinding.inflate(inflater, parent, false) },
 *     bind = { binding, item -> binding.tvName.text = item.name },
 *     onClick = { item -> handleClick(item) }
 * )
 * adapter.submitList(items)
 * ```
 */
class SimpleListAdapter<T, VB : ViewBinding>(
    private val inflate: (LayoutInflater, ViewGroup) -> VB,
    private val bind: (VB, T) -> Unit,
    private val onClick: ((T) -> Unit)? = null
) : RecyclerView.Adapter<SimpleListAdapter<T, VB>.VH>() {

    private var items: List<T> = emptyList()

    inner class VH(val binding: VB) : RecyclerView.ViewHolder(binding.root)

    fun submitList(newItems: List<T>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = inflate(LayoutInflater.from(parent.context), parent)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        bind(holder.binding, item)
        if (onClick != null) {
            holder.itemView.setOnClickListener { onClick.invoke(item) }
        }
    }

    override fun getItemCount() = items.size
}

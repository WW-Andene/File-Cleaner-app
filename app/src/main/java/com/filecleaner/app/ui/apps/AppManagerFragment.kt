package com.filecleaner.app.ui.apps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.filecleaner.app.R
import com.filecleaner.app.databinding.FragmentAppManagerBinding
import com.filecleaner.app.databinding.ItemInstalledAppBinding
import com.filecleaner.app.utils.AppManager
import com.filecleaner.app.utils.UndoHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lists all installed apps sorted by estimated storage size.
 * Users can tap an app to uninstall or view app info.
 */
class AppManagerFragment : Fragment() {

    private var _binding: FragmentAppManagerBinding? = null
    private val binding get() = _binding!!

    private var apps = listOf<AppManager.InstalledApp>()
    private var showSystem = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentAppManagerBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.recyclerApps.layoutManager = LinearLayoutManager(requireContext())

        binding.btnShowSystem.setOnClickListener {
            showSystem = !showSystem
            binding.btnShowSystem.text = getString(
                if (showSystem) R.string.app_manager_hide_system else R.string.app_manager_show_system)
            loadApps()
        }

        loadApps()
    }

    private fun loadApps() {
        binding.progress.visibility = View.VISIBLE
        binding.recyclerApps.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            apps = withContext(Dispatchers.IO) {
                AppManager.getInstalledApps(ctx, includeSystem = showSystem)
            }

            if (_binding == null) return@launch
            binding.progress.visibility = View.GONE

            // Summary
            val totalSize = apps.sumOf { it.sizeBytes }
            binding.tvSummary.text = getString(R.string.app_manager_summary,
                apps.size, UndoHelper.formatBytes(totalSize))

            // List
            binding.recyclerApps.visibility = View.VISIBLE
            binding.recyclerApps.adapter = AppAdapter(apps) { app ->
                showAppActions(app)
            }
        }
    }

    private fun showAppActions(app: AppManager.InstalledApp) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(app.name)
            .setMessage(getString(R.string.app_manager_detail,
                app.packageName,
                UndoHelper.formatBytes(app.sizeBytes)))
            .setPositiveButton(getString(R.string.app_manager_uninstall)) { _, _ ->
                AppManager.requestUninstall(ctx, app.packageName)
            }
            .setNeutralButton(getString(R.string.app_manager_info)) { _, _ ->
                AppManager.openAppInfo(ctx, app.packageName)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Refresh after returning from uninstall/app info
        if (apps.isNotEmpty()) loadApps()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Adapter ──

    private class AppAdapter(
        private val apps: List<AppManager.InstalledApp>,
        private val onClick: (AppManager.InstalledApp) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.VH>() {

        class VH(val binding: ItemInstalledAppBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemInstalledAppBinding.inflate(
                LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = apps[position]
            val ctx = holder.itemView.context
            holder.binding.tvAppName.text = app.name
            holder.binding.tvAppPackage.text = app.packageName
            holder.binding.tvAppSize.text = UndoHelper.formatBytes(app.sizeBytes)

            // Load app icon
            try {
                val pm = ctx.packageManager
                holder.binding.ivAppIcon.setImageDrawable(pm.getApplicationIcon(app.packageName))
            } catch (_: Exception) {
                holder.binding.ivAppIcon.setImageResource(R.drawable.ic_apk)
            }

            holder.itemView.setOnClickListener { onClick(app) }
        }

        override fun getItemCount() = apps.size
    }
}

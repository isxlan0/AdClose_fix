package com.close.hook.ads.ui.fragment.block

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.CloudRuleSourceSummary
import com.close.hook.ads.data.repository.CloudRuleRepository
import com.close.hook.ads.databinding.DialogCloudRuleSourceBinding
import com.close.hook.ads.databinding.FragmentCloudRuleBinding
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.ui.adapter.CloudRuleSourceAdapter
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.ui.viewmodel.CloudRuleViewModel
import com.close.hook.ads.util.FooterSpaceItemDecoration
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.IOnFabClickContainer
import com.close.hook.ads.util.IOnTabClickContainer
import com.close.hook.ads.util.IOnTabClickListener
import com.close.hook.ads.util.OnBackPressListener
import com.close.hook.ads.util.OnCLearCLickContainer
import com.close.hook.ads.util.OnClearClickListener
import com.close.hook.ads.util.dp
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.zhanghai.android.fastscroll.FastScrollerBuilder

class CloudRuleFragment : BaseFragment<FragmentCloudRuleBinding>(), OnClearClickListener,
    IOnTabClickListener, OnBackPressListener {

    private val viewModel by viewModels<CloudRuleViewModel>()
    private lateinit var adapter: CloudRuleSourceAdapter
    private lateinit var footerSpaceDecoration: FooterSpaceItemDecoration

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initFab()
        initObserve()
    }

    private fun initView() {
        adapter = CloudRuleSourceAdapter(
            onToggleEnabled = { item, enabled -> viewModel.setSourceEnabled(item.id, enabled) },
            onToggleAutoUpdate = { item, enabled -> viewModel.setAutoUpdateEnabled(item.id, enabled) },
            onSync = { item -> viewModel.syncSource(item.id) },
            onEdit = { item -> showSourceDialog(item) },
            onDelete = { item -> showDeleteSourceDialog(item) }
        )
        footerSpaceDecoration = FooterSpaceItemDecoration(footerHeight = 96.dp)

        binding.recyclerView.apply {
            adapter = this@CloudRuleFragment.adapter
            layoutManager = LinearLayoutManager(requireContext())
            addItemDecoration(footerSpaceDecoration)
            clipToPadding = false

            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val bottomNavHeight = (activity as? MainActivity)?.getBottomNavigationView()?.height ?: 0
                setPadding(paddingLeft, paddingTop, paddingRight, bottomNavHeight)
            }

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                private var totalDy = 0
                private val scrollThreshold = 20.dp

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    val navContainer = activity as? INavContainer
                    if (dy > 0) {
                        totalDy += dy
                        if (totalDy > scrollThreshold) {
                            navContainer?.hideNavigation()
                            totalDy = 0
                        }
                    } else if (dy < 0) {
                        totalDy += dy
                        if (totalDy < -scrollThreshold) {
                            navContainer?.showNavigation()
                            totalDy = 0
                        }
                    }
                }
            })

            FastScrollerBuilder(this).useMd2Style().build()
        }
    }

    private fun initFab() {
        binding.add.apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                behavior = HideBottomViewOnScrollBehavior<FloatingActionButton>()
            }
            visibility = View.VISIBLE
            setOnClickListener { showSourceDialog(null) }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.add.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                rightMargin = 25.dp
                bottomMargin = navigationBars.bottom + 105.dp
            }
            insets
        }
    }

    private fun initObserve() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.sources.collectLatest { sourceList ->
                        adapter.submitList(sourceList) {
                            binding.progressBar.visibility = View.GONE
                            updateViewFlipper(sourceList.isEmpty())
                        }
                    }
                }
                launch {
                    viewModel.workingIds.collectLatest { ids ->
                        adapter.submitWorkingIds(ids)
                    }
                }
                launch {
                    viewModel.messages.collectLatest { message ->
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun updateViewFlipper(isEmpty: Boolean) {
        val targetChild = if (isEmpty) 0 else 1
        if (binding.vfContainer.displayedChild != targetChild) {
            binding.vfContainer.displayedChild = targetChild
        }
    }

    private fun showSourceDialog(source: CloudRuleSourceSummary?) {
        val dialogBinding = DialogCloudRuleSourceBinding.inflate(LayoutInflater.from(requireContext()))
        dialogBinding.urlEditText.setText(source?.url.orEmpty())
        dialogBinding.enabledSwitch.isChecked = source?.enabled ?: false
        dialogBinding.autoUpdateSwitch.isChecked = source?.autoUpdateEnabled ?: false
        dialogBinding.intervalEditText.setText(
            (source?.updateIntervalHours ?: CloudRuleRepository.DEFAULT_UPDATE_INTERVAL_HOURS).toString()
        )

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (source == null) R.string.cloud_rule_add_source else R.string.cloud_rule_edit_source)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = dialogBinding.urlEditText.text?.toString().orEmpty().trim()
                val interval = dialogBinding.intervalEditText.text?.toString().orEmpty().trim()

                dialogBinding.urlLayout.error =
                    if (url.isBlank()) getString(R.string.cloud_rule_invalid_url) else null
                dialogBinding.intervalLayout.error =
                    if (interval.isBlank()) getString(R.string.cloud_rule_invalid_interval) else null

                if (url.isBlank() || interval.isBlank()) {
                    return@setOnClickListener
                }

                viewModel.saveSource(
                    sourceId = source?.id,
                    url = url,
                    enabled = dialogBinding.enabledSwitch.isChecked,
                    autoUpdateEnabled = dialogBinding.autoUpdateSwitch.isChecked,
                    updateIntervalHoursText = interval
                )
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showDeleteSourceDialog(source: CloudRuleSourceSummary) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cloud_rule_delete_source_title)
            .setMessage(getString(R.string.cloud_rule_delete_source_message, source.url))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.deleteSource(source.id)
            }
            .show()
    }

    override fun search(keyWord: String) {
        viewModel.setSearchQuery(keyWord)
    }

    override fun onReturnTop() {
        binding.recyclerView.scrollToPosition(0)
        (activity as? INavContainer)?.showNavigation()
    }

    override fun onBackPressed(): Boolean = false

    override fun onResume() {
        super.onResume()
        (parentFragment as? OnCLearCLickContainer)?.controller = this
        (parentFragment as? IOnTabClickContainer)?.tabController = this
        (parentFragment as? IOnFabClickContainer)?.fabController = null
    }
}

package com.close.hook.ads.ui.fragment.block

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.ActionMode
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.Selection
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.databinding.FragmentBlockRulesBinding
import com.close.hook.ads.databinding.ItemBlockListAddBinding
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.ui.adapter.BlockListAdapter
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.ui.viewmodel.BlockListViewModel
import com.close.hook.ads.util.AppUtils
import com.close.hook.ads.util.FooterSpaceItemDecoration
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.IOnFabClickContainer
import com.close.hook.ads.util.IOnFabClickListener
import com.close.hook.ads.util.IOnTabClickContainer
import com.close.hook.ads.util.IOnTabClickListener
import com.close.hook.ads.util.OnBackPressListener
import com.close.hook.ads.util.OnCLearCLickContainer
import com.close.hook.ads.util.OnClearClickListener
import com.close.hook.ads.util.RuleUtils
import com.close.hook.ads.util.dp
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.FastScrollerBuilder

class BlockRulesFragment : BaseFragment<FragmentBlockRulesBinding>(), OnClearClickListener,
    IOnTabClickListener, IOnFabClickListener, OnBackPressListener {

    private val viewModel by viewModels<BlockListViewModel>()
    private lateinit var adapter: BlockListAdapter
    private lateinit var footerSpaceDecoration: FooterSpaceItemDecoration
    private var tracker: SelectionTracker<Url>? = null
    private var selectedItems: Selection<Url>? = null
    private var actionMode: ActionMode? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initFab()
        initObserve()
        setUpTracker()
        addObserverToTracker()
    }

    private fun initView() {
        adapter = BlockListAdapter(requireContext(), viewModel::removeUrl, this::onEditUrl)
        footerSpaceDecoration = FooterSpaceItemDecoration(footerHeight = 96.dp)

        binding.recyclerView.apply {
            this.adapter = this@BlockRulesFragment.adapter
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

    private fun initObserve() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.blackList.collectLatest { blackList ->
                    adapter.submitList(blackList) {
                        binding.progressBar.visibility = View.GONE
                        updateViewFlipper(blackList.isEmpty())
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

    private fun getFabLayoutParams(): CoordinatorLayout.LayoutParams {
        return CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            behavior = HideBottomViewOnScrollBehavior<FloatingActionButton>()
        }
    }

    private fun initFab() {
        binding.delete.apply {
            layoutParams = getFabLayoutParams()
            visibility = View.VISIBLE
            setOnClickListener { clearBlockList() }
        }
        binding.add.apply {
            layoutParams = getFabLayoutParams()
            visibility = View.VISIBLE
            setOnClickListener { addRule() }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.delete.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                rightMargin = 25.dp
                bottomMargin = navigationBars.bottom + 105.dp
            }
            binding.add.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                rightMargin = 25.dp
                bottomMargin = navigationBars.bottom + 186.dp
            }
            insets
        }
    }

    private fun addObserverToTracker() {
        tracker?.addObserver(object : SelectionTracker.SelectionObserver<Url>() {
            override fun onSelectionChanged() {
                super.onSelectionChanged()
                selectedItems = tracker?.selection
                handleActionMode()
            }
        })
    }

    private fun handleActionMode() {
        val size = selectedItems?.size() ?: 0
        if (size > 0) {
            if (actionMode == null) {
                actionMode = (activity as? MainActivity)?.startSupportActionMode(actionModeCallback)
            }
            actionMode?.title = "Selected $size"
        } else {
            actionMode?.finish()
            actionMode = null
        }
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            mode?.menuInflater?.inflate(R.menu.menu_block, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            return when (item?.itemId) {
                R.id.clear -> {
                    removeSelectedRules()
                    true
                }
                R.id.action_copy -> {
                    copySelectedRules()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            actionMode = null
            tracker?.clearSelection()
        }
    }

    private fun removeSelectedRules() {
        selectedItems?.takeIf { it.size() > 0 }?.let { selection ->
            viewModel.removeList(selection.toList())
            Toast.makeText(requireContext(), getString(R.string.batch_remove_success), Toast.LENGTH_SHORT).show()
            tracker?.clearSelection()
            (activity as? INavContainer)?.showNavigation()
        }
    }

    private fun copySelectedRules() {
        selectedItems?.let { selection ->
            val copiedText = selection
                .mapNotNull { RuleUtils.formatRuleLine(it) }
                .distinct()
                .joinToString(separator = "\n")

            if (copiedText.isNotEmpty()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("copied_type_urls", copiedText))
                Toast.makeText(requireContext(), getString(R.string.batch_copy_to_clipboard), Toast.LENGTH_SHORT).show()
            }

            tracker?.clearSelection()
        }
    }

    private fun setUpTracker() {
        tracker = SelectionTracker.Builder(
            "block_rules_selection_id",
            binding.recyclerView,
            CategoryItemKeyProvider(adapter),
            CategoryItemDetailsLookup(binding.recyclerView),
            StorageStrategy.createParcelableStorage(Url::class.java)
        ).withSelectionPredicate(
            SelectionPredicates.createSelectAnything()
        ).build()
        adapter.tracker = tracker
    }

    private fun showRuleDialog(url: Url? = null) {
        val dialogBinding = ItemBlockListAddBinding.inflate(LayoutInflater.from(requireContext()))
        val ruleTypes = arrayOf(RuleUtils.TYPE_DOMAIN, RuleUtils.TYPE_URL, RuleUtils.TYPE_KEYWORD)
        var selectedType = RuleUtils.normalizeType(url?.type) ?: RuleUtils.TYPE_URL

        dialogBinding.editText.setText(url?.url.orEmpty())
        dialogBinding.type.setText(selectedType)

        dialogBinding.type.setOnClickListener {
            val currentTypeIndex = ruleTypes.indexOf(selectedType)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Select Rule Type")
                .setSingleChoiceItems(ruleTypes, currentTypeIndex) { dialog, which ->
                    val newType = ruleTypes[which]
                    if (selectedType == RuleUtils.TYPE_URL && newType == RuleUtils.TYPE_DOMAIN) {
                        val currentUrl = dialogBinding.editText.text?.toString().orEmpty()
                        if (currentUrl.isNotEmpty()) {
                            dialogBinding.editText.setText(AppUtils.extractHostOrSelf(currentUrl))
                        }
                    }
                    selectedType = newType
                    dialogBinding.type.setText(selectedType)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (url == null) R.string.add_rule else R.string.edit_rule)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val rawValue = dialogBinding.editText.text?.toString().orEmpty()
                if (rawValue.isBlank()) {
                    Toast.makeText(requireContext(), R.string.value_empty_error, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val normalizedItem = RuleUtils.normalizeRule(selectedType, rawValue)
                if (normalizedItem == null) {
                    Toast.makeText(requireContext(), R.string.invalid_rule_format, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newItem = normalizedItem.also { it.id = url?.id ?: 0L }
                lifecycleScope.launch {
                    val exists = viewModel.dataSource.isExist(newItem.type, newItem.url)
                    if (url == null) {
                        if (exists) {
                            Toast.makeText(requireContext(), R.string.rule_exists, Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.addUrl(newItem)
                        }
                    } else {
                        viewModel.updateUrl(newItem)
                    }
                }
            }
            .create()
            .apply {
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
                dialogBinding.editText.requestFocus()
            }
            .show()
    }

    private fun addRule() = showRuleDialog()

    private fun onEditUrl(url: Url) = showRuleDialog(url)

    private fun clearBlockList() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.clear_block_list_confirm))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.removeAll() }
            .show()
    }

    override fun search(keyWord: String) {
        viewModel.setBlackListSearchQuery(keyWord)
    }

    override fun onReturnTop() {
        binding.recyclerView.scrollToPosition(0)
        (activity as? INavContainer)?.showNavigation()
    }

    override fun onExport() {
        backupSAFLauncher.launch("block_list.rule")
    }

    override fun onRestore() {
        restoreSAFLauncher.launch(arrayOf("application/octet-stream", "text/plain"))
    }

    private val backupSAFLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    val allRules = viewModel.getAllUrls()
                    requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                        allRules.mapNotNull { RuleUtils.formatRuleLine(it) }
                            .distinct()
                            .filter { it.contains(",") }
                            .sorted()
                            .forEach { line -> writer?.write("$line\n") }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), getString(R.string.export_success), Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    withContext(Dispatchers.Main) {
                        showErrorDialog(getString(R.string.export_failed), it)
                    }
                }
            }
        }

    private val restoreSAFLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    val contentResolver = requireContext().contentResolver
                    val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        cursor.moveToFirst()
                        if (nameIndex != -1) cursor.getString(nameIndex) else "unknown"
                    } ?: "unknown"

                    if (!fileName.endsWith(".rule")) {
                        throw IllegalArgumentException(getString(R.string.invalid_file_format))
                    }

                    val currentRules = viewModel.getAllUrls()
                        .mapNotNull { RuleUtils.canonicalKey(it.type, it.url) }
                        .toSet()

                    val newUrls = contentResolver.openInputStream(uri)?.bufferedReader(StandardCharsets.UTF_8)?.useLines { lines ->
                        lines.mapNotNull { line ->
                            val parts = line.split(",\\s*".toRegex(), 2).map(String::trim)
                            if (parts.size != 2) return@mapNotNull null
                            RuleUtils.normalizeRule(parts[0], parts[1])
                        }.toList()
                    }?.distinct() ?: emptyList()

                    val urlsToAdd = newUrls.filter { RuleUtils.canonicalKey(it.type, it.url) !in currentRules }
                    if (urlsToAdd.isNotEmpty()) {
                        viewModel.addListUrl(urlsToAdd)
                    }

                    val message = if (urlsToAdd.isNotEmpty()) {
                        getString(R.string.import_success_count, urlsToAdd.size)
                    } else {
                        getString(R.string.import_no_new_rules)
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    withContext(Dispatchers.Main) {
                        showErrorDialog(getString(R.string.import_failed), it)
                    }
                }
            }
        }

    private fun showErrorDialog(title: String, error: Throwable) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(error.message)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(getString(R.string.crash_log)) { _, _ ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.crash_log))
                    .setMessage(error.stackTraceToString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            .show()
    }

    class CategoryItemDetailsLookup(private val recyclerView: RecyclerView) : ItemDetailsLookup<Url>() {
        override fun getItemDetails(e: MotionEvent): ItemDetails<Url>? {
            val view = recyclerView.findChildViewUnder(e.x, e.y) ?: return null
            return (recyclerView.getChildViewHolder(view) as? BlockListAdapter.ViewHolder)?.getItemDetails()
        }
    }

    class CategoryItemKeyProvider(private val adapter: BlockListAdapter) : ItemKeyProvider<Url>(SCOPE_CACHED) {
        override fun getKey(position: Int): Url? = adapter.currentList.getOrNull(position)

        override fun getPosition(key: Url): Int {
            val index = adapter.currentList.indexOfFirst { it == key }
            return if (index >= 0) index else RecyclerView.NO_POSITION
        }
    }

    override fun onBackPressed(): Boolean {
        if (tracker?.hasSelection() == true) {
            tracker?.clearSelection()
            return true
        }
        return false
    }

    override fun onPause() {
        super.onPause()
        tracker?.clearSelection()
    }

    override fun onResume() {
        super.onResume()
        (parentFragment as? OnCLearCLickContainer)?.controller = this
        (parentFragment as? IOnTabClickContainer)?.tabController = this
        (parentFragment as? IOnFabClickContainer)?.fabController = this
    }
}

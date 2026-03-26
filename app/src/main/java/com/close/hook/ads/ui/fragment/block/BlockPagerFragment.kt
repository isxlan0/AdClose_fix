package com.close.hook.ads.ui.fragment.block

import android.content.Context
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.close.hook.ads.R
import com.close.hook.ads.databinding.FragmentBlockPagerBinding
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.util.IOnFabClickContainer
import com.close.hook.ads.util.IOnFabClickListener
import com.close.hook.ads.util.IOnTabClickContainer
import com.close.hook.ads.util.IOnTabClickListener
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener
import com.close.hook.ads.util.OnCLearCLickContainer
import com.close.hook.ads.util.OnClearClickListener
import com.close.hook.ads.util.dp
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BlockPagerFragment : BaseFragment<FragmentBlockPagerBinding>(), OnBackPressListener,
    IOnFabClickContainer, IOnTabClickContainer, OnCLearCLickContainer {

    override var fabController: IOnFabClickListener? = null
    override var tabController: IOnTabClickListener? = null
    override var controller: OnClearClickListener? = null

    private val fragmentSuppliers: List<() -> Fragment> = listOf(
        ::BlockRulesFragment,
        ::CloudRuleFragment
    )
    private val backPressDelegates = mutableMapOf<Int, OnBackPressListener>()

    private val imm by lazy {
        requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    private var searchJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViewPager()
        initSearchBar()
        initToolbarButtons()
        initCloudFab()
    }

    private fun initViewPager() {
        binding.viewPager.offscreenPageLimit = fragmentSuppliers.size
        binding.viewPager.isUserInputEnabled = false
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = fragmentSuppliers.size

            override fun createFragment(position: Int): Fragment {
                val fragment = fragmentSuppliers[position]()
                if (fragment is OnBackPressListener) {
                    backPressDelegates[position] = fragment
                }
                return fragment
            }
        }

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = getString(
                if (position == TAB_ORIGINAL_RULES) R.string.original_rules else R.string.cloud_rules
            )
        }.attach()

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) = Unit

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit

            override fun onTabReselected(tab: TabLayout.Tab?) {
                tabController?.onReturnTop()
            }
        })

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateCurrentTabUi(position)
                binding.viewPager.post {
                    controller?.search(binding.editText.text?.toString().orEmpty())
                }
            }
        })

        updateCurrentTabUi(binding.viewPager.currentItem)
    }

    private fun initSearchBar() {
        binding.editText.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            setIconAndFocus(
                if (hasFocus) R.drawable.ic_magnifier_to_back else R.drawable.ic_back_to_magnifier,
                hasFocus
            )
        }

        binding.editText.addTextChangedListener { text ->
            val query = text?.toString().orEmpty()
            binding.clear.isVisible = query.isNotBlank()
            searchJob?.cancel()
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(300L)
                controller?.search(query)
            }
        }

        binding.searchIcon.setOnClickListener {
            val hasQuery = binding.editText.text?.isNotBlank() == true
            if (binding.editText.isFocused || hasQuery) {
                binding.editText.setText("")
                setIconAndFocus(R.drawable.ic_back_to_magnifier, false)
            } else {
                setIconAndFocus(R.drawable.ic_magnifier_to_back, true)
            }
        }
        binding.clear.setOnClickListener { binding.editText.setText("") }
    }

    private fun initToolbarButtons() {
        binding.export.setOnClickListener { fabController?.onExport() }
        binding.restore.setOnClickListener { fabController?.onRestore() }
    }

    private fun initCloudFab() {
        binding.cloudRulesFab.apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                behavior = HideBottomViewOnScrollBehavior<ExtendedFloatingActionButton>()
            }
            setOnClickListener {
                binding.viewPager.setCurrentItem(TAB_CLOUD_RULES, true)
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.cloudRulesFab.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                rightMargin = 25.dp
                bottomMargin = navigationBars.bottom + 267.dp
            }
            insets
        }
    }

    private fun updateCurrentTabUi(position: Int) {
        val isOriginalTab = position == TAB_ORIGINAL_RULES
        binding.export.isVisible = isOriginalTab
        binding.restore.isVisible = isOriginalTab
        binding.cloudRulesFab.isVisible = isOriginalTab
        if (!isOriginalTab) {
            fabController = null
        }
    }

    private fun setIconAndFocus(drawableId: Int, focus: Boolean) {
        binding.searchIcon.setImageDrawable(ContextCompat.getDrawable(requireContext(), drawableId))
        (binding.searchIcon.drawable as? AnimatedVectorDrawable)?.start()
        if (focus) {
            binding.editText.requestFocus()
            imm.showSoftInput(binding.editText, InputMethodManager.SHOW_IMPLICIT)
        } else {
            binding.editText.clearFocus()
            imm.hideSoftInputFromWindow(binding.editText.windowToken, 0)
        }
    }

    override fun onBackPressed(): Boolean {
        if (binding.editText.isFocused || binding.editText.text?.isNotBlank() == true) {
            binding.editText.setText("")
            setIconAndFocus(R.drawable.ic_back_to_magnifier, false)
            return true
        }
        return backPressDelegates[binding.viewPager.currentItem]?.onBackPressed() == true
    }

    override fun onResume() {
        super.onResume()
        (activity as? OnBackPressContainer)?.backController = this
    }

    override fun onPause() {
        super.onPause()
        (activity as? OnBackPressContainer)?.backController = null
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        backPressDelegates.clear()
        super.onDestroyView()
    }

    companion object {
        private const val TAB_ORIGINAL_RULES = 0
        private const val TAB_CLOUD_RULES = 1
    }
}

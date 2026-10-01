package com.topjohnwu.magisk.ui.log

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.topjohnwu.magisk.BuildConfig
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.BaseFragment
import com.topjohnwu.magisk.arch.viewModel
import com.topjohnwu.magisk.databinding.FragmentLogMd2Binding
import com.topjohnwu.magisk.ui.MainActivity
import com.topjohnwu.magisk.utils.MotionRevealHelper
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect

class LogFragment : BaseFragment<FragmentLogMd2Binding>(), MenuProvider {

    override val layoutRes = R.layout.fragment_log_md2
    override val viewModel by viewModel<LogViewModel>()
    override val snackbarView: View?
        get() = if (isMagiskLogVisible) binding.logFilterSuperuser.snackbarContainer
                else super.snackbarView
    override val snackbarAnchorView get() = binding.logFilterToggle

    private var actionSave: MenuItem? = null
    private var isMagiskLogVisible
        get() = binding.logFilter.isVisible
        set(value) {
            if (BuildConfig.STARDUST_UI) {
                binding.logFilter.isVisible = value
                updateLogTabs()
            } else {
                MotionRevealHelper.withViews(binding.logFilter, binding.logFilterToggle, value)
            }
            actionSave?.isVisible = !value
            if (!BuildConfig.STARDUST_UI) {
                with(activity as MainActivity) {
                    invalidateToolbar()
                    requestNavigationHidden(value)
                    setDisplayHomeAsUpEnabled(value)
                }
            }
        }

    override fun onStart() {
        super.onStart()
        activity?.setTitle(R.string.logs)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.logFilterToggle.setOnClickListener {
            isMagiskLogVisible = true
        }
        binding.root.findViewById<MaterialButton>(R.id.log_tab_magisk)?.setOnClickListener {
            isMagiskLogVisible = false
        }
        binding.root.findViewById<MaterialButton>(R.id.log_tab_su)?.setOnClickListener {
            isMagiskLogVisible = true
        }
        binding.root.findViewById<View>(R.id.log_save)?.setOnClickListener {
            viewModel.saveMagiskLog()
        }
        updateLogTabs()

        binding.logFilterSuperuser.logSuperuser.apply {
            addEdgeSpacing(bottom = R.dimen.l1)
            addItemSpacing(R.dimen.l1, R.dimen.l_50, R.dimen.l1)
            fixEdgeEffect()
        }
    }


    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_log_md2, menu)
        actionSave = menu.findItem(R.id.action_save)?.also {
            it.isVisible = !isMagiskLogVisible
        }
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_save -> viewModel.saveMagiskLog()
            R.id.action_clear ->
                if (!isMagiskLogVisible) viewModel.clearMagiskLog()
                else viewModel.clearLog()
        }
        return super.onOptionsItemSelected(item)
    }


    override fun onPreBind(binding: FragmentLogMd2Binding) = Unit

    override fun onBackPressed(): Boolean {
        if (binding.logFilter.isVisible) {
            isMagiskLogVisible = false
            return true
        }
        return super.onBackPressed()
    }

    private fun updateLogTabs() {
        val magisk = binding.root.findViewById<MaterialButton>(R.id.log_tab_magisk) ?: return
        val su = binding.root.findViewById<MaterialButton>(R.id.log_tab_su) ?: return
        val showingSu = binding.logFilter.isVisible
        fun MaterialButton.paint(selected: Boolean) {
            val color = if (selected)
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
            else
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant)
            val text = if (selected)
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)
            else
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
            backgroundTintList = ColorStateList.valueOf(color)
            setTextColor(text)
        }
        magisk.paint(!showingSu)
        su.paint(showingSu)
        binding.root.findViewById<View>(R.id.log_save)?.isVisible = !showingSu
    }

}

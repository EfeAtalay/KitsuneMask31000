package com.topjohnwu.magisk.ui.module

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.recyclerview.widget.RecyclerView
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.BaseFragment
import com.topjohnwu.magisk.arch.viewModel
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.ktx.hideKeyboard
import com.topjohnwu.magisk.databinding.FragmentModuleRepoBinding
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect

class ModuleRepoFragment : BaseFragment<FragmentModuleRepoBinding>(), MenuProvider {

    override val layoutRes = R.layout.fragment_module_repo
    override val viewModel by viewModel<ModuleRepoViewModel>()

    private var searchView: SearchView? = null

    override fun onStart() {
        super.onStart()
        activity?.setTitle(R.string.module_repo)
        activity?.supportActionBar?.subtitle = getString(R.string.module_repo_source)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.repoList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) activity?.hideKeyboard()
            }
        })
        binding.repoList.apply {
            addEdgeSpacing(top = R.dimen.l_50, bottom = R.dimen.l1)
            addItemSpacing(R.dimen.l1, R.dimen.l_50, R.dimen.l1)
            fixEdgeEffect()
        }
    }

    override fun onPreBind(binding: FragmentModuleRepoBinding) = Unit

    override fun onBackPressed(): Boolean {
        val search = searchView
        if (search != null && !search.isIconified) {
            search.isIconified = true
            return true
        }
        return super.onBackPressed()
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_module_repo, menu)
        val search = menu.findItem(R.id.action_search).actionView as SearchView
        searchView = search
        search.queryHint = search.context.getString(R.string.module_repo_search)
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.query = query.orEmpty()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.query = newText.orEmpty()
                return true
            }
        })
    }

    override fun onPrepareMenu(menu: Menu) {
        menu.findItem(R.id.action_repo_sort_name)?.isChecked =
            Config.repoOrder == Config.Value.ORDER_NAME
        menu.findItem(R.id.action_repo_sort_date)?.isChecked =
            Config.repoOrder == Config.Value.ORDER_DATE
        menu.findItem(R.id.action_repo_sort_stars)?.isChecked =
            Config.repoOrder == Config.Value.ORDER_STARS
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        val order = when (item.itemId) {
            R.id.action_repo_sort_name -> Config.Value.ORDER_NAME
            R.id.action_repo_sort_date -> Config.Value.ORDER_DATE
            R.id.action_repo_sort_stars -> Config.Value.ORDER_STARS
            else -> return false
        }
        viewModel.setOrder(order)
        activity?.invalidateOptionsMenu()
        return true
    }
}

package com.topjohnwu.magisk.ui.module

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.magisk.databinding.ActivityWebuiBinding
import com.topjohnwu.magisk.ui.theme.Theme

class WebUIActivity : AppCompatActivity() {

    private var binding: ActivityWebuiBinding? = null
    private var server: WebUiServer? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Theme.apply(this)
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        val root = path?.let { RootUtils.fs.getFile(it) }
        if (root == null || !root.isDirectory) {
            finish()
            return
        }
        val view = DataBindingUtil.setContentView<ActivityWebuiBinding>(this, R.layout.activity_webui)
        binding = view
        setSupportActionBar(view.webToolbar)
        supportActionBar?.title = intent.getStringExtra(EXTRA_NAME).orEmpty()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        view.webToolbar.setNavigationOnClickListener { finish() }

        val running = WebUiServer(root).also {
            it.start()
            server = it
        }
        view.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
        }
        view.webView.loadUrl("http://127.0.0.1:${running.port}/")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val web = binding?.webView
                if (web != null && web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    override fun onDestroy() {
        server?.close()
        binding?.webView?.apply {
            stopLoading()
            destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "webroot"
        const val EXTRA_NAME = "name"
    }
}

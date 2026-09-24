package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ActivityUserAssetBinding
import com.v2ray.ang.dto.entities.AssetUrlItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.util.GatewayGeoFiles
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.UserAssetViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class UserAssetActivity : HelperBaseActivity() {
    private val binding by lazy { ActivityUserAssetBinding.inflate(layoutInflater) }
    private val ownerActivity: UserAssetActivity
        get() = this
    private val viewModel: UserAssetViewModel by viewModels()
    private lateinit var adapter: UserAssetAdapter
    private var operationJob: Job? = null

    val extDir by lazy { File(Utils.userAssetPath(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_user_asset_setting))

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        adapter = UserAssetAdapter(viewModel, extDir, ActivityAdapterListener())
        binding.recyclerView.adapter = adapter

        binding.tvGeoFilesSourcesSummary.text = getGeoFilesSources()
        binding.layoutGeoFilesSources.setOnClickListener {
            setGeoFilesSources()
        }
        savedInstanceState?.getString("geoStatus")?.takeIf { it.isNotEmpty() }?.let(::showStatus)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("geoStatus", binding.tvGeoStatus.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_asset, menu)
        return super.onCreateOptionsMenu(menu)
    }

    // Use when to streamline the option selection
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.add_file -> showFileChooser().let { true }
        R.id.add_url -> startActivity(Intent(this, UserAssetUrlActivity::class.java)).let { true }
        R.id.add_qrcode -> importAssetFromQRcode().let { true }
        R.id.download_file -> downloadGeoFiles().let { true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun getGeoFilesSources(): String {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_GEO_FILES_SOURCES) ?: AppConfig.GEO_FILES_SOURCES.first()
    }

    private fun setGeoFilesSources() {
        if (operationJob?.isActive == true) return
        AlertDialog.Builder(this).setItems(AppConfig.GEO_FILES_SOURCES.toTypedArray()) { _, i ->
            val value = AppConfig.GEO_FILES_SOURCES[i]
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.geo_source_replace_preset, value))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    MmkvManager.decodeAssetUrls()
                        .filter { it.assetUrl.remarks in GatewayGeoFiles.names }
                        .forEach { MmkvManager.removeAssetUrl(it.guid) }
                    MmkvManager.encodeSettings(AppConfig.PREF_GEO_FILES_SOURCES, value)
                    refreshData()
                    showStatus(getString(R.string.geo_source_saved))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }.show()
    }

    private fun showFileChooser() {
        if (operationJob?.isActive == true) return
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            val name = getCursorName(uri)
            try {
                require(name != null)
                GatewayGeoFiles.validateName(name)
            } catch (_: IllegalArgumentException) {
                toast(R.string.geo_source_invalid_name)
                return@launchFileChooser
            }
            if (File(extDir, name).exists() ||
                MmkvManager.decodeAssetUrls().any { it.assetUrl.remarks == name }) {
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.geo_source_confirm_import, name))
                    .setPositiveButton(android.R.string.ok) { _, _ -> copyFile(uri, name) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                copyFile(uri, name)
            }
        }
    }

    private fun copyFile(uri: Uri, name: String) {
        if (operationJob?.isActive == true) return
        showLoading()
        operationJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    val assetId = MmkvManager.decodeAssetUrls()
                        .firstOrNull { it.assetUrl.remarks == name }?.guid ?: Utils.getUuid()
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Unable to open selected file" }
                        GatewayGeoFiles.replace(extDir, name, input)
                    }
                    MmkvManager.encodeAsset(assetId, AssetUrlItem(name, "file"))
                    SettingsChangeManager.makeRestartService()
                }
                toastSuccess(R.string.toast_success)
                showStatus(getString(R.string.geo_source_restart_needed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                showImportFailure(name, e)
            } catch (e: IllegalArgumentException) {
                showImportFailure(name, e)
            } catch (e: SecurityException) {
                showImportFailure(name, e)
            } finally {
                refreshData()
                hideLoading()
            }
        }
    }

    private fun showImportFailure(name: String, error: Exception) {
        LogUtil.e(AppConfig.TAG, "Failed to import asset: $name", error)
        toastError(R.string.toast_asset_copy_failed)
        showStatus(getString(R.string.geo_source_update_failed, name) + "\n" + error.localizedMessage.orEmpty())
    }

    private fun getCursorName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.let { cursor ->
            cursor.run {
                if (moveToFirst()) getString(getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                else null
            }.also { cursor.close() }
        }
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "Failed to get cursor name", e)
        null
    }

    private fun importAssetFromQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importAsset(scanResult)
            }
        }
        return true
    }


    private fun importAsset(url: String?): Boolean {
        try {
            if (url == null || !UserAssetViewModel.isHttpsUrl(url)) {
                toast(R.string.geo_source_https_required)
                return false
            }
            // Send URL to UserAssetUrlActivity for Processing
            startActivity(
                Intent(this, UserAssetUrlActivity::class.java)
                    .putExtra(UserAssetUrlActivity.ASSET_URL_QRCODE, url)
            )
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import asset from URL", e)
            return false
        }
        return true
    }

    private fun downloadGeoFiles() {
        if (operationJob?.isActive == true) return
        refreshData()
        showLoading()
        toast(R.string.msg_downloading_content)

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = SettingsManager.getHttpPort()
        operationJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    viewModel.downloadGeoFiles(extDir, httpPort, proxyUsername, proxyPassword)
                }
                val status = mutableListOf<String>()
                if (result.successCount > 0) {
                    status.add(getString(R.string.title_update_config_count, result.successCount))
                    status.add(getString(R.string.geo_source_restart_needed))
                }
                if (result.failureCount > 0) {
                    status.add(getString(R.string.geo_source_update_failed, result.failedAssets.joinToString(", ")))
                }
                if (status.isEmpty()) status.add(getString(R.string.geo_source_local_skipped))
                showStatus(status.joinToString("\n"))
            } catch (e: CancellationException) {
                throw e
            } finally {
                refreshData()
                hideLoading()
            }
        }
    }

    private fun showStatus(status: String) {
        binding.tvGeoStatus.text = status
        binding.tvGeoStatus.visibility = View.VISIBLE
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshData() {
        binding.tvGeoFilesSourcesSummary.text = getGeoFilesSources()
        viewModel.reload(getGeoFilesSources())
        adapter.notifyDataSetChanged()
    }

    private inner class ActivityAdapterListener : BaseAdapterListener {
        override fun onEdit(guid: String, position: Int) {
            if (operationJob?.isActive == true) return
            val item = viewModel.getAsset(position)?.takeIf { it.guid == guid } ?: return
            startActivity(
                Intent(ownerActivity, UserAssetUrlActivity::class.java)
                    .putExtra("assetId", guid)
                    .putExtra(UserAssetUrlActivity.ASSET_NAME, item.assetUrl.remarks)
                    .putExtra(UserAssetUrlActivity.ASSET_URL, item.assetUrl.url)
            )
        }

        override fun onRemove(guid: String, position: Int) {
            if (operationJob?.isActive == true) return
            val asset = viewModel.getAsset(position)?.takeIf { it.guid == guid }
                ?: viewModel.getAssets().find { it.guid == guid }
                ?: return
            val file = extDir.listFiles()?.find { it.name == asset.assetUrl.remarks }
            if (asset.assetUrl.remarks in GatewayGeoFiles.names) return

            AlertDialog.Builder(ownerActivity).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (file != null && !file.delete()) {
                        toast(R.string.toast_failure)
                        return@setPositiveButton
                    }
                    MmkvManager.removeAssetUrl(guid)
                    SettingsChangeManager.makeRestartService()
                    showStatus(getString(R.string.geo_source_restart_needed))
                    refreshData()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    // do nothing
                }
                .show()
        }

        override fun onShare(url: String) {
        }

        override fun onRefreshData() {
            refreshData()
        }
    }
}
package com.v2ray.ang.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityUserAssetUrlBinding
import com.v2ray.ang.dto.entities.AssetUrlItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.GatewayGeoFiles
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.UserAssetViewModel
import java.io.File

class UserAssetUrlActivity : BaseActivity() {
    // Receive QRcode URL from UserAssetActivity
    companion object {
        const val ASSET_URL_QRCODE = "ASSET_URL_QRCODE"
        const val ASSET_NAME = "ASSET_NAME"
        const val ASSET_URL = "ASSET_URL"
    }

    private val binding by lazy { ActivityUserAssetUrlBinding.inflate(layoutInflater) }

    private var del_config: MenuItem? = null
    private var save_config: MenuItem? = null

    private val editAssetId by lazy { intent.getStringExtra("assetId").orEmpty() }
    private var originalAsset: AssetUrlItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(binding.root)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_user_asset_add_url))

        val assetItem = MmkvManager.decodeAsset(editAssetId) ?: intent.getStringExtra(ASSET_NAME)?.let {
            AssetUrlItem(it, intent.getStringExtra(ASSET_URL).orEmpty())
        }
        originalAsset = assetItem
        val assetUrlQrcode = intent.getStringExtra(ASSET_URL_QRCODE)
        val assetNameQrcode = File(assetUrlQrcode.toString()).name
        when {
            assetItem != null -> bindingAsset(assetItem)
            assetUrlQrcode != null -> {
                binding.etRemarks.setText(assetNameQrcode)
                binding.etUrl.setText(assetUrlQrcode)
            }

            else -> clearAsset()
        }
        binding.etRemarks.isEnabled = assetItem == null
    }

    /**
     * bingding seleced asset config
     */
    private fun bindingAsset(assetItem: AssetUrlItem): Boolean {
        binding.etRemarks.text = Utils.getEditable(assetItem.remarks)
        binding.etUrl.text = Utils.getEditable(assetItem.url.takeUnless { it == "file" }.orEmpty())
        return true
    }

    /**
     * clear or init asset config
     */
    private fun clearAsset(): Boolean {
        binding.etRemarks.text = null
        binding.etUrl.text = null
        return true
    }

    /**
     * save asset config
     */
    private fun saveServer(): Boolean {
        val name = originalAsset?.remarks ?: binding.etRemarks.text.toString().trim()
        val url = binding.etUrl.text.toString().trim()
        try {
            GatewayGeoFiles.validateName(name)
        } catch (_: IllegalArgumentException) {
            toast(R.string.geo_source_invalid_name)
            return false
        }
        if (!UserAssetViewModel.isHttpsUrl(url)) {
            toast(R.string.geo_source_https_required)
            return false
        }
        val assetId = editAssetId.ifEmpty { Utils.getUuid() }
        val assetItem = (originalAsset ?: AssetUrlItem()).copy(remarks = name, url = url, locked = false)

        // check remarks unique
        val assetList = MmkvManager.decodeAssetUrls()
        if (assetList.any { it.assetUrl.remarks == assetItem.remarks && it.guid != assetId }) {
            toast(R.string.msg_remark_is_duplicate)
            return false
        }


        MmkvManager.encodeAsset(assetId, assetItem)
        toastSuccess(R.string.geo_source_saved)
        finish()
        return true
    }

    /**
     * save server config
     */
    private fun deleteServer(): Boolean {
        if (editAssetId.isNotEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    MmkvManager.removeAssetUrl(editAssetId)
                    finish()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    // do nothing
                }
                .show()
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_server, menu)
        del_config = menu.findItem(R.id.del_config)
        save_config = menu.findItem(R.id.save_config)

        if (editAssetId.isEmpty() || MmkvManager.decodeAsset(editAssetId) == null) {
            del_config?.isVisible = false
        }

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.del_config -> {
            deleteServer()
            true
        }

        R.id.save_config -> {
            saveServer()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }
}
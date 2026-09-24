package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ActivityRoutingSettingBinding
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.util.GatewayRuleFile
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.RoutingSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

class RoutingSettingActivity : HelperBaseActivity() {
    private val binding by lazy { ActivityRoutingSettingBinding.inflate(layoutInflater) }
    private val ownerActivity: RoutingSettingActivity
        get() = this
    private val viewModel: RoutingSettingsViewModel by viewModels()
    private lateinit var adapter: RoutingSettingRecyclerAdapter
    private var mItemTouchHelper: ItemTouchHelper? = null
    private val routing_domain_strategy: Array<out String> by lazy {
        resources.getStringArray(R.array.routing_domain_strategy)
    }
    private val preset_rulesets: Array<out String> by lazy {
        resources.getStringArray(R.array.preset_rulesets)
    }
    private val importRuleFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            loadGatewayRules {
                contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the selected file.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(binding.root)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.routing_settings_title))

        adapter = RoutingSettingRecyclerAdapter(viewModel, ActivityAdapterListener())

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        mItemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter))
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)

        binding.tvDomainStrategySummary.text = getDomainStrategy()
        binding.layoutDomainStrategy.setOnClickListener {
            setDomainStrategy()
        }
        binding.layoutGatewayDefault.setOnClickListener { setGatewayDefault() }
        binding.btnGatewayGeodata.setOnClickListener {
            startActivity(Intent(this, UserAssetActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_routing_setting, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.add_rule -> startActivity(Intent(this, RoutingEditActivity::class.java)).let { true }
        R.id.import_predefined_rulesets -> importPredefined().let { true }
        R.id.import_gateway_rule_file -> {
            try {
                importRuleFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            } catch (e: ActivityNotFoundException) {
                showGatewayImportError(e)
            }
            true
        }
        R.id.restore_gateway_rules -> {
            loadGatewayRules { assets.open(GatewayRuleFile.DEFAULT_ASSET) }
            true
        }
        R.id.import_rulesets_from_clipboard -> importFromClipboard().let { true }
        R.id.import_rulesets_from_qrcode -> importQRcode()
        R.id.export_rulesets_to_clipboard -> export2Clipboard().let { true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun getDomainStrategy(): String {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY) ?: routing_domain_strategy.first()
    }

    private fun setDomainStrategy() {
        android.app.AlertDialog.Builder(this).setItems(routing_domain_strategy.asList().toTypedArray()) { _, i ->
            try {
                val value = routing_domain_strategy[i]
                MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, value)
                SettingsChangeManager.makeRestartService()
                binding.tvDomainStrategySummary.text = value
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to set domain strategy", e)
            }
        }.show()
    }

    private fun setGatewayDefault() {
        val labels = arrayOf(getString(R.string.gateway_direct), getString(R.string.gateway_proxy))
        val tags = arrayOf(AppConfig.TAG_DIRECT, AppConfig.TAG_PROXY)
        AlertDialog.Builder(this)
            .setTitle(R.string.gateway_default_outbound)
            .setSingleChoiceItems(labels, tags.indexOf(SettingsManager.getGatewayDefaultOutbound())) { dialog, index ->
                if (MmkvManager.encodeSettings(AppConfig.PREF_GATEWAY_DEFAULT_OUTBOUND, tags[index])) {
                    SettingsChangeManager.makeRestartService()
                    refreshData()
                    toastSuccess(R.string.gateway_saved)
                } else {
                    toastError(R.string.toast_failure)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadGatewayRules(open: () -> InputStream) {
        lifecycleScope.launch {
            val rules = try {
                withContext(Dispatchers.IO) { open().use(GatewayRuleFile::read) }
            } catch (e: IOException) {
                showGatewayImportError(e)
                return@launch
            } catch (e: IllegalArgumentException) {
                showGatewayImportError(e)
                return@launch
            } catch (e: SecurityException) {
                showGatewayImportError(e)
                return@launch
            }
            AlertDialog.Builder(this@RoutingSettingActivity)
                .setTitle(R.string.gateway_import_file)
                .setMessage(getString(R.string.gateway_import_confirm, rules.size))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (SettingsManager.importGatewayRules(rules)) {
                        refreshData()
                        toastSuccess(R.string.gateway_saved)
                    } else {
                        toastError(R.string.toast_failure)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showGatewayImportError(error: Exception) {
        LogUtil.e(AppConfig.TAG, "Gateway rule import failed", error)
        AlertDialog.Builder(this)
            .setTitle(R.string.gateway_import_failed)
            .setMessage(getString(R.string.gateway_import_error, error.message ?: error.javaClass.simpleName))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun importPredefined() {
        AlertDialog.Builder(this).setItems(preset_rulesets.asList().toTypedArray()) { _, i ->
            AlertDialog.Builder(this).setMessage(R.string.routing_settings_import_rulesets_tip)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    try {
                        lifecycleScope.launch(Dispatchers.IO) {
                            SettingsManager.resetRoutingRulesetsFromPresets(this@RoutingSettingActivity, i)
                            launch(Dispatchers.Main) {
                                refreshData()
                                toastSuccess(R.string.toast_success)
                            }
                        }
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "Failed to import predefined ruleset", e)
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    //do nothing
                }
                .show()
        }.show()
    }

    private fun importFromClipboard() {
        AlertDialog.Builder(this).setMessage(R.string.routing_settings_import_rulesets_tip)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val clipboard = try {
                    Utils.getClipboard(this)
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to get clipboard content", e)
                    toastError(R.string.toast_failure)
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = SettingsManager.resetRoutingRulesets(clipboard)
                    withContext(Dispatchers.Main) {
                        if (result) {
                            refreshData()
                            toastSuccess(R.string.toast_success)
                        } else {
                            toastError(R.string.toast_failure)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do nothing
            }
            .show()
    }

    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importRulesetsFromQRcode(scanResult)
            }
        }
        return true
    }

    private fun export2Clipboard() {
        val rulesetList = MmkvManager.decodeRoutingRulesets()
        if (rulesetList.isNullOrEmpty()) {
            toastError(R.string.toast_failure)
        } else {
            Utils.setClipboard(this, JsonUtil.toJson(rulesetList))
            toastSuccess(R.string.toast_success)
        }
    }


    private fun importRulesetsFromQRcode(qrcode: String?): Boolean {
        AlertDialog.Builder(this).setMessage(R.string.routing_settings_import_rulesets_tip)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = SettingsManager.resetRoutingRulesets(qrcode)
                    withContext(Dispatchers.Main) {
                        if (result) {
                            refreshData()
                            toastSuccess(R.string.toast_success)
                        } else {
                            toastError(R.string.toast_failure)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do nothing
            }
            .show()
        return true
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshData() {
        viewModel.reload()
        adapter.notifyDataSetChanged()
        binding.tvGatewayDefaultSummary.setText(
            if (SettingsManager.getGatewayDefaultOutbound() == AppConfig.TAG_DIRECT) {
                R.string.gateway_direct
            } else {
                R.string.gateway_proxy
            }
        )
        binding.tvGatewayRuleCount.text = getString(
            R.string.gateway_rule_count,
            viewModel.getAll().count { it.enabled },
            viewModel.getAll().size
        )
    }

    private inner class ActivityAdapterListener : BaseAdapterListener {
        override fun onEdit(guid: String, position: Int) {
            startActivity(
                Intent(ownerActivity, RoutingEditActivity::class.java)
                    .putExtra("position", position)
            )
        }

        override fun onRemove(guid: String, position: Int) {
        }

        override fun onShare(url: String) {
        }

        override fun onRefreshData() {
            refreshData()
        }
    }
}
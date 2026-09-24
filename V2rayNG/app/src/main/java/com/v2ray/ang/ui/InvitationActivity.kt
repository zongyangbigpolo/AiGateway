package com.v2ray.ang.ui

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityInvitationBinding
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.util.InvitationError
import com.v2ray.ang.viewmodel.InvitationViewModel

class InvitationActivity : BaseActivity() {
    private val binding by lazy { ActivityInvitationBinding.inflate(layoutInflater) }
    private val model: InvitationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, true, getString(R.string.invitation_title))
        binding.btnRedeem.setOnClickListener {
            binding.tvStatus.text = ""
            model.redeem(binding.etCode.text.toString())
        }
        model.state.observe(this) { state ->
            val busy = state == InvitationViewModel.State.Busy
            binding.etCode.isEnabled = !busy
            binding.btnRedeem.isEnabled = !busy
            binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
            when (state) {
                is InvitationViewModel.State.Done -> {
                    binding.etCode.text?.clear()
                    toastSuccess(getString(R.string.invitation_success, state.result.nodeCount, state.result.name))
                    setResult(RESULT_OK)
                    finish()
                }
                is InvitationViewModel.State.Failed -> binding.tvStatus.setText(when (state.reason) {
                    InvitationError.SERVICE_URL -> R.string.invitation_error_service
                    InvitationError.CODE -> R.string.invitation_error_code
                    InvitationError.REJECTED -> R.string.invitation_error_rejected
                    InvitationError.RATE_LIMITED -> R.string.invitation_error_limited
                    InvitationError.NETWORK -> R.string.invitation_error_network
                    InvitationError.RESPONSE -> R.string.invitation_error_response
                    InvitationError.STORAGE -> R.string.invitation_error_storage
                })
                else -> Unit
            }
        }
    }
}

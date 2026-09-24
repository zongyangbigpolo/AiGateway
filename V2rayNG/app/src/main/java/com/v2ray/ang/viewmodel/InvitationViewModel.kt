package com.v2ray.ang.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.InvitationManager
import com.v2ray.ang.util.InvitationError
import com.v2ray.ang.util.InvitationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class InvitationViewModel : ViewModel() {
    sealed interface State {
        data object Idle : State
        data object Busy : State
        data class Done(val result: InvitationManager.Result) : State
        data class Failed(val reason: InvitationError) : State
    }

    val state = MutableLiveData<State>(State.Idle)

    fun redeem(code: String) {
        if (state.value == State.Busy || state.value is State.Done) return
        state.value = State.Busy
        viewModelScope.launch {
            state.value = try {
                State.Done(withContext(Dispatchers.IO) {
                    InvitationManager.redeem(BuildConfig.INVITATION_SERVICE_URL, code)
                })
            } catch (e: InvitationException) {
                State.Failed(e.reason)
            } catch (_: IOException) {
                State.Failed(InvitationError.NETWORK)
            }
        }
    }
}

package com.shareguard.app

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shareguard.app.data.MfaApiFactory
import com.shareguard.app.data.MfaVerifyRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import retrofit2.HttpException

class MfaViewModel : ViewModel() {
    sealed interface State {
        data object Idle : State
        data object Verifying : State
        data class Verified(val trustScore: Int, val trustBonus: Int) : State
        data class Failed(val message: String, val requiresVerification: Boolean = false) : State
    }

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = _state

    fun verify(baseUrl: String, accessToken: String, identityVerificationId: String) {
        if (_state.value == State.Verifying || _state.value is State.Verified) return
        if (accessToken.isBlank() || identityVerificationId.isBlank()) {
            _state.value = State.Failed("로그인 후 본인인증을 다시 시작해주세요.")
            return
        }
        _state.value = State.Verifying
        viewModelScope.launch {
            try {
                val response = MfaApiFactory.create(baseUrl).verify(
                    authorization = "Bearer $accessToken",
                    request = MfaVerifyRequest(identityVerificationId)
                )
                if (!response.verified) {
                    _state.value = State.Failed("서버에서 본인인증 완료를 확인하지 못했습니다.")
                } else {
                    _state.value = State.Verified(response.trustScore, response.trustBonusApplied)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: HttpException) {
                _state.value = State.Failed(
                    message = error.response()?.errorBody()?.string().messageOrNull()
                        ?: "본인인증 확인에 실패했습니다. (HTTP ${error.code()})",
                    requiresVerification = error.code() == 403
                )
            } catch (error: Exception) {
                _state.value = State.Failed(error.message ?: "본인인증 서버에 연결할 수 없습니다.")
            }
        }
    }

    private fun String?.messageOrNull(): String? {
        if (this.isNullOrBlank()) return null
        return runCatching { JSONObject(this).optString("message").ifBlank { this } }.getOrDefault(this)
    }
}

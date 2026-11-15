package nl.toonshift.android

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed interface PairingPhase {
    data object Idle : PairingPhase
    data object Connecting : PairingPhase
    data class Approval(val code: String) : PairingPhase
    data class Failed(val error: Exception) : PairingPhase
    data class Paired(val devices: List<Toon>, val selected: String) : PairingPhase
}

class PairingExpired : Exception()
class ScanCancelled : Exception()

class PairingViewModel : ViewModel() {
    private val state = MutableStateFlow<PairingPhase>(PairingPhase.Idle)
    val phase = state.asStateFlow()
    private var job: Job? = null

    fun connect(vault: Vault, payload: String, phoneName: String) {
        job?.cancel()
        state.value = PairingPhase.Connecting
        job = viewModelScope.launch {
            try {
                val pair = Pairing.parse(payload)
                val secret = newSecret()
                val token = "${pair.id}.$secret"
                val name = phoneName.trim().ifEmpty { "Android" }
                require(name.toByteArray().size <= 32)
                val verifier = sha256(secret.toByteArray())
                val claim = JSONObject().put("secret", pair.secret).put("name", name).put("verifier", verifier)
                val until = SystemClock.elapsedRealtime() + 120_000
                val response = ToonApi(pair.host, pair.pin).call("/v1/pair/claim", "POST", claim)
                val code = JSONObject(response).getString("code")
                state.value = PairingPhase.Approval(code)
                val api = ToonApi(pair.host, pair.pin, token)
                while (SystemClock.elapsedRealtime() < until) {
                    try {
                        val details = JSONObject(api.call("/v1/device"))
                        val toon = Toon(pair.id, details.optString("name", "ToonShift"), pair.host, pair.pin,
                            token, details.optString("timezone", "Europe/Amsterdam"))
                        val devices = runCatching { vault.load() }.getOrDefault(emptyList())
                            .filterNot { it.host == toon.host } + toon
                        vault.save(devices)
                        state.value = PairingPhase.Paired(devices, toon.id)
                        return@launch
                    } catch (error: HttpFailure) {
                        if (error.status != 401) throw error
                    }
                    delay(1800)
                }
                throw PairingExpired()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value = PairingPhase.Failed(error)
            }
        }
    }

    fun scanCancelled() {
        state.value = PairingPhase.Failed(ScanCancelled())
    }

    fun cancel() {
        job?.cancel()
        job = null
        state.value = PairingPhase.Idle
    }
}

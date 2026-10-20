package app.scanfit.feature.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.billing.Billing
import app.scanfit.core.billing.ProProduct
import app.scanfit.core.billing.ProductsState
import app.scanfit.core.billing.PurchaseOutcome
import app.scanfit.core.billing.RestoreOutcome
import app.scanfit.core.data.quota.PaywallRequests
import app.scanfit.core.data.quota.PaywallTrigger
import app.scanfit.core.data.quota.SaveGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the paywall says after an action (ALGORITHMS 14.3). A cancelled purchase says nothing. */
enum class PaywallMessage { THANKS, PENDING, FAILED, RESTORE_NONE, UNAVAILABLE }

data class PaywallUiState(
    /** Why it is open; `null` = closed. */
    val trigger: PaywallTrigger? = null,
    val products: ProductsState = ProductsState.Loading,
    /** A purchase or restore is running: both products and Restore are disabled. */
    val busy: Boolean = false,
    val message: PaywallMessage? = null,
)

/**
 * The paywall, hosted once at the app's root (ALGORITHMS 14.3): it opens when [PaywallRequests] asks (a blocked save
 * or Settings) and loads the products each time. It also provides [freeSavesLeft] for the reviews' "N free saves left".
 */
@HiltViewModel
class PaywallViewModel
@Inject
constructor(
    private val billing: Billing,
    private val requests: PaywallRequests,
    gate: SaveGate,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PaywallUiState())
    val uiState: StateFlow<PaywallUiState> = _uiState.asStateFlow()

    /** Collected for the app's lifetime: it also keeps the gate's state loaded for its non-suspending check. */
    val freeSavesLeft: StateFlow<Int?> =
        gate.status.map { if (it.showsLeft) it.remaining else null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var load: Job? = null
    private var session = 0L
    private var loadVersion = 0L
    private var operationRunning = false

    init {
        viewModelScope.launch {
            requests.current.collect { trigger ->
                if (trigger == null) {
                    resetPresentation()
                } else if (_uiState.value.trigger == null) {
                    session++
                    _uiState.value = PaywallUiState(trigger = trigger, busy = operationRunning)
                    loadProducts()
                }
            }
        }
    }

    fun onRetry() {
        if (_uiState.value.trigger == null || operationRunning) return
        _uiState.update { it.copy(products = ProductsState.Loading, message = null) }
        loadProducts()
    }

    fun onPurchase(product: ProProduct) {
        val state = _uiState.value
        val offer = state.products as? ProductsState.Ready ?: return
        if (state.trigger == null || operationRunning || offer.products.none { it.product == product }) return
        val startedIn = session
        operationRunning = true
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = when (billing.purchase(product)) {
                PurchaseOutcome.PURCHASED -> PaywallMessage.THANKS
                PurchaseOutcome.PENDING -> PaywallMessage.PENDING
                PurchaseOutcome.FAILED -> PaywallMessage.FAILED
                PurchaseOutcome.CANCELLED -> null
            }
            finishOperation(startedIn, message)
        }
    }

    fun onRestore() {
        if (_uiState.value.trigger == null || operationRunning) return
        val startedIn = session
        operationRunning = true
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = when (billing.restore()) {
                RestoreOutcome.RESTORED -> when {
                    billing.isPro.value -> PaywallMessage.THANKS
                    else -> PaywallMessage.RESTORE_NONE
                }

                RestoreOutcome.NOTHING -> PaywallMessage.RESTORE_NONE

                RestoreOutcome.FAILED -> PaywallMessage.UNAVAILABLE
            }
            finishOperation(startedIn, message)
        }
    }

    /** Closing changes nothing else: the review, its draft and its save state stay as they were. */
    fun onClose() {
        resetPresentation()
        requests.close()
    }

    private fun resetPresentation() {
        session++
        load?.cancel()
        _uiState.value = PaywallUiState()
    }

    private fun finishOperation(startedIn: Long, message: PaywallMessage?) {
        operationRunning = false
        _uiState.update {
            val nextMessage = if (startedIn == session) message else it.message
            if (it.trigger == null) it else it.copy(busy = false, message = nextMessage)
        }
    }

    private fun loadProducts() {
        load?.cancel()
        val startedIn = session
        val version = ++loadVersion
        load = viewModelScope.launch {
            val products = billing.products()
            if (startedIn == session && version == loadVersion) {
                _uiState.update { it.copy(products = products) }
            }
        }
    }
}

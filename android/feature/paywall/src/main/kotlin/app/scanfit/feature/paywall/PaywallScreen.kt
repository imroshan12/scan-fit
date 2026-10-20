package app.scanfit.feature.paywall

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanfit.core.billing.ProProduct
import app.scanfit.core.billing.ProductsState
import app.scanfit.core.billing.StoreProduct
import app.scanfit.core.config.AppLinks
import app.scanfit.core.data.quota.PaywallTrigger
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitRadius
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType

/** Shows the paywall over the whole app while [PaywallUiState.trigger] is set (ALGORITHMS 14.3). */
@Composable
fun PaywallHost(viewModel: PaywallViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.trigger == null) return
    Dialog(
        onDismissRequest = viewModel::onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        PaywallScreen(
            state,
            PaywallActions(viewModel::onPurchase, viewModel::onRestore, viewModel::onRetry, viewModel::onClose),
        )
    }
}

@Composable
internal fun PaywallScreen(
    state: PaywallUiState,
    actions: PaywallActions,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(ScanFitSpacing.xs), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = actions.onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                }
            }
            Column(
                modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ScanFitSpacing.screenMargin)
                    .padding(bottom = ScanFitSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.lg),
            ) {
                if (state.message == PaywallMessage.THANKS) Thanks(actions.onClose) else Offer(state, actions)
            }
        }
    }
}

@Composable
private fun Offer(
    state: PaywallUiState,
    actions: PaywallActions,
) {
    Text(
        stringResource(R.string.paywall_title),
        style = ScanFitType.display,
        modifier = Modifier.semantics { heading() },
    )
    if (state.trigger != PaywallTrigger.SETTINGS) {
        Text(stringResource(R.string.paywall_body), style = ScanFitType.body)
    }
    Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
        Benefit(R.string.paywall_benefit_unlimited)
        Benefit(R.string.paywall_benefit_private)
        Benefit(R.string.paywall_benefit_no_ads)
    }
    when (val products = state.products) {
        ProductsState.Loading -> Box(Modifier.fillMaxWidth().padding(ScanFitSpacing.lg), Alignment.Center) {
            CircularProgressIndicator()
        }

        ProductsState.Unavailable -> Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
            Text(stringResource(R.string.paywall_unavailable), style = ScanFitType.body)
            Button(onClick = actions.onRetry, modifier = Modifier.heightIn(min = ScanFitSpacing.minTouchTarget)) {
                Text(stringResource(R.string.common_retry))
            }
        }

        is ProductsState.Ready -> Products(products.products, enabled = !state.busy, onPurchase = actions.onPurchase)
    }
    state.message?.let { message ->
        Text(
            stringResource(message.text),
            style = ScanFitType.body,
            color = if (message == PaywallMessage.PENDING) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Text(
        stringResource(R.string.paywall_cancel_play),
        style = ScanFitType.caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(
        onClick = actions.onRestore,
        enabled = !state.busy,
        modifier = Modifier.heightIn(min = ScanFitSpacing.minTouchTarget),
    ) { Text(stringResource(R.string.paywall_restore)) }
    LegalLinks()
}

private val PaywallMessage.text: Int
    get() = when (this) {
        PaywallMessage.THANKS -> R.string.paywall_thanks
        PaywallMessage.PENDING -> R.string.paywall_pending
        PaywallMessage.FAILED -> R.string.paywall_failed
        PaywallMessage.RESTORE_NONE -> R.string.paywall_restore_none
        PaywallMessage.UNAVAILABLE -> R.string.paywall_unavailable
    }

@Composable
private fun Benefit(text: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(text), style = ScanFitType.body)
    }
}

/** Both products side by side; stacked when large text would squeeze them (UI_UX §5). */
@Composable
private fun Products(
    products: List<StoreProduct>,
    enabled: Boolean,
    onPurchase: (ProProduct) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth / LocalDensity.current.fontScale < SIDE_BY_SIDE_MIN
        val cards = @Composable { cardModifier: Modifier ->
            products.forEach { ProductCard(it, enabled, onPurchase, cardModifier) }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md)) { cards(Modifier.fillMaxWidth()) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.md)) { cards(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ProductCard(
    product: StoreProduct,
    enabled: Boolean,
    onPurchase: (ProProduct) -> Unit,
    modifier: Modifier,
) {
    val (title, price) = when (product.product) {
        ProProduct.YEARLY -> R.string.paywall_yearly to R.string.paywall_yearly_price
        ProProduct.LIFETIME -> R.string.paywall_lifetime to R.string.paywall_lifetime_price
    }
    OutlinedCard(
        onClick = { onPurchase(product.product) },
        enabled = enabled,
        shape = RoundedCornerShape(ScanFitRadius.card),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        modifier = modifier.heightIn(min = ScanFitSpacing.minTouchTarget),
    ) {
        Column(Modifier.padding(ScanFitSpacing.lg), verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.xs)) {
            Text(stringResource(title), style = ScanFitType.label, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(price, product.price), style = ScanFitType.title)
        }
    }
}

@Composable
private fun LegalLinks() {
    val uri = LocalUriHandler.current
    Row(horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
        TextButton(onClick = { uri.openUri(AppLinks.PRIVACY_POLICY) }) {
            Text(stringResource(R.string.legal_privacy_policy))
        }
        TextButton(onClick = { uri.openUri(AppLinks.TERMS) }) { Text(stringResource(R.string.legal_terms)) }
    }
}

@Composable
private fun Thanks(onDone: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = ScanFitSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.lg),
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Text(
            stringResource(R.string.paywall_thanks),
            style = ScanFitType.title,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = ScanFitSpacing.minTouchTarget)) {
            Text(stringResource(R.string.common_done))
        }
    }
}

private val SIDE_BY_SIDE_MIN = 340.dp

private val READY = PaywallUiState(
    trigger = PaywallTrigger.SAVE,
    products = ProductsState.Ready(
        listOf(StoreProduct(ProProduct.YEARLY, "₹99"), StoreProduct(ProProduct.LIFETIME, "₹199")),
    ),
)

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, fontScale = 2f, locale = "hi", heightDp = 1600)
@Composable
private fun PaywallReadyPreview() {
    ScanFitTheme { PaywallScreen(READY, PaywallActions()) }
}

@Preview(showBackground = true)
@Composable
private fun PaywallStatesPreview() {
    ScanFitTheme {
        Column {
            PaywallScreen(
                PaywallUiState(PaywallTrigger.SETTINGS, ProductsState.Unavailable),
                PaywallActions(),
                Modifier.heightIn(max = 520.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PaywallPendingAndThanksPreview() {
    ScanFitTheme {
        Column {
            val pending = READY.copy(message = PaywallMessage.PENDING)
            PaywallScreen(pending, PaywallActions(), Modifier.heightIn(max = 700.dp))
            val thanks = READY.copy(message = PaywallMessage.THANKS)
            PaywallScreen(thanks, PaywallActions(), Modifier.heightIn(max = 400.dp))
        }
    }
}

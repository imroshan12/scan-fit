package app.scanfit.feature.paywall

import app.scanfit.core.billing.ProProduct

internal class PaywallActions(
    val onPurchase: (ProProduct) -> Unit = {},
    val onRestore: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onClose: () -> Unit = {},
)

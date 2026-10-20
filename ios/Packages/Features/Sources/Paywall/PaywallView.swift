import Billing
import Config
import DesignSystem
import ScanData
import SwiftUI

/// The Pro paywall (ALGORITHMS 14.3, App Store guideline 3.1.2): what Pro gives, both products with the store's price
/// and period, how to cancel, Restore, and the privacy policy and terms.
public struct PaywallView: View {
    @Bindable private var model: PaywallViewModel
    private let strings: Strings
    @Environment(\.dynamicTypeSize) private var typeSize

    public init(model: PaywallViewModel, strings: Strings = Strings()) {
        self.model = model
        self.strings = strings
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: ScanFitSpacing.lg) {
                    if model.message == .thanks { thanks } else { offer }
                }
                .padding(ScanFitSpacing.screenMargin)
            }
            .background(ScanFitColor.background)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { model.close() } label: { Image(systemName: "xmark") }
                        .accessibilityLabel(strings.commonClose)
                        .accessibilityIdentifier("paywall.close")
                }
            }
        }
        .task { await model.appeared() }
    }

    @ViewBuilder
    private var offer: some View {
        Text(strings.paywallTitle)
            .scanFitText(.display)
            .accessibilityAddTraits(.isHeader)
            .accessibilityIdentifier("paywall.title")
        if model.trigger != .settings {
            Text(strings.paywallBody).scanFitText(.body)
        }
        VStack(alignment: .leading, spacing: ScanFitSpacing.sm) {
            benefit(strings.paywallBenefitUnlimited)
            benefit(strings.paywallBenefitPrivate)
            benefit(strings.paywallBenefitNoAds)
        }
        productsSection
        if let message = model.message {
            Text(text(message))
                .scanFitText(.body)
                .foregroundStyle(message == .pending ? ScanFitColor.onSurface : ScanFitColor.error)
                .accessibilityAddTraits(.updatesFrequently)
                .accessibilityIdentifier("paywall.message")
        }
        Text(strings.paywallCancelAppStore)
            .scanFitText(.caption)
            .foregroundStyle(ScanFitColor.onSurfaceVariant)
        Button(strings.paywallRestore) { Task { await model.restore() } }
            .disabled(model.busy)
            .frame(minHeight: ScanFitSpacing.minTouchTarget)
            .accessibilityIdentifier("paywall.restore")
        HStack(spacing: ScanFitSpacing.lg) {
            if let url = URL(string: AppLinks.privacyPolicy) {
                Link(strings.legalPrivacyPolicy, destination: url).accessibilityIdentifier("paywall.privacy")
            }
            if let url = URL(string: AppLinks.terms) {
                Link(strings.legalTerms, destination: url).accessibilityIdentifier("paywall.terms")
            }
        }
        .scanFitText(.body)
    }

    @ViewBuilder
    private var productsSection: some View {
        switch model.products {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, minHeight: 96)
        case .unavailable:
            VStack(alignment: .leading, spacing: ScanFitSpacing.sm) {
                Text(strings.paywallUnavailable).scanFitText(.body)
                Button(strings.commonRetry) { Task { await model.retry() } }
                    .buttonStyle(.borderedProminent)
                    .frame(minHeight: ScanFitSpacing.minTouchTarget)
                    .accessibilityIdentifier("paywall.retry")
            }
        case let .ready(products):
            // Side by side; stacked at large text (UI_UX §5).
            let layout = typeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(spacing: ScanFitSpacing.md))
                : AnyLayout(HStackLayout(alignment: .top, spacing: ScanFitSpacing.md))
            layout {
                ForEach(products, id: \.product) { product in card(product) }
            }
        }
    }

    private func card(_ product: StoreProduct) -> some View {
        let (title, price) = switch product.product {
        case .yearly: (strings.paywallYearly, strings.paywallYearlyPrice(price: product.price))
        case .lifetime: (strings.paywallLifetime, strings.paywallLifetimePrice(price: product.price))
        }
        return Button { Task { await model.purchase(product.product) } } label: {
            VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
                Text(title).scanFitText(.label).foregroundStyle(ScanFitColor.primary)
                Text(price).scanFitText(.title).foregroundStyle(ScanFitColor.onSurface)
            }
            .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget, alignment: .leading)
            .padding(ScanFitSpacing.lg)
            .background(
                RoundedRectangle(cornerRadius: ScanFitRadius.card).stroke(ScanFitColor.primary, lineWidth: 1)
            )
            .contentShape(RoundedRectangle(cornerRadius: ScanFitRadius.card))
        }
        .buttonStyle(.plain)
        .disabled(model.busy)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("paywall.product.\(product.product.rawValue)")
    }

    private func benefit(_ text: String) -> some View {
        Label {
            Text(text).scanFitText(.body)
        } icon: {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(ScanFitColor.primary)
        }
    }

    private var thanks: some View {
        VStack(spacing: ScanFitSpacing.lg) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(ScanFitColor.primary)
                .accessibilityHidden(true)
            Text(strings.paywallThanks).scanFitText(.title).multilineTextAlignment(.center)
                .accessibilityIdentifier("paywall.thanks")
            Button { model.close() } label: {
                Text(strings.commonDone).frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("paywall.done")
        }
        .frame(maxWidth: .infinity)
        .padding(.top, ScanFitSpacing.xxl)
    }

    private func text(_ message: PaywallMessage) -> String {
        switch message {
        case .thanks: strings.paywallThanks
        case .pending: strings.paywallPending
        case .failed: strings.paywallFailed
        case .restoreNone: strings.paywallRestoreNone
        case .unavailable: strings.paywallUnavailable
        }
    }
}

#Preview("Ready") {
    PaywallView(model: PaywallViewModel(
        billing: LocalBilling(fakeProducts: LocalBilling.debugProducts), requests: PaywallRequests()
    ))
}

#Preview("Ready, dark") {
    PaywallView(model: PaywallViewModel(
        billing: LocalBilling(fakeProducts: LocalBilling.debugProducts), requests: PaywallRequests()
    ))
    .preferredColorScheme(.dark)
}

#Preview("Unavailable, Hindi, large text") {
    PaywallView(
        model: PaywallViewModel(billing: LocalBilling(), requests: PaywallRequests()),
        strings: Strings(languageCode: "hi")
    )
    .environment(\.dynamicTypeSize, .accessibility3)
}

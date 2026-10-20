# core:billing

Pro purchases behind one interface (`Billing`, ARCHITECTURE §8, ALGORITHMS §14): the `pro` entitlement as a flow,
the two products, purchase and restore. `LocalBilling` is the stand-in until RevenueCat is wired: release builds say the
store is unavailable; debug builds can offer fake products that grant Pro in memory, to walk the paywall by hand.

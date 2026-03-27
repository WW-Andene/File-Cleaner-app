package com.filecleaner.app.billing

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages the premium tier subscription/purchase state.
 *
 * Premium features:
 * - Ad-free experience (when ads are added)
 * - Cloud backup scheduling
 * - Similar photo detection
 * - Unlimited saved searches
 * - Priority support
 *
 * Uses Google Play Billing Library v7 with a single non-consumable
 * in-app purchase product ("premium_unlock").
 */
object PremiumManager {

    private const val PRODUCT_ID = "premium_unlock"
    private const val PREFS_NAME = "premium"
    private const val KEY_PREMIUM = "is_premium"

    private var billingClient: BillingClient? = null

    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Initialize billing and restore purchase state.
     * Call from Application.onCreate() or MainActivity.onCreate().
     */
    fun init(context: Context) {
        // Restore cached state immediately (fast, no network)
        _isPremium.value = prefs(context).getBoolean(KEY_PREMIUM, false)

        billingClient = BillingClient.newBuilder(context.applicationContext)
            .setListener { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    purchases?.forEach { purchase ->
                        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            handlePurchase(context, purchase)
                        }
                    }
                }
            }
            .enablePendingPurchases()
            .build()

        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    restorePurchases(context)
                }
            }
            override fun onBillingServiceDisconnected() {
                // Will reconnect on next launchPurchaseFlow
            }
        })
    }

    /**
     * Launch the Google Play purchase flow for premium.
     */
    fun launchPurchaseFlow(activity: Activity) {
        val client = billingClient ?: return

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            ))
            .build()

        client.queryProductDetailsAsync(params) { result, productDetails ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && productDetails.isNotEmpty()) {
                val product = productDetails[0]
                val flowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(product)
                            .build()
                    ))
                    .build()
                client.launchBillingFlow(activity, flowParams)
            }
        }
    }

    /** Check if a specific premium feature is available. */
    fun hasFeature(feature: PremiumFeature): Boolean = _isPremium.value

    @Synchronized
    private fun handlePurchase(context: Context, purchase: Purchase) {
        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient?.acknowledgePurchase(params) { result ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    android.util.Log.w("PremiumManager",
                        "Acknowledge failed: ${result.debugMessage}")
                }
            }
        }

        updatePremiumState(context, true)
    }

    private fun restorePurchases(context: Context) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient?.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val hasPremium = purchases.any {
                    it.products.contains(PRODUCT_ID) &&
                        it.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                updatePremiumState(context, hasPremium)
            }
        }
    }

    /** Thread-safe premium state update from any callback thread. */
    @Synchronized
    private fun updatePremiumState(context: Context, isPremium: Boolean) {
        _isPremium.value = isPremium
        prefs(context).edit().putBoolean(KEY_PREMIUM, isPremium).apply()
    }

    fun destroy() {
        billingClient?.endConnection()
        billingClient = null
    }
}

/** Premium features that can be gated. */
enum class PremiumFeature {
    SIMILAR_PHOTOS,
    CLOUD_BACKUP_SCHEDULE,
    UNLIMITED_SAVED_SEARCHES,
    AD_FREE
}

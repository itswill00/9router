package com.ninerouter.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ninerouter.android.core.OAuthFlow
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.Store
import com.ninerouter.android.ui.AppShell
import com.ninerouter.android.ui.NineRouterTheme
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        // targetSdk 35 enforces edge-to-edge on Android 15; opt in everywhere
        // and let Compose apply the status/navigation bar insets.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            ?.isAppearanceLightStatusBars = false
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            ?.isAppearanceLightNavigationBars = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        handleOAuthCallback()
        if (Store.state.settings.autoStartGateway) {
            GatewayService.start(this)
        }
        setContent {
            NineRouterTheme {
                AppShell(refreshTrigger = {})
            }
        }
    }

    private fun handleOAuthCallback() {
        val uri = intent.data ?: return
        if (uri.scheme != "ninerouter" || uri.host != "oauth") return
        val code = uri.getQueryParameter("code").orEmpty()
        val state = uri.getQueryParameter("state").orEmpty()
        val providerId = OAuthFlow.providerFor(state) ?: return
        val def = Catalog.find(providerId) ?: return
        val tokens = OAuthFlow.exchangeCode(def, code, state)
        if (tokens != null) {
            val processed = OAuthFlow.postProcess(def, tokens).tokens ?: tokens
            Store.state.connections.add(
                Connection(
                    id = UUID.randomUUID().toString(),
                    provider = def.id,
                    name = def.name,
                    authType = "oauth",
                    accessToken = processed.access,
                    refreshToken = processed.refresh,
                    expiresAt = System.currentTimeMillis() + processed.expiresIn * 1000L,
                    projectId = processed.projectId,
                    email = processed.email,
                    accountId = processed.accountId,
                )
            )
            Store.save()
            Toast.makeText(this, "Connected ${def.name}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "OAuth exchange failed for ${def.name}", Toast.LENGTH_LONG).show()
        }
    }
}

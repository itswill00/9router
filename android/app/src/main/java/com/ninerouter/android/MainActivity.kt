package com.ninerouter.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.ninerouter.android.core.ConsoleLog
import com.ninerouter.android.core.OAuthFlow
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.Store
import com.ninerouter.android.ui.AppShell
import com.ninerouter.android.ui.NineRouterTheme
import com.ninerouter.android.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        // The app is always dark, so force light system-bar icons regardless of
        // the device theme; transparent scrims let content draw edge-to-edge.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (savedInstanceState == null) handleOAuthCallback()
        if (savedInstanceState == null && Store.state.settings.autoStartGateway) {
            ConsoleLog.info("[APP] auto-starting the gateway service")
            GatewayService.start(this)
        }
        setContent {
            NineRouterTheme {
                AppShell(refreshTrigger = {})
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthCallback()
    }

    private fun handleOAuthCallback() {
        val uri = intent.data ?: return
        if (uri.scheme != "ninerouter" || uri.host != "oauth") return
        // Consume the deep link so a configuration change cannot exchange the
        // same authorization code twice.
        intent.data = null
        // The loopback landing page fires this only to bring the app forward.
        if (uri.getQueryParameter("status") == "done") return
        val code = uri.getQueryParameter("code").orEmpty()
        val state = uri.getQueryParameter("state").orEmpty()
        if (code.isBlank() || state.isBlank()) return
        val providerId = OAuthFlow.providerFor(state) ?: return
        val def = Catalog.find(providerId) ?: return
        lifecycleScope.launch {
            val tokens = withContext(Dispatchers.IO) { OAuthFlow.exchangeCode(def, code, state) }
            if (tokens == null) {
                ConsoleLog.error("[AUTH] OAuth exchange failed for ${def.name}")
                Toast.makeText(this@MainActivity, tr("OAuth exchange failed for ${def.name}"), Toast.LENGTH_LONG).show()
                return@launch
            }
            val processed = withContext(Dispatchers.IO) {
                OAuthFlow.postProcess(def, tokens).tokens ?: tokens
            }
            val connection = Connection(
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
                deviceId = processed.deviceId,
            )
            ConsoleLog.info("[AUTH] OAuth connected ${def.id}")
            Store.mutate {
                val index = connections.indexOfFirst {
                    it.provider == def.id &&
                        ((processed.email.isNotBlank() && it.email == processed.email) ||
                            (processed.accountId.isNotBlank() && it.accountId == processed.accountId))
                }
                if (index >= 0) {
                    // Reconnecting the same account replaces it instead of
                    // creating a duplicate routing candidate.
                    connections[index] = connection.copy(id = connections[index].id)
                } else {
                    connections.add(connection)
                }
            }
            Toast.makeText(this@MainActivity, tr("Connected ${def.name}"), Toast.LENGTH_SHORT).show()
        }
    }
}

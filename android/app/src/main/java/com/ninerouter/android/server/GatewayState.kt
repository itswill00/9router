package com.ninerouter.android.server

import kotlinx.coroutines.flow.MutableStateFlow

object GatewayState {
    val running = MutableStateFlow(false)
    const val PORT = 20128
    const val BASE_URL = "http://127.0.0.1:$PORT"
}

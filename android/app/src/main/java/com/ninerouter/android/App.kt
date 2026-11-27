package com.ninerouter.android

import android.app.Application
import com.ninerouter.android.core.ConsoleLog
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Pricing
import com.ninerouter.android.data.Store
import com.ninerouter.android.ui.I18n
import com.ninerouter.android.ui.Translator
import com.ninerouter.android.ui.resolveLocale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Catalog.load(this)
        Pricing.load(this)
        I18n.current = Translator.load(this, resolveLocale(Store.state.settings.uiLocale, this))
        ConsoleLog.info("[APP] 9Router Android ${com.ninerouter.android.AppInfo.VERSION_NAME} started")
    }
}

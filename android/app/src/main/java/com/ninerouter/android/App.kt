package com.ninerouter.android

import android.app.Application
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Store

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Catalog.load(this)
    }
}

package me.kavishdevar.librepods

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.billing.BillingProviderFactory
import me.kavishdevar.librepods.utils.XposedServiceHolder

class LibrePodsApplication: Application(), XposedServiceHelper.OnServiceListener, DefaultLifecycleObserver {
    override fun onCreate() {
        XposedServiceHelper.registerListener(this)
        BillingManager.provider = BillingProviderFactory.create(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        super<Application>.onCreate()

    }

    override fun onResume(owner: LifecycleOwner) {
        BillingManager.provider.queryPurchases()
    }

    override fun onServiceBind(p0: XposedService) {
        XposedServiceHolder.service = p0
    }

    override fun onServiceDied(p0: XposedService) {
        XposedServiceHolder.service = null
    }
}

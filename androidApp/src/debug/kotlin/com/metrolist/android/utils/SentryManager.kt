package com.metrolist.android.utils

import android.app.Application
import com.metrolist.shared.settings.Settings
import dev.metrofuse.runtime.PluginFailure

object SentryManager {
    fun reportPluginFailure(failure: PluginFailure) = Unit

    fun initialize(application: Application) = Unit

    fun setEnabled(enabled: Boolean) {
        Settings.shared.sentryEnabled = enabled
    }
}

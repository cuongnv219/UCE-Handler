package com.katana

import android.app.Application
import com.uce.crash.CrashReportingManager
import com.uce.handle.UCEHandler

class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()

//        AnalyticsManager.initialize(this)
        CrashReportingManager.init(BuildConfig.IS_COLLECTED)
        UCEHandler.Builder(this)
                .setBackgroundModeEnabled(false)
                .setTrackActivitiesEnabled(true)
                .addCommaSeparatedEmailAddresses("Cuongnv219@gmail.com")
//                .setRestartActivityClass(SplashActivity::class.java)
                .build()
    }
}
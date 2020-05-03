package com.uce.crash

import com.crashlytics.android.Crashlytics

object CrashReportingManager {

    private var enable = true

    fun isEnable() = enable

    fun init(enable: Boolean) {
        this.enable = enable
    }

    @JvmStatic
    fun logException(e: Throwable, sendLog: Boolean = true) {
        if (enable && sendLog) {
            Crashlytics.logException(e)
        } else {
            e.printStackTrace()
        }
    }

    fun log(s: String?) {
        if (enable) {
            Crashlytics.log(s)
        }
    }

    fun log(tag: String, s: String) {
        if (enable)
            Crashlytics.log("$tag:$s")
    }
}
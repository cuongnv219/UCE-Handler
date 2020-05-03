package com.uce.crash

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.uce.BuildConfig
import java.io.File

object AnalyticsManager {
    private var appContext: Context? = null

    private val binaryPlaces = arrayOf("/data/bin/", "/system/bin/", "/system/xbin/", "/sbin/",
            "/data/local/xbin/", "/data/local/bin/", "/system/sd/xbin/", "/system/bin/failsafe/",
            "/data/local/")

    private const val LOG_PREFIX = "AnalyticsManager"
    private const val LOG_PREFIX_LENGTH = LOG_PREFIX.length
    private const val MAX_LOG_TAG_LENGTH = 23

    private var analytics: FirebaseAnalytics? = null
    private val TAG = makeLogTag(AnalyticsManager::class.java)

    fun makeLogTag(str: String) = if (str.length > MAX_LOG_TAG_LENGTH - LOG_PREFIX_LENGTH) {
        LOG_PREFIX + str.substring(0, MAX_LOG_TAG_LENGTH - LOG_PREFIX_LENGTH - 1)
    } else LOG_PREFIX + str

    /**
     * Don't use this when obfuscating class names!
     */
    fun makeLogTag(cls: Class<*>) = makeLogTag(cls.simpleName)

    private fun canSend() = appContext != null && analytics != null && !BuildConfig.DEBUG

    @Synchronized
    fun initialize(context: Context) {
        appContext = context
        analytics = FirebaseAnalytics.getInstance(context)
        setProperty("DeviceType", getDeviceType(context))
        setProperty("Rooted", java.lang.Boolean.toString(isRooted))
    }

    fun setProperty(propertyName: String?, propertyValue: String?) {
        if (!canSend()) {
            return
        }
        analytics?.setUserProperty(propertyName!!, propertyValue)
    }

    val isRooted: Boolean
        get() {
            for (p in binaryPlaces) {
                val su = File(p + "su")
                if (su.exists()) {
                    return true
                }
            }
            return false
        }

    fun logEvent(eventName: String?) {
        if (!canSend()) {
            return
        }
        analytics?.logEvent(eventName!!, Bundle())
    }

    fun logEvent(eventName: String?, params: Bundle?) {
        if (!canSend()) {
            return
        }
        analytics?.logEvent(eventName!!, params)
    }

    fun setCurrentScreen(activity: Activity?, screenName: String?) {
        if (!canSend()) {
            return
        }
        if (null != screenName) {
            analytics?.setCurrentScreen(activity!!, screenName, screenName)
        }
    }

    fun isTablet(context: Context) = context.resources.configuration.smallestScreenWidthDp >= 600

    fun getDeviceType(c: Context): String {
        val uiModeManager = c.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return when (uiModeManager.currentModeType) {
            Configuration.UI_MODE_TYPE_TELEVISION -> "TELEVISION"
            Configuration.UI_MODE_TYPE_WATCH -> "WATCH"
            Configuration.UI_MODE_TYPE_NORMAL -> {
                if (isTablet(c)) "TABLET" else "PHONE"
            }
            Configuration.UI_MODE_TYPE_UNDEFINED -> "UNKOWN"
            else -> ""
        }
    }
}

package com.tracklesstelemetry.sdk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Detect coarse device context from Android system APIs.
 *
 * Privacy invariants enforced:
 * - NO GAID (Google Advertising ID)
 * - NO SSAID (Android ID / Settings.Secure.ANDROID_ID)
 * - NO IMEI, serial number, SIM info, or telephony data
 * - NO exact device model (only "phone" or "tablet")
 * - NO full user agent string
 * - NO screen resolution or exact screen dimensions
 * - Region derived from Locale.getDefault() only (not IP-based)
 */
internal object ContextDetection {

    private const val SDK_VERSION = "android/0.3.0"

    /**
     * Detect the full event context.
     */
    fun detect(context: Context): EventContext {
        return EventContext(
            platform = "android",
            osVersion = detectOsVersion(),
            deviceClass = detectDeviceClass(context),
            region = detectRegion(),
            language = detectLanguage(),
            appVersion = detectAppVersion(context),
            buildNumber = detectBuildNumber(context),
            daysSinceInstall = detectDaysSinceInstall(context),
            sdkVersion = SDK_VERSION,
            distributionChannel = detectDistributionChannel(context),
        )
    }

    /**
     * Detect the environment from the app's debuggable flag.
     */
    fun detectEnvironment(context: Context): TracklessEnvironment {
        return try {
            val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (isDebuggable) TracklessEnvironment.SANDBOX else TracklessEnvironment.PRODUCTION
        } catch (_: Throwable) {
            TracklessEnvironment.PRODUCTION
        }
    }

    /**
     * OS version as API level integer string (e.g., "34", "35").
     * Uses Build.VERSION.SDK_INT which returns the integer API level.
     */
    private fun detectOsVersion(): String? {
        return try {
            Build.VERSION.SDK_INT.toString()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Detect device class from Configuration.screenLayout.
     *
     * | Screen Layout Size          | deviceClass |
     * | SCREENLAYOUT_SIZE_SMALL     | "phone"     |
     * | SCREENLAYOUT_SIZE_NORMAL    | "phone"     |
     * | SCREENLAYOUT_SIZE_LARGE     | "tablet"    |
     * | SCREENLAYOUT_SIZE_XLARGE    | "tablet"    |
     * | SCREENLAYOUT_SIZE_UNDEFINED | null        |
     */
    private fun detectDeviceClass(context: Context): String? {
        return try {
            val screenSize = context.resources.configuration.screenLayout and
                Configuration.SCREENLAYOUT_SIZE_MASK

            when (screenSize) {
                Configuration.SCREENLAYOUT_SIZE_SMALL,
                Configuration.SCREENLAYOUT_SIZE_NORMAL -> "phone"

                Configuration.SCREENLAYOUT_SIZE_LARGE,
                Configuration.SCREENLAYOUT_SIZE_XLARGE -> "tablet"

                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Country code from Locale.getDefault() (e.g., "US", "DE").
     */
    private fun detectRegion(): String? {
        return try {
            val country = Locale.getDefault().country
            if (country.isNullOrEmpty()) null else country
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Language code from Locale.getDefault() (e.g., "en", "fr", "de").
     * Returns the ISO 639-1 two-letter language code.
     */
    private fun detectLanguage(): String? {
        return try {
            val language = Locale.getDefault().language
            if (language.isNullOrEmpty()) null else language
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * App version name from PackageManager.
     */
    private fun detectAppVersion(context: Context): String? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Build number (version code) from PackageManager.
     */
    private fun detectBuildNumber(context: Context): String? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toString()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Days since first install from PackageManager.
     *
     * Uses firstInstallTime — a read-only system value (no disk writes).
     */
    private fun detectDaysSinceInstall(context: Context): Int? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val installTimeMs = packageInfo.firstInstallTime
            val nowMs = System.currentTimeMillis()
            val diffDays = ((nowMs - installTimeMs) / (1000L * 60 * 60 * 24)).toInt()
            diffDays.coerceAtLeast(0)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Detect the app distribution source.
     *
     * Returns one of: "debug", "play_store", "galaxy_store", "amazon_store",
     * "sideloaded", or "unknown".
     *
     * "unknown" is returned when the installer package name is null (which occurs
     * for `adb install` and some OEM scenarios where the install source is not
     * recorded) or when the lookup throws. A non-null installer that doesn't match
     * a known store returns "sideloaded" — i.e., we have positive evidence of a
     * non-store installer.
     */
    private fun detectDistributionChannel(context: Context): String {
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) return "debug"

        return try {
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
            when (installer) {
                null -> "unknown"
                "com.android.vending" -> "play_store"
                "com.sec.android.app.samsungapps" -> "galaxy_store"
                "com.amazon.venezia" -> "amazon_store"
                else -> "sideloaded"
            }
        } catch (_: Throwable) {
            "unknown"
        }
    }
}

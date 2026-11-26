package com.ninerouter.android

/**
 * User-visible build version, derived from Gradle (`versionName`/`versionCode`)
 * so `app/build.gradle.kts` stays the single source of truth: bumping the
 * version there updates the gateway banner, settings, OAuth client strings and
 * update channel in the same build.
 */
object AppInfo {
    const val VERSION_NAME: String = BuildConfig.VERSION_NAME
    val VERSION: String = VERSION_NAME.removeSuffix("-android")
    const val VERSION_CODE: Int = BuildConfig.VERSION_CODE
    const val REPO = "itswill00/9router"
    const val GITHUB_URL = "https://github.com/$REPO"
    const val RELEASES_URL = "$GITHUB_URL/releases"
}

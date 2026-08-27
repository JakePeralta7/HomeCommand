package net.elad.homecommand

/**
 * Access app version information.
 *
 * Version is defined in root build.gradle.kts using semantic versioning (major.minor.patch).
 * Update the Versions object there to change app version.
 */
object AppVersion {
    val versionName: String = BuildConfig.VERSION_NAME
    val versionCode: Int = BuildConfig.VERSION_CODE
}

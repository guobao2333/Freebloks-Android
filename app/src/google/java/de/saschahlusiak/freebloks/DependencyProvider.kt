package de.saschahlusiak.freebloks

import android.content.Context
import de.saschahlusiak.freebloks.utils.*

object DependencyProvider {
    // defaults to dummy implementations until initialised
    private var gamesHelper: GooglePlayGamesHelper = GooglePlayGamesHelper()
    private var analytics: AnalyticsProvider = AnalyticsProvider()
    private var crashReporter: CrashReporter = CrashReporter()

    private var initialised = false

    fun initialise(context: Context) {
        if (initialised) return

        crashReporter = CrashlyticsCrashReporter()
        analytics = FirebaseAnalyticsProvider(context)
        gamesHelper = DefaultGooglePlayGamesHelper(context.applicationContext)

        initialised = true
    }

    fun googlePlayGamesHelper() = gamesHelper

    fun analytics() = analytics

    fun crashReporter() = crashReporter
}

val analytics get() = DependencyProvider.analytics()
val crashReporter get() = DependencyProvider.crashReporter()
fun Exception.logException() = crashReporter.logException(this)
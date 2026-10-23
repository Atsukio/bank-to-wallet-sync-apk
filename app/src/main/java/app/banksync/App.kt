package app.banksync

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: follow the wallpaper colours on Android 12+, fall back to the theme palette.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}

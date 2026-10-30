package app.banksync

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: follow the wallpaper colours on Android 12+ unless turned off in Settings; otherwise
        // (and on older phones) the theme's own teal palette.
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder().setPrecondition { activity, _ -> Settings(activity).wallpaperColors }.build(),
        )
    }
}

package app.podsbridge

import android.app.Application
import app.podsbridge.audio.Effects

class PodsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        PodsStore.load()
        Effects.load()
        Gestures.load()
    }
}

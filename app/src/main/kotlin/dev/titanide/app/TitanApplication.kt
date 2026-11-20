package dev.titanide.app

import android.app.Application
import dev.titanide.app.di.TitanContainer

class TitanApplication : Application() {

    lateinit var container: TitanContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = TitanContainer(this)
    }
}

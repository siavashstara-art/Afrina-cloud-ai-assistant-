package com.example.afrina

import android.app.Application
import com.example.afrina.core.di.AfrinaContainer

class AfrinaApp : Application() {
    lateinit var container: AfrinaContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AfrinaContainer(this)
    }
}

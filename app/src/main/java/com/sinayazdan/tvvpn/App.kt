package com.sinayazdan.tvvpn

import android.app.Application
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File

class App : Application() {
    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val externalDir = getExternalFilesDir(null) ?: filesDir
        val workingDir = File(filesDir, "working").apply { mkdirs() }
        val options = SetupOptions()
        options.basePath = externalDir.absolutePath
        options.workingPath = workingDir.absolutePath
        options.tempPath = cacheDir.absolutePath
        options.fixAndroidStack = true
        runCatching { Libbox.setup(options) }
    }
}

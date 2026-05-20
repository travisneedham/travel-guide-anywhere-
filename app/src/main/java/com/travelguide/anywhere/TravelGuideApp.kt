package com.travelguide.anywhere

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class TravelGuideApp : Application(), ImageLoaderFactory {

    @Inject lateinit var okHttpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .build()
}

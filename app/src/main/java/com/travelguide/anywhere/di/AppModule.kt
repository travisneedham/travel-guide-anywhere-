package com.travelguide.anywhere.di

import android.content.Context
import com.google.gson.Gson
import com.travelguide.anywhere.data.remote.ClaudeApiService
import com.travelguide.anywhere.data.remote.NominatimService
import com.travelguide.anywhere.data.remote.OsrmService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.travelguide.anywhere.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            // Production timeouts. PoiRepository derives a longer-ceiling client for Overpass shards
            // (~120s server-side budget) from this one; everything else (Wiki, Claude, images) is fast.
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(125, TimeUnit.SECONDS)
            .callTimeout(125, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                // Set a descriptive User-Agent on every request (including Coil image loads).
                // Wikimedia's CDN blocks OkHttp's default "okhttp/x.y.z" agent.
                val req = chain.request().takeIf { it.header("User-Agent") != null }
                    ?: chain.request().newBuilder()
                        .header("User-Agent", "TravelGuideAnywhere/2.0 (Android)")
                        .build()
                chain.proceed(req)
            }
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                            else HttpLoggingInterceptor.Level.NONE
                }
            )
            .build()

    @Provides
    @Singleton
    fun provideClaudeRetrofit(client: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(ClaudeApiService.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    fun provideClaudeApiService(retrofit: Retrofit): ClaudeApiService =
        retrofit.create(ClaudeApiService::class.java)

    @Provides
    @Singleton
    fun provideOsrmService(client: OkHttpClient, gson: Gson): OsrmService =
        Retrofit.Builder()
            .baseUrl(OsrmService.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(OsrmService::class.java)

    @Provides
    @Singleton
    fun provideNominatimService(client: OkHttpClient, gson: Gson): NominatimService =
        Retrofit.Builder()
            .baseUrl(NominatimService.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(NominatimService::class.java)

    @Provides
    @Singleton
    fun provideFusedLocationClient(@ApplicationContext context: Context): FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
}

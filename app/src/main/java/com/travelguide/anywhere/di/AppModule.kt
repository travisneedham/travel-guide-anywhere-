package com.travelguide.anywhere.di

import android.content.Context
import androidx.room.Room
import com.travelguide.anywhere.data.local.MentionedPlaceDao
import com.travelguide.anywhere.data.local.TourDatabase
import com.travelguide.anywhere.data.remote.ClaudeApiService
import com.travelguide.anywhere.data.remote.OverpassApiService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
            .build()

    @Provides
    @Singleton
    @Named("overpass")
    fun provideOverpassRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(OverpassApiService.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    @Named("claude")
    fun provideClaudeRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(ClaudeApiService.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideOverpassApiService(@Named("overpass") retrofit: Retrofit): OverpassApiService =
        retrofit.create(OverpassApiService::class.java)

    @Provides
    @Singleton
    fun provideClaudeApiService(@Named("claude") retrofit: Retrofit): ClaudeApiService =
        retrofit.create(ClaudeApiService::class.java)

    @Provides
    @Singleton
    fun provideTourDatabase(@ApplicationContext context: Context): TourDatabase =
        Room.databaseBuilder(context, TourDatabase::class.java, "tour_db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideMentionedPlaceDao(db: TourDatabase): MentionedPlaceDao =
        db.mentionedPlaceDao()

    @Provides
    @Singleton
    fun provideFusedLocationClient(@ApplicationContext context: Context): FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
}

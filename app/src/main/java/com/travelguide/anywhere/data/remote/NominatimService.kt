package com.travelguide.anywhere.data.remote

import com.travelguide.anywhere.data.remote.dto.NominatimResult
import retrofit2.http.GET
import retrofit2.http.Query

interface NominatimService {

    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("format") format: String,
        @Query("limit") limit: Int,
        @Query("accept-language") acceptLanguage: String = "en",
        @Query("viewbox") viewbox: String? = null,
        @Query("bounded") bounded: Int? = null,
    ): List<NominatimResult>

    /** Reverse-geocodes a lat/lon back to a place name — used to label a dragged map pin. */
    @GET("reverse")
    suspend fun reverse(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("format") format: String = "json",
        @Query("zoom") zoom: Int = 16,
        @Query("accept-language") acceptLanguage: String = "en",
    ): NominatimResult

    companion object {
        const val BASE_URL = "https://nominatim.openstreetmap.org/"
    }
}

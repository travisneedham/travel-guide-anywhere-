package com.travelguide.anywhere.data.remote

import com.travelguide.anywhere.data.remote.dto.OpenTripMapPlace
import retrofit2.http.GET
import retrofit2.http.Query

interface OpenTripMapService {

    @GET("places/radius")
    suspend fun getPlacesInRadius(
        @Query("radius") radius: Int,
        @Query("lon") lon: Double,
        @Query("lat") lat: Double,
        @Query("kinds") kinds: String? = null,
        @Query("rate") rate: String? = null,
        @Query("orderby") orderby: String? = null,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 50,
        @Query("apikey") apiKey: String,
    ): List<OpenTripMapPlace>

    companion object {
        const val BASE_URL = "https://api.opentripmap.com/0.1/en/"
    }
}

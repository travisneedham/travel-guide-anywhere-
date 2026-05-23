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
        @Query("viewbox") viewbox: String? = null,
        // 1 = restrict results to inside the viewbox; omitted when viewbox is null.
        @Query("bounded") bounded: Int? = null,
    ): List<NominatimResult>

    companion object {
        const val BASE_URL = "https://nominatim.openstreetmap.org/"
    }
}

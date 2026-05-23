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
        // Optional geographic bias — "left,top,right,bottom" (min_lon,max_lat,max_lon,min_lat).
        // When non-null, results inside the box are ranked first. bounded=1 restricts results
        // to within the box; we leave it off so we always get a result even if the query
        // is slightly outside (e.g. destination just beyond origin's ±5° box).
        @Query("viewbox") viewbox: String? = null,
    ): List<NominatimResult>

    companion object {
        const val BASE_URL = "https://nominatim.openstreetmap.org/"
    }
}

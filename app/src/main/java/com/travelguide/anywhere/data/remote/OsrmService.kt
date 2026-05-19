package com.travelguide.anywhere.data.remote

import com.travelguide.anywhere.data.remote.dto.OsrmResponse
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface OsrmService {

    @GET("route/v1/{profile}/{coordinates}")
    suspend fun getRoute(
        @Path(value = "profile", encoded = true) profile: String,
        @Path(value = "coordinates", encoded = true) coordinates: String,
        @Query("geometries") geometries: String,
        @Query("overview") overview: String
    ): OsrmResponse

    companion object {
        const val BASE_URL = "https://router.project-osrm.org/"
    }
}

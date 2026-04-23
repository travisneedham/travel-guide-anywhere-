package com.travelguide.anywhere.data.remote

import com.travelguide.anywhere.data.remote.dto.OverpassResponse
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

interface OverpassApiService {

    @POST("api/interpreter")
    @FormUrlEncoded
    suspend fun queryPois(
        @Field("data") query: String
    ): OverpassResponse

    companion object {
        const val BASE_URL = "https://overpass-api.de/"

        fun buildQuery(lat: Double, lon: Double, radiusMeters: Int): String = """
            [out:json][timeout:25];
            (
              node["historic"](around:$radiusMeters,$lat,$lon);
              node["tourism"="museum"](around:$radiusMeters,$lat,$lon);
              node["tourism"="attraction"](around:$radiusMeters,$lat,$lon);
              node["tourism"="artwork"](around:$radiusMeters,$lat,$lon);
              node["tourism"="viewpoint"](around:$radiusMeters,$lat,$lon);
              node["amenity"="place_of_worship"]["name"](around:$radiusMeters,$lat,$lon);
              node["leisure"="park"]["name"](around:$radiusMeters,$lat,$lon);
              way["historic"](around:$radiusMeters,$lat,$lon);
              way["tourism"="museum"](around:$radiusMeters,$lat,$lon);
              way["tourism"="attraction"](around:$radiusMeters,$lat,$lon);
              way["building"="cathedral"]["name"](around:$radiusMeters,$lat,$lon);
              relation["historic"]["name"](around:$radiusMeters,$lat,$lon);
            );
            out body center 20;
        """.trimIndent()
    }
}

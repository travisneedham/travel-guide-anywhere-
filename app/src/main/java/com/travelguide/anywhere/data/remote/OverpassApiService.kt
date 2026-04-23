package com.travelguide.anywhere.data.remote

// Overpass queries are now made directly via OkHttp in PoiRepository
// to avoid Retrofit's automatic Accept header causing 406 responses.
object OverpassApiService {
    const val BASE_URL = "https://overpass-api.de/"
}

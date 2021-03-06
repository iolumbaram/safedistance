package org.openexposuretrace.oextrace.api

import org.openexposuretrace.oextrace.storage.KeysData
import org.openexposuretrace.oextrace.storage.TrackingPoint
import org.openexposuretrace.oextrace.storage.TracksData
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query


interface ApiEndpoint {

    @POST("tracks")
    fun sendTracks(@Body tracksData: TrackingPoint): Call<String>

    //@POST("tracks")
    //fun sendTracks(@Body tracksData: TracksData): Call<String>

    //@POST("tracks")
    //fun sendTracks(@Body tracksData: MutableList<TrackingPoint>): Call<String>

    //@POST("violation")
    //fun sendViolation(@Body violationData: TrackingPoint): Call<String>

    @POST("keys")
    fun sendKeys(@Body keysData: KeysData): Call<String>

    @GET("keys")
    fun fetchKeys(
        @Query("lastUpdateTimestamp") lastUpdateTimestamp: Long,
        @Query("minLat") minLat: Double,
        @Query("maxLat") maxLat: Double,
        @Query("minLng") minLng: Double,
        @Query("maxLng") maxLng: Double
    ): Call<KeysData>

    //@POST("tracks")
    //fun sendTracks(@Body tracksData: TracksData): Call<String>

    //@POST("tracks")
    //fun sendTracks(@Body tracksData: MutableList<TrackingPoint>): Call<String>

    @GET("tracks")
    fun fetchTracks(
        @Query("lastUpdateTimestamp") lastUpdateTimestamp: Long,
        @Query("minLat") minLat: Double,
        @Query("maxLat") maxLat: Double,
        @Query("minLng") minLng: Double,
        @Query("maxLng") maxLng: Double
    ): Call<TracksData>

}

package org.openexposuretrace.oextrace.data

import android.location.Location
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import org.openexposuretrace.oextrace.ext.text.dateFullFormat
import java.util.*
import kotlin.math.roundToInt

const val ADV_TAG = "ADV"
const val SCAN_TAG = "SCAN"

@Entity(tableName = "log_table")
data class LogTableValue(
    val tag: String,
    val text: String,
    val time: Date = Date(),
    @PrimaryKey(autoGenerate = true) var id: Int? = null
) {
    fun getLog() = "${time.dateFullFormat()} <$tag> $text"
}


data class ContactRequest(
    val token: String,
    val platform: String,
    val secret: String,
    val tst: Long
)

data class ContactMetaData(val coord: ContactCoord?, val date: Date)

data class ContactCoord(val lat: Double, val lng: Double, val accuracy: Int) {
    fun coordinate(): LatLng = LatLng(lat, lng)
}


data class LocationIndex(val latIdx : Int, val lngIdx: Int){

   companion object{
       const val diff = 0.25 // ~ 25km
       const val precision = 10.0 // ~ 10km square side per index
   }

    constructor(location: Location) : this(
        latIdx= (location.latitude * precision).roundToInt(),
        lngIdx= (location.longitude * precision).roundToInt()
    )

    override fun toString(): String {
        return Gson().toJson(this)
    }
}

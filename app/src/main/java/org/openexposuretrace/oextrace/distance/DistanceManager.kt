package org.openexposuretrace.oextrace.distance

import android.os.Build
import android.text.TextUtils

object DistanceManager {

    fun getDeviceName(): String? {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(manufacturer)) {
            capitalize(model)
        } else capitalize(manufacturer) + " " + model
    }

    private fun capitalize(str: String): String {
        if (TextUtils.isEmpty(str)) {
            return str
        }
        val arr = str.toCharArray()
        var capitalizeNext = true
        val phrase = StringBuilder()
        for (c in arr) {
            if (capitalizeNext && Character.isLetter(c)) {
                phrase.append(Character.toUpperCase(c))
                capitalizeNext = false
                continue
            } else if (Character.isWhitespace(c)) {
                capitalizeNext = true
            }
            phrase.append(c)
        }
        return phrase.toString()
    }

    fun calculateDistance(rssi: Int): Double? {
        var measuredPower = 0
        var deviceName = getDeviceName();
        if (deviceName == "Samsung SM-G975F") {
            measuredPower = -84
        } else if (deviceName == "HUAWEI ELE-L29") {
            measuredPower = -98
        } else {
            measuredPower = -127
        }

        measuredPower = -98

        return getDistance(rssi, measuredPower);
    }

    fun getDistance(rssi: Int, txPower: Int): Double {
        return Math.pow(10.0, (txPower.toDouble() - rssi) / (10 * 2))
    }
}

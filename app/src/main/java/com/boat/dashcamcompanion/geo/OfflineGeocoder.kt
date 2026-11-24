package com.boat.dashcamcompanion.geo

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Place names WITHOUT internet: "road, area, city" for a GPS position.
 *
 * Uses app/src/main/assets/places.db, built on a PC from OpenStreetMap with
 * tools/build_place_db.py (same rules as tools/place_lookup.py):
 *   road = nearest named road within 60 m
 *   area = nearest neighbourhood/quarter within 1.5 km, else suburb/locality/village within 3 km
 *   city = nearest city/town within 30 km
 * If the database is missing, lookup() returns null and nothing else changes.
 *
 * Map data (c) OpenStreetMap contributors, ODbL.
 */
class OfflineGeocoder private constructor(private val db: SQLiteDatabase) {

    fun lookup(lat: Double, lon: Double): String? = try {
        val road = nearest(
            "SELECT r.lat, r.lon, n.name FROM roads r JOIN names n ON n.id = r.name_id WHERE r.cell IN (%s)",
            lat, lon, ROAD_MAX_M
        )
        var area: String? = null
        for ((kinds, radius) in AREA_RULES) {
            area = nearest(
                "SELECT lat, lon, name FROM places WHERE cell IN (%s) AND kind IN (" +
                    kinds.joinToString(",") { "'$it'" } + ")",
                lat, lon, radius
            )
            if (area != null) break
        }
        val city = nearest(
            "SELECT lat, lon, name FROM places WHERE cell IN (%s) AND kind IN ('city','town')",
            lat, lon, CITY_MAX_M
        )
        listOfNotNull(road, area, city).distinct().joinToString(", ").ifEmpty { null }
    } catch (e: Exception) {
        Log.w(TAG, "lookup failed", e)
        null
    }

    private fun nearest(sqlTemplate: String, lat: Double, lon: Double, radiusM: Double): String? {
        val cells = cellsAround(lat, lon, radiusM)
        var best: String? = null
        var bestD = Double.MAX_VALUE
        db.rawQuery(sqlTemplate.format(cells.joinToString(",")), null).use { c ->
            while (c.moveToNext()) {
                val d = distM(lat, lon, c.getDouble(0), c.getDouble(1))
                if (d <= radiusM && d < bestD) {
                    bestD = d
                    best = c.getString(2)
                }
            }
        }
        return best
    }

    companion object {
        private const val TAG = "OfflineGeocoder"
        private const val ASSET = "places.db"
        private const val CELL = 200            // 1/200 degree cells, same as build_place_db.py
        private const val ROAD_MAX_M = 60.0
        private const val CITY_MAX_M = 30_000.0
        private val AREA_RULES = listOf(
            listOf("neighbourhood", "quarter") to 1_500.0,
            listOf("suburb", "locality", "village", "hamlet") to 3_000.0
        )

        @Volatile private var instance: OfflineGeocoder? = null
        @Volatile private var unavailable = false

        /** Shared instance, or null if assets/places.db is not in the app. */
        fun get(context: Context): OfflineGeocoder? {
            instance?.let { return it }
            if (unavailable) return null
            synchronized(this) {
                instance?.let { return it }
                return try {
                    // SQLite cannot open a file inside the APK, so copy it out once.
                    val file = File(context.filesDir, ASSET)
                    if (!file.exists()) {
                        context.assets.open(ASSET).use { input -> file.outputStream().use { input.copyTo(it) } }
                    }
                    OfflineGeocoder(SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY))
                        .also { instance = it }
                } catch (e: Exception) {
                    Log.i(TAG, "no offline place database (assets/$ASSET) - place names disabled")
                    unavailable = true
                    null
                }
            }
        }

        private fun cellsAround(lat: Double, lon: Double, radiusM: Double): List<Long> {
            val r = ceil(radiusM / 550.0).toInt()
            val cy = floor(lat * CELL).toLong()
            val cx = floor(lon * CELL).toLong()
            val out = ArrayList<Long>()
            for (dy in -r..r) for (dx in -r..r) out += (cy + dy) * 100000 + (cx + dx)
            return out
        }

        private fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val k = cos(Math.toRadians((lat1 + lat2) / 2))
            return hypot((lat1 - lat2) * 111_320.0, (lon1 - lon2) * 111_320.0 * k)
        }
    }
}

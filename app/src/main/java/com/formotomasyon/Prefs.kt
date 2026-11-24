package com.formotomasyon

import android.content.Context
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

object BinaTipleri {
    val liste = listOf(
        "Apartman",
        "Müstakil ev (gecekondu)",
        "Müstakil ev (villa)",
        "Site içinde apartman",
        "Köy evi",
        "Terk edilmiş (Baraka)",
        "Numarataj var ama boş arsa",
        "İşyeri"
    )
}

/** SharedPreferences üzerinde tutulan kullanıcı ayarları. */
class Cfg(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    var targetPkg by stringPref("targetPkg")
    var mahalle by stringPref("mahalle")
    var cadde by stringPref("cadde")
    var sokak by stringPref("sokak")
    var binaAdi by stringPref("binaAdi")
    var binaNo by stringPref("binaNo")
    /** Daire No en az 1: kayıtlı 0 veya negatif değer 1'e çevrilir. */
    var daireNo: String
        get() = floorDaire(prefs.getString("daireNo", "1") ?: "1")
        set(value) {
            prefs.edit().putString("daireNo", floorDaire(value)).apply()
        }
    var binaTipi by stringPref("binaTipi")
    var mahCache by stringPref("mahCache")
    var ilceIkinci by booleanPref("ilceIkinci")

    private fun floorDaire(value: String): String {
        val n = value.trim().toLongOrNull()
        return if (n != null && n < 1L) "1" else value
    }

    private fun stringPref(key: String, default: String = ""): ReadWriteProperty<Any?, String> =
        object : ReadWriteProperty<Any?, String> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): String =
                prefs.getString(key, default) ?: default

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
                prefs.edit().putString(key, value).apply()
            }
        }

    private fun booleanPref(key: String): ReadWriteProperty<Any?, Boolean> =
        object : ReadWriteProperty<Any?, Boolean> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean =
                prefs.getBoolean(key, false)

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
                prefs.edit().putBoolean(key, value).apply()
            }
        }
}

package app.podsbridge

import android.content.Context
import android.content.SharedPreferences

/** 极简持久化：设置、上次连接的设备、EQ、手势校准都存在这里。 */
object Prefs {
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp == null) sp = ctx.applicationContext.getSharedPreferences("podsbridge", Context.MODE_PRIVATE)
    }

    fun bool(k: String, d: Boolean) = sp?.getBoolean(k, d) ?: d
    fun int(k: String, d: Int) = sp?.getInt(k, d) ?: d
    fun str(k: String, d: String) = sp?.getString(k, d) ?: d
    fun putBool(k: String, v: Boolean) { sp?.edit()?.putBoolean(k, v)?.apply() }
    fun putInt(k: String, v: Int) { sp?.edit()?.putInt(k, v)?.apply() }
    fun putStr(k: String, v: String) { sp?.edit()?.putString(k, v)?.apply() }
}

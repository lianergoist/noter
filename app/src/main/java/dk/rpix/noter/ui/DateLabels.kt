package dk.rpix.noter.ui

import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Gruppe på listen. Teksterne slås op i strings.xml (måned og år formateres her). */
sealed interface DateGroup {
    data object Today : DateGroup
    data object Yesterday : DateGroup
    data object Last7Days : DateGroup
    data class Month(val year: Int, val month: Int) : DateGroup
    data object NoDate : DateGroup
}

/**
 * Datoformater som i webklienten (reference/app.js), men i telefonens sprog: mønstrene vælges
 * af Android ud fra et skelet ("MMMd" giver "12. okt." på dansk og "Oct 12" på engelsk).
 * java.time kræver API 26, så Calendar bruges.
 */
object DateLabels {
    /** Skelet -> mønster for et sprog. Udskiftes i JVM-tests, hvor Androids DateFormat ikke findes. */
    internal var bestPattern: (Locale, String) -> String = DateFormat::getBestDateTimePattern

    private val STAMP = Regex("""^(\d{4})(\d\d)(\d\d)T(\d\d)(\d\d)(\d\d)(Z?)$""")

    /** `LAST-MODIFIED` i UTC (med Z) eller lokal tid (uden Z). null = mangler eller ulæselig. */
    fun parseStamp(s: String, tz: TimeZone = TimeZone.getDefault()): Date? {
        val m = STAMP.matchEntire(s) ?: return null
        val v = m.groupValues
        val cal = Calendar.getInstance(if (v[7] == "Z") TimeZone.getTimeZone("UTC") else tz)
        cal.clear()
        cal.set(v[1].toInt(), v[2].toInt() - 1, v[3].toInt(), v[4].toInt(), v[5].toInt(), v[6].toInt())
        return cal.time
    }

    /** Antal kalenderdage fra [t] til [now] i lokal tid. */
    fun dayDiff(t: Date, now: Date, tz: TimeZone = TimeZone.getDefault()): Int {
        fun dayStart(d: Date): Long = Calendar.getInstance(tz).run {
            time = d
            set(Calendar.HOUR_OF_DAY, 12) // middag, så sommertid ikke flytter dagen
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            timeInMillis
        }
        return Math.round((dayStart(now) - dayStart(t)) / 86_400_000.0).toInt()
    }

    fun groupOf(t: Date?, now: Date, tz: TimeZone = TimeZone.getDefault()): DateGroup {
        if (t == null) return DateGroup.NoDate
        val diff = dayDiff(t, now, tz)
        return when {
            diff <= 0 -> DateGroup.Today
            diff == 1 -> DateGroup.Yesterday
            diff < 7 -> DateGroup.Last7Days
            else -> Calendar.getInstance(tz).run {
                time = t
                DateGroup.Month(get(Calendar.YEAR), get(Calendar.MONTH))
            }
        }
    }

    /** "oktober 2026", "October 2026" */
    fun monthLabel(g: DateGroup.Month, tz: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String {
        val cal = Calendar.getInstance(tz).apply { clear(); set(g.year, g.month, 1) }
        return fmt("yMMMM", tz, locale).format(cal.time)
    }

    /** Klokkeslæt i dag, ugedag den seneste uge, ellers dato. */
    fun shortTime(
        t: Date,
        now: Date,
        tz: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val diff = dayDiff(t, now, tz)
        return when {
            diff <= 0 -> fmt("jm", tz, locale).format(t)
            diff < 7 -> fmt("EEE", tz, locale).format(t)
            else -> fmt("MMMd", tz, locale).format(t)
        }
    }

    fun fullTime(t: Date, tz: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String =
        fmt("yMMMdjm", tz, locale).format(t)

    private fun fmt(skeleton: String, tz: TimeZone, locale: Locale) =
        SimpleDateFormat(bestPattern(locale, skeleton), locale).apply { timeZone = tz }
}

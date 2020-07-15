/*
 * Copyright (c) 2020 Martin Denham, Tuomas Airaksinen and the And Bible contributors.
 *
 * This file is part of And Bible (http://github.com/AndBible/and-bible).
 *
 * And Bible is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * And Bible is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with And Bible.
 * If not, see http://www.gnu.org/licenses/.
 *
 */

package net.bible.service.common

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager.NameNotFoundException
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Environment
import android.os.StatFs

import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonConfiguration

import net.bible.android.BibleApplication
import net.bible.android.activity.BuildConfig.BuildDate
import net.bible.android.activity.BuildConfig.GitHash
import net.bible.android.activity.R
import net.bible.android.database.WorkspaceEntities
import net.bible.android.database.json

import net.bible.android.view.activity.ActivityComponent
import net.bible.android.view.activity.DaggerActivityComponent
import net.bible.android.view.activity.base.CurrentActivityHolder
import net.bible.android.view.activity.page.MainBibleActivity
import net.bible.android.view.activity.page.MainBibleActivity.Companion.mainBibleActivity
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.time.DateUtils
import org.crosswire.common.util.IOUtil
import org.crosswire.jsword.passage.Key
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseRange

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URL
import java.util.Calendar
import java.util.Date
import java.util.Properties
import javax.net.ssl.HttpsURLConnection

/**
 * @author Martin Denham [mjdenham at gmail dot com]
 */
object CommonUtils {

    private const val COLON = ":"
    private const val DEFAULT_MAX_TEXT_LENGTH = 250
    private const val ELLIPSIS = "..."

	val JSON_CONFIG = JsonConfiguration(ignoreUnknownKeys = true)

    private const val TAG = "CommonUtils"
    var isAndroid = true
        private set

    val applicationNameMedium get() = BibleApplication.application.getString(R.string.app_name_medium)

    val applicationVersionName: String
        get() {
            var versionName: String
            try {
                val manager = BibleApplication.application.packageManager
                val info = manager.getPackageInfo(BibleApplication.application.packageName, 0)
                versionName = info.versionName
            } catch (e: NameNotFoundException) {
                Log.e(TAG, "Error getting package name.", e)
                versionName = "Error"
            }

            return "$versionName#$GitHash ($applicationVersionNumber) (built $BuildDate)"
        }
    val applicationVersionNumber: Int
        get() {
            // TODO we have to change this to Long if we one day will have very long version numbers.
            var versionNumber: Int
            try {
                val manager = BibleApplication.application.packageManager
                val info = manager.getPackageInfo(BibleApplication.application.packageName, 0)
                versionNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode.toInt()
                } else info.versionCode
            } catch (e: NameNotFoundException) {
                Log.e(TAG, "Error getting package name.", e)
                versionNumber = -1
            }

            return versionNumber
        }

    val isSplitVertically: Boolean get() {
        val reverse = mainBibleActivity.windowRepository.windowBehaviorSettings.enableReverseSplitMode
        return if(reverse) !isPortrait else isPortrait
    }

    val isPortrait: Boolean get() {
        val res = CurrentActivityHolder.getInstance().currentActivity?.resources?: BibleApplication.application.resources
        return res.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    val megabytesFree: Long
        get() {
            val bytesAvailable = getFreeSpace(Environment.getExternalStorageDirectory().path)
            val megAvailable = bytesAvailable / 1048576
            Log.d(TAG, "Megs available on internal memory :$megAvailable")
            return megAvailable
        }

    val localePref: String?
        get() = sharedPreferences.getString("locale_pref", null)

    /** get preferences used by User Prefs screen
     *
     * @return
     */
    val sharedPreferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(BibleApplication.application.applicationContext)

    val truncatedDate: Date
        get() = DateUtils.truncate(Date(), Calendar.DAY_OF_MONTH)

    /** enable performance adjustments for slow devices
     */
    val isSlowDevice: Boolean
        get() = Runtime.getRuntime().availableProcessors() == 1

    init {
        try {
            if (android.os.Build.ID != null) {
                isAndroid = true
            }
        } catch (cnfe: Exception) {
            isAndroid = false
        }

        println("isAndroid:$isAndroid")
    }

    fun buildActivityComponent(): ActivityComponent {
        return DaggerActivityComponent.builder()
                .applicationComponent(BibleApplication.application.applicationComponent)
                .build()
    }

    fun getFreeSpace(path: String): Long {
        val stat = StatFs(path)
        val bytesAvailable = stat.blockSize.toLong() * stat.availableBlocks.toLong()
        Log.d(TAG, "Free space :$bytesAvailable")
        return bytesAvailable
    }

    @JvmOverloads
    fun limitTextLength(text: String?, maxLength: Int = DEFAULT_MAX_TEXT_LENGTH, singleLine: Boolean = false): String? {
        var text = text
        if (text != null) {
            val origLength = text.length

            if (singleLine) {
                // get first line but limit length in case there are no line breaks
                text = StringUtils.substringBefore(text, "\n")
            }

            if (text!!.length > maxLength) {
                // break on a space rather than mid-word
                val cutPoint = text.indexOf(" ", maxLength)
                if (cutPoint >= maxLength) {
                    text = text.substring(0, cutPoint + 1)
                }
            }

            if (text.length != origLength) {
                text += ELLIPSIS
            }
        }
        return text
    }

    fun ensureDirExists(dir: File) {
        if (!dir.exists() || !dir.isDirectory) {
            dir.mkdirs()
        }
    }

    fun deleteDirectory(path: File): Boolean {
        Log.d(TAG, "Deleting directory:" + path.absolutePath)
        if (path.exists()) {
            if (path.isDirectory) {
                val files = path.listFiles()
                for (i in files.indices) {
                    if (files[i].isDirectory) {
                        deleteDirectory(files[i])
                    } else {
                        files[i].delete()
                        Log.d(TAG, "Deleted " + files[i])
                    }
                }
            }
            val deleted = path.delete()
            if (!deleted) {
                Log.w(TAG, "Failed to delete:" + path.absolutePath)
            }
            return deleted
        }
        return false
    }

    fun loadProperties(propertiesFile: File): Properties {
        val properties = Properties()
        if (propertiesFile.exists()) {
            var `in`: FileInputStream? = null
            try {
                `in` = FileInputStream(propertiesFile)
                properties.load(`in`)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading properties", e)
            } finally {
                IOUtil.close(`in`)
            }
        }
        return properties
    }

    fun pause(seconds: Int) {
        pauseMillis(seconds * 1000)
    }

    fun pauseMillis(millis: Int) {
        try {
            Thread.sleep(millis.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Error sleeping", e)
        }

    }

    fun getSharedPreference(key: String, defaultValue: String): String? {
        return sharedPreferences.getString(key, defaultValue)
    }

    fun saveSharedPreference(key: String, value: String) {
        sharedPreferences.edit()
                .putString(key, value)
                .apply()
    }

    fun getResourceString(resourceId: Int, vararg formatArgs: Any): String {
        return resources.getString(resourceId, *formatArgs)
    }

    fun getResourceInteger(resourceId: Int): Int {
        return resources.getInteger(resourceId)
    }

    fun getResourceBoolean(resourceId: Int): Boolean {
        return resources.getBoolean(resourceId)
    }

    val resources: Resources get() =
        CurrentActivityHolder.getInstance()?.currentActivity?.resources?: BibleApplication.application.resources


    fun getResourceColor(resourceId: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val theme = try {
                mainBibleActivity.theme
            } catch (e: UninitializedPropertyAccessException) {
                resources.newTheme().apply {
                    applyStyle(R.style.MyDayNightTheme, true)
                }
            }
            resources.getColor(resourceId, theme)
        } else {
            resources.getColor(resourceId)
        }

    /**
     * convert dip measurements to pixels
     */
    fun convertDipsToPx(dips: Int): Int {
        // Converts 14 dip into its equivalent px
        val scale = resources.displayMetrics.density
        return (dips * scale + 0.5f).toInt()
    }

    /**
     * convert dip measurements to pixels
     */
    fun convertPxToDips(px: Int): Int {
        val scale = resources.displayMetrics.density
        return Math.round(px / scale)
    }

    /**
     * StringUtils methods only compare with a single char and hence create lots
     * of temporary Strings This method compares with all chars and just creates
     * one new string for each original string. This is to minimise memory
     * overhead & gc.
     *
     * @param str
     * @param removeChars
     * @return
     */
    fun remove(str: String, removeChars: CharArray): String? {
        if (StringUtils.isEmpty(str) || !StringUtils.containsAny(str, *removeChars)) {
            return str
        }

        val r = StringBuilder(str.length)
        // for all chars in string
        for (i in 0 until str.length) {
            val strCur = str[i]

            // compare with all chars to be removed
            var matched = false
            var j = 0
            while (j < removeChars.size && !matched) {
                if (removeChars[j] == strCur) {
                    matched = true
                }
                j++
            }
            // if current char does not match any in the list then add it to the
            if (!matched) {
                r.append(strCur)
            }
        }
        return r.toString()
    }

    /** format seconds duration as h:m:s
     *
     * @param secs duration
     * @return h:m:s
     */
    fun getHoursMinsSecs(secs: Long): String {
        val h = (secs / 3600).toInt()
        val m = (secs / 60 % 60).toInt()
        val s = (secs % 60).toInt()

        val hms = StringBuilder()
        if (h > 0) {
            hms.append(h).append(COLON)
        }

        // add padding for 1 digit mins
        if (m < 10) {
            hms.append(0)
        }
        hms.append(m).append(COLON)

        // add padding for 1 digit secs
        if (s < 10) {
            hms.append(0)
        }
        hms.append(s)
        return hms.toString()
    }

    fun getKeyDescription(key: Key): String {
        var name: String
        try {
            name = key.name

            // do not show verse 0
            if (key is Verse) {
                if (key.verse == 0 && name.endsWith("0")) {
                    val verse0 = "[\\W]0$"
                    name = name.replace(verse0.toRegex(), "")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting key name - could that Versification does not contain book")
            // but this normally works
            name = key.osisRef.replace('.', ' ')
        }

        return name
    }

    fun getWholeChapter(currentVerse: Verse, showIntros: Boolean): Key {
        Log.i(TAG, "Get Chapter for:" + currentVerse.osisID)
        val versification = currentVerse.versification
        val book = currentVerse.book
        val chapter = currentVerse.chapter

        val startChapter = if(showIntros && chapter == 1) 0 else chapter
        val endChapter = if(showIntros && chapter == 0) 1 else chapter

        val targetChapterFirstVerse = Verse(versification, book, startChapter, 0)
        val targetChapterLastVerse = Verse(versification, book, endChapter, versification.getLastVerse(book, endChapter))

        // convert to full chapter before returning because bible view is for a full chapter
        return VerseRange(versification, targetChapterFirstVerse, targetChapterLastVerse)
    }

    fun restartApp(callingActivity: Activity) {
        val intent = Intent(callingActivity, MainBibleActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

        val pendingIntent: PendingIntent
        pendingIntent = PendingIntent.getActivity(callingActivity, 0, intent, 0)

        val mgr = callingActivity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 1000, pendingIntent)
        System.exit(2)
    }

    val lastDisplaySettings: List<WorkspaceEntities.TextDisplaySettings.Types> get() {
        val lastDisplaySettingsString = sharedPreferences.getString("lastDisplaySettings", null)
        var lastTypes = mutableListOf<WorkspaceEntities.TextDisplaySettings.Types>()
        if(lastDisplaySettingsString!= null) {
            try {
                lastTypes = LastTypesSerializer.fromJson(lastDisplaySettingsString).types
            } catch (e: SerializationException) {
                Log.e(TAG, "Could not deserialize $lastDisplaySettingsString")
            }
        }
        return lastTypes
    }

    fun displaySettingChanged(type: WorkspaceEntities.TextDisplaySettings.Types) {
        val lastTypes = lastDisplaySettings.toMutableList()
        lastTypes.remove(type)
        while (lastTypes.size >= 5) {
            lastTypes.removeAt(lastTypes.size-1)
        }
        lastTypes.add(0, type)
        sharedPreferences.edit().putString("lastDisplaySettings", LastTypesSerializer(lastTypes).toJson()).apply()
    }
}

@Serializable
data class LastTypesSerializer(val types: MutableList<WorkspaceEntities.TextDisplaySettings.Types>) {
    fun toJson(): String {
        return json.stringify(serializer(), this)
    }

    companion object {
        fun fromJson(jsonString: String): LastTypesSerializer {
            return json.parse(serializer(), jsonString)
        }
    }
}

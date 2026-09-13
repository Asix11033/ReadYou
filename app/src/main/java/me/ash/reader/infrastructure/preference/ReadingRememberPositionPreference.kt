package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.readingRememberPosition
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

/**
 * 「记住阅读位置」开关。
 *
 * 开启后：退出阅读页/重进同一篇文章时，自动跳回上次读到的地方（长文尤其有用）；
 * 关闭后：行为与改动前完全一致（永远从顶部开始），且不写任何位置记录。
 */
val LocalReadingRememberPosition = compositionLocalOf { ReadingRememberPositionPreference.default }

class ReadingRememberPositionPreference(val value: Boolean) : Preference() {
    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(DataStoreKey.readingRememberPosition, value)
        }
    }

    fun toggle(context: Context, scope: CoroutineScope) =
        ReadingRememberPositionPreference(!value).put(context, scope)

    companion object {
        val default = ReadingRememberPositionPreference(true)

        fun fromPreferences(preference: Preferences): ReadingRememberPositionPreference {
            val key = DataStoreKey.keys[readingRememberPosition]?.key as Preferences.Key<Boolean>
            return ReadingRememberPositionPreference(preference[key] ?: default.value)
        }
    }
}

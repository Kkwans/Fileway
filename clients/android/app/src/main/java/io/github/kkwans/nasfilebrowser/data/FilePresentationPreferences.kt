package io.github.kkwans.nasfilebrowser.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Presentation only, like the Web's local icon-size preference; never holds account or resource identity. */
class FilePresentationPreferences(context: Context, preferencesName: String = "file_presentation") {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val writes = Mutex()

    fun compactGridSize(): CompactGridSize = CompactGridSize.fromStored(preferences.getString("compact_grid_size", null))

    suspend fun saveCompactGridSize(value: CompactGridSize) = writes.withLock {
        withContext(Dispatchers.IO) {
            check(preferences.edit().putString("compact_grid_size", value.name).commit()) { "无法保存图标大小" }
        }
    }
}

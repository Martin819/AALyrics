package cz.aalyrics.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val OFFSET = longPreferencesKey("sync_offset_ms")
    private val PREFER_SYNCED = booleanPreferencesKey("prefer_synced")

    val offsetMsFlow: Flow<Long> = context.dataStore.data.map { it[OFFSET] ?: 0L }
    val preferSyncedFlow: Flow<Boolean> = context.dataStore.data.map { it[PREFER_SYNCED] ?: true }

    suspend fun setOffsetMs(value: Long) {
        val clamped = value.coerceIn(-5_000L, 5_000L)
        context.dataStore.edit { it[OFFSET] = clamped }
    }

    suspend fun setPreferSynced(value: Boolean) {
        context.dataStore.edit { it[PREFER_SYNCED] = value }
    }
}

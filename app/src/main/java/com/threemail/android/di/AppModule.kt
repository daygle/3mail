package com.threemail.android.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.threemail.android.data.local.ThreeMailDatabase
import com.threemail.android.data.local.dao.AccountDao
import com.threemail.android.data.local.dao.CalendarEventDao
import com.threemail.android.data.local.dao.FolderDao
import com.threemail.android.data.local.dao.MessageDao
import com.threemail.android.notifications.BadgeNotifier
import com.threemail.android.notifications.SystemBadgeNotifier
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {

    /**
     * Bind [BadgeNotifier] to the production [SystemBadgeNotifier]; tests
     * replace this with an in-memory fake via Hilt's testing APIs.
     */
    @Binds
    @Singleton
    abstract fun bindBadgeNotifier(impl: SystemBadgeNotifier): BadgeNotifier
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<androidx.datastore.preferences.core.Preferences> {
        return PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("threemail_prefs")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ThreeMailDatabase {
        return ThreeMailDatabase.getInstance(context)
    }

    @Provides
    fun provideAccountDao(database: ThreeMailDatabase): AccountDao = database.accountDao()

    @Provides
    fun provideFolderDao(database: ThreeMailDatabase): FolderDao = database.folderDao()

    @Provides
    fun provideMessageDao(database: ThreeMailDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideCalendarEventDao(database: ThreeMailDatabase): CalendarEventDao = database.calendarEventDao()
}

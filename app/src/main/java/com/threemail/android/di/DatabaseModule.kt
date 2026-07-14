package com.threemail.android.di

import android.content.Context
import androidx.room.Room
import com.threemail.android.data.local.ThreeMailDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ThreeMailDatabase {
        return Room.databaseBuilder(
            context,
            ThreeMailDatabase::class.java,
            "threemail_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }
}

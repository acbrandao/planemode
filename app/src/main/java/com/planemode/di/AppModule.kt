package com.planemode.di

import android.content.Context
import androidx.room.Room
import com.planemode.data.db.FlightDao
import com.planemode.data.db.FlightDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): FlightDatabase =
        Room.databaseBuilder(ctx, FlightDatabase::class.java, "planemode.db").build()

    @Provides fun dao(db: FlightDatabase): FlightDao = db.dao()
}

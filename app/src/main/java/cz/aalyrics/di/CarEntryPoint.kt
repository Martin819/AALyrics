package cz.aalyrics.di

import cz.aalyrics.domain.LyricsController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CarEntryPoint {
    fun lyricsController(): LyricsController
}

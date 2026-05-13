package cz.aalyrics.di

import android.content.Context
import androidx.room.Room
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import cz.aalyrics.data.local.AppDatabase
import cz.aalyrics.data.local.LyricsDao
import cz.aalyrics.data.remote.LrcLibApi
import cz.aalyrics.data.remote.LyricsOvhApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun moshi(): Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Provides @Singleton
    fun okHttp(): OkHttpClient {
        val log = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", "AALyrics/1.0 (Android)")
                    .build()
                chain.proceed(req)
            }
            .addInterceptor(log)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides @Singleton
    fun lrcLib(client: OkHttpClient, moshi: Moshi): LrcLibApi = Retrofit.Builder()
        .baseUrl("https://lrclib.net")
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(LrcLibApi::class.java)

    @Provides @Singleton
    fun lyricsOvh(client: OkHttpClient, moshi: Moshi): LyricsOvhApi = Retrofit.Builder()
        .baseUrl("https://api.lyrics.ovh")
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(LyricsOvhApi::class.java)

    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): AppDatabase = Room
        .databaseBuilder(ctx, AppDatabase::class.java, "aalyrics.db")
        .fallbackToDestructiveMigration()
        .build()

    @Provides
    fun dao(db: AppDatabase): LyricsDao = db.lyricsDao()
}

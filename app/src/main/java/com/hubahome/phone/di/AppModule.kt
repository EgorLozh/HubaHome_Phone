package com.hubahome.phone.di

import android.content.Context
import android.content.SharedPreferences
import com.hubahome.phone.core.audio.AudioChunkRecorder
import com.hubahome.phone.core.audio.AudioOutputPlayer
import com.hubahome.phone.core.audio.AudioTrackOutputPlayer
import com.hubahome.phone.core.audio.PcmAudioChunkRecorder
import com.hubahome.phone.core.network.OkHttpVoiceSessionClient
import com.hubahome.phone.core.network.VoiceSessionClient
import com.hubahome.phone.core.settings.ConnectionSettingsStore
import com.hubahome.phone.core.settings.SharedPrefsConnectionSettingsStore
import com.hubahome.phone.core.wakeword.SpeechRecognizerWakewordDetector
import com.hubahome.phone.core.wakeword.WakewordDetector
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {
    @Binds
    @Singleton
    abstract fun bindVoiceSessionClient(
        implementation: OkHttpVoiceSessionClient
    ): VoiceSessionClient

    @Binds
    @Singleton
    abstract fun bindConnectionSettingsStore(
        implementation: SharedPrefsConnectionSettingsStore
    ): ConnectionSettingsStore

    @Binds
    @Singleton
    abstract fun bindAudioChunkRecorder(
        implementation: PcmAudioChunkRecorder
    ): AudioChunkRecorder

    @Binds
    @Singleton
    abstract fun bindAudioOutputPlayer(
        implementation: AudioTrackOutputPlayer
    ): AudioOutputPlayer

    @Binds
    @Singleton
    abstract fun bindWakewordDetector(
        implementation: SpeechRecognizerWakewordDetector
    ): WakewordDetector
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @OptIn(ExperimentalSerializationApi::class)
    fun provideJson(): Json {
        return Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }

    @Provides
    @Singleton
    fun provideSharedPreferences(
        @ApplicationContext context: Context,
    ): SharedPreferences {
        return context.getSharedPreferences("hubahome_phone_prefs", Context.MODE_PRIVATE)
    }
}

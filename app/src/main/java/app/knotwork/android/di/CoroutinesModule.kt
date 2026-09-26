package app.knotwork.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Provides application-lifetime coroutine infrastructure shared by singletons.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {

    /**
     * Provides the application-lifetime [CoroutineScope] used by singletons that
     * own fire-and-forget background work (e.g. the voice recorder's capture
     * loop). A [SupervisorJob] keeps one failed child from cancelling its
     * siblings; [Dispatchers.Default] is the base context (blocking work
     * switches to [Dispatchers.IO] at its call site).
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Provides [Dispatchers.IO] under [IoDispatcher], for classes that take their
     * I/O dispatcher as a constructor dependency so a test can pass its own.
     *
     * @return the shared I/O dispatcher.
     */
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}

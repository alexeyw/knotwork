package app.knotwork.android.di

import javax.inject.Qualifier

/**
 * Qualifies the [kotlinx.coroutines.CoroutineDispatcher] for blocking I/O that
 * [CoroutinesModule] provides as [kotlinx.coroutines.Dispatchers.IO].
 *
 * Taken as a constructor dependency by a class that starts work the moment it is
 * built — the task queue's worker — so a test hands it a test dispatcher before
 * anything runs, instead of swapping one in after a coroutine has already been
 * started on a real thread.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

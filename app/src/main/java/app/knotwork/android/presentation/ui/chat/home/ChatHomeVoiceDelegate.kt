package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.services.AudioCaptureStore
import app.knotwork.android.domain.services.AudioRecorder
import app.knotwork.android.domain.services.RecordingState
import app.knotwork.android.domain.services.isTerminal
import app.knotwork.android.domain.usecases.TranscribeAudioUseCase
import app.knotwork.android.domain.usecases.TranscriptionOutcome
import app.knotwork.design.components.chat.ComposerVoiceNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Voice-input delegate of [ChatHomeViewModel].
 *
 * Voice is a preprocessing step: a clip is recorded (or picked) and the
 * multimodal model transcribes it to text BEFORE any pipeline runs — the audio
 * never travels the graph. The transcript is merged into the composer draft, so
 * everything this delegate writes lands in the `composer` sub-fields (`voice`,
 * `voiceNotice`, `audioChooserVisible`, `audioMaxDurationSec`) of
 * [ChatHomeScreenState]. It has no coupling to the send pipeline, the run
 * collector, or the `visual` axis.
 *
 * Shares the ViewModel's [scope] and single [state] reducer (see
 * `docs/architecture.md` §1.2). [voiceJob] owns the active
 * record→transcribe (or pick→transcribe) flow so a discard / re-start cancels
 * it cleanly.
 *
 * @property scope The ViewModel's [androidx.lifecycle.viewModelScope].
 * @property state The ViewModel's single source-of-truth state flow (shared reducer).
 * @property generationSettings Source of the configured recording limit.
 * @property audioRecorder Microphone capture engine.
 * @property audioCaptureStore Imports picked audio files; deletes orphaned clips.
 * @property transcribeAudioUseCase Transcribes a clip into text.
 */
class ChatHomeVoiceDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatHomeScreenState>,
    private val generationSettings: GenerationSettings,
    private val audioRecorder: AudioRecorder,
    private val audioCaptureStore: AudioCaptureStore,
    private val transcribeAudioUseCase: TranscribeAudioUseCase,
) {

    private val _voiceErrorEvents: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)

    /**
     * One-shot signal raised when voice input fails (recording failed to capture,
     * a picked audio file could not be imported, or transcription errored). The
     * screen shows a voice-specific snackbar — distinct from the image-attachment
     * failure message.
     */
    val voiceErrorEvents: SharedFlow<Unit> = _voiceErrorEvents.asSharedFlow()

    private var voiceJob: Job? = null

    /** Opens the voice-input source chooser (Record voice / Choose audio file). */
    fun onMicClicked() {
        scope.launch {
            val maxSec = generationSettings.audioMaxDurationSec.first()
            state.update {
                it.copy(
                    composer = it.composer.copy(
                        audioChooserVisible = true,
                        voiceNotice = null,
                        audioMaxDurationSec = maxSec,
                    ),
                )
            }
        }
    }

    /** Dismisses the voice-input source chooser without a choice. */
    fun dismissAudioChooser() {
        state.update { it.copy(composer = it.composer.copy(audioChooserVisible = false)) }
    }

    /**
     * Surfaces the microphone-permission notice when the user denied the
     * `RECORD_AUDIO` request from the screen's permission gate.
     */
    fun onMicPermissionDenied() {
        state.update {
            it.copy(
                composer = it.composer.copy(
                    audioChooserVisible = false,
                    voiceNotice = ComposerVoiceNotice.PermissionDenied,
                ),
            )
        }
    }

    /**
     * Starts a microphone capture (invoked by the screen after the `RECORD_AUDIO`
     * permission is granted). Observes the recorder, mirroring its elapsed time
     * into the composer's recording bar, and dispatches on the **terminal** state:
     * a finished clip is transcribed, a failed capture resets the composer and
     * pings the error snackbar. The limit is the value already loaded by
     * [onMicClicked] (no second DataStore read, and the bar starts at the real
     * limit rather than a `0:00` placeholder).
     */
    fun startRecording() {
        voiceJob?.cancel()
        val maxSec = state.value.composer.audioMaxDurationSec
        state.update {
            it.copy(
                composer = it.composer.copy(
                    audioChooserVisible = false,
                    voiceNotice = null,
                    voice = VoiceInputState.Recording(elapsedSec = 0, maxSec = maxSec),
                ),
            )
        }
        voiceJob = scope.launch {
            audioRecorder.start(maxSec)
            // Mirror elapsed time until capture reaches a terminal state
            // (Finished or Failed) — collecting only on `!is Finished` would
            // hang forever on the Failed/Idle path.
            audioRecorder.state
                .takeWhile { !it.isTerminal }
                .collect { recordingState ->
                    if (recordingState is RecordingState.Recording) {
                        state.update {
                            it.copy(
                                composer = it.composer.copy(
                                    voice = VoiceInputState.Recording(
                                        elapsedSec = recordingState.elapsedSec,
                                        maxSec = recordingState.maxSec,
                                    ),
                                ),
                            )
                        }
                    }
                }
            when (val terminal = audioRecorder.state.value) {
                is RecordingState.Finished -> transcribeClip(terminal.path)
                RecordingState.Failed -> {
                    state.update { it.copy(composer = it.composer.copy(voice = VoiceInputState.Idle)) }
                    _voiceErrorEvents.tryEmit(Unit)
                }
                // Idle here means a concurrent cancel raced in; just reset.
                else -> state.update { it.copy(composer = it.composer.copy(voice = VoiceInputState.Idle)) }
            }
        }
    }

    /** Stops the in-flight capture; the recorder transitions to finished and transcription runs. */
    fun onStopRecording() {
        scope.launch { audioRecorder.stop() }
    }

    /** Discards the in-flight capture and its clip, returning the composer to idle. */
    fun onDiscardRecording() {
        voiceJob?.cancel()
        voiceJob = null
        audioRecorder.cancel()
        state.update { it.copy(composer = it.composer.copy(voice = VoiceInputState.Idle)) }
    }

    /**
     * Imports a user-picked audio file and transcribes it.
     *
     * @param uri content URI string of the picked audio document.
     */
    fun onAudioFilePicked(uri: String) {
        voiceJob?.cancel()
        state.update {
            it.copy(
                composer = it.composer.copy(
                    audioChooserVisible = false,
                    voiceNotice = null,
                    voice = VoiceInputState.Transcribing,
                ),
            )
        }
        voiceJob = scope.launch {
            val path = audioCaptureStore.importFromUri(uri).getOrNull()
            if (path == null) {
                state.update { it.copy(composer = it.composer.copy(voice = VoiceInputState.Idle)) }
                _voiceErrorEvents.tryEmit(Unit)
                return@launch
            }
            transcribeClip(path)
        }
    }

    /**
     * Runs the clip at [path] through [TranscribeAudioUseCase] and folds the
     * outcome back into the composer: success drops the transcript into the input
     * field; the blocked outcomes raise the matching calm notice; a hard failure
     * pings the error snackbar.
     */
    private suspend fun transcribeClip(path: String) {
        state.update {
            it.copy(composer = it.composer.copy(voice = VoiceInputState.Transcribing, voiceNotice = null))
        }
        when (val outcome = transcribeAudioUseCase(path)) {
            is TranscriptionOutcome.Success -> state.update {
                it.copy(
                    composer = it.composer.copy(
                        voice = VoiceInputState.Idle,
                        value = mergeTranscript(it.composer.value, outcome.transcript),
                    ),
                )
            }

            TranscriptionOutcome.NoActiveModel,
            TranscriptionOutcome.ModelNotAudioCapable,
            -> setVoiceNotice(ComposerVoiceNotice.NoAudioModel)

            TranscriptionOutcome.EngineBusy -> {
                // The use case keeps the clip on busy for a retry; this UI re-records
                // instead of retrying the same path, so drop the orphan.
                audioCaptureStore.delete(path)
                setVoiceNotice(ComposerVoiceNotice.EngineBusy)
            }

            is TranscriptionOutcome.Failed -> {
                state.update { it.copy(composer = it.composer.copy(voice = VoiceInputState.Idle)) }
                _voiceErrorEvents.tryEmit(Unit)
            }
        }
    }

    private fun setVoiceNotice(notice: ComposerVoiceNotice) {
        state.update {
            it.copy(composer = it.composer.copy(voice = VoiceInputState.Idle, voiceNotice = notice))
        }
    }

    /**
     * Appends [transcript] to the existing composer [current] text (space-joined)
     * so a transcription adds to, rather than clobbers, anything already typed.
     */
    private fun mergeTranscript(current: String, transcript: String): String = when {
        transcript.isBlank() -> current
        current.isBlank() -> transcript
        else -> "${current.trimEnd()} $transcript"
    }
}

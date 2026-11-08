package app.knotwork.android.presentation.screenshots

import org.robolectric.annotation.Config

/**
 * The shared app-screen screenshots ([TranslatedScreensSnapshotTest]) in Spanish, at
 * font scale 200 %; baselines `src/test/snapshots/es_*_fontscale200_light.png`.
 */
@Config(qualifiers = "es-w360dp-h760dp-xhdpi")
class SpanishScreensSnapshotTest : TranslatedScreensSnapshotTest() {

    override val language: String = "es"

    override val samples: SampleTexts = SampleTexts(
        memories = listOf(
            "Prefiere respuestas cortas en forma de lista, sin introducciones.",
            "Trabaja en Santiago; su zona horaria es la de Chile.",
            "El jueves a las 15:00, reunión del equipo.",
            "Está aprendiendo ruso y pide respuestas en ruso los fines de semana.",
        ),
        chatName = "Planes de la semana",
        researchTask = "Investigación: el café en América Latina",
        queueStage = "Procesador de cola",
        digestTask = "Resumen de la mañana",
        digestPrompt = "Reúne las noticias de mis temas",
        reportTask = "Informe del viaje",
    )
}

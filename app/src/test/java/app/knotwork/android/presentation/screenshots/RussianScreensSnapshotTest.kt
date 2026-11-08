package app.knotwork.android.presentation.screenshots

import org.robolectric.annotation.Config

/**
 * The shared app-screen screenshots ([TranslatedScreensSnapshotTest]) in Russian, at
 * font scale 200 %; baselines `src/test/snapshots/ru_*_fontscale200_light.png`.
 */
@Config(qualifiers = "ru-w360dp-h760dp-xhdpi")
class RussianScreensSnapshotTest : TranslatedScreensSnapshotTest() {

    override val language: String = "ru"

    override val samples: SampleTexts = SampleTexts(
        memories = listOf(
            "Предпочитает короткие ответы списком, без вступлений.",
            "Работает в Сантьяго; часовой пояс — Чили.",
            "В четверг в 15:00 созвон команды.",
            "Учит испанский и просит отвечать на нём по выходным.",
        ),
        chatName = "Планы на неделю",
        researchTask = "Исследование: кофе в Латинской Америке",
        queueStage = "Обработчик очереди",
        digestTask = "Утренняя сводка",
        digestPrompt = "Собери новости по моим темам",
        reportTask = "Отчёт о поездке",
    )
}

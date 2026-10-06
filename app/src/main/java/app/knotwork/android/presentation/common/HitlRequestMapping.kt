package app.knotwork.android.presentation.common

import app.knotwork.android.domain.models.ApprovalRequestContext
import app.knotwork.android.domain.models.ApprovalRequestSource
import app.knotwork.design.components.chat.HitlRequestContext
import app.knotwork.design.components.chat.HitlRequestSource

/**
 * Maps what the run behind an approval was asked to do onto the catalog's
 * model of it — the one translation the approval card and the approval
 * notification both read, so the two cannot name a source differently.
 *
 * The text is passed through as the domain made it: one display-safe line,
 * already clamped for the card.
 *
 * @return the catalog request model.
 */
fun ApprovalRequestContext.toHitlRequestContext(): HitlRequestContext = HitlRequestContext(
    source = when (val from = source) {
        ApprovalRequestSource.Chat -> HitlRequestSource.Chat
        ApprovalRequestSource.Shared -> HitlRequestSource.Shared
        is ApprovalRequestSource.Trigger -> HitlRequestSource.Trigger(name = from.name)
        ApprovalRequestSource.ScheduledTask -> HitlRequestSource.ScheduledTask
        ApprovalRequestSource.QuickTile -> HitlRequestSource.QuickTile
        ApprovalRequestSource.OtherApp -> HitlRequestSource.OtherApp
    },
    request = request,
    shortened = shortened,
    hadImage = hadImage,
)

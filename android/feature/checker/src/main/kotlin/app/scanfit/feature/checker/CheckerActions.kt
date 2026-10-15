package app.scanfit.feature.checker

import app.scanfit.core.match.CheckerTarget
import app.scanfit.core.match.DocKind

/** The screen's callbacks, so previews and tests can render it without a view model. */
internal class CheckerActions(
    val onBack: () -> Unit = {},
    val onChoosePhoto: () -> Unit = {},
    val onChooseFile: () -> Unit = {},
    val onKind: (DocKind) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onTarget: (CheckerTarget) -> Unit = {},
    val onChangeExam: () -> Unit = {},
    val onFix: () -> Unit = {},
    val onConfirmHandwriting: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onShare: () -> Unit = {},
)

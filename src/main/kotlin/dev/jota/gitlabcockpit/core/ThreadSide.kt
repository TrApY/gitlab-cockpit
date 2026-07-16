package dev.jota.gitlabcockpit.core

/**
 * Which diff side a *new* review thread anchors to: the head (**NEW**) side or the base (**OLD**)
 * side. Distinct from [AnchorSide] (which classifies an *existing* thread's position) so the
 * new-thread flow can default to the new side; the two happen to carry the same information.
 *
 * Shared by [dev.jota.gitlabcockpit.ui.ChangesPanel]'s new-thread dialog and the diff-editor
 * "New comment at caret" action ([dev.jota.gitlabcockpit.ui.diff.CockpitCommentHandle]), which is why
 * it lives in `core` rather than being private to the panel.
 */
enum class ThreadSide { NEW, OLD }

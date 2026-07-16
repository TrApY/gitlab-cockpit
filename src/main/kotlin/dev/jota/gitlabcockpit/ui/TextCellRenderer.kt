package dev.jota.gitlabcockpit.ui

import com.intellij.ui.SimpleListCellRenderer
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * A text-only [ListCellRenderer] for combo boxes and lists, replacing the deprecated-for-removal
 * `SimpleListCellRenderer.create(String, Function)` factory. The [SimpleListCellRenderer] class
 * itself is not deprecated, so we subclass it and override [SimpleListCellRenderer.customize].
 *
 * [textOf] is only ever invoked with a non-null element; a null element (the empty combo/list slot)
 * renders as [nullText]. This keeps the call-site lambdas free of null handling.
 */
fun <T> textCellRenderer(nullText: String = "", textOf: (T) -> String): ListCellRenderer<T?> =
    object : SimpleListCellRenderer<T?>() {
        override fun customize(
            list: JList<out T?>,
            value: T?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            text = value?.let(textOf) ?: nullText
        }
    }

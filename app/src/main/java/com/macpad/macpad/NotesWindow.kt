package com.macpad.macpad

import android.view.View
import android.widget.TextView

/** Simple text editor ("Заметки"). STUB — replaced by the real implementation. */
class NotesWindow(host: DesktopHost) : MacWindow(host, "notes") {
    init {
        title = "Заметки"
    }

    override val icon: BuiltinIcon get() = BuiltinIcon.NOTES
    override fun onCreateContent(): View = TextView(context)
}

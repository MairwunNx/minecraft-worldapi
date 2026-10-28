package com.mairwunnx.worldapi.connections

/** What the in-game wizards show: lines of text and buttons, which each platform turns into its own chat components. */
sealed interface ChatLine {
    enum class Tone { PLAIN, GOOD, BAD, HINT }

    data class Text(val text: String, val tone: Tone = Tone.PLAIN) : ChatLine

    /** Buttons on one line; a button runs a command or copies text, never both. */
    data class Buttons(val buttons: List<Button>) : ChatLine

    data class Button(val label: String, val hover: String, val run: String? = null, val copy: String? = null, val url: String? = null)
}

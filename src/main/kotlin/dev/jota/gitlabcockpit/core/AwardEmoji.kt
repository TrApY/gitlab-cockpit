package dev.jota.gitlabcockpit.core

/**
 * The emoji-reactions vocabulary (GLC-40): the standard GitLab quick-reaction set and the pure
 * mapping from a GitLab emoji name (`thumbsup`, `tada`, `heart`…) to its Unicode character. The
 * timeline renders reactions as Unicode text (no SVGs), so this is the single source that turns the
 * award `name` GitLab returns into what the chip shows. Pure and platform-free so it can be unit tested.
 */
object AwardEmoji {

    /**
     * GitLab's standard MR quick-reaction set, in the order the "add reaction" popup offers them:
     * 👍 👎 😄 🎉 😕 ❤️ 🚀 👀. Each pair is `gitlabName to unicode`; [emojiFor] and the popup both read it.
     */
    val STANDARD: List<Pair<String, String>> = listOf(
        "thumbsup" to "👍",     // 👍
        "thumbsdown" to "👎",   // 👎
        "smile" to "😄",        // 😄
        "tada" to "🎉",         // 🎉
        "confused" to "😕",     // 😕
        "heart" to "❤️",        // ❤️
        "rocket" to "🚀",       // 🚀
        "eyes" to "👀",         // 👀
    )

    private val byName: Map<String, String> = STANDARD.toMap()

    /**
     * The Unicode character for GitLab emoji [name], or null when it is outside the standard set
     * (GitLab supports hundreds of names; only the standard MR reactions are mapped). Pure.
     */
    fun emojiFor(name: String): String? = byName[name]

    /**
     * What a reaction chip shows for GitLab emoji [name]: its Unicode character when known, otherwise
     * a `:name:` fallback so an unmapped reaction from the server still reads as itself. Pure.
     */
    fun display(name: String): String = byName[name] ?: ":$name:"
}

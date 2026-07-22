package dev.jota.gitlabcockpit.core

/**
 * The composer's emoji catalog (GLC-56): a curated, categorized set with searchable English names —
 * the full-picker counterpart of the small [AwardEmoji] reactions row. Pure and platform-free so
 * [search] is unit-testable; the picker popup renders [CATEGORIES] as sections and re-renders the
 * flat [search] result while typing.
 */
object EmojiCatalog {

    /** One pickable emoji: the [name] the search matches on and the [emoji] character inserted. */
    data class Entry(val name: String, val emoji: String)

    /**
     * Emoji-capable fonts in preference order — the same list the platform's reaction picker uses
     * (CodeReviewReactionsUIUtil), so emojis render in color on every OS instead of falling back to
     * monochrome glyphs (notably on Linux). The UI picks the first one installed.
     */
    val PREFERRED_FONTS: List<String> =
        listOf("Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji")

    /** One picker section: its [title] and its [entries] in display order. */
    data class Category(val title: String, val entries: List<Entry>)

    private fun e(name: String, emoji: String) = Entry(name, emoji)

    val CATEGORIES: List<Category> = listOf(
        Category(
            "People",
            listOf(
                e("grinning face", "😀"), e("smiling face with big eyes", "😃"), e("smiling face", "😄"),
                e("beaming face", "😁"), e("grinning squinting", "😆"), e("sweat smile", "😅"),
                e("rofl", "🤣"), e("joy tears", "😂"), e("slightly smiling", "🙂"), e("upside down", "🙃"),
                e("wink", "😉"), e("blush", "😊"), e("innocent halo", "😇"), e("smiling hearts", "🥰"),
                e("heart eyes", "😍"), e("star struck", "🤩"), e("kiss", "😘"), e("kissing", "😗"),
                e("yum tongue", "😋"), e("tongue out", "😛"), e("wink tongue", "😜"), e("zany", "🤪"),
                e("money mouth", "🤑"), e("hug", "🤗"), e("hand over mouth", "🤭"), e("shushing", "🤫"),
                e("thinking", "🤔"), e("zipper mouth", "🤐"), e("raised eyebrow", "🤨"), e("neutral", "😐"),
                e("expressionless", "😑"), e("no mouth", "😶"), e("smirk", "😏"), e("unamused", "😒"),
                e("eye roll", "🙄"), e("grimacing", "😬"), e("lying pinocchio", "🤥"), e("relieved", "😌"),
                e("pensive", "😔"), e("sleepy", "😪"), e("drooling", "🤤"), e("sleeping", "😴"),
                e("mask", "😷"), e("thermometer sick", "🤒"), e("head bandage", "🤕"), e("nauseated", "🤢"),
                e("vomiting", "🤮"), e("sneezing", "🤧"), e("hot face", "🥵"), e("cold face", "🥶"),
                e("woozy", "🥴"), e("dizzy knocked out", "😵"), e("exploding head", "🤯"),
                e("cowboy", "🤠"), e("party face", "🥳"), e("sunglasses cool", "😎"), e("nerd", "🤓"),
                e("monocle", "🧐"), e("confused", "😕"), e("worried", "😟"), e("slightly frowning", "🙁"),
                e("open mouth", "😮"), e("hushed", "😯"), e("astonished", "😲"), e("flushed", "😳"),
                e("pleading", "🥺"), e("frowning open mouth", "😦"), e("anguished", "😧"),
                e("fearful", "😨"), e("anxious sweat", "😰"), e("sad relieved", "😥"), e("crying", "😢"),
                e("loudly crying", "😭"), e("screaming", "😱"), e("confounded", "😖"),
                e("persevering", "😣"), e("disappointed", "😞"), e("downcast sweat", "😓"),
                e("weary", "😩"), e("tired", "😫"), e("yawning", "🥱"), e("triumph steam", "😤"),
                e("angry pouting", "😡"), e("angry", "😠"), e("cursing", "🤬"), e("devil smiling", "😈"),
                e("devil angry", "👿"), e("skull", "💀"), e("clown", "🤡"), e("ghost", "👻"),
                e("alien", "👽"), e("robot", "🤖"),
            ),
        ),
        Category(
            "Gestures",
            listOf(
                e("thumbs up", "👍"), e("thumbs down", "👎"), e("ok hand", "👌"), e("pinched fingers", "🤌"),
                e("victory peace", "✌️"), e("crossed fingers", "🤞"), e("love you gesture", "🤟"),
                e("horns", "🤘"), e("call me", "🤙"), e("point left", "👈"), e("point right", "👉"),
                e("point up", "👆"), e("point down", "👇"), e("raised hand", "✋"), e("raised back", "🤚"),
                e("wave", "👋"), e("clap", "👏"), e("open hands", "👐"), e("raising hands", "🙌"),
                e("handshake", "🤝"), e("pray thanks please", "🙏"), e("writing hand", "✍️"),
                e("muscle strong", "💪"), e("fist", "✊"), e("fist bump", "👊"),
                e("eyes looking", "👀"), e("eye", "👁️"), e("brain", "🧠"), e("shrug person", "🤷"),
                e("facepalm person", "🤦"),
            ),
        ),
        Category(
            "Nature",
            listOf(
                e("dog", "🐶"), e("cat", "🐱"), e("mouse", "🐭"), e("rabbit", "🐰"), e("fox", "🦊"),
                e("bear", "🐻"), e("panda", "🐼"), e("koala", "🐨"), e("lion", "🦁"), e("cow", "🐮"),
                e("pig", "🐷"), e("frog", "🐸"), e("monkey", "🐵"), e("penguin", "🐧"), e("bird", "🐦"),
                e("chick", "🐤"), e("unicorn", "🦄"), e("bee", "🐝"), e("bug", "🐛"), e("butterfly", "🦋"),
                e("snail", "🐌"), e("turtle", "🐢"), e("snake", "🐍"), e("octopus", "🐙"),
                e("whale", "🐳"), e("dolphin", "🐬"), e("fish", "🐟"), e("crab", "🦀"),
                e("tree", "🌳"), e("cactus", "🌵"), e("four leaf clover", "🍀"), e("rose", "🌹"),
                e("sunflower", "🌻"), e("sun", "☀️"), e("moon", "🌙"), e("star", "⭐"),
                e("cloud", "☁️"), e("rain", "🌧️"), e("snowflake", "❄️"), e("lightning", "⚡"),
                e("fire", "🔥"), e("rainbow", "🌈"), e("earth globe", "🌍"),
            ),
        ),
        Category(
            "Food",
            listOf(
                e("apple", "🍎"), e("banana", "🍌"), e("watermelon", "🍉"), e("grapes", "🍇"),
                e("strawberry", "🍓"), e("lemon", "🍋"), e("peach", "🍑"), e("avocado", "🥑"),
                e("tomato", "🍅"), e("corn", "🌽"), e("carrot", "🥕"), e("bread", "🍞"),
                e("cheese", "🧀"), e("bacon", "🥓"), e("hamburger", "🍔"), e("fries", "🍟"),
                e("pizza", "🍕"), e("hot dog", "🌭"), e("taco", "🌮"), e("sushi", "🍣"),
                e("ramen noodles", "🍜"), e("spaghetti", "🍝"), e("egg", "🥚"), e("salad", "🥗"),
                e("popcorn", "🍿"), e("cake", "🍰"), e("birthday cake", "🎂"), e("cookie", "🍪"),
                e("chocolate", "🍫"), e("candy", "🍬"), e("ice cream", "🍨"), e("donut", "🍩"),
                e("coffee", "☕"), e("tea", "🍵"), e("beer", "🍺"), e("beers cheers", "🍻"),
                e("wine", "🍷"), e("cocktail", "🍸"), e("champagne", "🍾"),
            ),
        ),
        Category(
            "Activities",
            listOf(
                e("soccer football", "⚽"), e("basketball", "🏀"), e("american football", "🏈"),
                e("baseball", "⚾"), e("tennis", "🎾"), e("volleyball", "🏐"), e("padel racket", "🎾"),
                e("ping pong", "🏓"), e("badminton", "🏸"), e("goal net", "🥅"), e("golf", "⛳"),
                e("bullseye dart", "🎯"), e("gaming controller", "🎮"), e("dice", "🎲"),
                e("chess pawn", "♟️"), e("puzzle piece", "🧩"), e("bowling", "🎳"),
                e("trophy", "🏆"), e("medal gold", "🥇"), e("medal silver", "🥈"),
                e("medal bronze", "🥉"), e("running", "🏃"), e("swimming", "🏊"), e("biking", "🚴"),
                e("weight lifting", "🏋️"), e("skiing", "⛷️"), e("guitar", "🎸"), e("piano", "🎹"),
                e("microphone", "🎤"), e("headphones", "🎧"), e("art palette", "🎨"),
                e("clapper movie", "🎬"), e("party popper", "🎉"), e("confetti", "🎊"),
                e("balloon", "🎈"), e("gift present", "🎁"),
            ),
        ),
        Category(
            "Travel",
            listOf(
                e("car", "🚗"), e("taxi", "🚕"), e("bus", "🚌"), e("racing car", "🏎️"),
                e("police car", "🚓"), e("ambulance", "🚑"), e("fire engine", "🚒"), e("truck", "🚚"),
                e("tractor", "🚜"), e("motorcycle", "🏍️"), e("bicycle", "🚲"), e("train", "🚆"),
                e("metro", "🚇"), e("airplane", "✈️"), e("rocket", "🚀"), e("helicopter", "🚁"),
                e("ship", "🚢"), e("sailboat", "⛵"), e("anchor", "⚓"), e("fuel pump", "⛽"),
                e("traffic light", "🚦"), e("map", "🗺️"), e("compass", "🧭"), e("beach", "🏖️"),
                e("mountain", "⛰️"), e("camping tent", "⛺"), e("house", "🏠"), e("office", "🏢"),
                e("hospital", "🏥"), e("bank", "🏦"), e("hotel", "🏨"), e("school", "🏫"),
                e("statue of liberty", "🗽"), e("stadium", "🏟️"),
            ),
        ),
        Category(
            "Objects",
            listOf(
                e("laptop", "💻"), e("desktop computer", "🖥️"), e("keyboard", "⌨️"), e("mouse computer", "🖱️"),
                e("phone", "📱"), e("telephone", "☎️"), e("printer", "🖨️"), e("camera", "📷"),
                e("video camera", "📹"), e("tv", "📺"), e("radio", "📻"), e("battery", "🔋"),
                e("plug", "🔌"), e("bulb idea", "💡"), e("flashlight", "🔦"), e("candle", "🕯️"),
                e("book", "📖"), e("books", "📚"), e("notebook", "📓"), e("memo pencil", "📝"),
                e("pencil", "✏️"), e("pen", "🖊️"), e("paperclip", "📎"), e("scissors", "✂️"),
                e("ruler", "📏"), e("pushpin", "📌"), e("folder", "📁"), e("calendar", "📅"),
                e("chart up", "📈"), e("chart down", "📉"), e("clipboard", "📋"), e("envelope", "✉️"),
                e("package box", "📦"), e("lock", "🔒"), e("unlock", "🔓"), e("key", "🔑"),
                e("hammer", "🔨"), e("wrench", "🔧"), e("screwdriver", "🪛"), e("gear", "⚙️"),
                e("toolbox", "🧰"), e("magnet", "🧲"), e("microscope", "🔬"), e("telescope", "🔭"),
                e("magnifier search", "🔍"), e("bell", "🔔"), e("hourglass", "⌛"),
                e("alarm clock", "⏰"), e("stopwatch", "⏱️"), e("watch", "⌚"), e("bomb", "💣"),
                e("shield", "🛡️"), e("trash wastebasket", "🗑️"), e("money bag", "💰"),
                e("dollar banknote", "💵"), e("euro banknote", "💶"), e("credit card", "💳"),
                e("gem diamond", "💎"),
            ),
        ),
        Category(
            "Symbols",
            listOf(
                e("red heart", "❤️"), e("orange heart", "🧡"), e("yellow heart", "💛"),
                e("green heart", "💚"), e("blue heart", "💙"), e("purple heart", "💜"),
                e("black heart", "🖤"), e("broken heart", "💔"), e("sparkling heart", "💖"),
                e("hundred points", "💯"), e("collision boom", "💥"), e("sparkles", "✨"),
                e("dizzy stars", "💫"), e("sweat droplets", "💦"), e("dash wind", "💨"),
                e("speech bubble", "💬"), e("thought bubble", "💭"), e("zzz", "💤"),
                e("check mark green", "✅"), e("check mark", "✔️"), e("cross mark", "❌"),
                e("warning", "⚠️"), e("no entry", "⛔"), e("prohibited", "🚫"),
                e("question mark", "❓"), e("exclamation mark", "❗"), e("plus", "➕"),
                e("minus", "➖"), e("divide", "➗"), e("infinity", "♾️"),
                e("recycle", "♻️"), e("trade mark", "™️"), e("copyright", "©️"),
                e("arrow right", "➡️"), e("arrow left", "⬅️"), e("arrow up", "⬆️"),
                e("arrow down", "⬇️"), e("arrows counterclockwise", "🔄"), e("top", "🔝"),
                e("new", "🆕"), e("free", "🆓"), e("ok button", "🆗"), e("sos", "🆘"),
                e("star glowing", "🌟"), e("music note", "🎵"), e("music notes", "🎶"),
                e("bell notification", "🛎️"), e("flag red", "🚩"), e("flag checkered", "🏁"),
                e("flag spain", "🇪🇸"),
            ),
        ),
    )

    /** Every entry, in category order — what an empty [search] returns. */
    val ALL: List<Entry> = CATEGORIES.flatMap { it.entries }

    /**
     * Case-insensitive substring search over the entry names; a blank [query] returns [ALL] (the
     * picker then renders by category). Word-start matches rank before mid-word ones so `fire` finds
     * the fire before the fire engine's mid-name hit, and duplicates (an emoji in no two categories)
     * are not possible by construction.
     */
    fun search(query: String): List<Entry> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return ALL
        val starts = mutableListOf<Entry>()
        val contains = mutableListOf<Entry>()
        for (entry in ALL) {
            val name = entry.name.lowercase()
            when {
                name.split(' ').any { it.startsWith(needle) } -> starts += entry
                name.contains(needle) -> contains += entry
            }
        }
        return starts + contains
    }
}

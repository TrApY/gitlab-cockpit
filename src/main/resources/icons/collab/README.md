Icons copied from IntelliJ Platform (intellij.platform.collaborationTools), © JetBrains, Apache License 2.0.

Loaded from the plugin's own resources so it never depends on that v2 module being on the runtime
classpath. `addEmoji.svg` / `addEmojiHovered.svg` are `CollaborationToolsIcons.AddEmoji` /
`AddEmojiHovered` (the resting / mouse-over states of the "add reaction" affordance, GLC-40 / iter4a).

`copyLink.svg` / `copyLink_dark.svg` are the New-UI chain-link glyph from the bundled Markdown plugin
(`icons/expui/editorActions/link.svg`, © JetBrains, Apache License 2.0). The platform's `AllIcons`
has no chain-link field — `AllIcons.Ide.Link` is a double-chevron `»`, not a chain — so the real chain
icon is vendored here and shared by every "copy link" affordance (GLC-43 C13).

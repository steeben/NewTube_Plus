# Translating NewTube

NewTube is translated on **Weblate**: https://hosted.weblate.org/projects/newtube/
You translate in the browser. Weblate collects the work and opens a pull request in this repository,
and merged translations ship in the next release.

## What to translate

The project has two parts:

| Weblate component | What it covers | Where it lives | Status |
|:--|:--|:--|:--|
| **App** | Everything built for the phone: the home feed, the player, comments, downloads, casting, sign-in, updates | `smarttubetv/src/stmobile/res/values-*/strings_mobile.xml` | English and Spanish only: **start here** |
| **Shared settings and messages** | Settings screens, player menus and messages inherited from [SmartTube](https://github.com/yuliskov/SmartTube) | `common/src/main/res/values-*/strings.xml` | About 45 languages, some incomplete |

Some shared strings belong to SmartTube's TV interface and never appear on a phone. They are
marked read-only on Weblate, so you can skip them.

## Style

- Match the tone of YouTube's own app in your language. If YouTube calls it "Watch later", use its
  translation of "Watch later". People recognise those words.
- Keep it short. Most strings are buttons, menu items and one-line messages on a phone screen.
- Keep **NewTube**, **SmartTube**, **YouTube**, **SponsorBlock**, **DeArrow** and **Return YouTube
  Dislike** as they are.
- Keep placeholders such as `%1$s` and `%2$d` exactly as written. You can move them within the
  sentence. Weblate warns you if one goes missing.
- `%%` is a literal percent sign. Keep both characters.
- For counts ("1 reply", "5 replies"), Weblate shows one field for each plural form your language
  uses. Fill them all.

## Trying your translation

Merged translations ship in the next release, and every release is on the
[releases page](https://github.com/aleixrodriala/newtube/releases). Questions are welcome on the
[Discord](https://discord.gg/xu3v6euSHq).

To have your language added, start it on Weblate ("Start new translation"). If you think a string
is unclear or wrong in English, comment on it on Weblate, or
[open an issue](https://github.com/aleixrodriala/newtube/issues/new/choose).

## For developers

- Add new user-visible text to `smarttubetv/src/stmobile/res/values/strings_mobile.xml` (English),
  and add the Spanish version to `values-es/strings_mobile.xml`. Weblate picks up new strings
  from `main` on its own.
- Anything the user should not translate (formats such as `0:00`, brand names, glyph paths) gets
  `translatable="false"`.
- Use `<plurals>` and `getQuantityString` for counts, and one format string with numbered
  placeholders rather than joining pieces of a sentence in code.
- Don't edit `values-*/` files other than Spanish by hand. Weblate owns them, and a hand edit can
  clash with its next pull request.
- To check a translation pull request locally, run
  `./gradlew :smarttubetv:lintStmobileDebug -PtranslationLint`. This runs only the lint checks a
  translation can break (placeholders, plurals, strings missing from the English file).
  `.github/workflows/translations.yml` runs the same check on every pull request that changes
  string resources.

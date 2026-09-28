---
name: translation
description: Rules for translating the MkDocs pages under docs/docs into any configured target locale. Load whenever a MkDocs page is created or its English text changes.
---

# Translation

* MkDocs pages are structured per the `mkdocs-static-i18n` "suffix" layout configured in
  `docs/mkdocs.yml`:
  * English is the default locale and lives in the un-suffixed file, e.g. `quick-start.md`
  * Every other locale configured under `plugins.i18n.languages` lives in the sibling file with
    that locale's suffix, e.g. `quick-start.de.md` for `de`
  * Every English page under `docs/docs` MUST have a matching suffixed sibling for EVERY
    configured non-English locale, in the same directory
* The `translator` agent MUST be used to carry out a translation into any target locale;
  translating a page inline in the main session is FORBIDDEN
  * Applies whenever a new MkDocs page is added, or an existing page's English text changes
* The main session prepares the English page itself (adding or changing an English `.md` page is
  ordinary `project-docs` work), then hands the exact set of changed English pages to the
  `translator` agent to translate into each target locale's sibling
* The `translator` agent MUST be handed, for each page and target locale: the page path, the
  target locale, its full current English Markdown content, and - for a changed (not new) page -
  the previous English content so the agent can translate only what actually changed instead of
  retranslating the whole page

## Language quality

* Terminology MUST stay consistent across the whole documentation of a given target locale: the
  same domain word (e.g. "Plugin", "Manifest", "Sandbox", and their locale-specific equivalent for
  "extension point") is translated the same way everywhere it appears in that locale
* Tone MUST match the existing pages of that locale: direct, factual, no marketing language; use
  that language's established formal register for addressing the reader (e.g. "Sie" in German)
* Markdown structure MUST be preserved 1:1: heading levels, lists, tables, links, and MkDocs
  Material extensions (`admonition`, `pymdownx.details`, `pymdownx.superfences`,
  `pymdownx.highlight`, `pymdownx.inlinehilite`) keep their exact markup
* Code blocks, file paths, identifiers, CLI commands and Kotlin/Gradle snippets MUST NOT be
  translated or altered, regardless of target locale
  * This includes comments, string literals and printed/logged text inside a code block - they
    stay in English even on a translated page
* Per-locale glossaries of established terms for recurring domain words MUST be kept below, so new
  translations stay consistent with already-reviewed pages of that locale:
  * German (`de`):
    * "override" -> "Überschreibung"
    * "security exception" -> "Sicherheitsausnahme"
* A link that points to another page inside `docs/docs` MUST point to that page's sibling for the
  CURRENT locale - i.e. on a `.de.` page, a link to another `docs/docs` page MUST use that page's
  `.de.` filename, not the un-suffixed English one
* When the correct translation is ambiguous or depends on a product decision (a term with no
  established equivalent yet in that locale, for instance), the `translator` agent asks the main
  session instead of guessing, and the main session asks the user
  * Once resolved, the chosen term MUST be added to that locale's glossary above in the same change

## Navigation

* `docs/mkdocs.yml` carries the per-locale nav labels in
  `plugins.i18n.languages[locale: <code>].nav_translations`
* Every nav entry title used in the top-level `nav:` tree MUST have a matching key in
  `nav_translations` for EVERY configured non-English locale - a new page added to `nav:` MUST get
  its label added there, for each locale, in the same change
* The `translator` agent updates `nav_translations` for the locale it is translating into,
  together with the page content, when a new page or a renamed nav title is involved

## Verification

* After a translation is written, every English page under `docs/docs` MUST have its sibling for
  every configured non-English locale, and vice versa - a missing or an orphaned locale file is a
  build-breaking condition
* The MkDocs build MUST be run afterwards (through an agent, per the global concurrency rule) to
  confirm the site builds for every configured locale

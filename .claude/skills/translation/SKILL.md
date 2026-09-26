---
name: translation
description: Rules for translating the MkDocs pages under docs/docs. Load whenever a MkDocs page is created or its English text changes.
---

# Translation

* MkDocs pages are structured per the `mkdocs-static-i18n` "suffix" layout configured in
  `docs/mkdocs.yml`:
  * English is the default locale and lives in the un-suffixed file, e.g. `quick-start.md`
  * German lives in the sibling file with the `.de.` suffix, e.g. `quick-start.de.md`
  * Every English page under `docs/docs` MUST have a matching `.de.` sibling in the same directory
* The `translator` agent MUST be used to carry out a translation; translating a page inline in the
  main session is FORBIDDEN
  * Applies whenever a new MkDocs page is added, or an existing page's English text changes
* The main session prepares the English page itself (adding or changing an English `.md` page is
  ordinary `project-docs` work), then hands the exact set of changed English pages to the
  `translator` agent to translate into the German sibling
* The `translator` agent MUST be handed, for each page: the page path, its full current English
  Markdown content, and - for a changed (not new) page - the previous English content so the agent
  can translate only what actually changed instead of retranslating the whole page

## Language quality

* Terminology MUST stay consistent across the whole German documentation: the same domain word
  (e.g. "Plugin", "Erweiterungspunkt", "Manifest", "Sandbox") is translated the same way everywhere
  it appears
* Tone MUST match the existing German pages: direct, factual, no marketing language, addressing
  the reader formally ("Sie")
* Markdown structure MUST be preserved 1:1: heading levels, lists, tables, links, and MkDocs
  Material extensions (`admonition`, `pymdownx.details`, `pymdownx.superfences`,
  `pymdownx.highlight`, `pymdownx.inlinehilite`) keep their exact markup
* Code blocks, file paths, identifiers, CLI commands and Kotlin/Gradle snippets MUST NOT be
  translated or altered
* A link that points to another page inside `docs/docs` MUST keep pointing to the same page (the
  i18n plugin resolves the locale automatically - the target filename in the link stays the
  un-suffixed English one)
* When the correct translation is ambiguous or depends on a product decision (a term with no
  established German equivalent yet, for instance), the `translator` agent asks the main session
  instead of guessing, and the main session asks the user

## Navigation

* `docs/mkdocs.yml` carries the German nav labels in `plugins.i18n.languages[locale: de].nav_translations`
* Every nav entry title used in the top-level `nav:` tree MUST have a matching key in
  `nav_translations` - a new page added to `nav:` MUST get its German label added there in the
  same change
* The `translator` agent updates `nav_translations` together with the page content when a new page
  or a renamed nav title is involved

## Verification

* After a translation is written, every English page under `docs/docs` MUST have its `.de.`
  sibling, and vice versa - a missing or an orphaned locale file is a build-breaking condition
* The MkDocs build MUST be run afterwards (through an agent, per the global concurrency rule) to
  confirm the site builds for both locales

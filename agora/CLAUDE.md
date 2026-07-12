# CLAUDE.md — Knowledge Graph Platform (Agora)
## Architecture Framework & Implementation Specification

---

## 1. Vision & Positioning

Most knowledge tools store conclusions. This platform stores reasoning chains.

Every person has felt the frustration of being right but not believed — the philosopher whose argument is dismissed, the engineer whose insight is ignored, the thinker whose reasoning is solid but cannot be made to land. This platform solves that: it makes reasoning legible, traceable, and challengeable at the level of each individual step.

The underlying insight is that human knowledge, unlike mathematical proof, lives in a fuzzy, probabilistic space. Not because reality is uncertain, but because our collective confidence in any claim is always partial and evolving. This platform embraces that honestly rather than forcing false binary conclusions. It is, in spirit, a rebirth of fuzzy logic applied to human sciences — closer to Bayesian epistemology than to formal proof, closer to Proudhon's systematic philosophical argumentation than to a Wikipedia article.

The cognitive style this tool embodies — explicit implication chains, defined terms, traceable confidence — is how rigorous thinkers already reason. People who think in implications naturally, who are frustrated by ambiguous claims, who need to trace the chain before accepting a conclusion. The platform does not ask them to simplify. It matches their cognitive architecture. And it extends that rigour to anyone motivated to think more clearly — not changing their nature, but assisting their reasoning and positioning. Writing itself did not change human cognition; it extended and disciplined it. This is a similar leap.

The name is **Agora** (the Greek public space where arguments were made openly, to be challenged), short, deep, historically rooted in exactly the intellectual tradition this project belongs to. 

---

## 2. Core Concept — The Knowledge Item (KI)

The atomic unit of the platform is a **Knowledge Item (KI)**. A KI is an immutable logical implication: given a set of inputs already held to be true, a specific output follows. It is the formalization of a single reasoning step.

KIs are not claims about reality. They are claims about reasoning. The difference matters: a KI can be challenged not because the world is different, but because the reasoning step is flawed, the terms are ambiguous, or the inputs do not actually support the output.

### Granularity

KI granularity is the claimer's responsibility. The system imposes no minimum or maximum scope. A KI can be as broad as a book's thesis or as narrow as a single definitional step. The social validation process naturally drives decomposition: when a KI is too broad, challengers will find something to contest, and the claimer's only recourse is to split into smaller, independently defensible units. This mirrors mathematics exactly — lemmas exist because theorems needed separable chunks that could be proven independently. The invalidation pressure, not a top-down schema, drives the right granularity.

A KI that survives long without invalidation and without needing to be split is a signal of quality. Primitives — KIs with no inputs — emerge naturally from this process. It's not mandatory to declare them as axioms; they simply have no inputs that need defending. The system does not distinguish between a declared axiom and an undeveloped claim — that distinction is resolved by social pressure over time.

### Definitions as KIs

Definitions are KIs. A definition KI's output is a semantic contract: "in this graph, *quick* means 0-100 km/h duration." Crucially, definition KIs can themselves be challenged. Someone can argue that a definition excludes relevant cases, or that a term is being used inconsistently across KIs. There is no hard bottom to the graph — even primitives can be challenged on definitional grounds. This is honest. That is how human knowledge actually works.

Key terms within any KI should link to their definition KI in the graph. The authoring interface makes this a single gesture. When a term used in a KI already has multiple definition KIs in the graph, that is a graph-native ambiguity signal surfaced automatically during authoring.

### Independent Derivations — separate KIs, not disjunctive antecedents

A KI has **exactly one input set** — a single conjunction of premises ("these inputs, together, imply this output"). This matches the atom's meaning (one reasoning step) and the implementation: `content.:inputs` is a flat list of TNLRs, pinned in `computed.:pins`, indexed once in `AGORA_SUCCESSOR`. There are no *sets of sets*.

**Rejected alternative — disjunctive antecedents.** An earlier design let a single KI be reached via multiple independent input sets (an OR of AND-sets). We dropped it: it forces `inputs`/pins/the successor cache to become nested, complicating storage, resolution and authoring for a case that is rare; and it muddies ownership (whose conclusion is it when the arguments differ?).

**How the same conclusion, reached independently, is modelled instead.** As **two (or more) separate KIs** — each a single-conjunction implication, each individually owned, versioned and challengeable. Refuting one derivation withdraws *that* KI; the others are untouched (independent invalidation falls out for free). Their relationship is surfaced two ways, not by fusing them into one node:

- **Automatically** — *Convergence path detection* and *KI similarity detection* (see §8) flag independent chains reaching the same conclusion as a robustness signal / possible merge.
- **Optionally, declared** — a lightweight, opt-in **equivalence link** ("these KIs assert the same output") a claimer can add between sibling derivations.

This keeps each node atomic and the data model flat, preserves individual accountability, and reconstructs the "supported from independent directions" view via a relation rather than a nested-input structure.

### KI Types

Every KI carries an **epistemic kind** (field `kind`) the claimer chooses — one of values: `inference / prediction ...`. **As implemented today, the type is a plain, mutable label**: it drives the coloured badge and nothing else. It is *not* part of identity and can be changed by editing (see "Identity: object type vs epistemic type"). The per-type differences described below — distinct lifecycles, challenge mechanisms, confidence interpretation, a resolution date for verifiable claims — are **design intent for later layers, not current behaviour**. The values fall into three conceptual families:

**Derived KI** — the standard case. Has inputs, produces a logical consequence. Challenged by counterexample or ambiguity challenge. Confidence reflects how well the reasoning chain has survived scrutiny.

**Verifiable claim** — a KI whose truth will be settled by an external observable event, possibly at a future date. "Donald Trump will be president again" is a verifiable claim. Before resolution, challenges are about reasoning soundness. At resolution, reality speaks and debate closes. The KI transitions to a resolved state — confirmed or refuted — regardless of community confidence at that point. Resolution date is a first-class property.

**Declared foundation** — a KI with no inputs, not grounded in external observable reality, consciously declared as a starting point. Cannot be falsified by counterexample or reasoning — only contested by an incompatible declared foundation. 

All are processed identically by the system at MVP. In advanced layers, the challenge process may differ by variation — a postulate invites logical challenge, a credo invites value confrontation — and the system may route challenges accordingly. The vocabulary choice is also a signal to readers and challengers about what kind of response is appropriate, guiding behaviour naturally even before the system enforces it.

### Identity: object type vs epistemic type

The `T` in the identity tuple is the **object type**, not the epistemic type above. The store is single-table and polymorphic (PLM-style): it holds **KIs**, **Article** and, from Layer 2, **Objections** (the PLM "change" analog) — different object types with their own lineages, standing in the same table. Identity is **ObjectType + Name + Lang + Major + Minor**, with `object_type = "ki"` today and `"objection"` later. `Lang` (the content language) is part of identity: each language is its own independent lineage, and the language versions of one concept are tied together simply by sharing a `Name` (see "Language & Translation"). The epistemic `type` is deliberately *not* in identity.

The **epistemic kind** (`inference / prediction / ...`) is a **mutable attribute of a KI**, deliberately *not* part of identity. People revise how they classify a claim — often *in response to a challenge* (e.g. a "postulate" is shown to actually follow from other KIs and becomes "derived"). Reclassifying is therefore an ordinary **edit → new minor**, never a new object, and edges (which reference `Name + Major`) follow automatically. Putting the epistemic type in the identity would fork the lineage and break edges on every reclassification, contradicting "KIs are immutable, evolving nodes."

(Name uniqueness is automatic: `Name` is an opaque, randomly-generated **cid** (`document/gen-cid`), so two documents never clash regardless of title or author — no de-clashing or owner-scoping needed.)

### Language & Translation

A KI's **content language** is part of its identity (`Lang`). A concept can exist in several languages, and **the language versions are grouped by their shared `Name`** — there is no explicit "translation link", no group id. Creating a KI with an existing name in a new language automatically makes it a sibling. This is the "translation-by-name" model, chosen after rejecting a separate `translation_group` column.

Consequences:

- **Inputs are pinned per language.** A KI's inputs are stored in its immutable `content.:inputs`, each pinned by **TNLR** (which includes Language) to a concrete predecessor `id` — so a French KI's inputs point at French predecessors. Translating a KI creates target-language siblings of its inputs and pins to them; the read still **falls back** to another language when a translation doesn't exist yet (the UI shows a "a version exists in your language" banner when relevant). (This replaced the earlier language-neutral `(Name, Major)` edge table.)
- **`Name` is an opaque, stable `cid`** — a random 10-char base62 key (`document/gen-cid`), **never derived from the title**. It is the identity used in URLs, edges, `[[ki:…]]` citations and translation grouping, and it **never changes**, so editing the title never dangles an inbound citation or successor pin. (Seeded documents keep readable stable names — e.g. `type-inference` — which serve as their cids just as well; `kind-def` still links kinds by those names.) The human-readable heading is the separate, per-language, editable **`title`**. The URL carries a **decorative `<cid>~<title-slug>` key** (`document-domain/permalink-slug`, cid first) whose slug is regenerated from the current title on render; **resolution keeps only the cid** — the part before the first `~` (`document-domain/cid-of`, shared clj/cljs). So the URL tracks the current title (edit it and the slug updates, a stale slug still resolves) while every reference resolves by the immutable cid. Deleting the title→slug coupling made `document/author-scoped-slug`/`unique-name` obsolete.
- **Two independent language dials.** (1) A **content language**, fixed in the permalink `/agora/<lang>/ki/<cid>~<title-slug>/<major>` — sharing a link forces that language. (2) An **interface-language preference** — a stored user setting (localStorage for everyone; `AGORA_USER.lang` for logged-in users, loaded at login) that drives the chrome, the discover feed and search. Changing the preference never changes the language of a KI permalink you are viewing. Set on the Preferences page.
- Translation authoring copies the source text as a starting point and offers a best-effort machine-translation suggestion (MyMemory) the author validates; the title is translated too. Direct inputs are duplicated alongside so the immediate graph exists in the new language.

### Timestamp & Provenance

Every KI carries an immutable timestamp for each version — a proof of intellectual antecedence: the claimer formulated this reasoning before it became mainstream, before a paper covered it, before the fact resolved. It is public and indexed by Google (a strong social signal, though not yet cryptographically anchored — see *Integrity & timestamp anchoring*). A forked version carries its own timestamp; antecedence belongs to the original branch. Beyond convincing others, the claimer is protecting their intellectual authorship.

### Sources & References

Two layers: a **source** (the reusable *work* — a book, magazine, website, whatever) and a **quotation** (one idea from it). A **source** is an `AGORA_SOURCE` row `{id, person_id (author→AGORA_USER, may be login-less external), title, year, editor, url}` — shared, edited in place. A **quotation is a `kind=source` KI**: its `:text` is the quoted idea, its `content.:source = {:source-id :locator}` references the work + this quotation's own locator (page/verse/entry), and — because `kind=source` declares `:inputs? false` — it takes no inputs (a leaf). One source → **many** `kind=source` KIs (e.g. a magazine → one KI per cited ranking/entry), so you can list all quotations of a work. On read, `source/resolve-ref` turns `content.:source` into the work's display fields + locator.

**A normal KI relates to a source only by *quoting* it** — never by "attaching" one. Quoting uses the **classical citation mechanism** (the quote-search box), with one difference declared in the domain: `kind=source` sets **`:in-text? false`** (`document-domain/kind-quotes-in-text?`), so a source citation is an **input edge only** — it is *not* written into the prose. The read layer splits these `kind=source` inputs out of `:inputs` into a resolved **`:quotes`** list (title + work-author + locator); the client sends them as `:quotes [{:name :major}…]` (transient — `document/inputs-of` folds them into `:inputs`, and the editor resubmits the full list each edit since they aren't in the text to re-derive).

**Attribution flows from the quoted source:** a position's kind-guided opening ("David Fricke soutient que …") takes its subject from `document-domain/attributed-author`, priority `:source` author (for a `kind=source` KI itself) → **`:quote-author-name`** (the work-author of the KI's first source input, computed by the read layer) → the KI's own author. Authoring: the source-editor (pick/create a work + locator) shows **only when kind=source**; other KIs get the quote-search box instead. Sources render on the page, attribute the quoted author on discover cards, and are emitted as schema.org `citation`. `AGORA_SOURCE` was **dropped in Layer 1A then restored** (migration `006`) — the intermediate "source is a `type=source` document" model was reverted; a source is a `kind=source` KI + a shared `AGORA_SOURCE` work.

### Integrity & timestamp anchoring — designed, deferred

`content.:published-at` is self-asserted: a claim, backdatable, only as trustworthy as the operator. Fine while the graph is operator-seeded, but it does not survive a **skeptical reader** or a **priority dispute**. Intended mechanism (deferred, Layer 2+): a per-version **content hash chained to its predecessor** (tamper-*evident*, but not tamper-*proof* against the operator — a single-operator service has no proof-of-work/consensus), plus an **external anchor** — a periodic Merkle root committed to Bitcoin via **OpenTimestamps** (free, no chain to run). That yields an un-forgeable "existed by time T": the *upper bound* that wins a priority dispute (an OTS proof is bound to one hash, so it cannot be reused for other content, and a faked-early `:published-at` has no matching anchor). It is **not retroactive** (covers only content anchored after it ships) and deferring costs nothing — content is already immutable, so the hash + anchor are purely additive later.

---

## 3. Objection System

Objections are the primary mechanism by which KIs are challenged and strengthened. They replace what might otherwise be called commentaries or issues — a single, richer mechanism rather than two.

An objection is not a transient signal. It is a **permanent, visible part of the KI itself**. Every objection ever raised against a KI remains attached to it, with its full lifecycle: opening statement, discussion thread, and the author's conclusion. Nothing is deleted. The KI page is not just "claim + confidence score" — it is "claim + all objections ever raised + how each was addressed."

### Objection Types

Objections are typed, because their type shapes the discussion that follows:

**Counterexample** — "here is an instance where the implication fails." Directly challenges the validity of the KI. The author's response is to defend, narrow, or fork.

**Ambiguity challenge** — "this term has multiple valid readings, under at least one of which the KI is false or meaningless." Does not say the KI is wrong — says it is not yet well-formed. The author's response is to link the term to a definition KI or split into multiple KIs covering each reading.

### The Reader Experience

This is the most important consequence of the objection model. A reader who has a doubt does not need to open a new objection — they first read the existing ones. The answer is probably already there. The KI becomes self-documenting against its own weaknesses. The full intellectual history of the claim is readable, not hidden behind a score.

A new objection is only needed when the reader's doubt has not already been raised and addressed. The objection archive acts as a natural filter against redundant challenges.

### Objections and the Fork Mechanism

An objection does not change the KI — only a fork can do that. An objection is a challenge; a fork is a response that proposes a new version. The author may fork in response to a compelling objection, or may provide a conclusion within the objection thread that settles the challenge without requiring a fork.

### Undecidable KIs — Coexisting Bubbles

Some KIs are formally undecidable — no reasoning chain can derive them from shared primitives, no counterexample can falsify them. The existence of God is the clearest example. It exists as a Belief or Stance — a declared foundation — with objections on both sides that are permanent, unresolved, and epistemically equivalent.

In the graph, both the theist and atheist positions exist as declared foundations, each with their own objection histories, their own discussion threads, their own author conclusions. Neither bubble dominates the other. Both coexist permanently. The platform does not force resolution where none exists — it makes the debate legible and permanent instead. This is a feature, not a limitation.

This applies to any formally contested domain: free will, the nature of consciousness, foundational political values. The graph holds all positions with their full objection histories, and readers can navigate the landscape of disagreement rather than being handed a false resolution.

---

## 4. Confidence

With the objection system in place, confidence as a primary epistemic signal is largely superseded. A reader can judge the strength of a KI directly by reading its objections — their type, the quality of the discussion, and the author's conclusions. The objection archive is the epistemic core; a numerical score was always a proxy for it.

Confidence retains a residual role as a **navigation signal** — helping readers decide what to read first, surfacing active or contested KIs in discovery pages, and feeding graph analysis. But it is no longer the truth-bearing mechanism.

### Confidence as Navigation Signal

Rather than measuring epistemic strength directly, confidence reflects the **objection lifecycle state** of a KI:

- Objections with strong author conclusions and no further challenge — neutral to slightly positive
- Objections with no author response — negative
- Objections with ongoing unresolved discussion — strongly negative
- Attestations from registered contributors — positive

A KI with many resolved objections and strong author conclusions is more readable and trustworthy than a score alone could convey. The score helps navigate; the objections convey the truth.

### Structural Constraints

These remain valid regardless of how confidence is computed:

Confidence is **strictly local** — affected only by a KI's own objection history and input chain. Unconnected graph additions are invisible to it. No global normalization.

A KI's confidence is **capped by its weakest input**. You cannot be more certain of a consequence than you are of its least certain premise. Since a KI has a single input set (see "Independent Derivations"), this is one bound — the floor set by the weakest premise — not a per-path interval. When the *same conclusion* is reached by independent derivations (separate KIs, related by convergence/equivalence), the stronger derivation raises overall confidence in the conclusion — but that is a property of the **relation between KIs**, computed by graph analysis (§8), not of a single node's inputs.

Confidence **can decrease** when new knowledge reveals a fragile assumption in an input chain. This is correct behaviour, not a bug.

Confidence is stored in the database as a live computed value, not in the immutable KI content.

---

## 5. Versioning

KIs are immutable. There is no editing in place. Every modification produces a new version. The old version persists permanently, carries its own confidence score, and may diverge from the new version over time.

Versions are sibling nodes in the graph, linked by a typed **supersedes** edge. This keeps the graph model pure and immutability intact.

### Change Types (PLM-inspired)

Changes are classified by their semantic impact:

- **Clarification** — no semantic change, auto-propagates to successors, who are notified for awareness only
- **Scope change** — narrowing or expansion, successors must re-validate
- **Major change** — breaking, forces a new version node, successors enter a review-required state

The change requester suggests the type. The KI owner declares the downstream consequences. The community corrects over time if wrong. This is a try-and-error model — unlike PLM where the owner knows their domain deeply enough to predict impact, the graph here is too diverse for that assumption.

### Successor Connection Model

A KI-B that depends on KI-A connects to the **concept**, not a specific version. Three connection states are possible:

- **Implicit** — KI-B follows the latest minor version of KI-A automatically
- **Pinned** — KI-B explicitly stays on a chosen version after a major change
- **Migrated** — KI-B moves to a new major version, possibly producing KI-B-v2

Any contributor can fork KI-A to create their own branch, becoming its owner. If KI-B's author disagrees with a major version of KI-A, they can pin to the previous version and effectively become a branch maintainer. This mirrors the open source model: anyone can fork, the community decides which branch gains traction.

### Referential Integrity

A KI cannot claim strong support if one of its inputs is in broken or contested state. When a dependency is withdrawn, successors enter a broken input state and their confidence is recomputed. Status propagates upward through the dependency chain automatically. This gives the graph referential integrity — a structural property, not a declared policy.

### Consistency rules

Inputs and in-text `[[ki:…]]` citations are the same thing (an input edge is a citation, and vice-versa), so both are held to the same **consistency rules**. These are:

1. **No dangling reference.** Every reference must point at a KI lineage that exists (in some language — the read falls back across languages). A citation of a non-existent `name@major` is a *dangling reference*.
2. **No self-reference.** A document may not cite its own lineage (same `type + name + major`). A node cannot be an input of itself — that is a degenerate cycle. (Only KIs can violate this: citations always target `type = ki`, so an *article* citing a KI that merely shares its name is a different lineage and is allowed.)

Enforcement is layered:

- **At authoring time (UI):** the citation editor's search **removes the current document from its results**, so a KI can never be made to quote itself (`cite/citation-editor`'s `self-name` argument). Dangling references can't normally be authored because you cite by picking an existing KI (or creating one inline).
- **After the fact (admin):** `landing.agora.document/consistency-issues` scans **every version** of every document and reports both violations — `:broken` (dangling) and `:self` (self-reference) — surfaced on the admin page, each row deep-linking to the exact offending version. It's a *cache/consistency check*, not a hard DB constraint, so it can catch drift (e.g. a withdrawn lineage that leaves dangling citations behind).

---

## 6. Authoring & Ownership

### Identity Model

- **Read** — anonymous, fully open, no registration required
- **Contribute** — registered contributors only, via OAuth (Google), frictionless

### Fork as the Only Mutation

Every change to an existing KI is a fork. There is no editing in place, ever. A fork creates a new version node, with the contributor as its owner. The contributor may then submit a merge request to the original branch owner.

- **Merge accepted** — new version on the main branch, original owner remains
- **Merge rejected or ignored** — fork lives as an independent branch, accumulates its own confidence score and traction

This is the GitHub/GitLab pull request model applied to knowledge.

### Ownership Rules

- **Create** — any registered contributor (a create is a fork with no parent)
- **Modify** — always via fork, open to any contributor
- **Merge request** — submitted to branch owner, accepted or rejected
- **Withdraw** — branch owner only; withdrawal is visible, history always preserved
- **Collective ownership** — collectives are affiliation tags on individually-owned KIs, not first-class entities. Individual accountability is always preserved.

### Orphan Policy

If a claimer disappears, the KI is marked archived, following open source convention. A new branch appears if the community finds it useful.

### Notifications & Dashboard

Connected users need a view of what requires their attention: new Issues on their KIs, merge requests awaiting review, major version changes on KIs they depend on, successor KIs flagged for re-validation. This is a connected-user dashboard, not a public-facing feature.

---

## 7. Platform Model

### Actors & Motivations

Each actor has a selfish motivation that grows the platform as a side effect.

- **Platform owner (Anthony)** — legacy, humanity contribution, making rigorous reasoning available beyond the niche that naturally thinks this way
- **Claimer** — frustrated by being right but not believed; wants reasoning to speak for itself; political thinkers, philosophers, engineers, writers, systematic minds
- **Writer** — wants precision and credibility; every word in their article backed by a definition KI, every argument by a supporting KI
- **Reader** — consumes structured, trustworthy, navigable knowledge; presence motivates claimers and writers to produce

### Network Effect Chain

Claimers seed KIs → attracts readers → readers become challengers → challenges strengthen KIs → stronger KIs attract writers → articles cite KIs → articles attract new readers and claimers.

The loop is self-reinforcing. Each actor's selfish action grows value for the others. There is also a cognitive contagion effect: using the tool teaches more explicit reasoning, which makes users better claimers, which grows the graph.

### Platform Threshold

The threshold for the social layer to become valuable is **KI density in a domain**, not user count. One well-developed subgraph on a topic someone cares about is enough to attract the first challengers. The seeding strategy: the platform owner builds the first subgraph, dense enough to be interesting, which attracts first challengers, who attract readers, who attract more claimers.

### Frictionless Mechanics

- Read: anonymous, no barrier
- Contribute: OAuth login, single gesture
- Agora's article authoring: as frictionless as KI creation
- Linking a term in an article to a definition KI: single gesture, not search-and-copy
- Non agora's article authoring: referencing permalink, or reuse badge (as github pages badges)

### Discoverability

- Full text search from day one
- Discoverability page — curated public-facing view promoting content to anonymous visitors
- Graph-native ambiguity detection — surfaces related KIs automatically during authoring
- Contribution guidance — under-delved KIs pushed to relevant contributors (post-MVP)
- Articles as discovery vectors for KIs

### Trust Mechanism

- Confidence score — computed, not declared, primary trust signal
- Individual accountability — KIs always personally owned, never opaque
- Fork history — visible and traceable
- OAuth identity — contributors are not anonymous

### Monetization

**Confidence budget mechanic**: each contributor has a quota of low-confidence KIs they can have in flight. As KIs gain confidence through attestations and resolved challenges, they free up budget. Highly confident KIs cost nothing to maintain — the community has validated them, they are assets not liabilities.

- **Free tier** — read, plus a small confidence budget for authoring
- **Paid tier** — larger confidence budget for prolific claimers, researchers, writers
- **Later** — API access for researchers and AI training datasets, institutional subscriptions, writer monetization

This mechanic is epistemically aligned: it rewards precise thinking and responsiveness to challenges. Spray-and-pray doesn't work; budget runs out. Someone who writes well-scoped KIs that gain confidence fast is not penalised.

---

## 8. Graph Analysis & Inference (Post-MVP)

These features are deferred but architecturally important to anticipate in the data model.

- **Consensus bubble detection** — clusters of contributors who systematically co-validate KIs *and* resolve objections the same way. Bubble detection must operate at the objection conclusion level, not just the KI level — an epistemically closed group is one that validates each other's objection resolutions, not just each other's claims. Coexisting bubbles on undecidable KIs (god's existence, free will, foundational political values) are legitimate and permanent — the system surfaces them without ranking one above the other
- **Contradiction detection** — compatible inputs, incompatible outputs, surfaced not resolved
- **Orphan chain detection** — high confidence KIs with no successors, underexplored territory
- **Confidence fault lines** — sharp confidence drops in a chain, made structurally visible
- **Work investment fault lines** — KIs that are structurally important but under-worked
- **Convergence path detection** — multiple independent chains reaching the same KI, robustness signal
- **KI similarity detection** — same concept formulated independently by different contributors, merge suggested not forced; counterforce to unnecessary duplication from disambiguation pressure
- **Similar chain detection** — convergent reasoning paths not yet explicitly connected
- **Version propagation inference** — KI changed but downstream consequences unreviewed, automated impact detection
- **Contribution guidance** — personalised suggestions of where attention is most needed, based on under-delved KIs

---

## 9. Public Pages & Google Discoverability

Three distinct public page types exist from Layer 1. They are read-only surfaces, separate from the authoring interface, and are the platform's public face.

**KI page** — permanent URL per KI, public and crawlable. Displays the KI text, its input KIs, its confidence score, its commentary history, and its version lineage. The permanent URL is stable across versions — the page shows the latest version with lineage visible.

**Article page** — permanent URL per article, public and crawlable. The primary SEO surface. Every polysemic term in the article links to its definition KI page. Every argument links to its supporting KI page. Articles are the most readable entry point into the graph for new visitors.

**Discoverability page** — the platform homepage for anonymous visitors. Curated, not algorithmic at first. Showcases high-confidence KIs, recent articles, and active domains. Its purpose is to make the platform non-empty and compelling to the first visitor, before any social layer exists.

### Google Discoverability

- Semantic markup on all public pages — schema.org, OpenGraph
- Permanent URLs from day one — no URL changes across versions
- High-confidence KIs prioritised for indexing
- Articles as primary SEO surface — aggregated, readable, linkable
- Google discoverability is implemented in Layer 1 — cheap to do correctly from the start, very hard to retrofit

---

## 11. AI Assistance (Post-MVP)

AI acts as a pre-publication stress tester and authoring assistant:

- **Ambiguity detection** — graph-native: if a term used in a KI already has multiple definition KIs in the graph, the system flags it automatically during authoring
- **Definition suggestion** — proposes existing KIs that could anchor an undefined term
- **Decomposition suggestion** — when a KI appears too coarse, suggests splits
- **Counterexample search** — adversarially attempts to invalidate the KI before publication

This makes AI a challenger assistant built into the authoring flow — the opposite of how AI usually works (confirming, expanding). Here it is adversarial by design.

---

## 12. Feature Layers

> **Implementation status (current — the authoritative "what's built").** Layer 1 is built, plus much beyond the original slices. The slice list below is the historical MVP breakdown; every "PostgreSQL schema" in it is actually **MySQL** (`resources/agora/schema.sql` — one idempotent snapshot; the incremental migrations were removed once applied, since there is one DB), and the table is `AGORA_DOCUMENT` (KIs and articles unified in one polymorphic table — no separate article table). Built beyond Layer 1:
> - **Unified document engine** — KIs and articles are the same `type` rows over one engine (`landing.agora.document`); prose is one `:text` field (a KI's "statement" and an article's "body" merged). Full **article** authoring/editing/translation, not just seeded.
> - **i18n** — content language in identity, interface-language preference, translation-by-name, per-language `title`.
> - **Auth** — Google OAuth + email/password (#38); admin allowlist via `AGORA_ADMIN_EMAILS` env var. **People** = `AGORA_USER`, extended to hold login-less `provider='external'` cited authors (incl. the platform author "Agora"); each person has a public `/agora/<lang>/author/:id` profile.
> - **Sources** — a document cites **one** external bibliographic **source**, which as of **Layer 1A** is itself a `type="source"` **document** (the shared work; owner = cited author person; title/year/editor in content) — no separate table. The citing doc holds `content.:source = {:name :major :locator}`, resolved on read; authored via a search-modal + person-picker. Rendered on the page, attributed on discover cards, emitted as schema.org `citation`. See "Sources & References".
> - **Epistemic types are self-hosted** — each `kind` is described by a `definition`-kind KI (slug `type-<kind>`); the kind→definition link is domain data (`document-domain/kind-def`).
> - **SEO** — server-rendered OpenGraph + schema.org `Article` (`isBasedOn` inputs, `citation` sources), dynamic `sitemap.xml`, `robots.txt` (#39).
> - **Admin** — maintenance page (list/compact/drop lineages) + a **consistency scan** (dangling + self references, all versions).
> - **Timestamp anchoring** — designed, deferred (see "Integrity & timestamp anchoring").
>
> The Objection system, confidence score, and fork/merge remain deferred to Layer 2.

### Layer 1 — First Deployable

Built as vertical slices, each slice producing a working product. Definitions are KIs with no inputs — no special mechanism needed. Confidence score deferred. Objection system deferred. Article authoring UI deferred — article seeded manually in DB.

**Slice 1 — Hidden page displaying a seeded KI**
- PostgreSQL schema — KI identity (id, name, type, major, minor, output_statement_hash, timestamp)
- Inline text — KI statements are stored inline on the node row (no separate blob store)
- Clever Cloud deployment pipeline
- Clojure DB connection and basic KI query
- Basic API route — single KI by id
- ClojureScript + React bootstrap — fetches KI route, confirms data in browser
- KI display component — name, type badge, timestamp, output statement
- Hidden route — accessible by direct URL, not linked from landing

**Slice 2 — Second KI, link, navigation**
- PostgreSQL schema — KI edges (input KI → output KI, references Major only)
- API route — fetch KI with its input KIs and successor KIs
- KI page navigation — follow edges to connected KIs

**Slice 3 — Versioning in links**
- KI identity model enforced — ObjectType + Name + Major + Minor (object_type = "ki"; epistemic type is a mutable attribute, see "Identity: object type vs epistemic type")
- Auto-resolution to latest minor within referenced major — implemented as DB query utility
- Navigation reflects versioned links correctly

**Slice 3-bis — Article**
- Schema — article (id, title, body, timestamp)
- Article seeded manually in DB, body inline
- Article display component — title, body, timestamp
- Hidden route for article

**Slice 4 — Edit a KI**
- Edit form for connected users — modify text and type
- Immutability enforced — edit produces new minor version, not in-place mutation

**Slice 5 — Manage links**
- Drop an existing input link
- Search for an existing KI to add as input
- Create a new KI inflight during link addition

**Slice 6 — Create a KI from the interface**
- Full KI creation form — name, type, output statement, input links
- KI kind selector — inference, prediction, postulate, position, belief, credo
- Versioning identity assignment — Major/Minor on creation

**Slice 7 — Public KI page with permanent URL**
- Permanent URL structure: `/ki/{type}/{name}/{major}`
- Displays KI text, type badge, timestamp, input KIs, successor KIs
- Publicly accessible, no auth required

**Slice 8 — Discoverability page**
- Public homepage for anonymous visitors
- Curated list of KIs, manually maintained at first
- Search entry point

**Slice 9 — Full text search**
- PostgreSQL full text search over KI name and output statement
- Search UI on discoverability page and KI pages

**Slice 10 — OAuth login**
- Google OAuth
- Gates authoring only — all public pages remain anonymous
- Minimal user profile: OAuth provider ID, display name, email

**Slice 11 — Google discoverability**
- schema.org and OpenGraph markup on all public pages
- Sitemap.xml covering all public KI pages
- robots.txt

### Layer 2 — Social

- Objection system — typed (counterexample, ambiguity challenge), permanent, visible on KI page, full discussion thread and author conclusion
- Fork and merge request mechanism — fork creates new version, merge request submitted to branch owner
- Article authoring UI — structured text with KI-backed terms and arguments, each polysemic term links to definition KI
- Version propagation notifications — major version forces successor review, connected user dashboard
- Confidence budget — monetization mechanic, free vs paid tier
- KI similarity detection — merge suggestions, counterforce to disambiguation duplication
- Contribution guidance — under-delved KI suggestions

### Layer 3 — Advanced

- AI assistance during authoring — ambiguity detection, definition suggestion, counterexample search, decomposition suggestion
- Full graph analysis and inference engine — bubble detection at objection conclusion level, contradiction detection, convergence paths, version propagation inference
- API access for researchers and AI training datasets
- Institutional subscriptions
- Full monetization beyond confidence budget

---

## 13. Technical Architecture

### Stack

- **Backend** — Clojure (Ring/Reitit), part of the `landing` app; Agora is served under `/agora`.
- **Frontend** — ClojureScript + Reagent/re-frame, own shadow-cljs `:agora` build (SPA). Layered, type-agnostic engine + thin per-type facades: `document-page` (shared widget/chrome engine, no type knowledge) → `view`/`edit` (generic page composition + authoring, driven by a `cfg`) → `ki-page` / `article-page` / `author-page` (thin facades that supply each type's `cfg` and are what `core` calls). The strings `"ki"`/`"article"` live only in the facades.
- **Hosting** — Clever Cloud. **DB** — MySQL (the shared addon, also used by the contact form). The addon caps at **5 connections split across dev/la/prod**, so the HikariCP pool is small and per-env: `AGORA_DB_POOL_MAX` (default 2; recommend prod 2, la/dev 1).
- **Auth** — session cookie + Google OAuth + email/password (bcrypt). Admin allowlist is env-driven (`AGORA_ADMIN_EMAILS`).
- **Tables** (`resources/agora/schema.sql`, applied manually via `scripts/agora_db.clj`): `AGORA_DOCUMENT` (KIs **and** articles — one polymorphic table; `type` ∈ {ki, article}, where a `kind=source` KI is a bibliographic quotation), `AGORA_SUCCESSOR` (derived reverse-edge cache), `AGORA_USER` (accounts **and** login-less `provider='external'` cited people), `AGORA_SOURCE` (the shared bibliographic *work* a `kind=source` KI references). All text stored inline; no blob store.

### Read model — document + caches (reads ≫ writes)

Graph traversal and version resolution are **precomputed on write and cached**, never done in SQL per read. Engine: `landing.agora.document` (+ `landing.agora.node`, the SQL/cache adapter; `landing.agora.cache`, Caffeine). Pure wiring/pin/successor rules live in `landing.agora.document-domain` (cljc).

- **TNLR** = (Type, Name, Lang, major Release) — identity minus `minor`; names a lineage whose latest minor is current.
- **A document is a row keyed by `id`.** Columns are only the identity keys (`id`, `type`, `name`, `lang`, `major`, `minor`) so indexes never parse EDN. Everything else is two EDN blobs: **immutable `content`** (`{:kind :title :text :author :owner-id :published-at :inputs [TNLR…] :references [{:source-id :locator}…]}`, never updated after insert) and **mutable `computed`** (`{:pins {tnlr-key → id}}`). A KI's "statement" and an article's "body" are the same `:text` slot.
- **Inputs = the `[[ki:…]]` citations in `:text`** (parsed on write). An input's *declaration* (a TNLR) lives in `content.:inputs`; its *pin* (the exact predecessor id) in `computed.:pins`. Changing inputs → a new **minor** (same permalink; successors re-pin); re-resolving a pin does not, and is never done client-side.
- **Reverse edges = `AGORA_SUCCESSOR`** (input-TNLR → successor id) — a cache rebuilt incrementally on write and fully every 24h (`landing.agora.scheduler`).
- **References** are resolved (source-id → work + author) on read by `landing.agora.source` (cached); their authors are `AGORA_USER` people. Confidence, once computed, also lives in mutable `computed` — never in the immutable content.
- **Caches** (Caffeine) front everything (`id → document`, TNLR → successors/versions, name → translations); warm hits touch MySQL 0 times. Frontend keeps a bounded LRU (≤1000) of documents by id.

### Cost

MySQL addon + one Clever app; under €100/month at MVP. Full-text search is the main future cost variable (may defer to embeddings/vector search rather than Elasticsearch).

---

## 14. Integration Context

This project is integrated into the Hephaistox landing project. It shares the existing Clojure/ClojureScript stack and Clever Cloud hosting. It is positioned as a distinct product within the Hephaistox platform, not a standalone deployment. UI follows the existing dark Forge Artisan theme (copper/amber accents, Cormorant/Jost typefaces) unless a distinct visual identity is decided for the product.

---

## 15. Open Questions

The following questions are registered but not yet resolved. They should be addressed before or during the relevant implementation layer.

| #  | Question                                     | Relevant Layer                                                                            |
|----|----------------------------------------------|-------------------------------------------------------------------------------------------|
| 4  | Validation governance                        | Resolved — confidence score replaces discrete status, no governance body needed           |
| 16 | MVP definition                               | Resolved — Layer 1 (above) is the MVP; the earlier 1a/1b split was dropped as unnecessary |
| 23 | Confidence score formula enrichment          | Layer 2+                                                                                  |
| 24 | Epistemic community scoping                  | Layer 2+                                                                                  |
| 7  | Monetization detail beyond confidence budget | Layer 2+                                                                                  |

---

## 16. How we collaborate (transferable)

Notes on the working relationship, written for the next build — not this app's specifics, but the method. The shortest path to being useful.

**Explore the trade-offs before building; ask questions before implementing.** The best designs here came from *conversation before code*. Talking through how a thing will actually be used — and what it costs — surfaces design rules that a straight-to-implementation path silently gets wrong; a single question about real usage or a read/write ratio has reshaped a whole design more than once. So when a request carries a non-obvious trade-off, **ask first**: what is the real usage, what are we optimizing for, what breaks at scale. Treat the first cut as a hypothesis to probe together, not a spec to type in — a design is cheap to change in dialogue and expensive to change in a built app. This is the single biggest lever; use it before writing code, not after.

**When a feature has forks, settle them in one exchange — don't guess, don't drip-feed.** A real feature usually carries a couple of genuine design decisions (what does the public see, is every edit a draft, …). The move is neither to pick silently and build the wrong thing, nor to ask them one question at a time. Present the whole model up front — the storage, the lifecycle, the seams — then name the two or three actual forks, **each with a recommendation and its trade-off**, so the owner can answer "go" or flip one in a single reply. Batch the decisions; make them cheap to make. This is the efficient form of "ask before implementing": one decisive exchange beats both guessing and ping-pong.

**Design is dialogic; a real objection beats fast agreement.** The owner reasons out loud, proposes, revises, and converges *through* the exchange ("the more you speak about it, the clearer it is to me"). Propose with a recommendation and its trade-offs rather than executing the literal ask; when asked "do you have any objections?", give a genuine critique. Sycophancy actively wastes their time.

**Start from the domain, with concrete examples — make it live early.** Begin a build by enumerating real **use cases** and writing the pure **domain functions (cljc)** that satisfy them, exercised by worked examples, *before* any storage, endpoint, or UI. Infrastructure is slid in beneath a living domain, never scaffolded ahead of it. Do not open with schemas, routes, or plumbing; open with the domain and a handful of cases.

**Unify and derive; resist special cases and premature structure.** The repeated wins came from *collapsing a special case into the general model* and *deriving a value instead of storing it*. A new type, a new branch, or a stored copy must earn itself. When scoping anything new, ask first: *can this be the general thing? can this be derived?*

**Latitude scales with the signal — read it.** Default cadence: one vertical slice, stop for review; the owner commits and closes issues themselves (`Closes #NN`). But they escalate explicitly ("go really ahead") and then tolerate breaking changes, because they run their own **code-review + testing sessions**. Don't over-checkpoint once told go; don't run ahead of an un-escalated task. Corrections are precise and craft-level — fix the *root cause*, not the symptom, and expect concrete design direction when they hold a view.

**Respect their tooling and operational conventions.** Verify through the owner's running dev tooling rather than spawning your own; treat dev data as disposable when they say so. **Apply schema/DB changes and run seeds yourself** once the code is ready — this is a standing rule, not something to wait to be asked (dev points at the shared DB; reseed freely). The issue tracker is the spine — file freely; they curate and close. (This repo's specifics — a shared nREPL, a connection-capped DB, `env/seed` — live in the sections above.)

**Format and lint after *every* change, not just before push.** Run the project formatter and linter (`bb format` / `bb lint`) on what you touched after each modification and fix any warnings before reporting it done. Compiling green is necessary but not sufficient — hand-edits (especially paren surgery) leave misaligned indentation that pollutes the review diff. A change isn't finished until it is formatted and lint-clean.

## 17. Engineering principles worth carrying to the next build

The *transferable* lessons. They describe the **target architecture**: how the pieces are organized, and why.

- **Domain-Driven Design, made concrete in cljc.** The domain is **pure functions in a shared `*-domain.cljc` namespace**: identity, parsing, derivation rules, invariants — the ubiquitous language and the rules that govern it. It is the single source of truth; storage, endpoints and UI are adapters built *around* it. Because backend and frontend call the *same* functions, they cannot drift. Coordination lives in the domain, and the domain dictates the seams.
- **Share on the cljc seam only what must be identical on both sides.** cljc is for logic that has to give the same answer on the server and the client — how an id / URL / reference is parsed, how a derived value is computed, what an invariant is. Use reader conditionals only for the genuine platform gap (string normalization, number parsing). Everything platform-bound stays in clj or cljs. The test for "does this belong in cljc?": *would a divergence between server and client be a bug?* If yes, it goes in the shared core.
- **Domain, storage and presentation each own their namespace.** The shape: the domain in cljc; a **type-agnostic engine** under the feature; **thin facades** that supply only the per-case specifics. Shared presentation lives in one reusable component.
- **Colocate by default; split a namespace only when a concrete benefit lands.** Keep things together until a real need appears, then introduce the new namespace / facade / abstraction at the moment it *starts paying for itself* — never in anticipation. Size a namespace by **responsibility, not line count**: when one starts doing two jobs, that is the split signal. Incidental complexity should appear only when the benefit does.
- **Layer both stacks the same way.** A thin storage/adapter layer, a domain-driven engine above it, endpoints/composition on top; on the client, a type-agnostic engine with thin per-case facades. Symmetric seams on server and client make the whole thing legible — you can reason about one side by analogy to the other.
- **Derive over store; unify over special-case.** Values are computed on read rather than persisted as copies; a new case is expressed through the general model rather than a branch. When a read-heavy path calls for denormalization, it takes the form of a **rebuildable cache, never a second source of truth.**
- **A feature is a field on the domain entity, not a conditional spread across namespaces.** When behaviour varies by a domain category (a kind, a type, a role), the variation is declared **as data on that category** and every consumer reads it — the backend and the UI branch on the *field*, never on the specific value. Example: "may a KI of this kind have inputs?" is `:inputs?` on the `kinds` data (`kind-allows-inputs?`), read by input-derivation *and* by whether the quotation feature renders — not a `(= "source" …)` check repeated in each namespace. Adding a per-category capability means adding a field, not hunting down the branches. This keeps the design **feature-oriented** rather than scattering the logic.
- **Test where it pays: unit-test the pure domain, verify integration by exercising it.** Pure cljc domain functions are what unit tests reward — no I/O to mock. Integration is verified by running the real code through the REPL and the UI. Keeping logic in a pure, testable place is what keeps verification cheap.
- **When extending, pull toward the domain, toward separation, toward one mechanism.** New capability goes into the domain core, keeps concerns separate, and reaches for the general mechanism before a special case.

---

*This document was produced through a structured design conversation and represents the current state of architectural thinking. It is a living document — implementation will surface new questions and invalidate some current assumptions. That is expected and correct.*

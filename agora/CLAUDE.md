# CLAUDE.md — Knowledge Graph Platform (Agora / Logos)
## Architecture Framework & Implementation Specification

---

## 1. Vision & Positioning

Most knowledge tools store conclusions. This platform stores reasoning chains.

Every person has felt the frustration of being right but not believed — the philosopher whose argument is dismissed, the engineer whose insight is ignored, the thinker whose reasoning is solid but cannot be made to land. This platform solves that: it makes reasoning legible, traceable, and challengeable at the level of each individual step.

The underlying insight is that human knowledge, unlike mathematical proof, lives in a fuzzy, probabilistic space. Not because reality is uncertain, but because our collective confidence in any claim is always partial and evolving. This platform embraces that honestly rather than forcing false binary conclusions. It is, in spirit, a rebirth of fuzzy logic applied to human sciences — closer to Bayesian epistemology than to formal proof, closer to Proudhon's systematic philosophical argumentation than to a Wikipedia article.

The cognitive style this tool embodies — explicit implication chains, defined terms, traceable confidence — is how rigorous thinkers already reason. People who think in implications naturally, who are frustrated by ambiguous claims, who need to trace the chain before accepting a conclusion. The platform does not ask them to simplify. It matches their cognitive architecture. And it extends that rigour to anyone motivated to think more clearly — not changing their nature, but assisting their reasoning and positioning. Writing itself did not change human cognition; it extended and disciplined it. This is a similar leap.

The name is either **Agora** (the Greek public space where arguments were made openly, to be challenged) or **Logos** (reason, word, logic — the tradition from Aristotle to Proudhon). Both are short, deep, historically rooted in exactly the intellectual tradition this project belongs to. Final choice pending real-world feedback.

---

## 2. Core Concept — The Knowledge Item (KI)

The atomic unit of the platform is a **Knowledge Item (KI)**. A KI is an immutable logical implication: given a set of inputs already held to be true, a specific output follows. It is the formalization of a single reasoning step.

KIs are not claims about reality. They are claims about reasoning. The difference matters: a KI can be challenged not because the world is different, but because the reasoning step is flawed, the terms are ambiguous, or the inputs do not actually support the output.

### Granularity

KI granularity is the claimer's responsibility. The system imposes no minimum or maximum scope. A KI can be as broad as a book's thesis or as narrow as a single definitional step. The social validation process naturally drives decomposition: when a KI is too broad, challengers will find something to contest, and the claimer's only recourse is to split into smaller, independently defensible units. This mirrors mathematics exactly — lemmas exist because theorems needed separable chunks that could be proven independently. The invalidation pressure, not a top-down schema, drives the right granularity.

A KI that survives long without invalidation and without needing to be split is a signal of quality. Primitives — KIs with no inputs — emerge naturally from this process. They are not declared as axioms; they simply have no inputs that need defending. The system does not distinguish between a declared axiom and an undeveloped claim — that distinction is resolved by social pressure over time.

### Definitions as KIs

Definitions are KIs. A definition KI's output is a semantic contract: "in this graph, *quick* means 0-100 km/h duration." Crucially, definition KIs can themselves be challenged. Someone can argue that a definition excludes relevant cases, or that a term is being used inconsistently across KIs. There is no hard bottom to the graph — even primitives can be challenged on definitional grounds. This is honest. That is how human knowledge actually works.

Key terms within any KI should link to their definition KI in the graph. The authoring interface makes this a single gesture. When a term used in a KI already has multiple definition KIs in the graph, that is a graph-native ambiguity signal surfaced automatically during authoring.

### Multiple Input Paths

A KI can be reached via multiple independent sets of inputs — disjunctive antecedents. Each input set is an independent argument for the same conclusion. Invalidating one input path does not invalidate the KI — it forces the claimer to remove or disambiguate that specific path. Multiple surviving input paths strengthen a KI structurally: the conclusion is supported from independent directions.

### KI Types

A KI is typed at creation by the claimer. The type determines the lifecycle process, the challenge mechanism, and how confidence is interpreted. Three families exist:

**Derived KI** — the standard case. Has inputs, produces a logical consequence. Challenged by counterexample or ambiguity challenge. Confidence reflects how well the reasoning chain has survived scrutiny.

**Verifiable claim** — a KI whose truth will be settled by an external observable event, possibly at a future date. "Donald Trump will be president again" is a verifiable claim. Before resolution, challenges are about reasoning soundness. At resolution, reality speaks and debate closes. The KI transitions to a resolved state — confirmed or refuted — regardless of community confidence at that point. Resolution date is a first-class property.

**Declared foundation** — a KI with no inputs, not grounded in external observable reality, consciously declared as a starting point. Cannot be falsified by counterexample or reasoning — only contested by an incompatible declared foundation. The claimer chooses the register that matches their tone and domain:

- **Postulate** — formal, scientific, mathematical register
- **Stance** — civic, political, argumentative register
- **Belief** — personal, philosophical, spiritual register
- **Credo** — manifesto-like, strong conviction, almost militant register

All four are processed identically by the system at MVP. In advanced layers, the challenge process may differ by variation — a postulate invites logical challenge, a credo invites value confrontation — and the system may route challenges accordingly. The vocabulary choice is also a signal to readers and challengers about what kind of response is appropriate, guiding behaviour naturally even before the system enforces it.

### Timestamp & Provenance

Every KI carries an immutable timestamp of first publication. This is a proof of intellectual antecedence — the claimer can establish that they formulated this reasoning before it became mainstream, before an academic paper covered it, before the fact resolved. The timestamp is public, indexed by Google, and incontestable. A forked version carries its own timestamp; antecedence belongs to the original branch. This is a strong claimer motivation: beyond convincing others, they are protecting their intellectual authorship.

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

A KI's confidence **ceiling is set by its weakest input**. You cannot be more certain of a consequence than you are of its least certain premise. Multiple input paths produce a confidence interval — floor from the weakest path, ceiling from the strongest. The operative value is always the floor.

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

---

## 6. Authoring & Ownership

### Identity Model

- **Read** — anonymous, fully open, no registration required
- **Contribute** — registered contributors only, via OAuth (Google, Facebook), frictionless

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
- Article authoring: as frictionless as KI creation
- Linking a term in an article to a definition KI: single gesture, not search-and-copy

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

Three distinct public page types exist from Layer 1a. They are read-only surfaces, separate from the authoring interface, and are the platform's public face.

**KI page** — permanent URL per KI, public and crawlable. Displays the KI text, its input KIs, its confidence score, its commentary history, and its version lineage. The permanent URL is stable across versions — the page shows the latest version with lineage visible.

**Article page** — permanent URL per article, public and crawlable. The primary SEO surface. Every polysemic term in the article links to its definition KI page. Every argument links to its supporting KI page. Articles are the most readable entry point into the graph for new visitors.

**Discoverability page** — the platform homepage for anonymous visitors. Curated, not algorithmic at first. Showcases high-confidence KIs, recent articles, and active domains. Its purpose is to make the platform non-empty and compelling to the first visitor, before any social layer exists.

### Google Discoverability

- Semantic markup on all public pages — schema.org, OpenGraph
- Permanent URLs from day one — no URL changes across versions
- High-confidence KIs prioritised for indexing
- Articles as primary SEO surface — aggregated, readable, linkable
- Google discoverability is implemented in Layer 1a — cheap to do correctly from the start, very hard to retrofit

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

### Layer 1a — First Deployable

Built as vertical slices, each slice producing a working product. Definitions are KIs with no inputs — no special mechanism needed. Confidence score deferred. Objection system deferred. Article authoring UI deferred — article seeded manually in DB.

**Slice 1 — Hidden page displaying a seeded KI**
- PostgreSQL schema — KI identity (id, name, type, major, minor, output_statement_hash, timestamp)
- Cellar object storage — content-addressed text blobs, DB stores hash only
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
- KI identity model enforced — Type + Name + Major + Minor
- Auto-resolution to latest minor within referenced major — implemented as DB query utility
- Navigation reflects versioned links correctly

**Slice 3-bis — Article**
- PostgreSQL schema — article (id, title, body_hash, timestamp)
- Article seeded manually in DB, body in Cellar
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
- KI type selector — derived, verifiable claim, postulate, stance, belief, credo
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
- Google and Facebook OAuth
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

- **Backend** — Clojure
- **Frontend** — ClojureScript + React
- **Hosting** — Clever Cloud
- **Database** — PostgreSQL (graph structure: nodes, edges, confidence scores, ownership, version links, metadata — all lightweight, mostly hashes and references)
- **Object storage** — Clever Cloud Cellar (S3-compatible) for immutable KI text content
- **Auth** — OAuth (Google, Facebook)

### Content-Addressed Storage

KI text content is immutable. It is stored in object storage, addressed by a hash of its content. The database stores only the reference (hash), not the text. Identical content across versions or similar KIs is automatically deduplicated. The database stores graph structure only — small, fast, scales slowly.

### Confidence Storage

Confidence is a navigation signal, not the primary epistemic mechanism — that role belongs to the objection system. It is stored as a live computed value in the database, recomputed as objection states change. It is not stored in the immutable KI content.

### Graph Model

For MVP, PostgreSQL with adjacency list and recursive queries handles the graph sufficiently. A dedicated graph database is not needed at this scale. Migration path exists if the graph grows to require it.

### Search

Full text search is needed from day one. Elasticsearch is the main cost variable. For MVP, consider deferring to AI-assisted vector search (embeddings stored cheaply, semantic search without Elasticsearch infrastructure) to keep costs low at small scale.

### Cost Profile (Clever Cloud)

- PostgreSQL XS — ~€15/month
- Cellar object storage — negligible until significant scale
- Search — main cost variable, possibly deferred
- **Total MVP** — under €100/month

---

## 14. Integration Context

This project is integrated into the Hephaistox landing project. It shares the existing Clojure/ClojureScript stack and Clever Cloud hosting. It is positioned as a distinct product within the Hephaistox platform, not a standalone deployment. UI follows the existing dark Forge Artisan theme (copper/amber accents, Cormorant/Jost typefaces) unless a distinct visual identity is decided for the product.

---

## 15. Open Questions

The following questions are registered but not yet resolved. They should be addressed before or during the relevant implementation layer.

| # | Question | Relevant Layer |
|---|----------|---------------|
| 4 | Validation governance | Resolved — confidence score replaces discrete status, no governance body needed |
| 16 | MVP definition | Layer 1a/1b split above is the current answer, to be validated against first usage |
| 23 | Confidence score formula enrichment | Layer 2+ |
| 24 | Epistemic community scoping | Layer 2+ |
| 7 | Monetization detail beyond confidence budget | Layer 2+ |

---

*This document was produced through a structured design conversation and represents the current state of architectural thinking. It is a living document — implementation will surface new questions and invalidate some current assumptions. That is expected and correct.*

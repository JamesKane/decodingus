# Feed Records

Public community-feed posts. Unlike the genomic record types, these carry **no donor
data** — just a member's short public text, an optional topic, and reply pointers. They
are published to the member's own PDS and indexed by the AppView (Jetstream), which merges
them into the community feed alongside the AppView-native (web-posted) `social.feed_post`
path. See `documents/planning/social-layer-roadmap.md` §3b.

---

## 1. Feed Post Record

A short public post. Threaded replies use Bluesky-style strong references.

**NSID:** `com.decodingus.atmosphere.feed.post`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.feed.post",
  "defs": {
    "main": {
      "type": "record",
      "description": "A public community-feed post.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["text", "createdAt"],
        "properties": {
          "text": { "type": "string", "maxLength": 3000 },
          "createdAt": { "type": "string", "format": "datetime" },
          "topic": {
            "type": "string",
            "description": "Optional channel tag, e.g. 'general' or 'haplogroup:R-M269'."
          },
          "reply": {
            "type": "object",
            "description": "Present on replies.",
            "properties": {
              "root":   { "type": "ref", "ref": "com.atproto.repo.strongRef" },
              "parent": { "type": "ref", "ref": "com.atproto.repo.strongRef" }
            }
          }
        }
      }
    }
  }
}
```

### AppView ingest (built)

- **Mirror:** `fed.feed_post` (mig 0045) — `(did, rkey)` PK, `text`/`topic`/`parent_uri`/
  `root_uri` + provenance; last-writer-wins by Jetstream `time_us`.
- **Consumer:** the du-jobs Jetstream consumer adds `com.decodingus.atmosphere.feed.post`
  to its `wantedCollections`; `build_feed_post` reads the **top-level** `createdAt` (not
  `meta.createdAt`) and `reply.{root,parent}.uri`. A record delete tombstones the mirror
  row via the shared NSID→table map.
- **Read path:** `du_db::fed::feed::recent` (top-level posts, author name resolved when the
  DID is bridged into `ident.users`); the web `/feed` interleaves these with central
  community posts by recency, badged **"via Atmosphere"** and **read-only** (vote/reply/
  block act on AppView-native posts only). The Edge `GET /api/v1/social/feed` returns them
  in a `federated` array.

### Pending (Edge)

Navigator must **publish** `feed.post` records (the AppView only ingests). Until then the
mirror is empty and the community feed shows only AppView-native posts. Cross-author
reply threading and federated-author block-filtering are read-path follow-ups.

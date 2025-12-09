# IBD Matching Records

Record types for identity-by-descent (IBD) matching consent, results, and contact requests.

**Status:** Future Scope (IBD Matching System)

---

## 1. Match Consent Record

This record represents a citizen's consent for IBD matching. Without this record, no matching is performed.

**NSID:** `com.decodingus.atmosphere.matchConsent`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.matchConsent",
  "defs": {
    "main": {
      "type": "record",
      "description": "Consent record for IBD matching participation. Presence enables matching; deletion revokes consent.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "biosampleRef", "consentLevel"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this consent record."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "biosampleRef": {
            "type": "string",
            "description": "AT URI of the biosample for which consent is granted."
          },
          "consentLevel": {
            "type": "string",
            "description": "Level of matching participation.",
            "knownValues": ["FULL", "ANONYMOUS", "PROJECT_ONLY"]
          },
          "allowedMatchTypes": {
            "type": "array",
            "description": "Types of matching allowed.",
            "items": {
              "type": "string",
              "knownValues": ["IBD", "Y_STR", "MT_SEQUENCE", "AUTOSOMAL"]
            }
          },
          "minimumSegmentCm": {
            "type": "float",
            "description": "Minimum segment size (cM) for matches to be shared. Default: 7.0"
          },
          "shareContactInfo": {
            "type": "boolean",
            "description": "Whether to share contact information with matches."
          },
          "consentedAt": {
            "type": "string",
            "format": "datetime",
            "description": "When consent was granted."
          },
          "expiresAt": {
            "type": "string",
            "format": "datetime",
            "description": "Optional expiration date for consent."
          }
        }
      }
    }
  }
}
```

---

## 2. Match List Record

This record contains the list of IBD matches for a biosample, updated by the DecodingUs AppView.

**NSID:** `com.decodingus.atmosphere.matchList`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.matchList",
  "defs": {
    "main": {
      "type": "record",
      "description": "List of IBD matches for a biosample. Updated by the DecodingUs AppView.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "biosampleRef", "matches"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this match list record."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "biosampleRef": {
            "type": "string",
            "description": "AT URI of the biosample these matches belong to."
          },
          "matchCount": {
            "type": "integer",
            "description": "Total number of matches in this list."
          },
          "lastCalculatedAt": {
            "type": "string",
            "format": "datetime",
            "description": "When matches were last calculated."
          },
          "matches": {
            "type": "array",
            "description": "List of matches.",
            "items": {
              "type": "ref",
              "ref": "#matchEntry"
            }
          },
          "continuationToken": {
            "type": "string",
            "description": "Token for paginating through large match lists."
          }
        }
      }
    },
    "matchEntry": {
      "type": "object",
      "description": "A single match entry in the match list.",
      "required": ["matchedBiosampleRef", "totalSharedCm", "segmentCount"],
      "properties": {
        "matchedBiosampleRef": {
          "type": "string",
          "description": "AT URI of the matched biosample."
        },
        "matchedCitizenDid": {
          "type": "string",
          "description": "DID of the matched citizen (if they consent to sharing)."
        },
        "relationshipEstimate": {
          "type": "string",
          "description": "Estimated relationship (e.g., '2nd Cousin', 'Half Sibling').",
          "knownValues": ["PARENT_CHILD", "FULL_SIBLING", "HALF_SIBLING", "GRANDPARENT", "AUNT_UNCLE",
                         "1ST_COUSIN", "1ST_COUSIN_1R", "2ND_COUSIN", "2ND_COUSIN_1R", "3RD_COUSIN",
                         "4TH_COUSIN", "5TH_COUSIN", "DISTANT", "UNKNOWN"]
        },
        "totalSharedCm": {
          "type": "float",
          "description": "Total centiMorgans shared across all segments."
        },
        "longestSegmentCm": {
          "type": "float",
          "description": "Length of the longest shared segment in cM."
        },
        "segmentCount": {
          "type": "integer",
          "description": "Number of shared segments."
        },
        "sharedSegments": {
          "type": "array",
          "description": "Detailed segment information (optional, can be large).",
          "items": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#ibdSegment"
          }
        },
        "matchedAt": {
          "type": "string",
          "format": "datetime",
          "description": "When this match was first detected."
        },
        "xMatchSharedCm": {
          "type": "float",
          "description": "cM shared on X chromosome (for X-DNA matching)."
        }
      }
    }
  }
}
```

---

## 3. Match Request Record

This record represents a request to initiate contact with a match.

**NSID:** `com.decodingus.atmosphere.matchRequest`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.matchRequest",
  "defs": {
    "main": {
      "type": "record",
      "description": "A request to initiate contact with a genetic match.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "fromBiosampleRef", "toBiosampleRef", "status"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this match request record."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "fromBiosampleRef": {
            "type": "string",
            "description": "AT URI of the requesting biosample."
          },
          "toBiosampleRef": {
            "type": "string",
            "description": "AT URI of the target biosample."
          },
          "status": {
            "type": "string",
            "description": "Current status of the request.",
            "knownValues": ["PENDING", "ACCEPTED", "DECLINED", "EXPIRED", "WITHDRAWN"]
          },
          "message": {
            "type": "string",
            "description": "Optional message to the match.",
            "maxLength": 1000
          },
          "sharedAncestorHint": {
            "type": "string",
            "description": "Suspected common ancestor or family line.",
            "maxLength": 500
          },
          "expiresAt": {
            "type": "string",
            "format": "datetime",
            "description": "When this request expires if not responded to."
          },
          "respondedAt": {
            "type": "string",
            "format": "datetime",
            "description": "When the request was responded to."
          }
        }
      }
    }
  }
}
```

---

## Backend Mapping

* **`MatchConsent`:** Maps to `match_consent` table tracking user opt-in status.
* **`MatchList`:** Maps to `match_results` table with match entries as JSON array or normalized child table.
* **`MatchRequest`:** Maps to `match_requests` table for contact workflow.

See [ibd-matching-system.md](../ibd-matching-system.md) for implementation planning.

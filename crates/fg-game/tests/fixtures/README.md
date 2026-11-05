# Version JSON fixtures

Real `version.json` files fetched from `piston-meta.mojang.com` on 2026-11-05,
one per M3 acceptance sample:

| File | Covers |
| --- | --- |
| `1.0.json` | legacy `minecraftArguments`, `natives` + classifiers, `map_to_resources` assets |
| `rd-132211.json` | classic / RubyDung main class, shared `pre-1.6` asset index |
| `1.7.10.json` | 1.7 launcher format, natives, log4j 1.7 config |
| `1.8.9.json` | launcher profile format used by 1.8.x |
| `1.12.2.json` | legacy arguments plus a log4j client config |
| `1.16.5.json` | new `arguments` format, both old and new natives styles |
| `1.20.1.json` | 1.19+ separate `natives-*` libraries, Java 17 |
| `1.21.4.json` | current release at M3 time, Java 21 |
| `26.4-snapshot-7.json` | snapshot format, `default-user-jvm`, `os.versionRange`, Java 25 |

These are Mojang metadata files, not code; they are kept verbatim so rules,
argument and merge tests exercise the real shapes. They live under
`tests/fixtures/` and are not part of the built binary.

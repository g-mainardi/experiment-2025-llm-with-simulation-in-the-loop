---
paths:
  - "**/*.scala"
  - "build.mill"
---

# Scala

- Scala 3.10.0 everywhere, the version the Alchemist ScaFi incarnation is built with. The programs the agent submits are compiled as Scala 3 too, against the server's classpath.
- Formatting is scalafmt with `.scalafmt.conf` (Scala 3 dialect, 120 columns). Check with `./mill mill.scalalib.scalafmt/checkFormatAll`, fix with `./mill mill.scalalib.scalafmt/reformatAll`; the `pre-commit` hook rejects unformatted sources. A commit that only reformats goes in `.git-blame-ignore-revs` with the SHA it gets on `master`.
- `./mill __.compile` compiles every module, as CI does.

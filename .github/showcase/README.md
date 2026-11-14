# Showcase image (this fork only)

`.github/workflows/build-showcase-image.yml` builds the image of the showcase module of `sboesebeck/morphium` (branch `release/7.x`) and pushes it to
`ghcr.io/bardioc1977/quarkus-morphium-showcase` (`:latest` and `:sha-<commit>`). The server is not known to GitHub: Watchtower on it pulls `:latest` when it changes.

How it works: every 20 minutes the workflow asks the origin for the newest commit of `release/7.x` whose `build.yml` run succeeded. If there is no image tagged
`sha-<commit>` yet, it builds one from that commit. A manual run (Actions > Build showcase image > Run workflow) can name a commit or force a build.

Needs once: the GHCR package `quarkus-morphium-showcase` (Package settings > Manage Actions access) allows this repository to write; the package is public so that Watchtower needs no login.
A scheduled workflow only runs from the default branch (`develop`) of this fork, so this has to be merged here first.

`build.yml` of the origin has to run on `release/7.x` for this to find anything (sboesebeck/morphium#523): its `push.branches` lists `develop` and `master`.

# Showcase deployment (this fork only)

`.github/workflows/deploy-showcase.yml` deploys the showcase module of `sboesebeck/morphium` (branch `release/7.x`) to the server of this account.
It is not part of the origin and is never sent there: nothing of the deployment (host, user, key) is stored anywhere but in this repository.

Set up once:

1. Settings > Environments > New environment `showcase`. Add the environment secrets `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`
   (the same as in the old showcase repository). Optional: required reviewers for a manual gate.
2. GitHub Container Registry: the package `quarkus-morphium-showcase` (Package settings > Manage Actions access) has to allow this repository to write.
3. Actions > Deploy showcase > Run workflow once. A scheduled workflow only runs from the default branch (`develop`) of this fork.

How it works: every 20 minutes the workflow asks the origin for the newest commit of `release/7.x` whose `build.yml` run succeeded. If it is not the one that is
deployed (the last GitHub deployment of the environment `showcase` carries it as its description), the image is built from that commit (job without secrets) and the
compose file of this directory is copied to the server and started (job with the secrets). A manual run can name a commit or force a deploy.

`build.yml` of the origin has to run on `release/7.x` for this to find anything: its `push.branches` lists only `develop` and `master`.

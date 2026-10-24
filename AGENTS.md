# Agent guidelines

Instructions for AI coding agents working in this repository. See `README.md` for what the
project is, its settings, and how to set it up.

## Development

- Set up: JDK 21 and Docker; use `./gradlew`, which pins Gradle. Services: a test IOC and
  epics2web, built from the working tree by `build.yaml`. Outside JLab, build the image without
  the JLab CA certificate: `docker compose -f build.yaml build --build-arg CUSTOM_CRT_URL=`. Then
  run `docker compose -f build.yaml up -d --wait`. They use ports 8080 (HTTP) and 5064 and 5065
  (CA, TCP and UDP); check what's already listening and running first.
- On every change, run `./gradlew spotlessCheck test` (under a minute); `./gradlew spotlessApply`
  formats. To run one test: `./gradlew test --tests '*WriteQueueTest'`.
- Before opening a pull request, also run the integration tests: rebuild the image, since the
  containers run the image and not your working tree, then run `./gradlew integrationTest`
  (about 1.5 minutes). CI runs both. `build.yaml` shortens the ping, timeout, send-timeout,
  healthcheck grace and frozen-check settings so the tests take seconds. `HealthcheckTest` stops
  and starts the `softioc` container, so don't run it while someone else is using the containers.
- Test IOC (`examples/softioc-db/softioc.db`): `HELLO` changes every 0.2 s; `channel1` and
  `channel2` stay 0, and tests rely on that, so don't `caput` them; `big_string_1` to
  `big_string_8` are 15000-character strings updating at 10 Hz, for `StalledClientTest`.
  `docker exec softioc caget <pv>` reads them.
- Tests that create CA contexts in the test JVM, such as `FrozenPvDetectionTest`, reach the test
  IOC through its published ports (`addr_list` 127.0.0.1). Set the `CA_DISABLE_REPEATER` system
  property in any JVM that creates a CA context, or CAJ leaves a repeater holding UDP 5065.
- Outside systems: EPICS IOCs and CA gateways, through Channel Access. The test IOC stands in for
  them. Never point `EPICS_CA_ADDR_LIST` at real IOCs or gateways.
- UI changes: check them in a browser; every page is linked from the overview page, `/epics2web/`.
  `epics2web.js` is the client library: `/epics2web/test-camonitor` is the page here that uses it.
  Other apps, such as WEDM, load `epics2web.min.js`, `epics2web.min.css`, jQuery and the
  connection-state images from this server's `/epics2web/resources/`, so keep those paths and the
  JS API compatible.
- New settings are environment variables read in `Application` (`getSecondsFromEnv`), and
  documented in the README's Configure section.
- Rules for code that uses CAJ (the JCA library), learned from bugs:
  - CAJ shares one channel per PV name, with a reference count. Create and destroy channels only
    inside `ChannelManager.monitorMap.compute` for that PV, as `addPv`, `removePv` and `get` do,
    or a channel created while its namesake is being destroyed comes back closed.
  - CAJ sends a subscription cancel at once but queues the add until a flush. Never clear a
    subscription before its first update has arrived (see `ChannelMonitor.close`), or the IOC
    or gateway may get the cancel first and drop the whole connection.
  - Never interrupt a thread that may call into CAJ: an interrupted thread can't destroy
    channels. Stop WebSocket writer threads with `WriteQueue.close()`.
  - CAJ's callbacks must not call back into CAJ; hand the work to another thread, as
    `ChannelMonitor` does with `callbackExecutor`.
  - An exception thrown inside a `ConcurrentHashMap` remapping function leaves the mapping
    unchanged; catch it inside.
  - JCA's `DBRType` constants stay null if a DBR class such as `DBR_Double` initializes first;
    in tests, create DBRs with `TestDbrs`.
  - Known CAJ bug: after a circuit becomes unresponsive and recovers, each update arrives twice
    (#48).

<!-- BEGIN coding-agents shared rules 7.0.0 (generated: edit in acc/iac/docs/coding-agents) -->

## Shared rules

These rules are the same in every project that uses them. Where this file's other sections say
otherwise, follow those. On GitHub, "merge request" means "pull request", and "pipeline" means
the pull request's required checks.

### Checking your work

- Run the Development section's checks before you call a change done, fix what fails, and
  report what you ran and what it returned.
- Verify each change in proportion to its behavior and regression risk: add or update tests
  where the risk warrants it; meaningful existing coverage can suffice. For a bug fix, show the
  regression test failing without the fix and passing with it when practical, or explain why
  not. For documentation or formatting, check links, examples or formatting. Never skip or
  weaken a check merely to make it pass.
- Where tests are thin, characterization tests can record a baseline: existing behavior, not
  necessarily correct behavior. Put them in a test-only merge request when risk or review needs
  warrant it.
- Never use production data, and never commit secrets.

### Task authorization

- Use the request and authorization already given in the session; do not ask again for an
  authorized action. "Look around and suggest improvements" means investigate, run local
  checks and report, not implement or publish. "Fix this bug" authorizes the local changes and
  verification needed to finish it; ask before a material expansion of scope.
- Commit and push only when asked. "Create a merge request for issue #14" also authorizes
  implementing, verifying, committing, pushing the branch and opening the merge request, and a
  request to address its review authorizes the follow-up commits and pushes.
- A requested review authorizes checks and posting findings as comments; commit and push fixes
  only when asked.
- Releases, deployments, production data changes and settings changes in external systems need
  authorization for the action and the target environment; a request to implement a change or
  open a merge request does not give it. Never approve, merge, or enable auto-merge.
- Ask only when a consequential ambiguity remains; continue independent work meanwhile.

### Commits

- Commit as the agent's bot, never as a person: the repository-local `user.name` and
  `user.email` (never `--global`) must be the bot's whose token you push with, so the Git host
  links the commits to it and a squash commit shows the same name. In an agent VM the clone
  helpers set them; on GitLab, `glab api user` shows the bot's `name` and `username`, and its
  address is `<username>@noreply.<host>`.
- Imperative summary line, then a body explaining what changed and why. Name the issue the
  change is for: `Fixes #<issue>`, or `Part of #<issue>` if some of it stays open; work you were
  asked to do needs no issue.
- Trailers come last, in one paragraph with no blank line among them, since git reads trailers
  only from the last paragraph: the release trailers this file asks for, if any (such as
  `Changelog:`), then attribution. Every agent-written commit, including follow-ups and a
  reviewer's, names the agent and model in a trailer: keep the one your tool adds (such as
  `Co-Authored-By`), or add `Assisted-by: <agent>:<model>`. An agent VM's commit hook refuses a
  message that breaks either rule; elsewhere, check with
  `git log -1 --format=%B | git interpret-trailers --parse`.
- The squash commit message template uses the first commit's message: write it for `main`, put
  release trailers on it only, and keep it current for the whole change, adding any distinct
  agent/model attribution from later commits. Later commits omit release trailers but keep
  attribution.

### Branches and merge requests

- Start each task on a new branch from an up-to-date `main`, in your own clone or worktree, and
  target `main`. To build on a merge request still in review, branch from its branch, target it,
  and say so in the description; once it merges, check that yours now targets `main` (GitHub
  retargets it only if the merged branch was deleted).
- Label every merge request `source::ai`, make the person who reviews it both reviewer and
  assignee (ask for their username if you don't know it), set the active milestone where the
  project uses one, and delete the source branch on merge. An agent VM's `glab` wrapper adds
  these when you leave them out.
- Describe what changed, any deployment steps, and the checks you ran and could not run, in the
  project's merge request template where it has one. Add a short Decisions part: what the person
  questioned or changed during the session, which alternatives were dropped and why, and any
  second opinion that changed something.
- End every agent-prepared merge request, pull request or issue description with a plain-text
  `Prepared-by: <vendor> <agent>:<model identifier>` footer, such as
  `Prepared-by: OpenAI Codex:GPT-6`: the specific model identifier when known, otherwise the
  known model family, and `unavailable` only when neither is known; never guess. Preserve
  existing credits, including a tool's equivalent footer; on a substantive update, add yours if
  that vendor, agent and model are not credited yet; routine edits need none. Keep all
  attribution at the end, and in a release merge request under its own heading after "Release
  notes".
- After pushing, wait for your last commit's pipeline to finish before reporting the merge
  request ready: read a failed job's log and fix the cause, never skip or weaken a check, and
  report how it ended with its link, saying if it failed for a reason outside your change. On
  GitLab, `glab mr view <number> -F json | jq .head_pipeline` shows it; until its `sha` is the
  commit you pushed, your push's pipeline has not started.
- Before pushing to a merge request's branch, check that it is still open: commits pushed after
  it merged never reach `main`.
- For another GitLab project's issue or merge request, write `project#123` or `project!456` with
  no space when it resolves from the current context, otherwise the full namespace path or URL,
  and check the rendered link; `project #123` links the bare number to the current project.

### Issues

- For something outside your task (another project, code another agent is working on, a change
  that needs a decision), don't fix it in passing: offer to open an issue with what you found,
  the evidence and a suggested fix. File issues as the bot, labeled `source::ai` and assigned to
  the person you work with, with the project's labels for its kind where it has them: one
  `type::` label and, for a bug, one `bug-origin::` label (on GitLab,
  `glab api 'projects/:id/labels?include_ancestor_groups=true'` lists them); `effort::` only as
  an estimate, and `priority::` left to the person.
- When asked to plan work for other agents: check claims before filing, list the draft issues
  before filing them, make each self-contained with "Done when" and what not to do, say which can
  start now and which are blocked or share files, and ask before filing anything
  security-sensitive (file it confidential).

### Review

- When asked to address a review, reply in each thread with what you changed, and push content
  fixes as new commits; the reviewer resolves the threads. Only the author may reword the first
  commit's message, for its description, release metadata or attribution, following the Git
  workflow guide's "Responding to review": save the remote tip, keep the base, patches and
  order, check `git range-diff` and an empty `git diff`, push with an explicit
  `--force-with-lease`, and report the old and new tips.
- When asked to review a merge request, comment by default, one thread per finding; commit and
  push fixes only when asked, coordinating with the author if they are still working on the
  branch. Add one commit per recommendation on top of the author's, never rewriting theirs, with
  your attribution and no release trailers, then post one comment listing each commit, what you
  tested, that the author must pull, and any first-message update needed. Never approve, merge,
  or resolve threads.
- When the person corrects you on something any agent here should know, propose adding it to
  this file's project rules, or better a check that catches it. Keep one developer's
  preferences out of it.

<!-- END coding-agents shared rules -->

## Project rules

- Required checks: `build` and `integration` from CI, and CodeQL.
- Changelog eligibility: unused. Types: unused.
- Breaking trailers: unused.
- Milestones: unused.
- Release trigger: changing `VERSION` on `main` creates a version tag and GitHub release with
  the WAR, then publishes the demo Docker image. Release-only change: `VERSION` only, no
  Changelog.
- Release notes: pull request description, including any upgrade steps for the maintainer to
  add to the release. Use [Semantic Versioning](https://semver.org/); an authorized release
  pull request changes only `VERSION` and uses `vX.Y.Z` as its commit message.
- Queue: GitHub organization project; list opened pull requests and issues when reporting, for
  the person to add to the period's project.

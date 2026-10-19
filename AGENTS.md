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

<!-- BEGIN coding-agents shared rules 6.2.0 (generated: edit in acc/iac/docs/coding-agents) -->

## Shared rules

These rules are the same in every project that uses them. Where this file's other sections say
otherwise, follow those.

On GitHub, "merge request" means "pull request", and "pipeline" means the pull request's
required checks.

### Checking your work

- Check each change with the Development section's commands before you call it done, fix what
  fails, and report what you ran and what it returned.
- Verify every change with checks appropriate to its behavior and regression risk. Add or update
  tests where that risk warrants them; meaningful existing coverage can suffice. For bug fixes,
  show the regression test fails without the fix and passes with it when practical; explain when
  this cannot be shown. For documentation or formatting, check links, examples, or formatting
  as applicable. Never skip or weaken a check merely to make it pass.
- For code with thin tests, use characterization tests where they help establish a baseline:
  they record existing behavior, not necessarily correct behavior. Separate them into a test-only
  merge request when risk or review needs warrant it; otherwise include them with the change.
- Never use production data, and never commit secrets.

### Task authorization

- Use the request and authorization already given in the session; do not ask again for an
  authorized action. “Look around and suggest improvements” means investigate, run local
  checks, and report; it does not authorize implementing or publishing the suggestions.
- An implementation request such as “Fix this bug” authorizes the local changes and verification
  needed to finish it. Complete them; ask before a material expansion of scope.
- Commit and push only when asked. “Create a merge request for issue #14” also authorizes
  implementation, verification, commits, pushing the branch, and opening the merge request.
  A local implementation request alone does not. A request to address review on that merge
  request authorizes the necessary follow-up commits and pushes.
- A requested review authorizes checks and posting findings as comments. Commit and push review
  fixes only when asked; follow the review conventions below.
- Releases, deployments, production data changes, and settings changes in external systems need
  authorization for the action and target environment. A request to implement a change or open
  a merge request alone does not give it. Follow the project's release, issue-filing, and access
  rules; never approve, merge, or enable auto-merge.
- Ask only when a consequential ambiguity remains; continue independent work within the
  authorized scope while waiting.

### Commit identity

Commits written by an agent must say so. Before committing, check that the repository-local
identity (never `--global`) is the bot's whose token you push with, so the Git host links the
commits to it and squash commits show the same name:

```bash
git config user.name "<bot's name>"   # e.g. "<username> office agent[bot]"
git config user.email "<bot's noreply address>"
```

On GitLab, `glab api user` shows the bot's `name` and `username`, and the address is
`<username>@noreply.<host>` (for example `project_…_bot_…@noreply.code.jlab.org`). Never commit
under a person's name or email. Name the agent and model in a trailer on every agent-written
commit, including follow-up and reviewer commits: keep the one your tool adds (such as
`Co-Authored-By`), or else add `Assisted-by: <agent>:<model>`.

### Commits

- Imperative summary line, then a body explaining what changed and why.
- Trailers come last, in one paragraph: the release trailers this file asks for, if any (such as
  `Changelog:`), then tool-required ones, with no blank line between them. Git reads trailers
  only from the last paragraph; check with
  `git log -1 --format=%B | git interpret-trailers --parse`.
- The squash commit message template uses the first commit's message: write it for `main`, and
  put release trailers on it only. Later commits, including a reviewer's, retain agent/model
  attribution trailers but omit release trailers. Keep the first message current for the whole
  change, preserving its attribution and adding any distinct agent/model attribution from later
  commits so the squash credits them too.
- Commit and push only when asked.

### Branches and merge requests

- Start each task on a new branch from an up-to-date `main`; target `main`. Work only in your
  own clone or worktree.
- Label every merge request `source::ai`, and make the person who reviews it both reviewer and
  assignee (ask for their username if you don't know it).
- Delete the source branch on merge. A person reviews and merges; never merge, approve, or
  enable auto-merge yourself.
- Before pushing to a merge request's branch, check that it is still open: commits pushed after
  it merged never reach `main`, so put them in a new merge request.
- To build on a merge request still in review, branch from its branch and target that branch,
  saying so in the description. Once the first merges, check that yours now targets `main`, and
  retarget it if not: GitHub retargets it only if the merged branch was deleted.
- Describe what changed, any deployment steps, and the checks you ran. Add a short Decisions
  part: what the person questioned or changed during the session, which alternatives were
  dropped and why, and any second opinion that changed something.
- End every agent-prepared merge request or pull request description with a plain-text
  `Prepared-by: <vendor> <agent>:<model identifier>` footer, such as
  `Prepared-by: OpenAI Codex:GPT-6`. Use the specific model identifier when known; otherwise
  use the known model family, omitting unknown variant or version details. Use `unavailable`
  only when neither is known; never guess. Preserve existing credits. For substantive
  description updates, append your own footer if that vendor, agent, and model are not already
  credited; routine metadata or formatting edits need no new footer. Keep equivalent
  tool-provided footer attribution that identifies the vendor, agent, and known model without
  adding an identical credit; if it is incomplete, keep it and add the `Prepared-by:` footer.
  Keep all attribution at the end. In a release merge request or pull request, put it under a
  separate heading after "Release notes", outside that section.
- After pushing, wait for the merge request's pipeline to finish before reporting it ready. Read
  failed jobs' logs and fix the cause; never skip or weaken a check to pass it. Report how it
  ended, with its link, and say if it failed for a reason outside your change. On GitLab,
  `glab mr view <number> -F json | jq '.head_pipeline | {sha, status, web_url}'` shows it; until
  its `sha` is the commit you pushed, your push's pipeline has not started, and
  `glab ci status --wait` would follow an older one.
- Name the issue a change is for in the commit body: `Fixes #<issue>`, or `Part of #<issue>` if
  some of it stays open. Work you were asked to do needs no issue.
- For another GitLab project's issue or merge request, prefer `project#123` or
  `project!456` with no space, when it resolves correctly from the current context. Check
  the rendered link's destination before publishing. Otherwise use the full namespace path
  or full URL; GitLab can display the URL as a reference. An explicit Markdown link can
  use a concise label. `project #123` and
  `project !456` link the bare number to the current project. Apply this to descriptions,
  comments, commit messages, and documentation.

### Issues

- For something outside your task (another project, code another agent is working on, or a
  change that needs a decision), don't fix it in passing: offer to open an issue, labeled
  `source::ai` and assigned to the person you work with, with what you found, the evidence, and a
  suggested fix.
- File issues as the bot with `source::ai`, and end their descriptions (including planning
  issues) with a plain-text `Prepared-by: <vendor> <agent>:<model identifier>` footer, such as
  `Prepared-by: OpenAI Codex:<model identifier>`,
  `Prepared-by: Anthropic Claude Code:<model identifier>`, or
  `Prepared-by: Google Gemini:<model identifier>`. Use the specific model identifier
  when known; otherwise use the known model family (for example `GPT-6`), omitting unknown
  variant or version details. Use `unavailable` only when neither is known; never guess.
  Preserve existing footers;
  for substantive analysis, proposed-change, or acceptance-criteria updates, append your own
  if that vendor, agent, and model are not already credited. Routine metadata or formatting
  edits need no new footer.
- When asked to plan work for other agents: check claims before filing, list draft issues before
  filing them, make each one self-contained with "Done when" and what not to do, say which can
  start now and which are blocked or share files, and ask before filing anything
  security-sensitive (file it confidential).

### Review

- When asked to address a review, reply in each thread with what you changed, and push content
  fixes as new commits; the reviewer resolves the threads. The author may reword only the first
  message when its description, release metadata, or attribution needs updating. Coordinate
  with anyone working on the branch, save the old tip, and keep the base, patches, and commit
  order unchanged. Rewording changes descendant IDs too: check `git range-diff` between the old
  and new series for only the intended message change, and `git diff <old-tip> HEAD` for no tree
  change. Push with `--force-with-lease=refs/heads/<branch>:<saved-remote-tip>`; stop if the lease
  fails. Report the old/new tips and message changes so the reviewer can compare patches.
- When asked to review a merge request, leave comments by default without asking which mode to
  use. Commit and push fixes only when asked; coordinate with the author before pushing if they
  are still working on the branch. Add one commit per recommendation on top of the author's
  (never rewrite theirs), retaining your agent/model attribution but omitting release trailers
  on follow-up commits. Then post one comment listing each commit, what you tested, that the
  author must pull, and any first-message update needed for the whole change or squash
  attribution. Never approve, merge, or resolve threads.
- When the person corrects you on something any agent here should know, propose adding it to
  this file's project rules, or better a check that catches it. Keep one developer's preferences
  out of it.

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

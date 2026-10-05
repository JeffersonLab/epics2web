# Agent guidelines

Instructions for AI coding agents working in this repository. See `README.md` for what the
project is, its settings, and how to set it up.

## Development

How to check your own work here. Check each change with these commands before you call it done,
fix what fails, and report what you ran and what it returned.

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
  Add or update tests with each change; never skip or weaken a test to make it pass.
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
- Never commit secrets.

## Commit identity

Commits written by an agent must say so. Before committing, check that the repository-local
identity (never `--global`) is that of the GitHub App bot you push with, so GitHub links the
commits to it and squash commits show the same name. Never commit under a person's name or
email. Name the agent and model in a trailer: keep the one your tool adds (such as
`Co-Authored-By`), or else add `Assisted-by: <agent>:<model>`.

## Commits

- Imperative summary line, then a body explaining what changed and why.
- Squash merges use the commit messages: write the first one for `main`.
- Commit and push only when asked.

## Branches and pull requests

- Start each task on a new branch from an up-to-date `main`; target `main`. Work only in your
  own clone or worktree.
- Label every pull request `source::ai`, and make the person who reviews it both reviewer and
  assignee (ask for their username if you don't know it).
- You cannot add items to the JeffersonLab organization's project: when you report back, list
  the pull requests and issues you opened, so the person can add them to the period's project.
- Never change `VERSION` unless asked: a change to it on `main` releases the project (tag, GitHub
  release with the war, and Docker image). When asked, open a pull request that changes only
  `VERSION`, with the commit message `vX.Y.Z`, and write any upgrade steps in its description for
  the maintainer to add to the release.
- A person reviews and merges, and merged branches are deleted; never merge, approve, or enable
  auto-merge yourself.
- Before pushing to a pull request's branch, check that it is still open: commits pushed after it
  merged never reach `main`, so put them in a new pull request.
- To build on a pull request still in review, branch from its branch and target that branch,
  saying so in the description. Once the first merges, check that yours now targets `main`, and
  retarget it if not.
- Describe what changed, any deployment steps, and the checks you ran, including what you could
  not run. Add a short Decisions part when the person questioned or changed something, or when
  alternatives were dropped.
- After pushing, wait for the pull request's checks to finish before reporting it ready (`build`
  and `integration` from CI, and CodeQL). Read failed jobs' logs and fix the cause; never skip or
  weaken a check to pass it. Report how they ended, and say if one failed for a reason outside
  your change.
- Name the issue a change is for in the commit body: `Fixes #<issue>`, or `Part of #<issue>` if
  some of it stays open. Work you were asked to do needs no issue.
- For something outside your task (another project, code another agent is working on, or a
  change that needs a decision), don't fix it in passing: offer to open an issue, labeled
  `source::ai` and assigned to the person you work with, with what you found, the evidence, and a
  suggested fix.
- When asked to address a review, reply in each thread with what you changed, and push new
  commits rather than rewriting ones already reviewed; the reviewer resolves the threads.
- When the person corrects you on something any agent here should know, propose adding it to
  this file, or better a check that catches it. Keep one developer's preferences out of it.

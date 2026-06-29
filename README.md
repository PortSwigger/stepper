# Stepper-NG

A Burp Suite extension for chaining multi-step HTTP request sequences with automatic variable extraction, session management, and conditional logic. Define a login flow once, extract tokens with regex, and let Stepper-NG replay it transparently whenever Scanner, Intruder, or Repeater needs a fresh session.

Examples & writeup: https://xreous.io/posts/stepper-ng/

Also check out this great video tutorial by [ryarmst](https://github.com/ryarmst): https://www.youtube.com/playlist?list=PLwdBLHqdMyeFp2jdshGSOYEYXqPQrEzIK

> Based on [Stepper](https://github.com/CoreyD97/Stepper) by CoreyD97. Migrated to the Burp Montoya API and extended with new features.

![Sequence overview page](images/overview.png)

*Sequence overview - summarises every step's condition, action, and extracted variables, and lists the sequence's published variables.*

![Step view with published variables and conditional logic](images/step.png)

*Step view - request/response editors with the action toolbar, post-execution variables, and the conditional-step controls.*

> UI refreshed for v2.3.0 with a master-detail tree view, new overview and step pages. If you preferred the classic tabbed UI, stay on the 2.2.x release line.

## Usage

### Quick Start
1. Create a sequence - click `+ Sequence` in the tree toolbar, right-click → Rename to retitle.
2. Add steps - click `+ Step` or right-click any request in Burp → Add to Stepper sequence. Reorder by dragging step nodes; drag across sequences to move (with auto-rebind of `$VAR:` references).
3. Extract variables - define a regex in the post-execution variable table (e.g., `"token":"(.*?)"` with identifier `jwt`). You can also highlight text in a response and click Auto Regex.
4. Use variables - insert `$VAR:jwt$` in later steps. It gets replaced at execution time.
5. Click `Run all` (or `Run through here` for a partial run) to execute the sequence.

### Automatic Session Handling
Stepper-NG can replace Burp's session handling rules + macros.

1. Publish a variable - check the Published checkbox on a post-execution variable (e.g., `jwt` in `login-seq`).
2. Reference it - use `$VAR:login-seq:jwt$` in Repeater, Intruder, or Scanner. Stepper-NG auto-executes the sequence when it detects the reference.
3. Validation step - right-click a step in the tree → "Set as Pre-Validation Step". If its condition fires (e.g., got 200), the session is valid and the rest is skipped. Otherwise, the full sequence runs.
4. Throttle - set Validate every N requests in Preferences to reduce checks during scans.

### Variable Syntax

| Type | Syntax | Scope |
|------|--------|-------|
| Step variable | `$VAR:name$` | Within the same sequence |
| Cross-sequence | `$VAR:SequenceName:name$` | From other sequences or Burp tools |
| Static global | `$GVAR:name$` | All tools, set manually |
| Dynamic global | `$DVAR:name$` | All tools, auto-extracted from responses |

## Features

### Published Variables
- Auto-trigger - when an outgoing request contains `$VAR:seq:name$` referencing a published variable, the owning sequence auto-executes first.
- Passthrough sync - published variables capture matching values from any HTTP response flowing through Burp. If a token refreshes through normal browsing, the variable updates without re-running the sequence.

### Conditional Steps
Each step can have a condition evaluated after execution:

> If status line matches `200` → then skip remaining steps. Else → continue.

Condition types: Status line / Response body (match or not-match regex), or Always (unconditional).
- Then - fires when the condition is true. Actions: continue, skip remaining steps, or go to a named step.
- Else - fires when false. Same actions. Hidden for Always conditions.
- Retry - re-execute up to N× with a delay. If a retry flips the condition, remaining retries are skipped and the Else action fires.

| Condition | Retry | Then | Else | Use case |
|-----------|-------|------|------|----------|
| Status matches `200` | - | Skip remaining | Continue | 200 = valid session, skip rest |
| Status doesn't match `200` | 1× 500ms | Continue | Go to (fetch-csrf) | Retry once, still failing → full login |
| Response matches `"error"` | - | Go to (login) | Continue | Error → jump to login |
| Always | - | Skip remaining | *(hidden)* | Unconditionally stop |

### Session Validation
Right-click a step in the tree and choose "Set as Pre-Validation Step". It runs first when the sequence is triggered. If its condition fires, the session is valid and remaining steps are skipped. Otherwise, the full sequence runs.

### Post-Validation
Right-click a step and choose "Set as Post-Validation Step" to verify the session was actually recovered. After the main sequence completes, the post-validation step executes:
- Condition triggers - session recovered, failure counter resets.
- Condition doesn't trigger - failure counter increments.

After consecutive failures (default 3), Stepper-NG pauses the task execution engine and shows an alert (configurable in Preferences).

### Concurrent Request Handling
Burp uses multiple worker threads (scanner, intruder). When a sequence is already executing (e.g., refreshing a token), other worker threads arriving with `$VAR:` references would normally read stale variable values.

Enable "Hold requests while sequence is executing" in Preferences → Session Handling to make concurrent threads wait until the sequence finishes, so they receive freshly updated values. Threads wait up to 30 seconds before timing out.

### Global Variables
- Static (`$GVAR:name$`) - manual key-value pairs in the Global Variables tab. Useful for credentials, hostnames, or constants.
- Dynamic (`$DVAR:name$`) - define a regex + optional host filter. Values auto-update from all HTTP responses. Enable "Also capture from requests" for client-side generated values (e.g., JS-computed tokens). Inspired by [burp_variables](https://github.com/0xceba/burp_variables).

### Shared Variable Names
When multiple steps extract the same value (e.g., a JWT) from different endpoints, give them the same identifier. The value syncs across same-named variables after either step executes. Only one needs to be published.

### Auto-Backup
Stepper-NG can periodically back up all sequences, variables, and settings to JSON files. This protects against data loss from Burp project file corruption.

Backup files use the same JSON format as manual Export, so they can be restored via Import. A final backup is automatically performed when the extension is unloaded.

### Other Features
- Auto-regex generation from highlighted response text
- Stepper Replacements tab - previews all variable replacements with highlighting
- Sequence Overview tab - summary of all steps with conditions, variables (✦ = published), and last result
- Sequence arguments via `X-Stepper-Execute-Before: seq: var=val` or `X-Stepper-Argument: var=val` headers
- Execute-before step ranges: `X-Stepper-Execute-Before: seq{N}` runs only steps 0..N of the named sequence (`seq{start:end}` also accepted)
- Disable/enable individual steps or entire sequences via right-click
- Import/export sequences as JSON
- Infinite loop prevention with max nesting depth
- Corrupted project file resilience - falls back to in-memory storage if Burp's persistence layer is broken

### Session Handling Action
Stepper-NG registers a session handling action called "Stepper-NG: Variable Replacement for Extensions". This is required when the session handling rule scope includes Extensions.

Burp's processing order differs by scope:

| Scope | Order |
|-------|-------|
| Built-in tools (Repeater, Scanner, …) | Request → session handling rules replace cookie with `$VAR:` reference → Stepper HTTP handler replaces with actual value → send |
| Extensions | Extension sends request with actual value → session handling rules overwrite it with `$VAR:` reference → send (broken) |

For Extensions, the HTTP handler has already run by the time the session handling rule injects the reference. The correct value gets overwritten with a literal `$VAR:` string - this breaks other extensions' requests and Stepper's own step execution.

The Session Handling Action resolves this by performing variable replacement inside the session handling phase, after the preceding rule action has injected the reference.

Setup:
1. Burp → Settings → Sessions → Session handling rules.
2. Add/edit a rule, add the action "Invoke a Burp extension action" → "Stepper-NG: Variable Replacement for Extensions".
3. Place it after any actions that inject `$VAR:`, `$DVAR:`, or `$GVAR:` references.
4. Scope the rule to include Extensions.

## Limitations

These limitations are caused by Burp Suite's architecture.

### Session Handling Rules and Extensions Scope
When a session handling rule includes Extensions in its scope, Burp applies the rule after extension HTTP handlers have already run. If the rule replaces a cookie or header with a `$VAR:` reference, it overwrites the value that Stepper already placed there. The reference is sent as a literal string.

This affects other extensions (ActiveScan++, Turbo Intruder, etc.) and Stepper-NG's own step execution.

Workaround: add the "Stepper-NG: Variable Replacement for Extensions" session handling action as the next action after the one that injects references, scoped to Extensions.

If the extension sends requests with no variable references (literal stale tokens), Stepper-NG has no way to know which values to update.

## Changes from Original Stepper

- Migrated from legacy Burp Extender API to Montoya API
- Auto session handler via published variables with validation step and validate-every-N throttling
- Passthrough sync - published variables update from proxy/tool responses
- Conditional steps with regex/status conditions, match/not-match mode, retry, goto, skip, and else actions
- Session validation mode with validation step
- Post-validation mode with auto-pause engine on repeated failures
- Concurrent request holding - optionally block worker threads until a sequence finishes executing
- Auto-backup with configurable interval, max files, and directory
- Corrupted project file resilience with in-memory fallback
- Shared variable names - same-named variables across steps sync values
- Unified Global Variables tab (static `$GVAR:` + dynamic `$DVAR:`)
- Auto-regex generation from highlighted response text
- Stepper Replacements tab with variable highlighting
- Sequence arguments via inline syntax and `X-Stepper-Argument` headers
- Disable/enable individual steps and entire sequences
- Sequence Overview tab
- Infinite loop prevention with max depth limit
- Session handling action for Extensions scope
- Performance optimizations for scanner/proxy workloads
- Binary-safe variable replacement at the byte level - no UTF-8 round-trip, no corruption of binary request/response bodies, and the original "binary content" warning is gone
- Fixed Content-Length update for Montoya API compatibility

## Building

```bash
./gradlew clean jar
```

Output: `releases/stepper-ng.jar`

Requirements: Java 21, Gradle 9.4.0 (wrapper included)

## Installation

1. Build or download from [releases](https://github.com/Xre0uS/Stepper-NG/releases)
2. Burp Suite → Extensions → Add → select `stepper-ng.jar`

## Concurrency

Stepper-NG is designed for Burp's concurrent workloads (scanner, intruder, proxy firing simultaneously):

- One-at-a-time execution - each sequence holds a `synchronized` lock. Concurrent callers arriving while a sequence runs are silently skipped (or held if **Hold requests** is enabled).
- Hold requests mode - when enabled, concurrent worker threads wait for the executing sequence to finish (up to 30s) so they receive freshly updated variable values instead of stale ones.
- Thread-local execution stack - prevents circular dependencies and enforces a maximum nesting depth of 10.
- Internal request tagging - step execution requests are tagged per-thread so the HTTP handler skips them.
- Volatile flags - `isExecuting` and `disabled` use `volatile` for lock-free pre-checks on the hot path.
- EDT safety - all Swing updates dispatch via `invokeAndWait`/`invokeLater`. Post-validation alerts show outside the synchronized block to prevent deadlocks.

## Credits

- [CoreyD97](https://github.com/CoreyD97/Stepper) - Original Stepper extension
- [Ratsch0k](https://github.com/C0DEbrained/Stepper/pull/79) - Sequence arguments & disable warning
- [0xceba](https://github.com/0xceba/burp_variables) - Dynamic variables inspiration
- [cutecatcat16/stepper](https://github.com/cutecatcat16/stepper) - UI redesign inspiration 

## Attribution

Stepper-NG is a continuation and substantial modification of the original [Stepper](https://github.com/C0DEbrained/Stepper) project by CoreyD97.

This repository includes rewritten and modified work derived from the original project. Original copyright for inherited portions remains with the original author(s). Copyright © 2026 Xre0uS for new and modified portions.

Licensed under the GNU Affero General Public License v3.0. See [LICENCE](LICENCE).

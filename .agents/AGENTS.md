# Project Rules

Work autonomously inside this repository.

## Allowed Actions
You may:
- inspect/edit source
- run Gradle
- use ADB
- install updates with `adb install -r` when signatures match
- force-stop/relaunch Komikku
- perform local runtime testing

## Prohibited Actions (Require Explicit User Approval)
Never do these without explicit user approval:
- uninstall Komikku
- clear Komikku data
- uninstall an extension because of signer mismatch
- git commit
- git push
- create tags/releases
- delete unrelated files
- reset/clean/stash/discard unrelated Git work
- modify Team X, Lava Scans, or anime-extension projects unless explicitly asked
- expose passwords, cookies, tokens, or private account data

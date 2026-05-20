# Travel Guide Anywhere

## GitHub Links

Never include GitHub URLs in responses. When code is pushed to a branch, just state the branch name and tell the user to go to github.com/travisneedham/travel-guide-anywhere- and use either the "Compare & pull request" banner (appears automatically after a push) or the branch dropdown to create a PR.

## Branch

Always push directly to `main`. Never create feature branches. Increment `versionCode` and `versionName` in `app/build.gradle.kts` with every push — including bug fix and build error commits — so the user can verify the build in Android Studio.

Version format is `vX.Y.Z` where X, Y, Z are each a single digit 0–9. Increment Z by 1 each push. When Z reaches 9 and needs to go higher, reset Z to 0 and increment Y by 1. When Y hits 9 and needs to go higher, reset Y to 0 and increment X by 1. Examples: 2.5.9 → 2.6.0, 2.9.9 → 3.0.0.

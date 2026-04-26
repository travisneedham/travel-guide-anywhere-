# Travel Guide Anywhere

## GitHub Links

Never include GitHub URLs in responses. When code is pushed to a branch, just state the branch name and tell the user to go to github.com/travisneedham/travel-guide-anywhere- and use either the "Compare & pull request" banner (appears automatically after a push) or the branch dropdown to create a PR.

## Branch

Always push directly to `main`. Never create feature branches. Increment `versionCode` and `versionName` in `app/build.gradle.kts` with every push so the user can verify the build in Android Studio.

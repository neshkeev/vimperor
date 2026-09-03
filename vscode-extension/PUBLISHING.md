# Publishing Vimperor

Three commands, and one secret that is never in this repository.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

./gradlew :vscode-extension:assembleExtension   # dist/, which is what ships
./gradlew :vscode-extension:packageExtension    # the .vsix, in vscode-extension/build/
./gradlew :vscode-extension:publishExtension    # to the Marketplace. Needs VSCE_PAT
```

`vsce` is fetched by `npx` at a pinned version, using the Node the Kotlin plugin already downloads.
Nothing has to be installed on the machine, and nothing is added to this repository.

## Before the first publish

The publisher id in `package.json` is `neshkeev`, and it has to exist on the Marketplace before
anything can be pushed to it. Creating one is a three-step thing and only the first step is on
Microsoft's side:

1. Sign in at <https://marketplace.visualstudio.com/manage> and create the publisher. The id you
   choose there is the one `package.json` must carry; they cannot differ.
2. In an Azure DevOps organisation, make a **personal access token** with *Marketplace → Manage*
   scope, and set its organisation to **All accessible organizations**. A token scoped to one
   organisation is the usual reason a first publish fails with a 401.
3. `export VSCE_PAT=<the token>`. `publishExtension` refuses to run without it rather than letting
   `vsce` ask for it interactively, because a task that prompts is a task that hangs in CI.

The token expires — a year at most, and Azure DevOps will not tell you when. A publish that starts
failing with a 401 after months of working is almost always this.

`publishExtension` unpacks the archive it just built and activates it in a stubbed host before
sending anything - see `checkPackagedExtension`. That is the check `.vscodeignore` can fail:
`runInStubHost` loads out of the working tree, where every file exists whether or not it is
packaged, and this one loads out of the package. Excluding one file too many is exactly how a
`.vscodeignore` goes wrong.

## Testing a release before making one

```bash
./gradlew :vscode-extension:packageExtension
code --install-extension vscode-extension/build/vimperor-0.0.1.vsix
```

This is the only check that runs the extension the way a *user* gets it rather than the way a
developer does: `--extensionDevelopmentPath` loads from `build/`, and a `.vsix` loads from `dist/`
with `.vscodeignore` applied. Something excluded by mistake shows up here and nowhere else.

Disable any other Vim extension first. VS Code's `type` command has one owner.

## Versions

`package.json` holds the version and is the only place it lives — the Gradle `version` property is
`SNAPSHOT` and belongs to the IntelliJ plugin, which this module only shares a build with. The
`.vsix` takes its name from `package.json` for the same reason.

The Marketplace refuses a version that has already been published, which is the whole of the
release discipline: bump `package.json`, note the change in `CHANGELOG.md`, tag, publish.

## Releasing from GitHub

`.github/workflows/publish-vimperor.yml` does the same three commands on a `vimperor-v*` tag, with
`VSCE_PAT` as a repository secret. It is the only live workflow in this fork - everything inherited
from JetBrains is disabled by living in `.github/workflows-disabled/`, and this one is here because
a release should not depend on which machine it was run from.

```bash
git tag vimperor-v0.0.1
git push origin vimperor-v0.0.1
```

The tag's version and `package.json`'s must agree; the workflow checks and stops if they do not,
because a tag that says one thing and a package that says another is a release nobody can find
again.

# Publishing Vimperor

Three commands, and no secret in this repository.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)

./gradlew :vscode-extension:assembleExtension   # dist/, which is what ships
./gradlew :vscode-extension:packageExtension    # the .vsix, in vscode-extension/build/
./gradlew :vscode-extension:publishExtension    # to the Marketplace
./gradlew :vscode-extension:publishToOpenVsx    # to Open VSX, for every VS Code fork
```

**Two registries, one archive.** The Marketplace reaches VS Code and the forks cannot use it; Open
VSX reaches Cursor, Windsurf, VSCodium and the rest. Both take the same `.vsix` - the one
`checkPackagedExtension` unpacked and ran - rather than each building its own. See
[Open VSX](#open-vsx-cursor-windsurf-vscodium) below.

`vsce` is fetched by `npx` at a pinned version, using the Node the Kotlin plugin already downloads.
Nothing has to be installed on the machine, and nothing is added to this repository.

`publishExtension` unpacks the archive it just built and activates it in a stubbed host before
sending anything - see `checkPackagedExtension`. That is the check `.vscodeignore` can fail:
`runInStubHost` loads out of the working tree, where every file exists whether or not it is
packaged, and this one loads out of the package. Excluding one file too many is exactly how a
`.vscodeignore` goes wrong.

## Creating the publisher

The publisher id in `package.json` is `neshkeev`, and it has to exist on the Marketplace before
anything can be pushed to it. Sign in at <https://marketplace.visualstudio.com/manage> and create
it; the id chosen there is the one `package.json` must carry, they cannot differ, and it cannot be
changed afterwards.

Everything else on that page is about *who may publish to it* - the members list, and the role each
member has. Publishing needs **Contributor**.

## Signing in

**Azure DevOps retires global personal access tokens on 1 December 2026.** That is what this fork
used to publish with, and Microsoft's advice is now to use a Microsoft Entra ID identity instead:
[Publishing Extensions][docs] and [Retirement of global Personal Access Tokens][retirement]. So
there are three ways to prove who you are, `-PvsceAuth=` picks one, and the default is the one that
stores nothing.

### `entra` - a Microsoft Entra ID identity (the default)

```bash
az login --allow-no-subscriptions
./gradlew :vscode-extension:publishExtension
```

`vsce --azure-credential` asks `@azure/identity` for an Azure DevOps token for whatever identity
the machine is signed in as. There is no token to copy, store or rotate: the credential is the
sign-in, and it lasts as long as the sign-in does.

What has to be true is that the identity is a member of the `neshkeev` publisher with the
Contributor role. That is the same members list as above, and it is the only step Microsoft's side
of this needs.

**Two things go wrong on the way in, and neither says what it is.** Both were met publishing
0.0.1, and the second wasted the most time because it looks like an account problem and is not.

*The picker crashes.* On an account with no subscriptions, `az login` can die in its interactive
tenant picker:

```
AttributeError: 'NoneType' object has no attribute 'get'
  .../azure/cli/command_modules/profile/_subscription_selector.py, line 98
```

That is [azure-cli#31992](https://github.com/Azure/azure-cli/issues/31992) - the picker has no
active row to render - and `az config set core.login_experience_v2=off` turns the picker off.

*The browser silently does not sign you in.* Past the crash, `az login` printed `[]` and
`az account show` then said there was no account; `portal.azure.com` answered every click with
`AADSTS50058: A silent sign-in request was sent but no user is signed in`; and `dev.azure.com`
bounced to its marketing page.

That reads exactly like an identity with no Entra tenant, and it was not. AADSTS50058 says the
cause in its own text - *the cookies used to represent the user's session were not sent* - and the
portal, `dev.azure.com` and `az login`'s browser round trip all depend on silent auth through
hidden iframes, which needs third-party cookies. Safari blocks those by default under **Prevent
cross-site tracking**, as do Firefox's Total Cookie Protection, Brave and most privacy extensions.
Signing in from Chrome and going straight to <https://aex.dev.azure.com/me> worked at once.

So before concluding anything about the account: try another browser, and use
`az login --use-device-code`, which has no redirect and no iframe to block.

A sign-in it cannot use fails like this, before anything is uploaded:

```
ERROR  Can not acquire a Microsoft Entra ID access token. Additional information:
AggregateAuthenticationError: ChainedTokenCredential authentication failed.
```

The chain it tried is printed underneath - environment variables, the Azure CLI, a managed
identity, PowerShell, the Developer CLI - which is usually enough to see which one was expected to
work and did not.

### `oidc` - GitHub Actions' own token

`vsce --oidc` asks GitHub Actions for a token that says which repository and which workflow is
publishing, and trades it with the Marketplace for a credential. No Azure tenant, no identity to
register, no secret anywhere: exactly the right shape for the release workflow, and one line.

It is not usable yet. As of September 2026 the flag is hidden from `vsce --help` and ships only in
`@vscode/vsce@next`, so it is worth checking on before doing any of the Entra setup below. To try
it, and to see whether it has landed:

```bash
./gradlew :vscode-extension:publishExtension -PvsceVersion=3.9.3-11 -PvsceAuth=oidc
```

When it reaches a stable release: bump `vsceVersion` in `vscode-extension/build.gradle.kts`, and in
`.github/workflows/publish-vimperor.yml` delete the sign-in step and set `-PvsceAuth=oidc`.

### `pat` - a personal access token, until December 2026

In an Azure DevOps organisation, make a token with *Marketplace → Manage* scope and its
organisation set to **All accessible organizations**. A token scoped to one organisation is the
usual reason a first publish fails with a 401.

```bash
export VSCE_PAT=<the token>
./gradlew :vscode-extension:publishExtension
```

A `VSCE_PAT` in the environment selects this mode on its own, because a PAT that is set is a PAT
that was meant to be used. The other two modes empty it for the `vsce` process rather than leaving
it alone: `vsce` reads `--pat` before `--azure-credential`, and `--pat` defaults to `$VSCE_PAT`, so
a leftover token would quietly publish and leave you believing the Entra setup worked.

The token expires - a year at most, and Azure DevOps will not tell you when. A publish that starts
failing with a 401 after months of working is almost always that, and after December 2026 it will
be this whole section.

[docs]: https://code.visualstudio.com/api/working-with-extensions/publishing-extension
[retirement]: https://devblogs.microsoft.com/devops/retirement-of-global-personal-access-tokens-in-azure-devops/

## Open VSX (Cursor, Windsurf, VSCodium)

**A VS Code fork cannot install from Microsoft's Marketplace.** The terms have always limited it to
Microsoft's own products, and in 2025 that stopped being only a term - the forks were cut off. So
an extension published to the Marketplace alone is installable in VS Code and nowhere else by
search, however permissive its `engines.vscode` is.

[Open VSX](https://open-vsx.org) is the Eclipse Foundation's open registry, and it is what those
editors search instead. It is a separate service with a separate account, a separate token and a
separate namespace; **a Marketplace credential cannot publish to it** and neither can an Entra
identity. Nothing about the extension changes - the same `.vsix` goes to both.

### Setting it up, once

1. Sign in at <https://open-vsx.org> with GitHub. This creates the Eclipse account behind it.
2. **Sign the Eclipse Foundation Open VSX Publisher Agreement**, from your user settings. Publishing
   fails until this is done, and the error does not mention it.
3. Create an access token at <https://open-vsx.org/user-settings/tokens>. It is shown once.
4. Claim the namespace - it is `publisher` in `package.json`, and the build reads it from there
   rather than keeping a second copy that can disagree:

   ```bash
   export OVSX_PAT=<the token>
   ./gradlew :vscode-extension:createOpenVsxNamespace
   ```

   A namespace is claimed rather than created by publishing, the way a Marketplace publisher is.
   Skip it and the first publish fails with "namespace not found", which does not say what to do.

5. Check the token can actually publish to it, which is the one part of this that cannot be
   verified by reading:

   ```bash
   ./gradlew :vscode-extension:verifyOpenVsxToken
   ```

### From GitHub

Set `OVSX_PAT` as a repository **secret** and the release workflow publishes to both registries. It
is a secret and not a variable - unlike `AZURE_CLIENT_ID`, this one authenticates as you.

Leave it unset and the release still succeeds, reaching the Marketplace only, and the run carries a
warning saying exactly that. A half-delivered release that says nothing is the thing worth avoiding;
a half-delivered release that says so is a decision.

Open VSX has no federated sign-in to move to, so unlike the Marketplace this token is the long-term
arrangement rather than the one with a date on it. It expires when you tell it to.

### Re-running a release

`publishToOpenVsx` passes `--skip-duplicate`, so a version already there is not an error. That is
deliberate and asymmetric: adding a second registry creates a failure this release did not have -
one registry accepting a version and the other not - and finishing the missing half has to be
possible without tripping over the done half. The Marketplace publish has no such flag, because it
goes first and should still refuse a version that has been used.

So if Open VSX is the half that failed, fix the token and run that one task; there is no need to
re-run the whole workflow.

## Testing a release before making one

```bash
./gradlew :vscode-extension:packageExtension
code --install-extension vscode-extension/build/vimperor-0.0.4.vsix
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

`.github/workflows/publish-vimperor.yml` does the same three commands on a `vimperor-v*` tag. It is
the only workflow in this fork - everything inherited from IdeaVim was deleted rather than kept
switched off - and it is here because a release should not depend on which machine it was run
from.

```bash
git tag vimperor-v0.0.4
git push origin vimperor-v0.0.4
```

The tag's version and `package.json`'s must agree; the workflow checks and stops if they do not,
because a tag that says one thing and a package that says another is a release nobody can find
again. `workflow_dispatch` packages without publishing, and uploads the `.vsix` as an artifact
either way, so the release path can be exercised before a tag is trusted to it.

### What it signs in with

Set the repository **variables** `AZURE_CLIENT_ID` and `AZURE_TENANT_ID` and the workflow federates
to Entra ID: GitHub signs a token saying which repository is asking, Azure trades it for an access
token, and nothing durable is stored on either side. Variables and not secrets, deliberately - they
name an identity, they do not authenticate as it.

Leave them unset and it falls back to a `VSCE_PAT` repository secret, which is the arrangement with
a date on it.

Setting up the federation is Microsoft's [secure automated publishing][docs] with GitHub Actions in
place of Azure Pipelines, so their step 1 (a service connection) does not apply:

1. Register an application in Entra ID, or make a user-assigned managed identity. Note its client
   id and tenant id - those are the two variables.
2. Give it a **federated credential** with issuer `https://token.actions.githubusercontent.com`,
   audience `api://AzureADTokenExchange`, and a subject naming this repository. How to scope the
   subject is the one real decision: `repo:neshkeev/vimperor:ref:refs/heads/master` is a branch,
   `repo:neshkeev/vimperor:environment:marketplace` is a GitHub environment, and a tag pattern
   needs Entra's claims-matching rather than a literal subject.
3. Add the identity to the `neshkeev` publisher as a Contributor. This needs its Azure DevOps
   profile id rather than its client id; Microsoft's docs give the `az rest` call that prints it.

Until that exists, `workflow_dispatch` with no variables and no secret still packages and uploads a
`.vsix`, which is enough to install by hand.

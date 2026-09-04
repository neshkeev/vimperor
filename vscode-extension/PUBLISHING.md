# Publishing Vimperor

Three commands, and no secret in this repository.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

./gradlew :vscode-extension:assembleExtension   # dist/, which is what ships
./gradlew :vscode-extension:packageExtension    # the .vsix, in vscode-extension/build/
./gradlew :vscode-extension:publishExtension    # to the Marketplace
```

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

`.github/workflows/publish-vimperor.yml` does the same three commands on a `vimperor-v*` tag. It is
the only live workflow in this fork - everything inherited from JetBrains is disabled by living in
`.github/workflows-disabled/`, and this one is here because a release should not depend on which
machine it was run from.

```bash
git tag vimperor-v0.0.1
git push origin vimperor-v0.0.1
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

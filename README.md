# App Cloner

Clones an Android app on the device: gives it a new name and package, optionally a badged icon in
the corner of your choosing, re-signs it with a key you control, and installs or exports the
result. It then remembers where the clone came from, so it can tell you when the original has been
updated, rebuild the clone from the new version, or rebuild it with different settings.

Signing is the same machinery as [apk-signer](https://github.com/miron404/apk-signer): multiple
identities, private keys sealed behind the Titan M2 secure element, and Google's `apksig` for the
signatures themselves. Backup archives are format-compatible, so identities move between the two
apps.

## What it actually does to an APK

There is no Android SDK on the device, so nothing is decompiled and no resources are recompiled.
[ARSCLib](https://github.com/REAndroid/ARSCLib) is used to edit the binary `AndroidManifest.xml`
and `resources.arsc` in place, and everything else — dex, native libraries, assets — is copied
across untouched.

Renaming the package is more than swapping one attribute:

| What | Why it has to change |
| --- | --- |
| `package` on `<manifest>`, and the package name in `resources.arsc` | Both together, so `Resources.getIdentifier(name, type, getPackageName())` still resolves. |
| `android:name` on every component | It is relative to the manifest package. Expanded to an absolute class name **before** the package moves, or every component points at a class that is not there. |
| `<provider android:authorities>` | A device-global namespace. A duplicate is rejected with `INSTALL_FAILED_CONFLICTING_PROVIDER`. |
| `<permission android:name>` the app declares, and its own `uses-permission` | Also global: `INSTALL_FAILED_DUPLICATE_PERMISSION`. |
| `android:taskAffinity` | Left alone, the clone would share a recents entry with the original. |
| `android:process` (unless it starts with `:`) | A global process name would be shared too. |
| `android:sharedUserId` | Removed. It cannot survive a change of signing key, and the install would fail. |

Then the APK is re-aligned and signed with v1, v2 and v3, and `ApkVerifier` has to accept the
result before it is offered to you.

## Split APKs

Almost anything installed from Play arrives as a base plus config splits. All of them are rewritten
and signed with the same key, the same `minSdk` and the same set of schemes — a session install
rejects a set whose members disagree — and committed to one `PackageInstaller` session together.
Export writes a `.apks` zip when there is more than one file.

Sources can be an installed app, one APK, several APKs selected at once, or a single
`.apks`/`.xapk`/`.apkm` container.

## The icon, and Material You

This is the part with a real constraint. A themed icon is drawn from the `<monochrome>` layer of an
adaptive icon and filled with a single colour from the wallpaper palette. With themed icons on,
**every icon on the device is the same colour**, so a badge painted onto the foreground is simply
not visible, and recolouring the icon achieves nothing at all.

The only thing that survives the tint is shape. So the badge is *cut out* of the monochrome layer
rather than drawn on it: a transparent ring, a solid disc inside it, and the character knocked back
out of the disc. Tinted, that reads as a clear marker; untinted, the same badge is drawn in colour
on the foreground layer, so it looks deliberate either way.

The source icon is rendered by asking the platform for the real `Drawable` — `getPackageArchiveInfo`
with the source and split paths filled in, which works for an APK that is not installed — and
splitting an `AdaptiveIconDrawable` into its three layers. A legacy icon with no layers is inset
into the mask's safe area and given a background sampled from its own colours, and its monochrome
layer is derived from its alpha.

The new icon is added as a **new** resource rather than overwriting the app's existing one, because
an icon resource is usually referenced from a notification or an about screen as well. Only the
launcher's view of it is repointed, including any launcher activity that declares an
`android:icon` of its own — otherwise the launcher shows the unbadged original.

The badge goes in whichever **corner** you pick. All four are equally safe, and that is arithmetic
rather than taste: an adaptive icon is authored at 108dp and a launcher only ever shows the central
72dp, so the strictest mask in use — the circular one — keeps a disc of radius 0.333 of the canvas.
Each corner puts the badge's centre 0.155·√2 = 0.219 out, leaving its 0.100 radius inside that disc
with 0.014 to spare, about 6px of the 432px layer. `BadgeGeometryTest` checks it, because a corner
that fails this looks fine on the phone it was drawn on and gets clipped on the next one.

The form previews both results as you change them: the icon as a launcher will draw it, and the
monochrome layer alone, tinted — which is the whole point of the cut-out and the case worth seeing
before building rather than after installing.

"Keep the original" is always available and is the option that cannot go wrong.

## Deep rename (off by default)

Renaming the package in the manifest is invisible to code that was compiled against the old name.
The usual casualty is `BuildConfig.APPLICATION_ID`, which the compiler inlines as a string literal:
an app that builds a `FileProvider` authority from it asks for `com.old.provider` while its
manifest now declares `com.new.provider`, and crashes the first time it shares a file.

With deep rename on, the string pools of every `classes*.dex` are rewritten. Two rules keep it as
narrow as it can usefully be:

- Only strings that are exactly the old package, or live under it as `old.something`, are touched.
  Type descriptors are written `Lcom/old/Thing;` with slashes, so classes are never renamed.
- A string that names a class the app actually contains is left alone — those are `Class.forName`
  targets and reflection keys, and rewriting them would point the app at a class that does not
  exist. Every dex in the app is indexed before any of them is edited, so a class in `classes2.dex`
  still protects a string in `classes.dex`.

It is still a modification of someone else's code, and it is marked experimental for that reason.

### Where it runs, and why

Changing one string means re-sorting the dex string pool, and ARSCLib re-sorts the type, proto,
field, method and class sections with it, because items there reference strings by object rather
than by index. So the whole dex has to exist as objects at once. Measured against this app's own
debug build, whose largest dex is 42 MB, that costs **more than twelve times the size of the file**:
rebuilding it does not fit in the 512 MB a JVM gets by default, which is why the unit test that does
it asks for 2 GB.

That model used to be built in the same process as everything else, and it had to share the heap
with the resource table being rewritten and with whatever the screen was holding. Leaving the app
mid-build and coming back was enough to run it dry — and an `OutOfMemoryError` is thrown on
whichever thread allocates next, which was the one rebuilding the Compose tree. The app died, and
the build with it.

So dex files are now rewritten in **a process of their own**, `:dex`, by `DexWorkerService`. It
gets the whole of a phone's 512 MB `largeHeap` to itself, and if it runs out anyway, that process
ends and this one reports a failed build. What crosses between them is a description of the job —
paths, package names, and a class index written to a file — never a dex itself, which would be far
past the megabyte a binder call can carry. The worker holds no key material and never creates the
app's container; `AppClonerApplication` steps aside in any process but the main one, because
clearing the work directory there would delete the staged APKs that worker is about to read.

One worker is started per build and ended as soon as there are no dex files left, before signing,
so its heap goes back to the phone instead of lingering in a cached process. Ending it is also what
makes **Cancel** work in the middle of a dex: a binder call cannot be interrupted, so cancelling
stops the process, and the call returns at once.

Separately, the size is checked before any of this starts. The largest dex is weighed against the
worker's heap limit — the ceiling, not what happens to be free, because a model that has just been
released still counts as used until something collects it — and an app that cannot fit is refused
**before** the indexing pass with a message saying how big the dex is and how much it would have
needed. Running out no longer costs the app, but it would still cost the minutes spent before it
happened.

The manifest rename needs none of this and is never refused.

## What will not work

Cloning changes the package name and the signing key. Anything keyed to either of those breaks, and
no amount of rewriting fixes it:

- **Google Play services.** Maps, Sign-In, Play Integrity and SafetyNet are tied to the package name
  and certificate fingerprint registered with Google. The clone is neither.
- **Firebase Cloud Messaging.** `google-services.json` is compiled in with the original package
  name; registration fails.
- **Apps that check their own signature.** Banking apps, DRM-protected media, anything with an
  integrity check — the clone is detected.
- **Server-side app identity.** An app that sends its package name to its own backend sends the new
  one.
- **Anything relying on `sharedUserId`**, which is dropped.

The app scans for the first two while it works and says so before you install.

## A build outlives its screen

Repacking and signing a large app takes minutes, and the user will leave. The work therefore runs
in the application scope with a foreground service to keep the process, and — the part that is easy
to get wrong — the state of the build lives in `BuildController`, in the application container,
rather than in a view model. A view model dies with the activity; if the running build lived there,
leaving the app would leave a build with no screen and no way back to one.

So there are three ways back to a running build, and all of them lead to the same screen:

- the card at the top of the clones list, which shows the current step and its progress;
- the notification, which brings the app to that screen rather than to the list;
- **Rebuild** on a clone's page, which while something is building says "Go to the build in
  progress" and takes you there instead of queueing a second build.

That screen redraws itself from the build alone, so it works after the activity has been destroyed
and recreated, when there is no draft and no chosen source left anywhere. Leaving it does not
cancel anything — only Cancel does. A build that finishes while you are elsewhere keeps its result
until you have looked at it.

## Detecting updates

A clone and its source are unrelated packages as far as the system is concerned, so nothing links
them. This app keeps that link itself: for every clone it stores the source's package name and the
version code it was built from. On each launch it asks the package manager what version of that
source is installed now, and a clone whose source has moved on is flagged.

Rebuilding replays the same choices — name, package, icon, identity, deep rename — against the new
version. Because the signing key is the same, installing the result **updates** the existing clone
and its data survives.

"Change settings and rebuild" opens the same form on an existing clone with its own settings filled
in, so anything can be reconsidered later: the badge and its corner, whether the icon is touched at
all, the deep rename, the name, the signing identity. The source is read again from scratch — the
APKs this app produced are output, never input — so a clone built from a file that is no longer
installed asks for that file again, and only accepts the same package. Keeping the package name
makes the result an update of the clone on the device; changing it makes a second app, tracked as a
clone of its own, and the form says which of the two is about to happen.

This needs `QUERY_ALL_PACKAGES` to see other apps at all. There is still no `INTERNET` permission:
everything compared here is read locally.

## Building

CI (`.github/workflows/build.yml`) runs on GitHub Actions and uploads the debug and release APKs.

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The tests that touch APKs use the debug build as their fixture, so run `assembleDebug` first; they
skip themselves otherwise rather than a binary being committed.

They exist because none of this can be checked by the compiler. Everything here writes binary
formats by hand, so each test writes an APK out and reads it back with a different parser than the
one that produced it:

| Test | What it proves |
| --- | --- |
| `RewriteApkTest` | The renamed APK's package is what the platform's own manifest parser reports, no component name is left relative, and the result still signs and verifies. |
| `RewriteApkTest` (dex) | A dex whose string pool was rewritten can be parsed again — which is the real check, since an unsorted string table or a stale offset makes it unreadable — and that class names and type descriptors came through untouched. |
| `IconInjectionTest` | The hand-built `<adaptive-icon>` document round-trips through the parser, the manifest's icon id resolves to an entry carrying both configurations, and each layer references a drawable that was actually added. |
| `ClonePipelineTest` | A whole build in the right order: renamed, re-iconed, signed, and accepted by `ApkVerifier` as the identity that signed it, with the badge in the corner the request asked for. |
| `BadgeGeometryTest` | Every badge corner leaves the badge inside a circular mask, and a clone recorded before the corner was a setting still decodes — which matters because the registry treats a decoding failure as an empty file. |
| `DexHeapBudgetTest` | The budget that decides whether a deep rename is attempted: this app's own 42 MB dex is refused against a phone's 512 MB heap and allowed against the 2 GB the tests get, the reserve is left free, and an undeclared entry size is not read as costing nothing. |
| `DexWorkerTest` | Everything that crosses into the `:dex` process, without the process: the class index survives a file — spaces and characters outside the BMP included — jobs and outcomes survive the protocol, an impossible job comes back as an answer rather than an exception, and each way of failing becomes a message that names the dex. |
| `ClonePipelineTest` (worker) | A build hands its dex files to the worker, refuses up front when the worker's heap is too small, fails cleanly when the worker runs out, starts no worker when deep rename is off — and shuts the worker down every time, because on a phone a forgotten one is a process holding half a gigabyte. |

What no test here covers is the last step: a clone actually installing and running on a device.
`PackageInstaller` needs a real user confirming a real dialog, so that is the part to try by hand.
The same goes for leaving the app mid-build and finding the way back to it — that is Android
lifecycle behaviour, not something a JVM test can stand in for — and for the binder transport to the
`:dex` process: the tests drive the same runner in-process, but starting the process, cancelling a
build mid-dex, and the worker dying under it are things to try on a phone.

### Release signing

Without secrets configured, CI mints a throwaway RSA key per run, names the artifact
`...-cikey.apk`, and prints its SHA-256 in the log. The key differs every run, so a new build
replaces rather than updates an existing install — export an encrypted backup of the vault first if
it has identities in it. To sign with a key you control, set `SIGNING_KEYSTORE_B64`,
`SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` as repository secrets.

## Requirements

- Android 13 (API 33) or newer
- A secure lock screen; a StrongBox-backed device (Pixel 3 and later) for hardware key protection
- Enough free space for roughly three copies of the app being cloned while it is being built

## Notes

- Signature schemes are fixed at v1 + v2 + v3, which is right for every device this can install to.
- Unlike apk-signer, the window is not `FLAG_SECURE`. Nothing secret is ever on screen here — no
  private key material is displayed — and blocking screenshots of an app list is not worth it.
- Cloning apps you have the right to use is a normal thing to do on your own device. Redistributing
  a modified, re-signed build of someone else's app usually is not; that is between you and their
  licence.

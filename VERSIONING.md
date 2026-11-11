# CivMaker versioning

CivMaker uses `MAJOR.MINOR.PATCH`. Choose the version when preparing a JAR for a server update or a shared release. Group the changes since the previous release, take the highest applicable bump, and bump once for the whole release. Local builds and commits can share the current development version.

| Bump | When to use it | CivMaker example |
| --- | --- | --- |
| Patch (`0.2.14` → `0.2.15`) | Fix or tune existing behavior while keeping upgrades compatible. | Fix an incorrect raid payout, adjust the price of an existing shop item, or fix an Atlas display bug. |
| Minor (`0.2.14` → `0.3.0`) | Add a player-facing or admin-facing feature that existing servers can upgrade to without a manual migration. | Add warp portals, a configurable combat timeout, or a new `/civ` subcommand while keeping existing worlds, commands, and configs working. |
| Major (`0.2.14` → `1.0.0`) | Ship an intentional compatibility break, including during the `0.x` series. | Require a newer Paper version, remove a command used by server scripts, or require admins to convert existing village data or config by hand. |

When deciding whether an upgrade is compatible, check saved worlds and plugin data, configs, commands, permissions, supported Paper and Java versions, and the World Atlas export. For example, adding an optional Atlas field is a minor change. Renaming a field that deployed Atlas clients depend on is a major change unless the old field still works. Documentation-only and internal-only changes can wait for the next release. Use a patch bump if you ship a new JAR containing only those changes.

## Preparing the release

1. Set the same version in the project-level `<version>` in [`pom.xml`](pom.xml) and the `version` field in [`src/main/resources/plugin.yml`](src/main/resources/plugin.yml).
2. Update the JAR filename in the build and install section of [`README.md`](README.md).
3. Run `mvn package` and check that the filename under `target/` and the `plugin.yml` inside the JAR report the intended version. Preserve older release JARs. If a clean build is necessary, archive the existing JARs outside `target/` first because `mvn clean` deletes that directory.
4. For changes that affect upgrades, test with a copy of an existing server's CivMaker data and config. Include any required upgrade steps in the release notes.

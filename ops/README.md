# ops: the weekly craft catalog drift probe

Upstream storytold apps move by thousands of commits a month and rearrange their file layout
(`commands.rs` -> `cmd.rs`, `tools.rs` -> `tools/`, `control.rs` -> `control/`). A rearrangement
silently empties an extractor. The drift probe runs every catalog extractor against storytold
HEAD and reports, per app, the pinned revision, HEAD, commits behind, and the catalog ids added
and removed per kind (engine, MCP, control).

## Running it by hand

From the main checkout (`~/PP/hive/hive-artcraft`, beside `hive-photocraft` and `hive-vectorcraft`):

```sh
clojure -M:drift                       # all 17 apps, fetching the clones first
clojure -M:drift --no-fetch pdfcraft   # one app, last fetched HEAD
clojure -M:drift --clones ~/PP/artcraft --out /tmp/drift
```

Output, in `target/drift/<UTC date>/` unless `--out` says otherwise:

- `drift.edn`: the report as data (`craft-drift.report/Report`)
- `drift.md`: the same report as a table, followed by failures, warnings and the ids that moved
- `catalogs/<app>.edn`: each catalog as extracted at HEAD, ready to diff against `resources/`

Exit codes: **1** when an extractor failed, meaning it threw or a kind that had ids at the pin has
none at HEAD. **64** on bad arguments. **0** otherwise. Drift on its own never fails the run.

## What it touches

For each `~/PP/artcraft/<repo>` clone the probe only runs `git fetch origin`. It never checks the
clone out. HEAD (`origin/HEAD`) is read through a throwaway `git clone --shared --no-checkout`
in a temp dir, and that dir is deleted afterwards. The probe never reads or moves the pinned
`~/PP/hive/clones-ref` trees that `catalogs-reproduce-from-the-clones` relies on.

## Weekly schedule (systemd --user)

GitHub-hosted runners cannot reach the storytold clones on this host, so the schedule is a
user timer, in the same style as `build-reap`. Install it yourself; nothing installs it for you:

```sh
mkdir -p ~/.config/systemd/user
cp ops/systemd/craft-drift.service ops/systemd/craft-drift.timer ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now craft-drift.timer
systemctl --user list-timers craft-drift.timer       # next run: Mondays 06:00 (+0-30 min)
systemctl --user start craft-drift.service           # run once now
journalctl --user -u craft-drift.service -e          # the markdown report is printed here
```

A failed run (exit 1) puts the unit into `failed`, so `systemctl --user --failed` lists it.
To remove the schedule: `systemctl --user disable --now craft-drift.timer`, then delete the two
unit files.

# hive-olympus-vim

Olympus in Vim. This brick has no code: it is one manifest that mounts
`hive-olympus.harness/addon-ctor` with `{:olympus/host "hive.vim"}`. The core
(`hive.olympus`) renders the agent grid as one `:ui/show-panel` per tab; the
brick hands those ops to `hive.vim`'s `:vessel/dispatch!`, and Vim paints each
tab as a `hive://olympus/tab-N` panel buffer in its own split.

```
resources/META-INF/hive-addons/hive-olympus-vim.edn
```

Requires `hive.olympus` and `hive.vim` mounted in the same hive, and a Vim
running the hive-vim and hive-vessel plugins connected to `hive.vim`.

hive.vim exposes both `:vessel/target` and `:vessel/dispatch!`; the harness
prefers dispatch, so health reports route `:host-dispatch`. If Vim connects
after the brick mounts, the first delivery fails, the presenter reads
`:degraded`, and the core's refresh tick delivers again once Vim is there.

## Test

hive-olympus and hive-vim are not yet published, so point at sibling checkouts
with an untracked `local.deps.edn`:

```clojure
{:deps {io.github.hive-agi/hive-olympus {:local/root "../hive-olympus"}
        io.github.hive-agi/hive-vim {:local/root "../hive-vim"}}}
```

Manifest tests (stub hosts, then the real core):

```
clojure -Sdeps "$(cat local.deps.edn)" -M:test
```

Real Vim (needs `vim` and `tmux`; starts a detached tmux session and removes it):

```
clojure -Sdeps "$(cat local.deps.edn)" -M:dev -m hive-olympus-vim.vim-e2e
```

It mounts the real hive.vim, hive.olympus (a six-agent stub roster) and this
brick in a fresh JVM, connects a Vim, and checks that the `olympus/tab-1` and
`olympus/tab-2` buffers hold exactly the lines hive-vessel renders for each
tab. From a REPL on the `:dev` alias, `(hive-olympus-vim.vim-e2e/run!)` returns
the same evidence as data.

MIT licensed.

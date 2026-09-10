Vimperor Plugins
--------------------

Vimperor bundles its own ports of popular Vim plugins, so there is nothing to install. They work like the original Vim plugins: if you want to turn any of them on, enable it with this command in your `~/.vimperorrc`:

```
Plug '<plugin-github-reference>'
```

or with `set <plugin-name>`. `Plugin '<plugin-github-reference>'`, as Vundle spells it, works as well.

If you reuse your existing `.vimrc` file using `source ~/.vimrc`, Vimperor turns on the bundled plugins that are defined
using [vim-plug](https://github.com/junegunn/vim-plug) or [vundle](https://github.com/VundleVim/Vundle.vim).
No additional set commands in `~/.vimperorrc` are required.
If you'd like to disable some plugin that's enabled in `.vimrc`, you can use `set no<plugin-name>`
in `~/.vimperorrc`. E.g. `set nosurround`.

Available plugins:

<details>
<summary><h2>abolish: Case-aware substitute and coercion mappings between case styles</h2></summary>

Original plugin: [vim-abolish](https://github.com/tpope/vim-abolish).

### Summary:
Coercion mappings (`crs`/`cr_`, `crm`/`crp`, `crc`, `cru`/`crU`, `cr-`/`crk`, `cr.`, `cr<Space>`, `crt`)
recase the word under the cursor between snake_case, MixedCase, camelCase, UPPER_SNAKE, kebab-case,
dot.case, space case and Title Case. `:Subvert` (alias `:S`) is a case-aware `:substitute` that
handles all case variants and `{a,b,c}` brace alternatives in a single command. With only a pattern
(`:S/foo/` or `:S?foo?`) it does a case-aware forward or backward search instead.

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'tpope/vim-abolish'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'tpope/vim-abolish'</code>
      <br/>
      <code>Plug 'https://github.com/tpope/vim-abolish'</code>
      <br/>
      <code>Plug 'vim-abolish'</code>
      <br/>
      <code>set abolish</code>
      </details>

### Instructions

https://github.com/tpope/vim-abolish/blob/master/doc/abolish.txt

`:Abolish` (insert-mode auto-correcting abbreviations) is not supported.

By default each `cr<x>` mapping recases the inner word under the cursor.
To get tpope-style motion support (`crsiw`, `crsap`, `crs2w`, …) remap to the
per-style operator `<Plug>` mappings:

```
nmap crs <Plug>(abolish-coerce-snake)
nmap crm <Plug>(abolish-coerce-pascal)
nmap crc <Plug>(abolish-coerce-camel)
nmap cru <Plug>(abolish-coerce-upper_snake)
nmap cr- <Plug>(abolish-coerce-kebab)
nmap cr. <Plug>(abolish-coerce-dot)
nmap cr<Space> <Plug>(abolish-coerce-space)
nmap crt <Plug>(abolish-coerce-title)
```

To bind extra trigger characters, set `g:abolish_coercions` *before* enabling
the plugin. Each entry maps a single character to the name of a built-in case
style (case-insensitive: `snake`, `pascal`, `camel`, `upper_snake`, `kebab`,
`dot`, `space`, `title`).

```
let g:abolish_coercions = {'q': 'kebab', 'x': 'upper_snake'}
```

After this, `crq` recases to kebab-case and `crx` to UPPER_SNAKE.

</details>

<details>
<summary><h2>argtextobj: Provides a text-object 'a' argument</h2></summary>

Original plugin: [argtextobj.vim](https://www.vim.org/scripts/script.php?script_id=2699).

### Summary:
This plugin provides a text-object 'a' (argument).
You can d(elete), c(hange), v(select)... an argument or inner argument in familiar ways.

That is, such as 'daa'(delete-an-argument) 'cia'(change-inner-argument) 'via'(select-inner-argument).
What this script does is more than just typing

F,dt,

because it recognizes inclusion relationship of parentheses.

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'vim-scripts/argtextobj.vim'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'vim-scripts/argtextobj.vim'</code>
      <br/>
      <code>Plug 'https://github.com/vim-scripts/argtextobj.vim'</code>
      <br/>
      <code>Plug 'argtextobj.vim'</code>
      <br/>
      <code>Plug 'https://www.vim.org/scripts/script.php?script_id=2699'</code>
      <br/>
      <code>set argtextobj</code>
      </details>

### Instructions

By default, only the arguments inside parenthesis are considered. To extend the functionality
to other types of brackets, set `g:argtextobj_pairs` variable to a comma-separated
list of colon-separated pairs (same as VIM's `matchpairs` option), like
`let g:argtextobj_pairs="(:),{:},<:>"`. The order of pairs matters when
handling symbols that can also be operators: `func(x << 5, 20) >> 17`. To handle
this syntax parenthesis, must come before angle brackets in the list.

https://www.vim.org/scripts/script.php?script_id=2699

</details>

<details>
<summary><h2>CamelCaseMotion: Motions through CamelCase and snake_case words</h2></summary>

Original plugin: [CamelCaseMotion](https://github.com/bkad/CamelCaseMotion).

### Summary:
Adds `w`, `b`, `e`, `ge` style motions and `iw`/`ib`/`ie`/`ige` inner "word" text objects that move
and select by sub-word boundaries — uppercase letters in `CamelCase` and `_`/`-` delimiters in
`snake_case`/`kebab-case` — instead of whole words. For example, with the leader as the key,
`<leader>w` jumps `Camel|CaseWord` → `Camel[C]aseWord`, and `ci<leader>w` changes a single chunk of
an identifier. The motions honour `[count]`, work in normal, visual and operator-pending modes, and
respect the `'iskeyword'` option.

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'bkad/CamelCaseMotion'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'bkad/CamelCaseMotion'</code>
      <br/>
      <code>Plug 'https://github.com/bkad/CamelCaseMotion'</code>
      <br/>
      <code>Plug 'CamelCaseMotion'</code>
      <br/>
      <code>set CamelCaseMotion</code>
      </details>
- No mappings are created until you choose a key. The most common setup is to add this to your
  `~/.vimperorrc` (before the `Plug`/`set` line):

  ```
  let g:camelcasemotion_key = '<leader>'
  ```

  which creates `<leader>w`, `<leader>b`, `<leader>e`, `<leader>ge` and the inner objects
  `i<leader>w`, `i<leader>b`, `i<leader>e`, `i<leader>ge`.

### Instructions

https://github.com/bkad/CamelCaseMotion/blob/master/README.markdown

With `g:camelcasemotion_key` set to `<leader>` (the default leader is `\`):

| Mapping | Description |
|---------|-------------|
| `<leader>w` | Move forward to the next start of a sub-word |
| `<leader>b` | Move backward to the previous start of a sub-word |
| `<leader>e` | Move forward to the next end of a sub-word |
| `<leader>ge` | Move backward to the previous end of a sub-word |
| `i<leader>w` | Inner sub-word (e.g. `ci<leader>w`, `vi<leader>w`) |
| `i<leader>b` | Inner sub-word, extends backward with a count |
| `i<leader>e` | Inner sub-word, excludes a trailing delimiter |
| `i<leader>ge` | Inner sub-word, backward to the previous word end |

Instead of (or in addition to) `g:camelcasemotion_key`, you can map the motion `<Plug>` targets
directly, for example to replace the built-in `w`/`b`/`e`:

```
map <silent> w <Plug>CamelCaseMotion_w
map <silent> b <Plug>CamelCaseMotion_b
map <silent> e <Plug>CamelCaseMotion_e
map <silent> ge <Plug>CamelCaseMotion_ge
```

</details>

<details>
<summary><h2>classtextobj: Provides a text object for class definitions</h2></summary>

### Summary:
Adds the `ac` text object that selects "a class definition" — the entire class declaration and body, or the
interface, struct or enum you are inside. It works in any language whose VS Code extension provides document
symbols, which is what VS Code's Outline view shows.

### Setup:
- Add the following command to `~/.vimperorrc`: `set classtextobj`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plug 'kana/vim-textobj-class'</code>
      <br/>
      <code>Plug 'vim-textobj-class'</code>
    </details>

### Instructions

| Mapping | Description |
|---------|-------------|
| `ac`    | A class definition (e.g. `vac`, `dac`, `cac`, `yac`) |

When classes are nested, the innermost enclosing class is selected.

The symbols are kept up to date in the background. For the moment between an edit and the language's answer,
and in a language with no symbols at all, `ac` does nothing rather than select a range that has moved.

</details>

<details>
<summary><h2>commentary: Adds mapping for quickly commenting stuff out</h2></summary>

By [Daniel Leong](https://github.com/dhleong)
Original plugin: [commentary.vim](https://github.com/tpope/vim-commentary).

### Summary:
Comment stuff out.
Use gcc to comment out a line (takes a count), gc to comment out the target of a motion
(for example, gcap to comment out a paragraph), gc in visual mode to comment out the selection,
and gcu to uncomment a set of adjacent commented lines.
You can also use it as a command, either with a range like :7,17Commentary,
or as part of a :global invocation like with :g/TODO/Commentary.
That's it.

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'tpope/vim-commentary'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'tpope/vim-commentary'</code>
      <br/>
      <code>Plug 'https://github.com/tpope/vim-commentary'</code>
      <br/>
      <code>Plug 'vim-commentary'</code>
      <br/>
      <code>Plug 'tcomment_vim'</code>
      <br/>
      <code>set commentary</code>
      </details>

### Instructions

https://github.com/tpope/vim-commentary/blob/master/doc/commentary.txt

Comments use the syntax VS Code knows for the file's language, the same as its own *Toggle Line Comment*.
`gc` as a text object in operator-pending mode (`dgc`) needs a syntax tree and does nothing.

</details>

<details>
<summary><h2>easymotion: Simplifies some motions</h2></summary>

Original plugin: [vim-easymotion](https://github.com/easymotion/vim-easymotion).

### Summary:
EasyMotion provides a much simpler way to use some motions in vim.
It takes the \<number> out of \<number>w or \<number>f{char} by highlighting all possible choices
and allowing you to press one key to jump directly to the target.

Vimperor's easymotion is written for Vimperor. It follows vim-easymotion's documentation, default mappings and
labelling, and needs nothing else installed.

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'easymotion/vim-easymotion'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'easymotion/vim-easymotion'</code>
      <br/>
      <code>Plug 'https://github.com/easymotion/vim-easymotion'</code>
      <br/>
      <code>Plug 'vim-easymotion'</code>
      <br/>
      <code>set easymotion</code>
      </details>

### Instructions

https://github.com/easymotion/vim-easymotion/blob/master/doc/easymotion.txt

Press `<Leader><Leader>` and then a motion key. Every target of that motion on the screen gets a label, and
typing the label jumps there. A label longer than one key narrows as you type it, and `<Esc>` cancels.
The motions work in normal, visual and operator-pending modes, so `d<Leader><Leader>w` deletes up to the word
you pick, and `j` and `k` take whole lines under an operator.

The default mappings, each after `<Leader><Leader>` (the default leader is `\`):

| Mapping | Jumps to |
|---------|----------|
| `w` / `W` | The start of a word / WORD, forward |
| `b` / `B` | The start of a word / WORD, backward |
| `e` / `E` | The end of a word / WORD, forward |
| `ge` / `gE` | The end of a word / WORD, backward |
| `j` / `k` | A line below / above |
| `f{char}` / `F{char}` | `{char}`, forward / backward |
| `t{char}` / `T{char}` | Next to `{char}`, forward / backward |
| `s{char}` | `{char}`, in both directions |
| `n` / `N` | A match of the last search, forward / backward |

To hang the defaults off a different key, map `<Plug>(easymotion-prefix)`: `map <Leader> <Plug>(easymotion-prefix)`.

These motions have no default mapping, and are there to map yourself:

- `<Plug>(easymotion-bd-f)`, `bd-t`, `bd-w`, `bd-W`, `bd-e`, `bd-E`, `bd-jk` and `bd-n`, in both directions
- `<Plug>(easymotion-sol-j)`, `sol-k` and `sol-bd-jk` to the first non-blank of a line, and `eol-j`, `eol-k`
  and `eol-bd-jk` to the end of one
- `<Plug>(easymotion-sl)`, `fl`, `Fl`, `bd-fl`, `tl`, `Tl`, `bd-tl`, `wl`, `bl`, `bd-wl`, `el`, `gel` and
  `bd-el`, within the current line
- `<Plug>(easymotion-iskeyword-w)`, `iskeyword-b`, `iskeyword-bd-w`, `iskeyword-e`, `iskeyword-ge` and
  `iskeyword-bd-e`, by words as `'iskeyword'` defines them
- `<Plug>(easymotion-vim-n)` and `vim-N`

```
map <Leader>j <Plug>(easymotion-bd-jk)
nmap s <Plug>(easymotion-bd-f)
```

| Variable | Default | Description |
|----------|---------|-------------|
| `g:EasyMotion_do_mapping` | `1` | Set to `0` to create no default mappings |
| `g:EasyMotion_keys` | `asdghklqwertyuiopzxcvbnmfj;` | The keys labels are made of |
| `g:EasyMotion_startofline` | `1` | `j` and `k` jump to the first non-blank; `0` keeps the column |
| `g:EasyMotion_smartcase` | `0` | A lowercase `{char}` also finds its uppercase letter |
| `g:EasyMotion_do_shade` | `1` | Dim the text around the labels |
| `g:EasyMotion_use_upper` | `0` | Labels are matched in upper case, so upper-case `g:EasyMotion_keys` can be typed in either case |

Labels are typed as commands, so with `'keyboardlayout'` set they can be typed on that layout too; the
`{char}` a find looks for is text, and is taken as typed.

Labels are badges in your theme's colours. `:highlight EasyMotionTarget`, `EasyMotionTarget2First` and
`EasyMotionShade` recolour them, as they do in Vim. The labels sit over the text by way of a styling trick VS Code
does not document; if an update breaks it, they will appear beside their targets rather than on them.

Not supported yet: the multi-character finds (`s2`, `sn`), `repeat`, `next`, `prev`, `jumptoanywhere`, the `line*`
motions, and repeating a jump with `.`.

</details>

<details>
<summary><h2>exchange: Easy text exchange operator</h2></summary>

By [fan-tom](https://github.com/fan-tom)
Original plugin: [vim-exchange](https://github.com/tommcdo/vim-exchange).

### Summary:
Easy text exchange operator for Vim.

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'tommcdo/vim-exchange'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'tommcdo/vim-exchange'</code>
      <br/>
      <code>Plug 'https://github.com/tommcdo/vim-exchange'</code>
      <br/>
      <code>Plug 'vim-exchange'</code>
      <br/>
      <code>set exchange</code>
      </details>

### Instructions

https://github.com/tommcdo/vim-exchange/blob/master/doc/exchange.txt

</details>

<details>
<summary><h2>functextobj: Provides text objects for method/function definitions</h2></summary>

Inspired by [vim-textobj-function](https://github.com/kana/vim-textobj-function).

### Summary:
Adds text objects for selecting methods/functions. Three variants distinguish what is included: the signature and
body, the same with the doc comment, or just the inner body content. It works in any language whose VS Code
extension provides document symbols, which is what VS Code's Outline view shows.

### Setup:
- Add the following command to `~/.vimperorrc`: `set functextobj`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plug 'kana/vim-textobj-function'</code>
      <br/>
      <code>Plug 'vim-textobj-function'</code>
    </details>

### Instructions

| Mapping | Description |
|---------|-------------|
| `am`    | A method definition (signature + body, excluding the doc comment) |
| `aM`    | A Method definition (same as `am`, but including the doc comment) |
| `im`    | Inner method definition (only the contents between the body braces) |

Use with operators (`d`, `c`, `y`) or in visual mode — e.g. `dam`, `cim`, `vaM`. When methods are nested, the innermost enclosing method is selected.

The symbols are kept up to date in the background. For the moment between an edit and the language's answer,
and in a language with no symbols at all, these text objects do nothing rather than select a range that has moved.

</details>

<details>
<summary><h2>highlightedyank: Highlights the yanked region</h2></summary>

By [KostkaBrukowa](https://github.com/KostkaBrukowa)
Original plugin: [vim-highlightedyank](https://github.com/machakann/vim-highlightedyank).

### Summary:
Make the yanked region apparent!

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'machakann/vim-highlightedyank'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'machakann/vim-highlightedyank'</code>
      <br/>
      <code>Plug 'https://github.com/machakann/vim-highlightedyank'</code>
      <br/>
      <code>Plug 'vim-highlightedyank'</code>
      <br/>
      <code>set highlightedyank</code>
      </details>

### Instructions

If you want to optimize highlight duration, assign a time in milliseconds:
`let g:highlightedyank_highlight_duration = "1000"`
A negative number makes the highlight persistent.

If you want to change background color of highlight you can provide the rgba of the color you want e.g.
`let g:highlightedyank_highlight_color = "rgba(160, 160, 160, 155)"`

If you want to change text color of highlight you can provide the rgba of the color you want e.g.
`let g:highlightedyank_highlight_foreground_color = "rgba(0, 0, 0, 255)"`

https://github.com/machakann/vim-highlightedyank/blob/master/doc/highlightedyank.txt

</details>

<details>
<summary><h2>indent-object: Adds text objects for manipulating sentences/paragraphs/etc...</h2></summary>

By [Shrikant Sharat Kandula](https://github.com/sharat87)
Original plugin: [vim-indent-object](https://github.com/michaeljsmith/vim-indent-object).

### Summary:
Vim text objects provide a convenient way to select and operate on various types of objects.
These objects include regions surrounded by various types of brackets and various parts of language
(ie sentences, paragraphs, etc).

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'michaeljsmith/vim-indent-object'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'michaeljsmith/vim-indent-object'</code>
      <br/>
      <code>Plug 'https://github.com/michaeljsmith/vim-indent-object'</code>
      <br/>
      <code>Plug 'vim-indent-object'</code>
      <br/>
      <code>set textobj-indent</code>
      </details>

### Instructions

https://github.com/michaeljsmith/vim-indent-object/blob/master/doc/indent-object.txt

</details>

<details>
<summary><h2>indentwise: Motions based on indentation depth and indent blocks</h2></summary>

Original plugin: [vim-indentwise](https://github.com/jeetsukumaran/vim-indentwise).

### Summary:
Adds motions that navigate by the indentation level of lines rather than by their content. You can
jump to the previous/next line of lesser, greater or equal indentation relative to the current line,
and jump to the beginning/end of the current indentation block. Blank and whitespace-only lines are
skipped, so the motions follow the visible structure of the code. The motions take a `[count]`, and
work in normal, visual and operator-pending modes (e.g. `d]-`, `v[%`). Operator-pending and visual
moves are line-wise.

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'jeetsukumaran/vim-indentwise'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'jeetsukumaran/vim-indentwise'</code>
      <br/>
      <code>Plug 'https://github.com/jeetsukumaran/vim-indentwise'</code>
      <br/>
      <code>Plug 'vim-indentwise'</code>
      <br/>
      <code>set indentwise</code>
      </details>

### Instructions

https://github.com/jeetsukumaran/vim-indentwise/blob/master/doc/indentwise.txt

| Mapping | Description |
|---------|-------------|
| `[-`    | Move to the previous line of **lesser** indent |
| `]-`    | Move to the next line of **lesser** indent |
| `[+`    | Move to the previous line of **greater** indent |
| `]+`    | Move to the next line of **greater** indent |
| `[=`    | Move to the previous line of **equal** indent |
| `]=`    | Move to the next line of **equal** indent |
| `[%`    | Move to the **beginning** of the current indent block |
| `]%`    | Move to the **end** of the current indent block |

For the block-scope motions, a `[count]` repeats outward through enclosing blocks (e.g. `2[%`). The
absolute-indent motions (`[_`, `]_`) from the original plugin are not currently supported.

</details>

<details>
<summary><h2>Mini.ai: Extend and create a/i textobjects (IMPORTANT: The plugin is not related with artificial intelligence)</h2></summary>

### Summary:
Extend and create a/i textobjects

### Features:
Provides additional text object motions for handling quotes and brackets. The following motions are included:

- aq: Around any quotes.
- iq: Inside any quotes.
- ab: Around any parentheses, curly braces, and square brackets.
- ib: Inside any parentheses, curly braces, and square brackets.

Original plugin: [mini.ai](https://github.com/echasnovski/mini.ai).

### Setup:
- Add the following command to `~/.vimperorrc`: `set mini-ai`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plug 'echasnovski/mini.ai'</code>
      <br/>
      <code>Plug 'mini.ai'</code>
    </details>

</details>

<details>
<summary><h2>multiple-cursors: Extends multicursor support</h2></summary>

Original plugin: [vim-multiple-cursors](https://github.com/terryma/vim-multiple-cursors).

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'terryma/vim-multiple-cursors'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'terryma/vim-multiple-cursors'</code>
      <br/>
      <code>Plug 'https://github.com/terryma/vim-multiple-cursors'</code>
      <br/>
      <code>Plug 'vim-multiple-cursors'</code>
      <br/>
      <code>set multiple-cursors</code>
      </details>

### Instructions

The default key bindings match the original terryma/vim-multiple-cursors plugin:

| Action                                            | Shortcut  |
|---------------------------------------------------|-----------|
| Start / add next occurrence (whole word)          | `<C-n>`   |
| Start / add next occurrence (not whole word)      | `g<C-n>`  |
| Select all occurrences in file (whole word)       | `<A-n>`   |
| Select all occurrences in file (not whole word)   | `g<A-n>`  |
| Skip current and select next (during selection)   | `<C-x>`   |
| Remove current selection (during selection)       | `<C-p>`   |

These map to the following `<Plug>` mappings:

```
nmap <C-n> <Plug>NextWholeOccurrence
xmap <C-n> <Plug>NextWholeOccurrence
nmap g<C-n> <Plug>NextOccurrence
xmap g<C-n> <Plug>NextOccurrence
nmap <A-n> <Plug>AllWholeOccurrences
xmap <A-n> <Plug>AllWholeOccurrences
nmap g<A-n> <Plug>AllOccurrences
xmap g<A-n> <Plug>AllOccurrences
xmap <C-x> <Plug>SkipOccurrence
xmap <C-p> <Plug>RemoveOccurrence
```

### Custom mappings

To use your own mappings instead of the defaults, set `g:multi_cursor_use_default_mapping` to `0` and bind the
`<Plug>` mappings yourself. For example, to put everything on `<A-…>` keys:

```
let g:multi_cursor_use_default_mapping = 0

" Note that the <A-n> and g<A-n> shortcuts don't work on Mac due to dead keys.
" <A-n> is used to enter accented text e.g. ñ
nmap <A-n> <Plug>NextWholeOccurrence
xmap <A-n> <Plug>NextWholeOccurrence
nmap g<A-n> <Plug>NextOccurrence
xmap g<A-n> <Plug>NextOccurrence
xmap <A-x> <Plug>SkipOccurrence
xmap <A-p> <Plug>RemoveOccurrence
```

</details>

<details>
<summary><h2>NERDTree: Adds NERDTree navigation to VS Code's Explorer</h2></summary>

Original plugin: [NERDTree](https://github.com/preservim/nerdtree).

### Summary:
Adds NERDTree commands and navigation keys to VS Code's Explorer.

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'preservim/nerdtree'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'preservim/nerdtree'</code>
      <br/>
      <code>Plug 'https://github.com/preservim/nerdtree'</code>
      <br/>
      <code>Plug 'nerdtree'</code>
      <br/>
      <code>set NERDTree</code>
      </details>

### Instructions

[See here](NERDTree-support.md).

</details>

<details>
<summary><h2>paragraph-motion: Extends the { and } motions to ignore whitespace on otherwise empty lines</h2></summary>

Original plugin: [vim-paragraph-motion](https://github.com/dbakker/vim-paragraph-motion).

### Summary:
Normally the { and } motions only match completely empty lines.
With this plugin lines that only contain whitespace are also matched.

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'dbakker/vim-paragraph-motion'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'dbakker/vim-paragraph-motion'</code>
      <br/>
      <code>Plug 'https://github.com/dbakker/vim-paragraph-motion'</code>
      <br/>
      <code>Plug 'vim-paragraph-motion'</code>
      <br/>
      <code>Plug 'https://github.com/vim-scripts/Improved-paragraph-motion'</code>
      <br/>
      <code>Plug 'vim-scripts/Improved-paragraph-motion'</code>
      <br/>
      <code>Plug 'Improved-paragraph-motion'</code>
      <br/>
      <code>set vim-paragraph-motion</code>
      </details>

### Instructions

https://github.com/dbakker/vim-paragraph-motion#vim-paragraph-motion

</details>

<details>
<summary><h2>ReplaceWithRegister: Adds two-in-one command that replaces text with the contents of a register.</h2></summary>

By [igrekster](https://github.com/igrekster)
Original plugin: [ReplaceWithRegister](https://github.com/vim-scripts/ReplaceWithRegister).

### Summary:
This plugin offers a two-in-one command that replaces text covered by a
{motion}, entire line(s) or the current selection with the contents of a
register; the old text is deleted into the black-hole register, i.e. it's
gone. (But of course, the command can be easily undone.)

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'vim-scripts/ReplaceWithRegister'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'vim-scripts/ReplaceWithRegister'</code>
      <br/>
      <code>Plug 'ReplaceWithRegister'</code>
      <br/>
      <code>Plug 'https://github.com/inkarkat/vim-ReplaceWithRegister'</code>
      <br/>
      <code>Plug 'inkarkat/vim-ReplaceWithRegister'</code>
      <br/>
      <code>Plug 'vim-ReplaceWithRegister'</code>
      <br/>
      <code>Plug 'https://www.vim.org/scripts/script.php?script_id=2703'</code>
      <br/>
      <code>set ReplaceWithRegister</code>
      </details>

### Instructions

https://github.com/vim-scripts/ReplaceWithRegister/blob/master/doc/ReplaceWithRegister.txt

</details>

<details>
<summary><h2>signature: Shows marks in the gutter</h2></summary>

Original plugin: [vim-signature](https://github.com/kshenoy/vim-signature).

### Summary:
Draws your `a`-`z` marks in the gutter, next to the line each one is on, and keeps them there as marks are set,
moved and deleted.

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'kshenoy/vim-signature'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'kshenoy/vim-signature'</code>
      <br/>
      <code>Plug 'https://github.com/kshenoy/vim-signature'</code>
      <br/>
      <code>Plug 'vim-signature'</code>
      <br/>
      <code>set signature</code>
      </details>

### Instructions

The marks are drawn as Vim signs, in a sign group of their own named `signature`: `:sign place group=signature`
lists them, and a `:sign unplace *` without `group=` leaves them alone. Only the lowercase marks are drawn. The plugin maps no keys.

</details>

<details>
<summary><h2>sneak: Jump to any location specified by two characters</h2></summary>

By [Mikhail Levchenko](https://github.com/Mishkun)
Original plugin: [vim-sneak](https://github.com/justinmk/vim-sneak).

### Summary:
Jump to any location specified by two characters.

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'justinmk/vim-sneak'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'justinmk/vim-sneak'</code>
      <br/>
      <code>Plug 'https://github.com/justinmk/vim-sneak'</code>
      <br/>
      <code>Plug 'vim-sneak'</code>
      <br/>
      <code>set sneak</code>
      </details>

### Instructions

* Type `s` and two chars to start sneaking in forward direction
* Type `S` and two chars to start sneaking in backward direction
* Type `;` or `,` to proceed with sneaking just as if you were using `f` or `t` commands

</details>

<details>
<summary><h2>surround: Adds provides mappings to easily delete, change, and add surroundings in pairs</h2></summary>

Original plugin: [vim-surround](https://github.com/tpope/vim-surround).

### Summary:
Surround.vim is all about "surroundings": parentheses, brackets, quotes, XML tags, and more.
The plugin provides mappings to easily delete, change, and add such surroundings in pairs.

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'tpope/vim-surround'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'tpope/vim-surround'</code>
      <br/>
      <code>Plug 'https://www.vim.org/scripts/script.php?script_id=1697'</code>
      <br/>
      <code>Plug 'vim-surround'</code>
      <br/>
      <code>set surround</code>
      </details>

### Instructions

https://github.com/tpope/vim-surround/blob/master/doc/surround.txt

</details>

<details>
<summary><h2>targets: Adds many seeking text objects for pairs, quotes, separators, arguments and tags</h2></summary>

Original plugin: [targets.vim](https://github.com/wellle/targets.vim).

### Summary:
Targets.vim expands on the built-in text objects (`di(`, `ci"`, …) to give you more targets to
operate on and the ability to reach them without moving the cursor there first. It adds text objects
for pairs (`( ) { } [ ] < >`, plus `b` for "any block"), quotes (`' " \``, plus `q` for "any quote"),
separators (`, . ; : + - = ~ _ * # / | \ & $`), arguments (`a`) and tags (`t`), each available with
four modifiers and the `n`/`l` next/last qualifiers:

- `i` — inside the object (e.g. `ci(`)
- `a` — a whole object, including its delimiters (e.g. `da,` deletes one list item and a separator)
- `I` — inside, excluding surrounding whitespace
- `A` — around, including adjacent whitespace
- `in`/`il` (and `an`, `In`, `Al`, …) — operate on the next / last object without moving there, e.g.
  `cin)` changes inside the next parentheses; a `[count]` skips further (`c2in)`).

When the cursor is not inside an object, the plain commands seek to the nearest one. Quotes are
paired by counting from the start of the line, so `ci"` skips the gap between two strings and changes
the real one. Re-issuing a text object in visual mode grows the selection outward (`vi(i(`).

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'wellle/targets.vim'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'wellle/targets.vim'</code>
      <br/>
      <code>Plug 'https://github.com/wellle/targets.vim'</code>
      <br/>
      <code>Plug 'targets.vim'</code>
      <br/>
      <code>set targets</code>
      </details>

### Instructions

https://github.com/wellle/targets.vim/blob/master/README.md
(see also the [cheat sheet](https://github.com/wellle/targets.vim/blob/master/cheatsheet.md)).

The settings (`g:targets_aiAI`, `g:targets_nl`, `g:targets_seekRanges`, `targets#mappings#extend`, …)
and forced motions (`dVi-`, `d<C-V>i-`) are not currently supported.

</details>

<details>
<summary><h2>textobj-entire: Adds mapping for selecting entire contents of file regardless of cursor position</h2></summary>

By [Alexandre Grison](https://github.com/agrison)
Original plugin: [vim-textobj-entire](https://github.com/kana/vim-textobj-entire).

### Summary:
vim-textobj-entire is a Vim plugin to provide text objects
(ae and ie by default) to select the entire content of a buffer.
Though these are trivial operations (e.g. ggVG), text object versions are more handy,
because you do not have to be conscious of the cursor position (e.g. vae).

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'kana/vim-textobj-entire'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'kana/vim-textobj-entire'</code>
      <br/>
      <code>Plug 'vim-textobj-entire'</code>
      <br/>
      <code>Plug 'https://www.vim.org/scripts/script.php?script_id=2610'</code>
      <br/>
      <code>set textobj-entire</code>
      </details>

### Instructions

https://github.com/kana/vim-textobj-entire/blob/master/doc/textobj-entire.txt

</details>

<details>
<summary><h2>textobj-line: Adds text objects for the current line</h2></summary>

Original plugin: [vim-textobj-line](https://github.com/kana/vim-textobj-line).

### Summary:
Adds `al` and `il`, text objects for the current line: `al` is the whole line, and `il` is the line without its
leading and trailing whitespace. Unlike `V`, they are characterwise and leave out the line break, so `yil` and
`dal` never take the newline with them.

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'kana/vim-textobj-line'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'kana/vim-textobj-line'</code>
      <br/>
      <code>Plug 'https://github.com/kana/vim-textobj-line'</code>
      <br/>
      <code>Plug 'vim-textobj-line'</code>
      <br/>
      <code>set textobj-line</code>
      </details>

### Instructions

https://github.com/kana/vim-textobj-line/blob/master/doc/textobj-line.txt

| Mapping | Description |
|---------|-------------|
| `al`    | The current line, without the line break |
| `il`    | The current line, without leading and trailing whitespace |

Both select nothing on an empty line, and `il` selects nothing on a line of only whitespace, so `dil` there does
nothing. `let g:textobj_line_no_default_key_mappings = 1` before enabling the plugin skips the `al` and `il`
mappings.

</details>

<details>
<summary><h2>textobj-user: Framework for defining your own text objects</h2></summary>

Original plugin: [vim-textobj-user](https://github.com/kana/vim-textobj-user).

### Summary:
A framework that lets you declaratively define your own text objects from your `~/.vimperorrc`, without
writing any plugin code. You describe each object with a Vim pattern and the keys that should select
it, and `textobj-user` creates the mappings for you. For example, define `ad`/`id` to select an
ISO date such as `2013-03-16`:

```
call textobj#user#plugin('datetime', {
\   'date': {
\     'pattern': '\<\d\d\d\d-\d\d-\d\d\>',
\     'select': ['ad', 'id'],
\   },
\ })
```

After this, `dad` deletes the date under (or ahead of) the cursor, `vad` selects it, and so on.

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'kana/vim-textobj-user'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'kana/vim-textobj-user'</code>
      <br/>
      <code>Plug 'https://github.com/kana/vim-textobj-user'</code>
      <br/>
      <code>Plug 'vim-textobj-user'</code>
      <br/>
      <code>set textobj-user</code>
      </details>

### Instructions

https://github.com/kana/vim-textobj-user/blob/master/doc/textobj-user.txt

Define text objects by calling `textobj#user#plugin({name}, {specs})`, where `{specs}` maps an object
name to a dictionary of properties. The following properties are supported:

| Property | Description |
|----------|-------------|
| `pattern` | The Vim regexp for the object. A single string selects the whole match; a `[head, tail]` pair selects the region between the two delimiter patterns. |
| `select` | Key(s) that select a single-`pattern` object (string or list of strings). |
| `select-a` | Key(s) that select "a" region of a `[head, tail]` pair — including the delimiters. |
| `select-i` | Key(s) that select the "inner" region of a `[head, tail]` pair — excluding the delimiters. |
| `region-type` | Selection type: `'v'` (charwise, the default), `'V'` (linewise) or `"\<C-v>"` (blockwise). |
| `move-n` / `move-p` | Key(s) that move the cursor to the **beginning** of the next / previous object. |
| `move-N` / `move-P` | Key(s) that move the cursor to the **end** of the next / previous object. |

Selecting objects works with operators (`d`, `c`, `y`) and in visual mode; the `move-*` mappings also
work in normal, visual and operator-pending modes.

A `[head, tail]` pair example — `aA` selects `<<…>>` including the markers, `iA` only the text between
them:

```
call textobj#user#plugin('braces', {
\   'angle': {
\     'pattern': ['<<', '>>'],
\     'select-a': 'aA',
\     'select-i': 'iA',
\   },
\ })
```

Each operation also gets a remappable `<Plug>` interface mapping named
`<Plug>(textobj-{plugin}-{object}-{operation})`, where `{operation}` is the mapping-property suffix
(`a`, `i`, `n`, `p`, `N`, `P`, or omitted for a plain `select`). For the datetime example above that
is `<Plug>(textobj-datetime-date)`, so you can choose your own keys:

```
omap gd <Plug>(textobj-datetime-date)
xmap gd <Plug>(textobj-datetime-date)
```

`textobj#user#map({name}, {specs})` binds additional keys to an already-defined plugin's `<Plug>`
names (reading only the `select` / `select-a` / `select-i` / `move-*` properties):

```
call textobj#user#map('datetime', {'date': {'select': 'gd'}})
```

The following features of the original plugin are **not** currently supported:
- `select-function`, `select-a-function`, `select-i-function` and the `move-*-function` variants
  (custom objects computed by a Vim function). This also means `[count]` support, which the original
  plugin only offers through such functions, is unavailable.
- The `scan` property (configurable search direction).
- Blockwise `region-type` (`"\<C-v>"`) only takes effect in visual mode; an operator (e.g. `dad`)
  over a blockwise object acts charwise.

</details>

<details>
<summary><h2>visual-star-search: Search for the selected text with * and #</h2></summary>

Original plugin: [vim-visual-star-search](https://github.com/bronson/vim-visual-star-search).

### Summary:
In visual mode, `*` searches forward and `#` searches backward for the selected text, rather than for the word
under the cursor.

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'bronson/vim-visual-star-search'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'bronson/vim-visual-star-search'</code>
      <br/>
      <code>Plug 'https://github.com/bronson/vim-visual-star-search'</code>
      <br/>
      <code>Plug 'vim-visual-star-search'</code>
      <br/>
      <code>set visual-star-search</code>
      </details>

### Instructions

| Mapping | Description |
|---------|-------------|
| `*`     | In visual mode, search forward for the selected text |
| `#`     | In visual mode, search backward for the selected text |

To use other keys, map `<Plug>VisualStarSearch` and `<Plug>VisualHashSearch`.

</details>

<details>
<summary><h2>yankring: Keeps a history of yanks, deletes and changes to paste from</h2></summary>

Original plugin: [YankRing.vim](https://www.vim.org/scripts/script.php?script_id=1234).

### Summary:
Vim keeps a history of deletes in `"1`-`"9`, but a yank always overwrites the last one. The yank ring
adds the missing history - the Emacs kill ring - over yanks, deletes and changes alike. Paste with
`p`, then press `<C-P>` to swap what you just pasted for the entry before it, and keep pressing to
walk further back. `<C-N>` walks forward again, and a count like `2<C-P>` skips.

### Setup:
- Add the following command to `~/.vimperorrc`: `Plug 'vim-scripts/YankRing.vim'`
    <details>
      <summary>Alternative syntax</summary>
      <code>Plugin 'vim-scripts/YankRing.vim'</code>
      <br/>
      <code>Plug 'https://github.com/vim-scripts/YankRing.vim'</code>
      <br/>
      <code>Plug 'YankRing'</code>
      <br/>
      <code>set yankring</code>
      </details>

### Instructions

Cycling replaces the text of the last paste, so it works while the caret is still on the line you
pasted onto and you have not edited the buffer since; otherwise it tells you to paste first. `p`,
`P`, `gp`, `gP`, `]p`, `[p`, `]P` and `[P` are all tracked, in normal and visual mode, counts
included. One `u` undoes the paste and the cycling together.

`:YRShow` lists the ring newest first, `:YRClear` empties it, and `:YRReplace {offset} {p|P}` is what
the keys call - a negative offset steps to an older entry, a positive one to a newer one.

`let g:yankring_max_history = 100` caps how many entries are kept.

Note that `<C-P>` and `<C-N>` stop being `k` and `j` in normal mode, as with the original plugin.
Visual, operator-pending, insert and command-line mode are untouched. To keep the keys, map your own
in `~/.vimperorrc`, which suppresses the defaults:

```
nmap <A-p> <Plug>YankRingReplacePrevious
nmap <A-n> <Plug>YankRingReplaceNext
```

Not implemented yet: the ring window (`:YRShow` prints the listing instead), the other `:YR*`
commands, every `g:yankring_*` option but the one above, persistence across restarts, and clipboard
monitoring. Behaviour with multiple carets is undefined.

https://github.com/vim-scripts/YankRing.vim/blob/master/doc/yankring.txt

</details>

<details>
<summary><h2>youcompleteme: Cycle through code completion with Tab</h2></summary>

Inspired by [YouCompleteMe](https://github.com/ycm-core/YouCompleteMe) / [SuperTab](https://github.com/ervandew/supertab).

### Summary:
Makes `<Tab>` cycle through VS Code's suggestion list, SuperTab style. While the suggestion list is open, `<Tab>`
selects the next item and `<S-Tab>` the previous one, instead of accepting the suggestion. When no list is open,
`<Tab>` keeps its normal behaviour, so ordinary tabbing is unaffected.

### Setup:
- Add the following command to `~/.vimperorrc`: `set youcompleteme`

### Instructions

| Mapping | Description |
|---------|-------------|
| `<Tab>`   | Select the next item in the suggestion list (otherwise Vim's own `<Tab>`) |
| `<S-Tab>` | Select the previous item in the suggestion list |

Both keys are VS Code keybindings that apply only while the suggestion list is open and the plugin is enabled.
`<Enter>` still accepts the selected suggestion.

</details>

## Not available

Two plugins from the same family are not part of Vimperor:

- **matchit.vim.** Jumping with `%` between `if` and `endif`, or between HTML tags, needs to know what a token is,
  and all VS Code tells an extension about a file's structure is where its symbols are. `%` still jumps between the
  pairs in `'matchpairs'`.
- **VimEverywhere.** Its point is to label every clickable thing in the window and click it from the keyboard. An
  extension can draw over editor text, but not over VS Code's workbench — the activity bar, the tabs, the sidebar —
  and cannot enumerate what is on it.

List of Supported Options
=========================

The following options can be set with the `:set`, `:setglobal` and `:setlocal` commands.
They can be added to the `~/.vimperorrc` file, or set manually in Command-line mode.
For more details of each option, please see the Vim documentation.
Every effort is made to make these options compatible with Vim behaviour.
However, some differences are inevitable.

```
'clipboard'     'cb'    Defines clipboard behavior
        A comma-separated list of words to control clipboard behaviour:
           unnamed      The clipboard register '*' is used instead of the
                        unnamed register
           unnamedplus  The clipboard register '+' is used instead of the
                        unnamed register

'cmdheight'     'ch'    Number of screen lines to use for the command-line.
                        Accepted, and has no effect: Vimperor's command-line
                        is one line in the status bar.
'digraph'       'dg'    Enable using <BS> to enter digraphs in Insert mode
'gdefault'      'gd'    The ":substitute" flag 'g' is by default
'history'       'hi'    Number of command-lines that are remembered
'hlsearch'      'hls'   Highlight matches with the last search pattern
'ignorecase'    'ic'    Ignore case in search patterns
'incsearch'     'is'    Show where search pattern typed so far matches. Also
                        previews the pattern of :s, :g and :v
'isfname'       'isf'   Characters included in file names, for gf and
                        <C-R><C-F>
'iskeyword'     'isk'   Defines keywords for commands like 'w', '*', etc.
'keymodel'      'km'    Controls selection behaviour with special keys
        List of comma separated words, which enable special things that keys
        can do. These values can be used:
           startsel     Using a shifted special key starts selection (either
                        Select mode or Visual mode, depending on "key" being
                        present in 'selectmode')
           stopsel      Using a NOT-shifted special key stops selection.
                        Automatically enables `stopselect` and `stopvisual`
           stopselect   Using a NOT-shifted special key stops select mode
                        and removes selection - not in Vim
           stopvisual   Using a NOT-shifted special key stops visual mode
                        and removes selection - not in Vim
           continueselect   Using a shifted arrow key doesn't start selection,
                        but in select mode acts like startsel is enabled
                        - not in Vim
           continuevisual   Using a shifted arrow key doesn't start selection,
                        but in visual mode acts like startsel is enabled
                        - not in Vim
                                
        Special keys in this context are the cursor keys, <End>, <Home>,
        <PageUp> and <PageDown>.

'keyboardlayout' 'kbl' Keyboard layouts whose keys work as Vim commands
        A comma-separated list of: russian, ukrainian, belarusian.
        A key typed in one of these layouts in Normal or Visual mode, or as
        the name of a register or mark, is read as the Latin key in the same
        place, so both `ciw` and `сшц` change a word. Letters and the
        punctuation on letter keys are mapped; characters that exist on a
        US keyboard are never mapped, so `.`, `:` and `/` keep working in the
        Latin layout. A whole command-line typed in the wrong layout, with no
        Latin letter in it, is corrected when the corrected line is a command
        and the typed one is not: `:ыуе тщцкфз` runs `:set nowrap`.
        'langmap' is consulted first.

'langmap'       'lmap'  Enter Vim commands from a different language keyboard.
        List of character pairs that map from a user's entered language to
        plain ASCII. Used to enter standard ASCII-based Vim commands from the
        user's preferred language keyboard. When in Normal or Visual mode, or
        when entering the name of a register or mark, the typed key is mapped
        by 'langmap'. When typing in Insert or Replace mode, or entering a
        character to search in `f{char}`, the character is accepted as typed.
        See the Vim docs for more infomration and an example for entering Vim
        commands from a Greek keyboard. Dvorak examples are available 
        elsewhere.

'langnoremap'   'lnr'   See 'langremap'
'langremap'     'lrm'   Apply 'langmap' to characters resulting from a mapping.
'matchpairs'    'mps'   Pairs of characters that "%" can match
'maxmapdepth'   'mmd'   Maximum depth of mappings
'messagesopt'   'mopt'  Option settings for outputting messages.
                        Accepted, and has no effect: long messages and
                        listings such as :registers are written to the
                        Vimperor output channel, which never waits for a key,
                        so there is no hit-enter prompt to configure.
'more'          'more'  When on, listings pause when the whole screen is filled.
                        Accepted, and has no effect, for the same reason
'nrformats'     'nf'    Number formats recognized for CTRL-A command
'operatorfunc'  'opfunc'    Name of a function to call with the g@ operator
'scroll'        'scr'   Number of lines to scroll with CTRL-U and CTRL-D
'scrolloff'     'so'    Minimal number of lines above and below the cursor
'selection'     'sel'   What type of selection to use
'selectmode'    'slm'   Controls when to start Select mode instead of Visual
        This is a comma-separated list of words:
                        
           mouse        When using the mouse
           key          When using shifted special[1] keys
           cmd          When using "v", "V", or <C-V>

'shell'         'sh'    The shell to use to execute commands with ! and :!
'shellcmdflag'  'shcf'  The command flag passed to the shell
'shellxescape'  'sxe'   The characters to be escaped when calling a shell
'shellxquote'   'sxq'   The quote character to use in a shell command
'showcmd'       'sc'    Show (partial) command
'showmode'      'smd'   Show the current mode
'smartcase'     'scs'   Use case sensitive search if any character in the
                        pattern is uppercase
'startofline'   'sol'   When on, some commands move the cursor to the first
                        non-blank of the line
                        When off, the cursor is kept in the same column
                        (if possible)
'timeout'       'to'    Use timeout for mapped key sequences
'timeoutlen'    'tm'    Timeout duration for a mapped key sequence
'viminfo'       'vi'    Information to remember after restart
'virtualedit'   've'    Placement of the cursor where there is no actual text
        A comma-separated list of these words:
           block        Allow virtual editing in Visual mode (not supported)
           insert       Allow virtual editing in Insert mode (not supported)
           all          Allow virtual editing in all modes (not supported)
           onemore      Allow the cursor to move just past the end of the line

'visualbell'    'vb'    When on, prevents beeping on error
'whichwrap'     'ww'    Which keys that move the cursor left/right can wrap to
                        other lines
        A comma-separated list of these flags:
           char key     modes
           b    <BS>    Normal and Visual
           s    <Space> Normal and Visual
           h    "h"     Normal and Visual
           l    "l"     Normal and Visual
           <    <Left>  Normal and Visual
           >    <Right> Normal and Visual
           ~    "~"     Normal
           [    <Left>  Insert and Replace
           ]    <Right> Insert and Replace

'wrapscan'      'ws'    Search will wrap around the end of file
```

## Options applied to VS Code

Some Vim features are drawn by VS Code rather than by Vimperor, such as line numbers, soft-wrap and indentation.
The following options change the equivalent VS Code editor state or setting.

```
'number'            'nu'    Show line numbers
'relativenumber'    'rnu'   Show line numbers relative to the line with the
                            cursor. Together with 'number' this sets the
                            editor's line numbers to on, relative or off
'wrap'              'wrap'  Enable soft-wraps. Writes VS Code's
                            "editor.wordWrap" setting for the file's language,
                            in the workspace folder's settings, or in your user
                            settings outside a folder, so the change persists.
                            Starts at what VS Code is already doing rather than
                            at Vim's default.
                            View | Toggle Word Wrap (Alt+Z) gives an editor a
                            wrap of its own, which no extension can read or
                            clear. In that editor the option has no visible
                            effect until Alt+Z is pressed again
'expandtab'         'et'    Use spaces when indenting
'tabstop'           'ts'    Number of columns a tab character is drawn as
'shiftwidth'        'sw'    Number of columns >> and << shift by. 0 (the
                            default) means the value of 'tabstop'
                            These three start at the indentation VS Code chose
                            for the file, and are written to the editor only
                            when they are set
'softtabstop'       'sts'   Number of columns a <Tab> typed in Insert mode
                            covers. 0 (the default) means the value of
                            'tabstop'. Read by Vimperor's own <Tab>, and not
                            written to the editor
'filetype'          'ft'    The language mode of the buffer
'syntax'            'syn'   The same as 'filetype': both set VS Code's language
                            mode
```

## Options accepted without effect

A Vim config often sets options for things VS Code decides for itself: how text is drawn, how files are written, how windows are laid out.
Vimperor accepts these options so the config loads instead of stopping at `E518: Unknown option`.
Setting one changes nothing, and `:set {option}?` answers with the value that was set rather than with what VS Code is doing.
Among them:

```
'background'        'bg'
'backup'            'bk'
'bomb'              'bomb'
'breakindent'       'bri'
'colorcolumn'       'cc'
'cursorcolumn'      'cuc'
'cursorline'        'cul'
'encoding'          'enc'
'fileencoding'      'fenc'
'fileformat'        'ff'
'foldmethod'        'fdm'
'guicursor'         'gcr'   The cursor's shape follows the mode instead: a
                            block in Normal mode, a line in Insert mode and an
                            underline in Replace mode
'hidden'            'hid'
'laststatus'        'ls'
'linebreak'         'lbr'
'list'              'list'
'ruler'             'ru'
'scrolljump'        'sj'
'sidescroll'        'ss'
'sidescrolloff'     'siso'
'signcolumn'        'scl'
'spell'             'spell'
'swapfile'          'swf'
'termguicolors'     'tgc'
'undofile'          'udf'
```

## Vimperor only options

These options are not supported by Vim.
They control integration with the editor Vimperor runs in.
Unless otherwise stated, these options do not have abbreviations.

```
'ide'                   string  (default: the editor's name)
                        global
        The name of the editor Vimperor is running in, such as "Visual Studio
        Code" or "Cursor". A config shared between editors can branch on it:
           if &ide =~? 'cursor'

'ideastatusicon'        string  (default "enabled")
                        global
        This option controls the mode indicator in the status bar:
           enabled      Show the indicator
           gray         Show the indicator in the theme's muted colour
           disabled     Hide the indicator

'ideawrite'             string  (default "file")
                        global
        This option defines the behaviour of the :w command:
           file         Save the current file only
           all          The :w command works like :wa and saves every open
                        file, which also runs whatever is set up to happen on
                        save, such as formatting, for each of them
```

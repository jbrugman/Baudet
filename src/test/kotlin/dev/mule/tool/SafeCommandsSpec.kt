package dev.mule.tool

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The safe-command gate lets the user opt out of approving harmless reads, so the cost of a wrong safe
 * verdict is a command run without a human. These cases pin the conservative read: only a bare known
 * read-only program, carrying none of its own write or exec flags, passes. A program named by path is
 * refused so a planted binary cannot borrow a trusted name, and a nominally safe program that is handed
 * a write or exec flag falls back to approval. Anything that chains, redirects or substitutes also falls
 * back. git is judged per subcommand, so looking passes and writing does not, even though both spell the
 * same program.
 */
class SafeCommandsSpec : BehaviorSpec({

    given("single known read-only commands") {
        val safe = listOf(
            "ls", "pwd", "cat notes.txt", "echo hello world", "head -n 5 log.txt",
            "grep pattern file", "find . -name somefile", "du -sh", "wc -l file",
            "sort -n file", "rg pattern file", "date"
        )
        `when`("classified") {
            then("each is treated as safe") {
                safe.forEach { SafeCommands.isSafe(it) shouldBe true }
            }
        }
    }

    given("commands that chain, redirect, substitute or are not read-only") {
        val unsafe = listOf(
            "ls && rm -rf /", "echo hi > file", "cat f; rm f", "ls | grep x",
            "\$(whoami)", "cat f1 f2 || echo fail", "echo `date`", "find . -delete",
            "find . -exec rm {} ;", "sudo ls", "rm -rf /", "./deploy.sh", "mvn -B test"
        )
        `when`("classified") {
            then("none is treated as safe") {
                unsafe.forEach { SafeCommands.isSafe(it) shouldBe false }
            }
        }
    }

    given("a read-only program named by path") {
        // The program is matched on its bare name only. Trusting a basename after a slash let a planted
        // binary in a writable folder be auto-run as if it were the safe command of that name.
        val unsafe = listOf("/bin/ls", "/usr/bin/ls", "./ls", "/tmp/evil/ls", "/tmp/mule-run-123/grep")
        `when`("classified") {
            then("none is safe, because only the bare name is recognised") {
                unsafe.forEach { SafeCommands.isSafe(it) shouldBe false }
            }
        }
    }

    given("a nominally read-only program handed one of its write or exec flags") {
        // sort --output writes a file, rg --pre runs a filter program, find -fprint writes a file, and
        // date --set moves the clock: a name on the read-only list is not safe once such a flag rides it.
        val unsafe = listOf(
            "sort -o out.txt in.txt", "sort --output out.txt in.txt", "sort -oout.txt in.txt",
            "rg --pre /bin/sh file", "rg --pre-glob x --pre prog file",
            "find . -fprint out.txt", "find . -fprint0 out", "find . -fls out", "find . -fprintf out %p",
            "date -s 2020-01-01", "date --set 2020-01-01"
        )
        `when`("classified") {
            then("none is safe, because that flag makes it a write or an exec") {
                unsafe.forEach { SafeCommands.isSafe(it) shouldBe false }
            }
        }
    }

    given("a write hiding behind bundling, quoting or a positional operand") {
        // The shell parses the command before the program sees it, so a deny flag can be smuggled in
        // several ways the old naive token check missed: bundled into a short cluster (sort -no sets
        // -o), wrapped in quotes (find '-delete' still reaches find bare), or skipped entirely when the
        // program writes through a positional operand rather than a flag (uniq with two files writes the
        // second). Each is a write, so each must fall back to approval, while an ordinary read of the
        // same program stays safe -- a long option must never be caught on a letter it shares with a
        // deny flag, which is why --numeric and -R pass.
        val unsafe = listOf(
            "uniq input.txt output.txt", "uniq -c in.txt out.txt",
            "sort -no output.txt input.txt", "sort -n -o output.txt input.txt",
            "find . '-delete'", "find . \"-delete\"", "find . '-fprint' out"
        )
        val safe = listOf(
            "uniq input.txt", "uniq -c notes.txt",
            "sort -rn file", "sort -nu file", "sort --numeric file",
            "find . -name f", "date -R"
        )
        `when`("classified as unsafe") {
            then("each write is refused despite the bundling, quote or positional trick") {
                unsafe.forEach { SafeCommands.isSafe(it) shouldBe false }
            }
        }
        `when`("classified as safe") {
            then("the equivalent read still passes, so the fix does not nag") {
                safe.forEach { SafeCommands.isSafe(it) shouldBe true }
            }
        }
    }

    given("a write evading the gate through quoting or an end-of-options marker") {
        // Two more ways the shell hands a nominally read-only command a write that the raw token
        // check missed: git given its output or ext-diff option wrapped in quotes still reaches git
        // bare, and uniq past a lone double dash reads a dash-prefixed name as a file, so its second
        // file becomes an output. Each must fall back to approval, while the plain read of the same
        // shape -- a bare git log, a single uniq file -- stays safe, since a gate that nagged on every
        // read is a gate the user turns off.
        val unsafe = listOf(
            "git log '--output=/tmp/out.txt'", "git log \"--output=/tmp/out.txt\"",
            "git show '--ext-diff'", "uniq -- input.txt -output.txt", "uniq -- a.txt b.txt"
        )
        val safe = listOf("git log --oneline", "git show 'HEAD'", "uniq -- notes.txt")
        `when`("a write is smuggled through a quote or a double dash") {
            then("the gate falls back to approval") {
                unsafe.forEach { SafeCommands.isSafe(it) shouldBe false }
            }
        }
        `when`("the equivalent plain read is asked about") {
            then("it stays safe, so the fix does not nag") {
                safe.forEach { SafeCommands.isSafe(it) shouldBe true }
            }
        }
    }

    given("git asked to only look") {
        val safe = listOf(
            "git status", "git log", "git diff", "git diff --stat", "git show HEAD",
            "git blame src/main/kotlin/App.kt", "git ls-files", "git rev-parse --short HEAD"
        )
        `when`("classified") {
            then("inspection runs without a prompt, which is what the opt-in promises") {
                safe.forEach { SafeCommands.isSafe(it) shouldBe true }
            }
        }
    }

    given("git asked to do more than look") {
        val unsafe = listOf(
            // Writes, network and history moves: the reason git is admitted by subcommand.
            "git", "git commit -m done", "git push", "git add .", "git checkout main",
            "git restore file", "git clean -fd", "git stash", "git fetch", "git config x y",
            "git branch -D stale", "git reset --hard",
            // Flags that keep a read-only subcommand from being read-only: a file written, a
            // program run, or a different repository addressed.
            "git log --output=/tmp/out", "git diff --ext-diff", "git log --paginate",
            "git -c core.pager=rm log", "git --git-dir /elsewhere status",
            "git --work-tree /elsewhere status"
        )
        `when`("classified") {
            then("none of it is treated as safe") {
                unsafe.forEach { SafeCommands.isSafe(it) shouldBe false }
            }
        }
    }

    given("a read-only command that quotes its argument") {
        // The gate once vetoed any quote, which nagged on ordinary quoted reads. A quote is now
        // allowed, but only over a known read-only program and never over a hidden metacharacter.
        val safe = listOf(
            "grep \"pattern\" file", "grep -n 'todo' notes.txt", "cat \"notes with space.txt\""
        )
        `when`("classified") {
            then("the quoted read passes, which is the nag this relaxation removes") {
                safe.forEach { SafeCommands.isSafe(it) shouldBe true }
            }
        }
        `when`("a quote hides a forbidden metacharacter") {
            then("it is still refused, because the check reads the raw string") {
                SafeCommands.isSafe("grep 'a; rm -rf /' f") shouldBe false
                SafeCommands.isSafe("grep \"a | b\" f") shouldBe false
                SafeCommands.isSafe("grep '$(whoami)' f") shouldBe false
            }
        }
    }

    given("an empty or blank command") {
        `when`("classified") {
            then("it is not safe") {
                SafeCommands.isSafe("") shouldBe false
                SafeCommands.isSafe("   ") shouldBe false
            }
        }
    }
})

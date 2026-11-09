package dev.baudet.diff

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class DiffHighlightSpec : BehaviorSpec({

    given("a unified-diff line") {
        `when`("it marks an added line") {
            then("it is Added") {
                DiffHighlight.classify("+added") shouldBe DiffLineKind.Added
            }
        }
        `when`("it marks a removed line") {
            then("it is Removed") {
                DiffHighlight.classify("-removed") shouldBe DiffLineKind.Removed
            }
        }
        `when`("it is an added-file header") {
            then("it is a FileHeader") {
                DiffHighlight.classify("+++ b/file.txt") shouldBe DiffLineKind.FileHeader
            }
        }
        `when`("it is an original-file header") {
            then("it is a FileHeader") {
                DiffHighlight.classify("--- a/file.txt") shouldBe DiffLineKind.FileHeader
            }
        }
        `when`("it is a hunk range") {
            then("it is a HunkHeader") {
                DiffHighlight.classify("@@ -1 +1 @@") shouldBe DiffLineKind.HunkHeader
            }
        }
        `when`("it is git metadata") {
            then("the file rename header is Meta") {
                DiffHighlight.classify("diff --git a/x b/x") shouldBe DiffLineKind.Meta
            }
            then("the blob index is Meta") {
                DiffHighlight.classify("index 1a2b3c4..5d6e7f8 100644") shouldBe DiffLineKind.Meta
            }
            then("the no-newline marker is Meta") {
                DiffHighlight.classify("\\ No newline at end of file") shouldBe DiffLineKind.Meta
            }
            then("a rename line is Meta") {
                DiffHighlight.classify("rename from old.txt") shouldBe DiffLineKind.Meta
            }
        }
        `when`("it is plain content") {
            then("a context line is Context") {
                DiffHighlight.classify(" unchanged") shouldBe DiffLineKind.Context
            }
            then("a plain sentence is Context") {
                DiffHighlight.classify("New untracked file") shouldBe DiffLineKind.Context
            }
            then("an empty line is Context") {
                DiffHighlight.classify("") shouldBe DiffLineKind.Context
            }
        }
        `when`("a removed line begins with the header signs but has no trailing space") {
            then("it stays Removed rather than a FileHeader") {
                DiffHighlight.classify("---") shouldBe DiffLineKind.Removed
            }
        }
    }
})

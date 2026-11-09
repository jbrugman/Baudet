package dev.baudet.api

/**
 * The context window a model is known to have, so a turn can be guarded against the size the
 * backend actually refuses instead of against a number someone typed into Settings.
 *
 * Every entry is deliberately pessimistic, and the direction is not arbitrary: a value that is
 * too low only folds a conversation a little early, while a value that is too high lets a request
 * through that the backend is certain to reject -- the very failure this table exists to prevent.
 * Where a family ships both a plain and a long-context variant, only the id that says so is
 * admitted at the larger size.
 *
 * The table is a floor under the guess in [dev.baudet.config.AppConfig.contextBudgetTokens], never
 * a replacement for it: a budget set below the window still wins, because that is the user
 * keeping a conversation cheap on purpose.
 */
object ModelWindows {

    /** Id fragments, most specific first: the first fragment contained in the id decides. */
    private val KNOWN = listOf(
        // OpenAI: the 4.1 line really is that large, the plain gpt-4 id is not.
        "gpt-4.1" to 1_047_576,
        "gpt-4o" to 128_000,
        "gpt-4-turbo" to 128_072,
        "gpt-oss" to 131_072,
        "gpt-4" to 8_192,
        "gpt-3.5" to 16_385,
        // Llama: only 3.1 and up widened the window; the earlier ids stayed small.
        "llama-3.1" to 131_072,
        "llama-3.2" to 131_072,
        "llama-3.3" to 131_072,
        "llama-3" to 8_192,
        "llama-2" to 4_096,
        "codellama" to 16_384,
        "llama" to 4_096,
        // Qwen: many builds run with YaRN applied, which is not visible in the id.
        "qwen" to 32_768,
        // Mistral family: nemo is the documented exception at 128k.
        "mistral-nemo" to 128_000,
        "mistral" to 32_768,
        "mixtral" to 32_768,
        "command-r" to 128_000,
        "gemma-3" to 32_768,
        "gemma" to 8_192,
        // Small local models: several run at 4k in practice regardless of their papers.
        "phi" to 4_096,
        "deepseek" to 32_768,
        "glm-4" to 32_768,
        "yi-" to 32_768
    )

    /** The window for [model], or null when this id is not in the table. */
    fun known(model: String): Int? {
        val id = model.lowercase()
        return KNOWN.firstOrNull { id.contains(it.first) }?.second
    }
}

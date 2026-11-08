package dev.mule.i18n

/** Languages the UI offers. English is the default. */
enum class Language { EN, NL }

/**
 * Every user-visible string, one entry per message, both languages side by side.
 *
 * An enum table rather than a ResourceBundle, for two reasons that matter here:
 * a missing translation is a compile error instead of a runtime surprise, and
 * there is nothing to register by reflection or to pack as a resource, which
 * keeps the later native-image build free of resource-path guesswork.
 *
 * Placeholders are positional: {0}, {1}, ... Substitute through [Texts.fmt].
 *
 * Scope: UI-facing text only. Anything the model reads, such as the system
 * prompt, the compaction prompt and the summary labels folded into a prompt, is
 * deliberately NOT here and stays English regardless of the UI language.
 */
enum class Key(val en: String, val nl: String) {

    // ---- shared ------------------------------------------------------------
    sendButton("Send  (ctrl+enter)", "Verzenden  (ctrl+enter)"),
    newSessionButton("New session", "Nieuwe sessie"),
    compactButton("Compact", "Compacten"),
    none("none", "geen"),
    failedPrefix("FAILED: {0}", "FAALDE: {0}"),
    errorPrefix("ERROR: {0}", "FOUT: {0}"),
    tabChat("Chat", "Chat"),
    tabModels("Models", "Modellen"),
    tabSettings("Settings", "Instellingen"),
    languageLabel("language", "taal"),
    keyPresent("present (from UI or environment)", "aanwezig (uit UI of omgevingsvariabele)"),
    keyMissing("MISSING", "ONTBREEKT"),
    httpFromBackend("HTTP {0} from backend: {1}", "HTTP {0} van de backend: {1}"),

    // ---- chat --------------------------------------------------------------
    summaryBanner("- active summary of {0} characters -", "- samenvatting van {0} tekens actief -"),
    messagesCount("{0} messages", "{0} berichten"),
    serverSession("server session {0}", "server-sessie {0}"),
    clientReplay("client-side replay", "client-side replay"),
    pickModelFirst(
        "pick a model first, under Models or in Settings",
        "kies eerst een model bij Modellen of in Instellingen"
    ),
    streaming("streaming...", "streamen..."),
    lastEvent("last={0}", "eind={0}"),
    truncated("TRUNCATED ({0})", "AFGEBROKEN ({0})"),
    truncatedUnknown("TRUNCATED (no reason reported)", "AFGEBROKEN (reden niet gemeld)"),
    turnFailed("turn failed", "beurt mislukt"),
    newSessionStatus("new session {0}", "nieuwe sessie {0}"),
    tooFewTurns("too few turns to compact", "te weinig beurten om te compacten"),
    summarising("summarising...", "samenvatten..."),
    compactionEmpty("compaction returned nothing", "compaction gaf niets terug"),
    compactionDone("compacted into a summary of {0} characters", "compact naar {0} tekens samenvatting"),
    compactionFailed("compaction failed: {0}", "compaction mislukt: {0}"),
    youLabel("you: ", "jij: "),
    muleLabel("mule: ", "mule: "),
    reasoningLabel("reasoning:", "redenering:"),
    answerLabel("answer:", "antwoord:"),
    compactRecommended("  COMPACT RECOMMENDED", "  COMPACT AANBEVOLEN"),

    // ---- models ------------------------------------------------------------
    fetchModels("Fetch (GET /models)", "Ophalen (GET /models)"),
    useThisModel("Use this model", "Gebruik dit model"),
    fetching("fetching...", "ophalen..."),
    modelsListed("{0} models via {1}", "{0} modellen via {1}"),
    pickFirst("select a model first", "eerst een model kiezen"),
    modelSaved("model '{0}' adopted and saved", "model '{0}' overgenomen en opgeslagen"),

    // ---- settings ----------------------------------------------------------
    baseUrlLabel("base url", "basis-url"),
    apiKeyLabel("api-key", "api-key"),
    orEnvVar("or env-var", "of env-var"),
    modelLabel("model", "model"),
    maxTokensLabel("max output tokens", "max output tokens"),
    contextBudgetLabel("context budget (tokens)", "context-budget (tokens)"),
    temperatureLabel("temperature", "temperature"),
    topPLabel("top_p (blank = do not send)", "top_p (leeg = niet sturen)"),
    reasoningEffortLabel("reasoning-effort", "reasoning-effort"),
    systemPromptLabel("system prompt (blank = built-in)", "system-prompt (leeg = ingebouwd)"),
    useResponsesApi("Responses API (not chat/completions)", "Responses-API (geen chat/completions)"),
    storeResponses("store=true  (backend keeps the response)", "store=true  (server bewaart de reactie)"),
    sendPreviousResponseId("send previous_response_id", "previous_response_id meesturen"),
    saveButton("Save", "Opslaan"),
    testButton("Test connection + capabilities", "Test verbinding + capabilities"),
    reloadButton("Revert to saved", "Herstel opgeslagen"),
    tipSave("Save these settings to the local store. The language switch is applied here too.",
        "Sla deze instellingen op in de lokale opslag. De taalkeuze wordt hier ook toegepast."),
    tipTest("Saves, then contacts the backend and runs the capability probe.",
        "Slaat op, neemt dan contact op met de backend en draait de capability-probe."),
    tipReload("Discard unsaved edits and refill the form from the last saved settings.",
        "Verwerp niet-opgeslagen wijzigingen en vul het formulier opnieuw vanuit de laatst opgeslagen instellingen."),
    tipLanguage("Interface language; applied when you press Save.",
        "Taal van de interface; toegepast zodra u Opslaan drukt."),
    diagnostics("diagnostics", "diagnostiek"),
    loadedFromDisk("loaded from {0}", "laden vanaf {0}"),
    errMaxTokens("max output tokens is not a positive number", "max output tokens is geen positief getal"),
    errContextBudget("context budget is not a positive number", "context-budget is geen positief getal"),
    errTemperature("temperature must be between 0 and 2", "temperature moet tussen 0 en 2"),
    errTopP("top_p must be between 0 (exclusive) and 1, or blank", "top_p moet tussen 0 (excl) en 1, of leeg"),
    errBaseUrl("base url must start with http:// or https://", "basis-url moet met http:// of https:// beginnen"),
    savedToDisk("saved, model='{0}'", "opgeslagen, model='{0}'"),
    testingProbe(
        "testing (two mini-calls, a few seconds of GPU time)...",
        "bezig met testen (twee mini-calls, een paar seconden GPU-tijd)..."
    ),
    diagnosticsDone("diagnostics done", "diagnostiek klaar"),
    testFailed("test failed: {0}", "test mislukt: {0}"),

    // ---- capability probe --------------------------------------------------
    probeAvailable("{0} available", "{0} beschikbaar"),
    probeModelNotListed("model '{0}' is not in the list", "model '{0}' staat niet in de lijst"),
    probeTruncationNone("no, completed cleanly", "nee, netjes voltooid"),
    probeNoResponseId(
        "NO response_id -> server-side sessions are not usable here",
        "GEEN response_id -> server-side sessies zijn hier niet bruikbaar"
    ),
    probeReread("GET /responses/{id} -> HTTP {0}", "GET /responses/{id} -> HTTP {0}"),
    probeHonored(
        "previous_response_id IS HONORED (prompt grew {0} -> {1})",
        "previous_response_id WORDT GEHONOREERD (prompt groeide {0} -> {1})"
    ),
    probeIgnored(
        "previous_response_id is IGNORED (prompt {0} -> {1}) -> enable 'client-side replay' or you lose context",
        "previous_response_id wordt GENEGEERD (prompt {0} -> {1}) -> zet 'client-side replay' aan, anders ben je context kwijt"
    ),
    probeFail("FAIL: {0}", "FAAL: {0}"),

    // ---- startup smoke run -------------------------------------------------
    smokeDataDir("mule smoke · data-dir={0}", "mule smoke · data-dir={0}"),
    smokeModelSet("model     = set to '{0}' and saved", "model     = gezet op '{0}' en opgeslagen"),
    smokeKeyLabel("key       = ", "sleutel   = "),
    smokeSession("session   = {0} with {1} messages", "sessie    = {0} met {1} berichten"),
    smokeModelsFailed("models    = FAILED: {0}", "models    = FAALDE: {0}"),
    smokeModels("models    = {0}", "modellen  = {0}"),
}

/**
 * The live language. AppState keeps this in step with the persisted AppConfig,
 * so any component can read [get] without threading the config through.
 */
object Texts {

    var language: Language = Language.EN

    operator fun get(k: Key): String = when (language) {
        Language.EN -> k.en
        Language.NL -> k.nl
    }

    /** Substitutes {0}, {1}, ... in the active language. */
    fun fmt(k: Key, vararg args: Any): String {
        var s = get(k)
        args.forEachIndexed { i, v -> s = s.replace("{$i}", v.toString()) }
        return s
    }

    /** Anything unrecognised falls back to English rather than failing. */
    fun parse(stored: String): Language =
        if (stored.trim().equals("nl", ignoreCase = true)) Language.NL else Language.EN
}

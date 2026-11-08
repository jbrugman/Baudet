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
    SendButton("Send  (ctrl+enter)", "Verzenden  (ctrl+enter)"),
    NewSessionButton("New session", "Nieuwe sessie"),
    CompactButton("Compact", "Compacten"),
    None("none", "geen"),
    FailedPrefix("FAILED: {0}", "FAALDE: {0}"),
    ErrorPrefix("ERROR: {0}", "FOUT: {0}"),
    TabChat("Chat", "Chat"),
    TabModels("Models", "Modellen"),
    TabSettings("Settings", "Instellingen"),
    LanguageLabel("language", "taal"),
    KeyPresent("present (from UI or environment)", "aanwezig (uit UI of omgevingsvariabele)"),
    KeyMissing("MISSING", "ONTBREEKT"),
    HttpFromBackend("HTTP {0} from backend: {1}", "HTTP {0} van de backend: {1}"),

    // ---- chat --------------------------------------------------------------
    SummaryBanner("- active summary of {0} characters -", "- samenvatting van {0} tekens actief -"),
    MessagesCount("{0} messages", "{0} berichten"),
    ServerSession("server session {0}", "server-sessie {0}"),
    ClientReplay("client-side replay", "client-side replay"),
    PickModelFirst(
        "pick a model first, under Models or in Settings",
        "kies eerst een model bij Modellen of in Instellingen"
    ),
    Streaming("streaming...", "streamen..."),
    LastEvent("last={0}", "eind={0}"),
    Truncated("TRUNCATED ({0})", "AFGEBROKEN ({0})"),
    TruncatedUnknown("TRUNCATED (no reason reported)", "AFGEBROKEN (reden niet gemeld)"),
    TurnFailed("turn failed", "beurt mislukt"),
    NewSessionStatus("new session {0}", "nieuwe sessie {0}"),
    TooFewTurns("too few turns to compact", "te weinig beurten om te compacten"),
    Summarising("summarising...", "samenvatten..."),
    CompactionEmpty("compaction returned nothing", "compaction gaf niets terug"),
    CompactionDone("compacted into a summary of {0} characters", "compact naar {0} tekens samenvatting"),
    CompactionFailed("compaction failed: {0}", "compaction mislukt: {0}"),
    YouLabel("you: ", "jij: "),
    MuleLabel("mule: ", "mule: "),
    ReasoningLabel("reasoning:", "redenering:"),
    AnswerLabel("answer:", "antwoord:"),
    CompactRecommended("  COMPACT RECOMMENDED", "  COMPACT AANBEVOLEN"),

    // ---- models ------------------------------------------------------------
    FetchModels("Fetch (GET /models)", "Ophalen (GET /models)"),
    UseThisModel("Use this model", "Gebruik dit model"),
    Fetching("fetching...", "ophalen..."),
    ModelsListed("{0} models via {1}", "{0} modellen via {1}"),
    PickFirst("select a model first", "eerst een model kiezen"),
    ModelSaved("model '{0}' adopted and saved", "model '{0}' overgenomen en opgeslagen"),

    // ---- settings ----------------------------------------------------------
    BaseUrlLabel("base url", "basis-url"),
    ApiKeyLabel("api-key", "api-key"),
    OrEnvVar("or env-var", "of env-var"),
    ModelLabel("model", "model"),
    MaxTokensLabel("max output tokens", "max output tokens"),
    ContextBudgetLabel("context budget (tokens)", "context-budget (tokens)"),
    TemperatureLabel("temperature", "temperature"),
    TopPLabel("top_p (blank = do not send)", "top_p (leeg = niet sturen)"),
    ReasoningEffortLabel("reasoning-effort", "reasoning-effort"),
    SystemPromptLabel("system prompt (blank = built-in)", "system-prompt (leeg = ingebouwd)"),
    UseResponsesApi("Responses API (not chat/completions)", "Responses-API (geen chat/completions)"),
    StoreResponses("store=true  (backend keeps the response)", "store=true  (server bewaart de reactie)"),
    SendPreviousResponseId("send previous_response_id", "previous_response_id meesturen"),
    SaveButton("Save", "Opslaan"),
    TestButton("Test connection + capabilities", "Test verbinding + capabilities"),
    ReloadButton("Revert to saved", "Herstel opgeslagen"),
    TipSave("Save these settings to the local store. The language switch is applied here too.",
        "Sla deze instellingen op in de lokale opslag. De taalkeuze wordt hier ook toegepast."),
    TipTest("Saves, then contacts the backend and runs the capability probe.",
        "Slaat op, neemt dan contact op met de backend en draait de capability-probe."),
    TipReload("Discard unsaved edits and refill the form from the last saved settings.",
        "Verwerp niet-opgeslagen wijzigingen en vul het formulier opnieuw vanuit de laatst opgeslagen instellingen."),
    TipLanguage("Interface language; applied when you press Save.",
        "Taal van de interface; toegepast zodra u Opslaan drukt."),
    Diagnostics("diagnostics", "diagnostiek"),
    LoadedFromDisk("loaded from {0}", "laden vanaf {0}"),
    ErrMaxTokens("max output tokens is not a positive number", "max output tokens is geen positief getal"),
    ErrContextBudget("context budget is not a positive number", "context-budget is geen positief getal"),
    ErrTemperature("temperature must be between 0 and 2", "temperature moet tussen 0 en 2"),
    ErrTopP("top_p must be between 0 (exclusive) and 1, or blank", "top_p moet tussen 0 (excl) en 1, of leeg"),
    ErrBaseUrl("base url must start with http:// or https://", "basis-url moet met http:// of https:// beginnen"),
    SavedToDisk("saved, model='{0}'", "opgeslagen, model='{0}'"),
    TestingProbe(
        "testing (two mini-calls, a few seconds of GPU time)...",
        "bezig met testen (twee mini-calls, een paar seconden GPU-tijd)..."
    ),
    DiagnosticsDone("diagnostics done", "diagnostiek klaar"),
    TestFailed("test failed: {0}", "test mislukt: {0}"),

    // ---- capability probe --------------------------------------------------
    ProbeAvailable("{0} available", "{0} beschikbaar"),
    ProbeModelNotListed("model '{0}' is not in the list", "model '{0}' staat niet in de lijst"),
    ProbeTruncationNone("no, completed cleanly", "nee, netjes voltooid"),
    ProbeNoResponseId(
        "NO response_id -> server-side sessions are not usable here",
        "GEEN response_id -> server-side sessies zijn hier niet bruikbaar"
    ),
    ProbeReread("GET /responses/{id} -> HTTP {0}", "GET /responses/{id} -> HTTP {0}"),
    ProbeHonored(
        "previous_response_id IS HONORED (prompt grew {0} -> {1})",
        "previous_response_id WORDT GEHONOREERD (prompt groeide {0} -> {1})"
    ),
    ProbeIgnored(
        "previous_response_id is IGNORED (prompt {0} -> {1}) -> enable 'client-side replay' or you lose context",
        "previous_response_id wordt GENEGEERD (prompt {0} -> {1}) -> zet 'client-side replay' aan, anders ben je context kwijt"
    ),
    ProbeFail("FAIL: {0}", "FAAL: {0}"),

    // ---- startup smoke run -------------------------------------------------
    SmokeDataDir("mule smoke · data-dir={0}", "mule smoke · data-dir={0}"),
    SmokeModelSet("model     = set to '{0}' and saved", "model     = gezet op '{0}' en opgeslagen"),
    SmokeKeyLabel("key       = ", "sleutel   = "),
    SmokeSession("session   = {0} with {1} messages", "sessie    = {0} met {1} berichten"),
    SmokeModelsFailed("models    = FAILED: {0}", "models    = FAALDE: {0}"),
    SmokeModels("models    = {0}", "modellen  = {0}"),
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

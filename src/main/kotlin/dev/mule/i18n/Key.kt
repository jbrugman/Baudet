package dev.mule.i18n

/**
 * Every user-visible string, one entry per message, every language side by side.
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
enum class Key(val en: String, val nl: String, val fr: String, val de: String) {

    // ---- shared ------------------------------------------------------------
    SendButton("Send  (ctrl+enter)", "Verzenden  (ctrl+enter)", "Envoyer  (ctrl+entrée)", "Senden  (Strg+Eingabe)"),
    NewSessionButton("New session", "Nieuwe sessie", "Nouvelle session", "Neue Sitzung"),
    CompactButton("Compact", "Compacten", "Compresser", "Kompaktieren"),
    None("none", "geen", "aucun", "keine"),
    FailedPrefix("FAILED: {0}", "FAALDE: {0}", "ÉCHEC : {0}", "FEHLGESCHLAGEN : {0}"),
    ErrorPrefix("ERROR: {0}", "FOUT: {0}", "ERREUR : {0}", "FEHLER : {0}"),
    TabChat("Chat", "Chat", "Chat", "Chat"),
    TabModels("Models", "Modellen", "Modèles", "Modelle"),
    TabSettings("Settings", "Instellingen", "Paramètres", "Einstellungen"),
    LanguageLabel("language", "taal", "langue", "Sprache"),
    KeyPresent("present (from UI or environment)", "aanwezig (uit UI of omgevingsvariabele)", "présent (via l'interface ou l'environnement)", "vorhanden (aus UI oder Umgebung)"),
    KeyMissing("MISSING", "ONTBREEKT", "MANQUANT", "FEHLT"),
    HttpFromBackend("HTTP {0} from backend: {1}", "HTTP {0} van de backend: {1}", "HTTP {0} du backend : {1}", "HTTP {0} vom Backend: {1}"),

    // ---- chat --------------------------------------------------------------
    SummaryBanner("- active summary of {0} characters -", "- samenvatting van {0} tekens actief -", "- résumé actif de {0} caractères -", "- aktive Zusammenfassung von {0} Zeichen -"),
    MessagesCount("{0} messages", "{0} berichten", "{0} messages", "{0} Nachrichten"),
    ServerSession("server session {0}", "server-sessie {0}", "session serveur {0}", "Server-Sitzung {0}"),
    ClientReplay("client-side replay", "client-side replay", "relecture côté client", "Client-seitiges Wiederholen"),
    PickModelFirst(
        "pick a model first, under Models or in Settings",
        "kies eerst een model bij Modellen of in Instellingen", "choisissez d'abord un modèle, sous Modèles ou dans Paramètres", "wählen Sie zuerst ein Modell, unter Modelle oder in Einstellungen"
    ),
    Streaming("streaming...", "streamen...", "flux en cours...", "streamt..."),
    LastEvent("last={0}", "eind={0}", "dernier={0}", "letztes={0}"),
    Truncated("TRUNCATED ({0})", "AFGEBROKEN ({0})", "TRONQUÉ ({0})", "ABGESCHNITTEN ({0})"),
    TruncatedUnknown("TRUNCATED (no reason reported)", "AFGEBROKEN (reden niet gemeld)", "TRONQUÉ (aucune raison signalée)", "ABGESCHNITTEN (kein Grund gemeldet)"),
    TurnFailed("turn failed", "beurt mislukt", "échec du tour", "Runde fehlgeschlagen"),
    NewSessionStatus("new session {0}", "nieuwe sessie {0}", "nouvelle session {0}", "neue Sitzung {0}"),
    TooFewTurns("too few turns to compact", "te weinig beurten om te compacten", "trop peu de tours à compresser", "zu wenige Runden zum Kompaktieren"),
    Summarising("summarising...", "samenvatten...", "résumé en cours...", "Zusammenfassung..."),
    CompactionEmpty("compaction returned nothing", "compaction gaf niets terug", "la compression n'a rien renvoyé", "Kompaktierung lieferte nichts zurück"),
    CompactionDone("compacted into a summary of {0} characters", "compact naar {0} tekens samenvatting", "compressé en un résumé de {0} caractères", "kompaktiert zu einer Zusammenfassung von {0} Zeichen"),
    CompactionFailed("compaction failed: {0}", "compaction mislukt: {0}", "échec de la compression : {0}", "Kompaktierung fehlgeschlagen: {0}"),
    YouLabel("you: ", "jij: ", "vous : ", "Sie: "),
    MuleLabel("mule: ", "mule: ", "mule : ", "mule: "),
    ReasoningLabel("reasoning:", "redenering:", "raisonnement :", "Begründung:"),
    CompactRecommended("  COMPACT RECOMMENDED", "  COMPACT AANBEVOLEN", "  COMPRESSION RECOMMANDÉE", "  KOMPAKTIERUNG EMPFOHLEN"),

    // ---- models ------------------------------------------------------------
    FetchModels("Fetch (GET /models)", "Ophalen (GET /models)", "Récupérer (GET /models)", "Abrufen (GET /models)"),
    UseThisModel("Use this model", "Gebruik dit model", "Utiliser ce modèle", "Dieses Modell verwenden"),
    Fetching("fetching...", "ophalen...", "récupération...", "Abrufen..."),
    ModelsListed("{0} models via {1}", "{0} modellen via {1}", "{0} modèles via {1}", "{0} Modelle über {1}"),
    PickFirst("select a model first", "eerst een model kiezen", "sélectionnez d'abord un modèle", "zuerst ein Modell auswählen"),
    ModelSaved("model '{0}' adopted and saved", "model '{0}' overgenomen en opgeslagen", "modèle '{0}' adopté et enregistré", "Modell '{0}' übernommen und gespeichert"),

    // ---- settings ----------------------------------------------------------
    BaseUrlLabel("base url", "basis-url", "url de base", "Basis-URL"),
    ApiKeyLabel("api-key", "api-key", "clé d'API", "API-Schlüssel"),
    OrEnvVar("or env-var", "of env-var", "ou variable d'env.", "oder Umgebungsvariable"),
    ModelLabel("model", "model", "modèle", "Modell"),
    MaxTokensLabel("max output tokens (0 = server)", "max output tokens (0 = server)", "jetons de sortie max (0 = serveur)", "max. Ausgabetoken (0 = Server)"),
    ContextBudgetLabel("context budget (tokens, 0 = auto)", "context-budget (tokens, 0 = automatisch)", "budget de contexte (jetons, 0 = auto)", "Kontextbudget (Token, 0 = auto)"),
    ContextStrategyLabel("when context runs out", "wanneer de context opraakt", "en cas de saturation du contexte", "bei erschöpftem Kontext"),
    TemperatureLabel("temperature", "temperature", "température", "Temperatur"),
    TopPLabel("top_p (blank = do not send)", "top_p (leeg = niet sturen)", "top_p (vide = ne pas envoyer)", "top_p (leer = nicht senden)"),
    ReasoningEffortLabel("reasoning-effort", "reasoning-effort", "effort de raisonnement", "Begründungsaufwand"),
    SystemPromptLabel("system prompt (blank = built-in)", "system-prompt (leeg = ingebouwd)", "invite système (vide = intégré)", "System-Prompt (leer = integriert)"),
    UseResponsesApi("Responses API (not chat/completions)", "Responses-API (geen chat/completions)", "Responses-API (pas chat/completions)", "Responses-API (kein chat/completions)"),
    StoreResponses("store=true  (backend keeps the response)", "store=true  (server bewaart de reactie)", "store=true  (le serveur garde la réponse)", "store=true  (Server behält die Antwort)"),
    SendPreviousResponseId("send previous_response_id", "previous_response_id meesturen", "envoyer previous_response_id", "previous_response_id senden"),
    SaveButton("Save", "Opslaan", "Enregistrer", "Speichern"),
    TestButton("Test connection + capabilities", "Test verbinding + capabilities", "Tester connexion + capacités", "Verbindung + Fähigkeiten testen"),
    ReloadButton("Revert to saved", "Herstel opgeslagen", "Rétablir l'enregistré", "Auf Gespeichertes zurücksetzen"),
    TipSave("Save these settings to the local store. The language switch is applied here too.",
        "Sla deze instellingen op in de lokale opslag. De taalkeuze wordt hier ook toegepast.", "Enregistrez ces paramètres dans le stockage local. Le changement de langue est aussi appliqué ici.", "Speichern Sie diese Einstellungen im lokalen Speicher. Die Sprachumschaltung wird hier ebenfalls angewendet."),
    TipTest("Saves, then contacts the backend and runs the capability probe.",
        "Slaat op, neemt dan contact op met de backend en draait de capability-probe.", "Enregistre, puis contacte le backend et lance la sonde de capacités.", "Speichert, kontaktiert dann das Backend und führt die Fähigkeitsprüfung aus."),
    TipReload("Discard unsaved edits and refill the form from the last saved settings.",
        "Verwerp niet-opgeslagen wijzigingen en vul het formulier opnieuw vanuit de laatst opgeslagen instellingen.", "Ignorez les modifications non enregistrées et rechargez le formulaire depuis les derniers paramètres enregistrés.", "Nicht gespeicherte Änderungen verwerfen und das Formular aus den zuletzt gespeicherten Einstellungen neu ausfüllen."),
    TipLanguage("Interface language; applied when you press Save.",
        "Taal van de interface; toegepast zodra u Opslaan drukt.", "Langue de l'interface ; appliquée lorsque vous enregistrez.", "Sprache der Oberfläche; wird beim Speichern angewendet."),
    TipMaxTokens(
        "Upper bound on the tokens the model may generate in one turn. 0 or blank leaves that to the server, which applies the model's own limit.",
        "Bovengrens voor de tokens die het model in één beurt mag genereren. Bij 0 of leeg bepaalt de server dat, naar de eigen limiet van het model.",
        "Limite supérieure des jetons que le modèle peut générer en un tour. À 0 ou vide, le serveur décide et applique la propre limite du modèle.",
        "Obergrenze für die Token, die das Modell in einer Runde erzeugen darf. Bei 0 oder leer entscheidet das Server und wendet die eigene Grenze des Modells an."
    ),
    TipContextBudget(
        "A client-side estimate only: it fills the token bar and drives the automatic fold. Nothing of it is sent to the server. 0 or blank means the app decides: fold at three quarters of the model's context window and refuse at its edge, taking the window from the refusal a backend named most recently when it named one. A number you type is a ceiling, never a floor.",
        "Alleen een schatting aan de kant van de client: hij vult de tokenbalk en stuurt de automatische vouwing aan. Er gaat niets ervan naar de server. Bij 0 of leeg kiest het programma zelf: vouwen bij driekwart van de contextgrootte en weigeren aan de rand, met de grootte die een backend het laatst noemde als die er een noemde. Een getal dat je invult is een plafond, nooit een vloer.",
        "Une estimation côté client uniquement : elle remplit la barre de jetons et déclenche la compression automatique. Rien n'en est envoyé au serveur. À 0 ou vide, l'application décide : compresser aux trois quarts de la fenêtre de contexte et refuser à son bord, en reprenant la fenêtre qu'un serveur a citée le plus récemment s'il en a cité une. Un nombre que vous introduisez est un plafond, jamais un plancher.",
        "Nur eine Schätzung auf Client-Seite: sie füllt die Token-Leiste und steuert die automatische Komprimierung. Nichts davon wird an den Server gesendet. Bei 0 oder leer entscheidet das Programm: komprimieren bei drei Vierteln der Kontextgröße und ablehnen an ihrer Grenze, wobei die Größe zählt, die ein Server zuletzt nannte, falls er eine nannte. Eine eingetragene Zahl ist eine Obergrenze, nie eine Untergrenze."
    ),
    TipThinkingToggle("Show or hide the reasoning of this turn. It is never stored.", "Toon of verberg de redenering van deze beurt. Die wordt nooit opgeslagen.", "Affiche ou masque le raisonnement de ce tour. Il n'est jamais enregistré.", "Denkvorgang dieser Runde ein- oder ausblenden. Er wird nie gespeichert."),
    TipBaseUrl("The OpenAI-compatible API endpoint, usually ending in /v1. Point it at your local backend, for example 127.0.0.1:8000.", "De OpenAI-compatibele API, meestal eindigend op /v1. Richt deze op uw lokale backend, bijvoorbeeld 127.0.0.1:8000.", "Le point de terminaison de l'API compatible OpenAI, se terminant généralement par /v1. Pointez-le vers votre backend local, par exemple 127.0.0.1:8000.", "Der Endpunkt der OpenAI-kompatiblen API, normalerweise endend auf /v1. Richten Sie ihn auf Ihr lokales Backend, zum Beispiel 127.0.0.1:8000."),
    TipApiKey("The bearer token sent with each request. Leave blank to read it from the environment variable named on the next line.", "De bearer-token die bij elk verzoek wordt meegestuurd. Laat leeg om hem uit de omgevingsvariabele op de volgende regel te lezen.", "Le jeton bearer envoyé à chaque requête. Laissez vide pour le lire depuis la variable d'environnement indiquée sur la ligne suivante.", "Das Bearer-Token, das mit jeder Anfrage gesendet wird. Leer lassen, um es aus der Umgebungsvariable in der nächsten Zeile zu lesen."),
    TipEnvVar("Name of the environment variable to read the api key from, used only when the field above is blank.", "Naam van de omgevingsvariabele waar de api-key wordt uitgelezen, alleen gebruikt wanneer het veld hierboven leeg is.", "Nom de la variable d'environnement d'où lire la clé d'API, utilisée seulement si le champ ci-dessus est vide.", "Name der Umgebungsvariable, aus der der API-Schlüssel gelesen wird, nur verwendet, wenn das Feld darüber leer ist."),
    TipModel("The model id sent with each request. Pick one from the Models tab, or type an id your backend accepts.", "De model-id die bij elk verzoek wordt verstuurd. Kies er een op het tabblad Modellen, of typ een id die uw backend accepteert.", "L'identifiant du modèle envoyé à chaque requête. Choisissez-en un dans l'onglet Modèles, ou saisissez un identifiant accepté par votre backend.", "Die Modell-ID, die mit jeder Anfrage gesendet wird. Wählen Sie eine im Tab Modelle, oder geben Sie eine ID ein, die Ihr Backend akzeptiert."),
    TipTemperature("Sampling randomness. Lower is more deterministic, higher is more varied; most coding tasks want a low value.", "De randomheid van de sampling. Lager is deterministischer, hoger is gevarieerder; de meeste codeertaken willen een lage waarde.", "Le caractère aléatoire de l'échantillonnage. Plus bas est plus déterministe, plus haut est plus varié ; la plupart des tâches de code veulent une valeur basse.", "Die Zufälligkeit der Stichprobenziehung. Niedriger ist deterministischer, höher ist variantenreicher; die meisten Codieraufgaben wollen einen niedrigen Wert."),
    TipTopP("Nucleus sampling cutoff, an alternative to temperature. Leave blank to omit it and let the server decide.", "De nucleus-sampling drempel, een alternatief voor temperature. Laat leeg om hem weg te laten en de server te laten beslissen.", "Le seuil d'échantillonnage par noyau, une alternative à la température. Laissez vide pour l'omettre et laisser le serveur décider.", "Die Kernstichproben-Grenze, eine Alternative zur Temperatur. Leer lassen, um sie wegzulassen und den Server entscheiden zu lassen."),
    TipReasoning("Asks reasoning-capable backends to spend more effort before answering. Blank sends nothing; only some models honour it.", "Vraagt reasoning-capable backends om meer moeite te doen vóór het antwoord. Leeg stuurt niets; niet alle modellen volgen dat.", "Demande aux backends capables de raisonnement un effort accru avant de répondre. Vide n'envoie rien ; seuls certains modèles l'honorent.", "Bittet reasoning-fähige Backends, vor der Antwort mehr Aufwand zu betreiben. Leer sendet nichts; nur manche Modelle folgen dem."),
    TipSystemPrompt("Instructions that steer every turn. Leave blank to use the built-in default; anything you type here takes over, and it is always sent in English.", "Instructies die elke beurt sturen. Laat leeg voor de ingebouwde standaard; wat u hier typt neemt over, en gaat altijd in het Engels mee.", "Des instructions qui orientent chaque tour. Laissez vide pour le défaut intégré ; ce que vous saisissez ici prend le relais, et part toujours en anglais.", "Anweisungen, die jede Runde steuern. Leer lassen für die integrierte Vorgabe; was Sie hier eingeben, übernimmt, und geht immer auf Englisch mit."),
    TipUseResponses("Call the newer Responses endpoint instead of chat completions. Use it when your backend offers it; it carries server-side session state.", "Roep het nieuwere Responses-endpoint aan in plaats van chat completions. Gebruik het als uw backend het biedt; het draagt sessietoestand aan de serverzijde.", "Appeler le point Responses plus récent au lieu de chat completions. Utilisez-le si votre backend le propose ; il porte l'état de session côté serveur.", "Den neueren Responses-Endpunkt statt chat completions aufrufen. Verwenden, wenn Ihr Backend ihn bietet; er trägt den Sitzungsstatus auf der Serverseite."),
    TipStoreResponses("Sets store=true so the backend keeps each response. Needed when you also send previous_response_id.", "Zet store=true zodat de backend elke reactie bewaart. Nodig wanneer u ook previous_response_id meestuurt.", "Place store=true pour que le backend garde chaque réponse. Nécessaire si vous envoyez aussi previous_response_id.", "Setzt store=true, damit das Backend jede Antwort behält. Nötig, wenn Sie auch previous_response_id senden."),
    TipSendResponseId("Sends previous_response_id so the backend reuses its own context instead of replaying the whole transcript each turn.", "Stuurt previous_response_id mee zodat de backend zijn eigen context hergebruikt in plaats van elke beurt het hele gesprek opnieuw te sturen.", "Envoie previous_response_id pour que le backend réutilise son propre contexte au lieu de rejouer tout le transcript à chaque tour.", "Sendet previous_response_id, damit das Backend seinen eigenen Kontext wiederverwendet, statt jeden Turn das ganze Protokoll erneut zu senden."),
    TipMaxIterations("How many tool round-trips one answer may take before the loop stops. A guard against a tool that never settles.", "Hoeveel tool-rondes één antwoord mag maken voordat de lus stopt. Een bewaking tegen een tool die nooit uitkomt.", "Le nombre d'allers-retours d'outils qu'une réponse peut faire avant que la boucle s'arrête. Un garde-fou contre un outil qui ne se stabilise jamais.", "Wie viele Werkzeug-Runden eine Antwort machen darf, bevor die Schleife stoppt. Eine Sicherung gegen ein Werkzeug, das nie zur Ruhe kommt."),
    TipAutoApproveSafe("Lets clearly safe read-only commands run without an approval prompt. Writes still ask unless the tool mode is auto.", "Laat duidelijk veilige alleen-lezen commando's draaien zonder een goedkeuringsprompt. Schrijven vraagt nog, tenzij de toolmodus op auto staat.", "Exécute les commandes en lecture seule manifestement sûres sans demande d'approbation. Les écritures demandent encore, sauf si le mode d'outil est auto.", "Lässt eindeutig sichere Nur-Lese-Befehle ohne Freigabeaufforderung laufen. Schreibfragen bleiben, außer der Werkzeugmodus steht auf auto."),
    StrategyTruncate(
        "trim the middle, keep start and end",
        "midden inkorten, begin en eind houden",
        "couper au milieu, garder début et fin",
        "Mitte kürzen, Anfang und Ende behalten"
    ),
    StrategyRolling(
        "keep only the latest turns",
        "alleen de nieuwste beurten houden",
        "ne garder que les derniers tours",
        "nur die neuesten Runden behalten"
    ),
    StrategyStop(
        "stop and ask me to compact",
        "stoppen en mij vragen te compacten",
        "arrêter et me demander de compacter",
        "stoppen und zum Kompaktieren auffordern"
    ),
    TipContextStrategy(
        "How a conversation that outgrows the model is handled. Trim the middle keeps how it began plus the newest turns and drops what is between them; keep only the latest turns sends just the newest tail; stop and ask refuses the turn until you compact or start fresh. The stored conversation is never edited, so only the window sent to the model changes.",
        "Hoe een gesprek wordt behandeld dat groter wordt dan het model aankan. Midden inkorten houdt het begin en de nieuwste beurten en laat het ertussen weg; alleen de nieuwste beurten stuurt alleen de nieuwste staart; stoppen en mij vragen weigert de beurt tot je compact of opnieuw begint. Het opgeslagen gesprek wordt nooit bewerkt, alleen het venster naar het model verandert.",
        "Comment gérer une conversation plus grande que le modèle. Couper au milieu garde le début et les derniers tours et supprime ce qui les sépare ; ne garder que les derniers tours n'envoie que la dernière partie ; arrêter et me demander refuse le tour tant que vous ne compactez pas ou ne recommencez pas. La conversation enregistrée n'est jamais modifiée, seule la fenêtre envoyée au modèle change.",
        "Wie eine Unterhaltung behandelt wird, die über das Modell hinauswächst. Mitte kürzen behält den Anfang und die neuesten Runden und lässt das Dazwischen weg; nur die neuesten Runden sendet nur den neuesten Teil; stoppen und fragen lehnt die Runde ab, bis Sie kompaktieren oder neu beginnen. Die gespeicherte Unterhaltung wird nie bearbeitet, nur das Fenster an das Modell ändert sich."
    ),
    ContextTooLong(
        "not sent: this conversation is too large for the model. Nothing left your machine, so your message is still in the box. Compact it, start a new conversation, or raise the context budget in Settings.",
        "niet verzonden: dit gesprek is te groot voor het model. Er heeft niets je machine verlaten, dus je bericht staat nog in het vak. Compact het, start een nieuw gesprek, of verhoog het contextbudget in de instellingen.",
        "non envoyé : cette conversation est trop grande pour le modèle. Rien n'a quitté votre machine, votre message est donc toujours dans la zone de saisie. Compactez-la, démarrez une nouvelle conversation ou relevez le budget de contexte dans les réglages.",
        "nicht gesendet: diese Unterhaltung ist zu groß für das Modell. Nichts hat Ihren Rechner verlassen, Ihre Nachricht steht also noch im Eingabefeld. Kompaktieren Sie sie, starten Sie eine neue Unterhaltung oder erhöhen Sie das Kontextbudget in den Einstellungen."
    ),
    ContextOverflow(
        "the model ran out of room for this conversation. Compact it to fold the older turns into a summary, or start a new conversation to begin with a clean window.",
        "het model heeft geen ruimte meer voor dit gesprek. Compact het om oudere beurten in een samenvatting te vouwen, of start een nieuw gesprek met een leeg venster.",
        "le modèle n'a plus de place pour cette conversation. Compactez-la pour replier les anciens tours dans un résumé, ou démarrez une nouvelle conversation avec une fenêtre vierge.",
        "dem Modell fehlt der Platz für diese Unterhaltung. Kompaktieren Sie sie, um ältere Runden in einer Zusammenfassung zu falten, oder starten Sie eine neue Unterhaltung mit einem leeren Fenster."
    ),
    // ---- tools (settings) ---------------------------------------------------
    ToolModeLabel("tool mode", "toolmodus", "mode d'outil", "Werkzeugmodus"),
    ToolModeOff("off", "uit", "inactif", "aus"),
    ToolModeAsk("ask before writing", "vragen voor schrijven", "demander avant d'écrire", "vor dem Schreiben fragen"),
    ToolModeAuto("auto (write without asking)", "auto (schrijven zonder vragen)", "auto (écrire sans demander)", "auto (ohne Nachfrage schreiben)"),
    MaxToolIterationsLabel("max tool iterations", "max tool-iteraties", "itérations d'outil max", "max. Werkzeug-Iterationen"),
    ErrMaxIterations("max tool iterations is not a positive number", "max tool-iteraties is geen positief getal", "le nombre maximal d'itérations d'outil n'est pas un nombre positif", "max. Werkzeug-Iterationen ist keine positive Zahl"),
    TipToolMode(
        "When the assistant may use tools. Ask shows each change for approval; Auto writes without asking; Off keeps the loop out.",
        "Wanneer de assistent tools mag gebruiken. Vragen toont elke wijziging ter goedkeuring; Auto schrijft zonder vragen; Off houdt de lus buiten spel.",
        "Quand l'assistant peut utiliser des outils. Demander montre chaque modification pour approbation ; Auto écrit sans demander ; Off garde la boucle hors jeu.",
        "Wann der Assistent Werkzeuge nutzen darf. Fragen zeigt jede Änderung zur Freigabe; Auto schreibt ohne Nachfrage; Off lässt die Schleife außen vor."
    ),
    EditReviewTitle("review change", "wijziging controleren", "vérifier la modification", "Änderung prüfen"),
    EditReviewPrompt("allow Mule to write to {0}?", "Mule toestaan om {0} te schrijven?", "autoriser Mule à écrire dans {0} ?", "Mule erlauben, {0} zu schreiben?"),
    RunReviewTitle("run command", "commando uitvoeren", "exécuter la commande", "Befehl ausführen"),
    RunReviewPrompt("run this command in {0} (timeout {1}s)?", "voer dit commando uit in {0} (timeout {1}s)?", "exécuter cette commande dans {0} (délai {1} s) ?", "dieses Kommando in {0} ausführen (Timeout {1} s)?"),
    AllowOnce("Allow once", "Eenmaal toestaan", "Autoriser une fois", "Einmal zulassen"),
    AlwaysAllow("Always allow", "Altijd toestaan", "Toujours autoriser", "Immer zulassen"),
    DenyButton("Deny", "Weigeren", "Refuser", "Ablehnen"),
    AutoApproveSafe(
        "Auto-approve safe read-only commands",
        "Veilige alleen-lezen commando's automatisch goedkeuren",
        "Approuver automatiquement les commandes en lecture seule sûres",
        "Sichere schreibgeschützte Befehle automatisch genehmigen"
    ),
    Diagnostics("diagnostics", "diagnostiek", "diagnostic", "Diagnose"),
    LoadedFromDisk("loaded from {0}", "laden vanaf {0}", "chargé depuis {0}", "geladen von {0}"),
    ErrMaxTokens("max output tokens must be 0 or a positive number", "max output tokens moet 0 of een positief getal zijn", "les jetons de sortie max doivent être 0 ou un nombre positif", "max. Ausgabetoken muss 0 oder eine positive Zahl sein"),
    ErrContextBudget("context budget must be 0 or a positive number", "context-budget moet 0 of een positief getal zijn", "le budget de contexte doit être 0 ou un nombre positif", "Kontextbudget muss 0 oder eine positive Zahl sein"),
    ErrTemperature("temperature must be between 0 and 2", "temperature moet tussen 0 en 2", "la température doit être comprise entre 0 et 2", "Temperatur muss zwischen 0 und 2 liegen"),
    ErrTopP("top_p must be between 0 (exclusive) and 1, or blank", "top_p moet tussen 0 (excl) en 1, of leeg", "top_p doit être entre 0 (exclus) et 1, ou vide", "top_p muss zwischen 0 (ausschließlich) und 1 sein, oder leer"),
    ErrBaseUrl("base url must start with http:// or https://", "basis-url moet met http:// of https:// beginnen", "l'url de base doit commencer par http:// ou https://", "Basis-URL muss mit http:// oder https:// beginnen"),
    SavedToDisk("saved, model='{0}'", "opgeslagen, model='{0}'", "enregistré, modèle='{0}'", "gespeichert, Modell='{0}'"),
    TestingProbe(
        "testing (two mini-calls, a few seconds of GPU time)...",
        "bezig met testen (twee mini-calls, een paar seconden GPU-tijd)...", "test en cours (deux mini-appels, quelques secondes de GPU)...", "Teste (zwei Mini-Aufrufe, ein paar Sekunden GPU-Zeit)..."
    ),
    DiagnosticsDone("diagnostics done", "diagnostiek klaar", "diagnostic terminé", "Diagnose abgeschlossen"),
    TestFailed("test failed: {0}", "test mislukt: {0}", "échec du test : {0}", "Test fehlgeschlagen: {0}"),

    // ---- capability probe --------------------------------------------------
    ProbeAvailable("{0} available", "{0} beschikbaar", "{0} disponible", "{0} verfügbar"),
    ProbeModelNotListed("model '{0}' is not in the list", "model '{0}' staat niet in de lijst", "le modèle '{0}' ne figure pas dans la liste", "Modell '{0}' ist nicht in der Liste"),
    ProbeTruncationNone("no, completed cleanly", "nee, netjes voltooid", "non, terminé proprement", "nein, sauber abgeschlossen"),
    ProbeNoResponseId(
        "NO response_id -> server-side sessions are not usable here",
        "GEEN response_id -> server-side sessies zijn hier niet bruikbaar", "AUCUN response_id -> les sessions côté serveur ne sont pas utilisables ici", "KEIN response_id -> serverseitige Sitzungen sind hier nicht nutzbar"
    ),
    ProbeReread("GET /responses/{id} -> HTTP {0}", "GET /responses/{id} -> HTTP {0}", "GET /responses/{id} -> HTTP {0}", "GET /responses/{id} -> HTTP {0}"),
    ProbeHonored(
        "previous_response_id IS HONORED (prompt grew {0} -> {1})",
        "previous_response_id WORDT GEHONOREERD (prompt groeide {0} -> {1})", "previous_response_id EST RESPECTÉ (prompt de {0} -> {1})", "previous_response_id WIRD BEACHTET (Prompt wuchs {0} -> {1})"
    ),
    ProbeIgnored(
        "previous_response_id is IGNORED (prompt {0} -> {1}) -> enable 'client-side replay' or you lose context",
        "previous_response_id wordt GENEGEERD (prompt {0} -> {1}) -> zet 'client-side replay' aan, anders ben je context kwijt", "previous_response_id est IGNORÉ (prompt {0} -> {1}) -> activez 'relecture côté client' sinon vous perdez le contexte", "previous_response_id wird IGNORIERT (Prompt {0} -> {1}) -> aktivieren Sie 'client-seitiges Wiederholen', sonst verlieren Sie den Kontext"
    ),
    ProbeFail("FAIL: {0}", "FAAL: {0}", "ÉCHEC : {0}", "FEHLER: {0}"),

    // ---- projects / sidebar ------------------------------------------------
    AddProjectButton("Add project", "Project toevoegen", "Ajouter un projet", "Projekt hinzufügen"),
    ToggleSidebar("Show or hide the project sidebar", "Toon of verberg de projectbalk", "Afficher ou masquer la barre latérale des projets", "Projektleiste ein-/ausblenden"),
    RenameButton("Rename", "Hernoemen", "Renommer", "Umbenennen"),
    ArchiveButton("Archive", "Archiveren", "Archiver", "Archivieren"),
    UnarchiveButton("Unarchive", "Dearchiveren", "Désarchiver", "Dearchivieren"),
    DeleteButton("Delete", "Verwijderen", "Supprimer", "Löschen"),
    LinkFolderButton("Link folder…", "Koppel map…", "Lier un dossier…", "Ordner verknüpfen…"),
    UnlinkFolderButton("Unlink folder", "Ontkoppel map", "Détacher le dossier", "Ordner lösen"),
    ArchiveProjectButton("Archive project", "Archiveer project", "Archiver le projet", "Projekt archivieren"),
    UnarchiveProjectButton("Unarchive project", "Dearchiveer project", "Désarchiver le projet", "Projekt dearchivieren"),
    DeleteProjectButton("Delete project", "Verwijder project", "Supprimer le projet", "Projekt löschen"),
    OpenSessionButton("Open", "Open", "Ouvrir", "Öffnen"),
    ProjectChatStatus("new conversation in {0}", "nieuw gesprek in {0}", "nouvelle conversation dans {0}", "neue Unterhaltung in {0}"),
    InboxNode("Unassigned", "Niet toegewezen", "Non attribué", "Nicht zugewiesen"),
    WarnNoFolder(
        "This project has no folder linked, so the assistant cannot read or change files. Link a folder and grant Write access.",
        "Aan dit project is geen map gekoppeld, dus de assistent kan geen bestanden lezen of wijzigen. Koppel een map en geef schrijfrechten.",
        "Aucun dossier n’est associé à ce projet ; l’assistant ne peut ni lire ni modifier de fichiers. Associez un dossier et accordez les droits d’écriture.",
        "Diesem Projekt ist kein Ordner zugeordnet, daher kann der Assistent keine Dateien lesen oder ändern. Ordnen Sie einen Ordner zu und erteilen Sie Schreibrechte."
    ),
    WarnNoWrite(
        "The assistant cannot change files here: the project folder is read-only. Grant Write access to allow edits.",
        "De assistent kan hier geen bestanden wijzigen: de projectmap is alleen-lezen. Geef schrijfrechten om aanpassingen toe te staan.",
        "L’assistant ne peut pas modifier de fichiers ici : le dossier du projet est en lecture seule. Accordez les droits d’écriture pour autoriser les modifications.",
        "Der Assistent kann hier keine Dateien ändern: Der Projektordner ist schreibgeschützt. Erteilen Sie Schreibrechte, um Änderungen zu erlauben."
    ),
    ArchivedSuffix("archived", "gearchiveerd", "archivé", "archiviert"),
    FolderLabel("folder:", "map:", "dossier :", "Ordner:"),
    FolderNone("no folder linked", "geen map gekoppeld", "aucun dossier lié", "kein Ordner verknüpft"),
    FolderPermissionsTitle("Folder permissions", "Maprechten", "Droits du dossier", "Ordnerrechte"),
    PermRead("Read", "Lezen", "Lecture", "Lesen"),
    PermWrite("Write", "Schrijven", "Écriture", "Schreiben"),
    PermExecute("Execute", "Uitvoeren", "Exécution", "Ausführen"),
    DiskCapPrefix("on disk: ", "op schijf: ", "sur le disque : ", "auf Festplatte: "),
    CapReadable("readable", "leesbaar", "lisible", "lesbar"),
    CapWritable("writable", "schrijfbaar", "inscriptible", "schreibbar"),
    CapExecutable("executable", "uitvoerbaar", "exécutable", "ausführbar"),
    Yes("yes", "ja", "oui", "ja"),
    No("no", "nee", "non", "nein"),
    ProjectNamePrompt("Project name:", "Projectnaam:", "Nom du projet :", "Projektname:"),
    RenameProjectPrompt("Rename project:", "Hernoem project:", "Renommer le projet :", "Projekt umbenennen:"),
    RenameSessionPrompt("Rename conversation:", "Hernoem gesprek:", "Renommer la conversation :", "Unterhaltung umbenennen:"),
    DeleteProjectConfirm(
        "Delete project \"{0}\" and its {1} conversation(s)? Every message is removed permanently. Files in the linked folder are left untouched. This cannot be undone.",
        "Project \"{0}\" en al zijn {1} gesprek(len) verwijderen? Elk bericht wordt definitief verwijderd. Bestanden in de gekoppelde map blijven ongemoeid. Dit kan niet ongedaan worden gemaakt.", "Supprimer le projet « {0} » et son/ses {1} conversation(s) ? Chaque message est supprimé définitivement. Les fichiers du dossier lié ne sont pas touchés. Action irréversible.", "Projekt \"{0}\" und seine {1} Unterhaltung(en) löschen? Jede Nachricht wird endgültig entfernt. Dateien im verknüpften Ordner bleiben unberührt. Dies kann nicht rückgängig gemacht werden."
    ),
    TipAdd(
        "Add a project. New conversations start inside the one you have selected.",
        "Voeg een project toe. Nieuwe gesprekken starten binnen het geselecteerde project.", "Ajouter un projet. Les nouvelles conversations démarrent dans celui que vous avez sélectionné.", "Ein Projekt hinzufügen. Neue Unterhaltungen starten in dem ausgewählten."
    ),
    TipRename("Give the project a different name.", "Geef het project een andere naam.", "Donner au projet un autre nom.", "Dem Projekt einen anderen Namen geben."),
    TipArchive(
        "Archive keeps the project and its conversations out of the way without deleting them.",
        "Archiveren houdt het project en zijn gesprekken uit de weg zonder ze te verwijderen.", "L'archivage met le projet et ses conversations de côté sans les supprimer.", "Archivieren räumt das Projekt und seine Unterhaltungen aus dem Weg, ohne sie zu löschen."
    ),
    TipDelete(
        "Delete the project and all of its conversations and messages. Files on disk are never removed.",
        "Verwijder het project met al zijn gesprekken en berichten. Bestanden op schijf worden nooit verwijderd.", "Supprimer le projet et toutes ses conversations et messages. Les fichiers sur le disque ne sont jamais supprimés.", "Das Projekt und alle seine Unterhaltungen und Nachrichten löschen. Dateien auf der Festplatte werden nie entfernt."
    ),
    DeleteConversationButton("Delete conversation", "Verwijder gesprek", "Supprimer la conversation", "Unterhaltung löschen"),
    DeleteConversationConfirm(
        "Delete the conversation \"{0}\" and all of its messages? This cannot be undone.",
        "Verwijder het gesprek \"{0}\" met al zijn berichten? Dit kan niet ongedaan worden gemaakt.", "Supprimer la conversation « {0} » et tous ses messages ? Action irréversible.", "Unterhaltung \"{0}\" und alle ihre Nachrichten löschen? Dies kann nicht rückgängig gemacht werden."
    ),
    TipDeleteConversation(
        "Delete the selected conversation and its messages. Files on disk are never removed.",
        "Verwijder het geselecteerde gesprek en zijn berichten. Bestanden op schijf worden nooit verwijderd.", "Supprimer la conversation sélectionnée et ses messages. Les fichiers sur le disque ne sont jamais supprimés.", "Die ausgewählte Unterhaltung und ihre Nachrichten löschen. Dateien auf der Festplatte werden nie entfernt."
    ),
    TipLinkFolder(
        "Pick a folder and grant it read, write and/or execute rights.",
        "Kies een map en geef haar lees-, schrijf- en/of uitvoerrechten.", "Choisissez un dossier et accordez-lui les droits de lecture, d'écriture et/ou d'exécution.", "Wählen Sie einen Ordner und gewähren Sie Lese-, Schreib- und/oder Ausführungsrechte."
    ),
    TipUnlinkFolder("Detach the folder; its granted rights are cleared.", "Koppel de map los; de verleende rechten worden gewist.", "Détacher le dossier ; ses droits accordés sont effacés.", "Ordner ablösen; die gewährten Rechte werden gelöscht."),
    TipPerms(
        "The rights this app grants itself on the linked folder. Only Read is enforced today.",
        "De rechten die deze app zichzelf geeft op de gekoppelde map. Alleen Lezen wordt nu afgedwongen.", "Les droits que cette application s'accorde sur le dossier lié. Seul Lecture est appliqué aujourd'hui.", "Die Rechte, die sich diese App auf dem verknüpften Ordner gibt. Nur Lesen wird derzeit durchgesetzt."
    ),

    // ---- file tree (right panel) -------------------------------------------
    ToggleFileTree(
        "Show or hide the project file tree",
        "Toon of verberg de bestandsboom van het project", "Afficher ou masquer l'arborescence du projet", "Projektdateibaum ein-/ausblenden"
    ),
    FileTreeRefreshButton("Refresh", "Vernieuwen", "Rafraîchir", "Aktualisieren"),
    TipFileTreeRefresh(
        "Reload the folder listing from disk.",
        "Herlaadt de mapindeling van de schijf.", "Recharger la liste du dossier depuis le disque.", "Ordnerinhalte von der Festplatte neu laden."
    ),
    FileTreeHidden("Hidden files", "Verborgen bestanden", "Fichiers cachés", "Versteckte Dateien"),
    FileTreeNoFolder(
        "Select a project with a linked folder to see its files.",
        "Kies een project met een gekoppelde map om de bestanden te zien.", "Sélectionnez un projet avec un dossier lié pour voir ses fichiers.", "Wählen Sie ein Projekt mit verknüpftem Ordner, um dessen Dateien zu sehen."
    ),
    FileTreeNoRead(
        "Read access is not granted for this project's folder.",
        "Leestoegang is niet verleend voor de map van dit project.", "L'accès en lecture n'est pas accordé au dossier de ce projet.", "Lesezugriff ist für den Ordner dieses Projekts nicht gewährt."
    ),
    FileTreeEmpty("empty folder", "lege map", "dossier vide", "leerer Ordner"),
    CopyPath("Copy path", "Kopieer pad", "Copier le chemin", "Pfad kopieren"),
    FileTreeChangesHeader(
        "changes ({0})", "wijzigingen ({0})", "modifications ({0})", "Änderungen ({0})"
    ),
    FileTreeChangesClean("no changes", "geen wijzigingen", "aucune modification", "keine Änderungen"),
    FileTreeChangesNotGit(
        "not a git repository", "geen git-repository", "n'est pas un dépôt Git", "kein Git-Repository"
    ),
    TipFileTreeChanges(
        "Only the files git sees as new, changed or deleted. Double-click one for its patch; drag the bar above to give this list more or less room.",
        "Alleen de bestanden die git als nieuw, gewijzigd of verwijderd ziet. Dubbelklik voor de patch; sleep de balk erboven om deze lijst meer of minder ruimte te geven.",
        "Seulement les fichiers que Git voit comme nouveaux, modifiés ou supprimés. Double-cliquez pour la patch ; faites glisser la barre du haut pour donner plus ou moins de place à cette liste.",
        "Nur die Dateien, die Git als neu, geändert oder gelöscht sieht. Doppelklick zeigt den Patch; ziehen Sie den Balken darüber, um dieser Liste mehr oder weniger Platz zu geben."
    ),

    // ---- git status in the file tree -----------------------------------
    DiffTitle("Git changes for {0}", "Git-wijzigingen voor {0}", "Modifications Git pour {0}", "Git-Änderungen für {0}"),
    DiffNoGit("This folder is not a git repository.", "Deze map is geen git-repository.", "Ce dossier n'est pas un dépôt Git.", "Dieser Ordner ist kein Git-Repository."),
    DiffNoChanges("{0} has no git changes.", "{0} heeft geen git-wijzigingen.", "{0} n'a aucune modification Git.", "{0} weist keine Git-Änderungen auf."),
    DiffUntracked("{0} is new and untracked, so there is no committed version to compare.", "{0} is nieuw en niet gevolgd, er is dus geen vastgelegde versie om mee te vergelijken.", "{0} est nouveau et non suivi, il n'y a donc pas de version enregistrée à comparer.", "{0} ist neu und nicht verfolgt, daher gibt es keine gespeicherte Version zum Vergleich."),
    FileTitle("File: {0}", "Bestand: {0}", "Fichier : {0}", "Datei: {0}"),
    DiffShowFile("Show file", "Bestand tonen", "Afficher le fichier", "Datei anzeigen"),
    DiffShowPatch("Show changes", "Wijzigingen tonen", "Afficher les modifications", "Änderungen anzeigen"),
    GitTagAdded("added", "toegevoegd", "ajouté", "hinzugefügt"),
    GitTagModified("modified", "gewijzigd", "modifié", "geändert"),
    GitTagDeleted("deleted", "verwijderd", "supprimé", "gelöscht"),
    GitTagUntracked("untracked", "niet gevolgd", "non suivi", "nicht verfolgt"),

    // ---- startup smoke run -------------------------------------------------
    SmokeDataDir("mule smoke · data-dir={0}", "mule smoke · data-dir={0}", "mule smoke · rép-données={0}", "mule smoke · Datenverzeichnis={0}"),
    SmokeModelSet("model     = set to '{0}' and saved", "model     = gezet op '{0}' en opgeslagen", "modèle   = réglé sur '{0}' et enregistré", "Modell    = auf '{0}' gesetzt und gespeichert"),
    SmokeKeyLabel("key       = ", "sleutel   = ", "clé      = ", "Schlüssel = "),
    SmokeSession("session   = {0} with {1} messages", "sessie    = {0} met {1} berichten", "session   = {0} avec {1} messages", "Sitzung   = {0} mit {1} Nachrichten"),
    SmokeModelsFailed("models    = FAILED: {0}", "models    = FAALDE: {0}", "modèles   = ÉCHEC : {0}", "Modelle   = FEHLGESCHLAGEN: {0}"),
    SmokeModels("models    = {0}", "modellen  = {0}", "modèles   = {0}", "Modelle   = {0}"),
}

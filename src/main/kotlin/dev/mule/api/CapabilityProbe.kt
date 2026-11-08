package dev.mule.api

import dev.mule.config.AppConfig
import dev.mule.i18n.Key
import dev.mule.i18n.Texts

/**
 * Establishes what the backend actually DOES rather than what it accepts.
 * Some local servers accept previous_response_id and silently ignore it; the
 * only visible tell is the token count of the follow-up call.
 */
class CapabilityProbe {

    fun run(cfg: AppConfig, onLine: (String) -> Unit): List<String> {
        val out = mutableListOf<String>()
        fun line(s: String) {
            out.add(s)
            onLine(s)
        }

        line("base-url  : ${cfg.baseUrl}")
        line("key       : " + if (cfg.hasKey()) Texts[Key.KeyPresent] else Texts[Key.KeyMissing])

        val models = try {
            ModelsClient.fetch(cfg)
        } catch (e: Exception) {
            line("models    : " + Texts.fmt(Key.FailedPrefix, e.message ?: "?"))
            return out
        }
        line("models    : " + Texts.fmt(Key.ProbeAvailable, models.size))
        val probeModel = cfg.model.ifBlank { models.first().id }
        if (models.none { it.id == cfg.model } && cfg.model.isNotBlank()) {
            line("note      : " + Texts.fmt(Key.ProbeModelNotListed, cfg.model))
        }
        line("probe     : $probeModel")

        val small = cfg.copy(model = probeModel, maxOutputTokens = 400, temperature = 0.0, storeResponses = true)
        val client = ResponsesClient(small)

        try {
            val first = client.turn(
                SYSTEM, emptyList(), PROMPT_1, null,
                onDelta = {}
            )
            line("answer 1  : ${(first.text.take(60)).replace("\n", " ")}")
            line("reasoning1: ${(first.reasoning.take(60)).replace("\n", " ")}")
            line("truncated : ${first.incompleteReason ?: Texts[Key.ProbeTruncationNone]}")
            val id = first.responseId
            if (id == null) {
                line("turn 1    : " + Texts[Key.ProbeNoResponseId])
                return out
            }
            line("turn 1    : id=$id prompt=${first.usage?.prompt} completion=${first.usage?.completion}")
            // The census is the diagnosis: it names the events the backend really
            // sends, and the sample shows the shape behind each name.
            line("events 1  : ${first.events}")
            first.samples.forEach { (k, v) -> line("  wire[$k] $v") }

            val (st, body) = Http(small).get("/responses/$id")
            line("reread    : " + Texts.fmt(Key.ProbeReread, st) +
                (if (st >= 400) " (body: ${body.take(120)})" else ""))

            val second = client.turn(
                SYSTEM,
                emptyList(),
                PROMPT_2,
                id,
                onDelta = {}
            )
            line("turn 2    : ${(second.text.take(40)).replace("\n", " ")}")
            val p1 = first.usage?.prompt
            val p2 = second.usage?.prompt
            line("turn 2    : prompt=${p2} completion=${second.usage?.completion}")
            if (p1 != null && p2 != null) {
                line(
                    if (p2 > p1) "verdict   : " + Texts.fmt(Key.ProbeHonored, p1, p2)
                    else "verdict   : " + Texts.fmt(Key.ProbeIgnored, p1, p2)
                )
            }
        } catch (e: Exception) {
            line(Texts.fmt(Key.ProbeFail, e.message ?: "?"))
        }
        return out
    }

    private companion object {
        /**
         * Model-facing, so always English. The probe asks for a fixed concrete word
         * and then its reverse, so the follow-up can be checked without guessing what
         * the model felt like answering.
         */
        const val SYSTEM = "Answer as briefly as possible."
        const val PROMPT_1 = "Say the word: cucumber"
        const val PROMPT_2 = "Now: say that word backwards."
    }
}

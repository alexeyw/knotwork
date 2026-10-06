package app.knotwork.android.domain.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The supersede fixture (`src/test/resources/memory/supersede_pairs.json`): pairs of a
 * stored memory fact and a newly extracted one, each labelled with what the new fact
 * is and the verdict the supersede judge must give, plus the embedding of every fact
 * as recorded from the app's bundled on-device embedder.
 *
 * The vectors are a recording of the shipped model, so tests that read them run on
 * the geometry the app really has. `record_supersede_vectors.py` next to the file
 * re-records them; the fixture test fails when the bundled model no longer matches
 * the recording.
 */
internal object MemorySupersedeFixture {

    /** What the new fact of a pair is relative to the stored one. */
    enum class PairClass {
        /** The same fact in other words. */
        PARAPHRASE,

        /** The same subject with a changed value; the stored fact no longer holds. */
        CORRECTION,

        /** A different fact that can hold together with the stored one. */
        DISTINCT,

        /** A fact about something else entirely. */
        UNRELATED,
    }

    /**
     * One labelled pair.
     *
     * @property id Stable, human-readable pair id.
     * @property pairClass What the new fact is relative to the stored one.
     * @property verdict The verdict the judge must give for this pair.
     * @property stored Text of the fact already in memory.
     * @property incoming Text of the fact just extracted.
     * @property cosine Cosine similarity of the two recorded vectors.
     */
    data class FixturePair(
        val id: String,
        val pairClass: PairClass,
        val verdict: SupersedeVerdict,
        val stored: String,
        val incoming: String,
        val cosine: Float,
    )

    private val root: JsonObject by lazy {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(RESOURCE)) { "missing $RESOURCE" }
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    /** SHA-256 of the model file the vectors were recorded from. */
    val modelSha256: String by lazy {
        root.getValue("embedder").jsonObject.getValue("modelSha256").jsonPrimitive.content
    }

    /** Dimension of every recorded vector. */
    val dimension: Int by lazy { root.getValue("embedder").jsonObject.getValue("dimension").jsonPrimitive.int }

    /** Every labelled pair, in file order. */
    val pairs: List<FixturePair> by lazy {
        root.getValue("pairs").jsonArray.map { element ->
            val pair = element.jsonObject
            FixturePair(
                id = pair.getValue("id").jsonPrimitive.content,
                pairClass = PairClass.valueOf(pair.getValue("class").jsonPrimitive.content.uppercase()),
                verdict = SupersedeVerdict.valueOf(pair.getValue("verdict").jsonPrimitive.content),
                stored = pair.getValue("stored").jsonPrimitive.content,
                incoming = pair.getValue("incoming").jsonPrimitive.content,
                cosine = pair.getValue("cosine").jsonPrimitive.float,
            )
        }
    }

    private val vectors: Map<String, FloatArray> by lazy {
        root.getValue("vectors").jsonObject.mapValues { (_, values) ->
            values.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
        }
    }

    /**
     * The recorded embedding of [text].
     *
     * @param text A fact text that occurs in [pairs].
     * @return Its vector as the bundled embedder produced it.
     */
    fun vector(text: String): FloatArray = requireNotNull(vectors[text]) { "no recorded vector for '$text'" }

    private const val RESOURCE = "memory/supersede_pairs.json"
}

/**
* ContainerParser.kt
* ============================================================================
* A pure, structural, recursive-descent parser for nested cargo packing
* strings (e.g. "Crate [ Box [ PKG-101 ] , PKG-102 ] ").
*
* DESIGN CONSTRAINTS
* -------------------
*   - No global mutable lists: every function is referentially transparent.
*     Each call receives its input state (the string + a cursor index) as
*     parameters and RETURNS a new, immutable `List<String>` plus the next
*     cursor position. Nothing is accumulated into a shared/mutable
*     collection anywhere in this file.
*   - No regular expressions: tokenizing is done character-by-character via
*     recursion (see `readToken` / `skipWhitespace`).
*   - No `String.split`: the comma/bracket structure is walked manually by
*     the recursive descent functions below.
*
* GRAMMAR (informal)
* -------------------
*   sequence   := element (',' sequence)?   |  ε
*   element    := whitespace* token whitespace* ('[' sequence ']')?
*   token      := any run of characters that is not '[', ']', ',', or
*                 whitespace (this captures both container labels like
*                 "Crate"/"Box" and leaf ids like "PKG-101").
*
* A leaf token is only kept in the output if it starts with "PKG-".
* Container labels ("Crate", "Box", ...) are structural noise and are
* discarded once their bracketed contents have been flattened into the
* result. Empty segments produced by consecutive commas, empty brackets,
* or leading/trailing commas simply contribute an empty list and vanish.
*
* ============================================================================
* JVM CALL STACK WALKTHROUGH
* ============================================================================
* Every one of the helper functions below (`skipWhitespace`, `readToken`,
* `parseSequence`, `parseElement`) is a plain, non-tail-recursive JVM method.
* Kotlin does NOT automatically apply tail-call elimination unless a
* function is marked `tailrec` AND its only recursive call is in tail
* position with no further work after it. None of these functions qualify
* for that (they combine the recursive result with local data afterwards,
* e.g. `s[i] + rest` or `idsElem + rest`), so each call genuinely pushes a
* new stack frame onto the JVM thread stack.
* Key properties of this call pattern:
*   1. STACK GROWTH tracks NESTING DEPTH of brackets, not the total number
*      of packages. `parseSequence`/`parseElement` recurse into each other
*      once per bracket level (Crate -> Box -> ... ), so stack depth is
*      O(max nesting depth), while `readToken`/`skipWhitespace` add a
*      shallow, transient burst of frames (O(token length) or O(run of
*      whitespace)) that are pushed and immediately popped before parsing
*      continues — they never coexist with deeper bracket levels.
*   2. Every frame that is pushed for a *successful* parse eventually pops
*      cleanly, unwinding in the reverse order it was created (LIFO), which
*      is exactly how the JVM's operand/frame stack works for method calls.
*   3. If the input is malformed (e.g. a missing ']'), a
*      `MalformedCargoStructureException` is thrown from deep inside the
*      stack (from `parseElement`). The JVM unwinds every pending frame
*      above the try/catch (or the top-level caller) automatically — we
*      never manually pop frames or reach past the end of the string, which
*      is what avoids a raw `StringIndexOutOfBoundsException`.
*   4. Because there are no shared mutable structures, each frame's local
*      `List<String>` is independent; combining results on the way back UP
*      the stack (`idsElem + rest`) is the only "merge" operation, so the
*      function is safe to reason about frame-by-frame.
* ============================================================================
*/

/**
 * Thrown when the cargo string is structurally invalid — most notably
 * unbalanced brackets (a '[' with no matching ']', a stray ']', or trailing
 * unparsed content after the top-level structure closes).
 */
class MalformedCargoStructureException(message: String) : RuntimeException(message)

/**
 * Public entry point. Parses [input] and returns a flattened, immutable
 * list of every package id (tokens beginning with "PKG-") found anywhere
 * in the nested structure, in left-to-right order.
 *
 * @throws MalformedCargoStructureException if brackets are unbalanced or
 *         unexpected trailing content remains after parsing.
 */
fun parseCargoString(input: String): List<String> {
    val (ids, endIndex) = parseSequence(input, 0)
    val trailing = skipWhitespace(input, endIndex)
    if (trailing < input.length) {
        throw MalformedCargoStructureException(
            "Unexpected character '${input[trailing]}' at position $trailing " +
                    "- likely an unmatched or stray closing bracket."
        )
    }
    return ids
}

/**
 * Recursively parses a comma-separated sequence of elements starting at
 * index [i], stopping when it encounters the string's end or an
 * unconsumed ']' (which belongs to the enclosing container and is left
 * for the caller to consume).
 *
 * Handles empty segments (leading commas, trailing commas, or consecutive
 * commas) by simply contributing an empty list for that segment instead
 * of throwing or inserting blank strings.
 *
 * @return a pair of (flattened package ids found in this sequence, index
 *         just past the sequence — pointing at ']' or end-of-string).
 */
private fun parseSequence(s: String, i: Int): Pair<List<String>, Int> {
    val j = skipWhitespace(s, i)

    if (j >= s.length) {
        // Base case: ran out of input. Let the caller (parseElement, or the
        // top-level parseCargoString) decide whether that is acceptable.
        return emptyList<String>() to j
    }

    return when (s[j]) {
        ']' -> {
            // Base case: this sequence is empty (e.g. "Box[]") or has just
            // finished; leave the ']' unconsumed for parseElement to close.
            emptyList<String>() to j
        }
        ',' -> {
            // Base case turned recursive step: an empty element between
            // commas (or a leading comma). Skip it and keep going.
            val (rest, idx) = parseSequence(s, j + 1)
            rest to idx
        }
        else -> {
            // Recursive step: parse one real element, then optionally
            // continue past a comma into the rest of the sequence.
            val (idsElem, idxAfterElement) = parseElement(s, j)
            val k = skipWhitespace(s, idxAfterElement)
            if (k < s.length && s[k] == ',') {
                val (rest, idxAfterRest) = parseSequence(s, k + 1)
                (idsElem + rest) to idxAfterRest
            } else {
                idsElem to k
            }
        }
    }
}

/**
 * Parses a single structural element starting at index [i]:
 *   - reads a bare token (a container label or a leaf id),
 *   - if the token is immediately followed by '[', treats it as a
 *     container: recursively parses its contents as a sequence, then
 *     requires and consumes the matching ']'. The container's own label
 *     (e.g. "Crate", "Box") is structural noise and is discarded — only
 *     the flattened contents are returned.
 *   - otherwise treats it as a leaf token, keeping it only if it starts
 *     with the "PKG-" prefix.
 *
 * @return a pair of (package ids contributed by this single element,
 *         index just past this element).
 * @throws MalformedCargoStructureException on end-of-input where an
 *         element was expected, or a missing closing ']'.
 */
private fun parseElement(s: String, i: Int): Pair<List<String>, Int> {
    val start = skipWhitespace(s, i)
    if (start >= s.length) {
        throw MalformedCargoStructureException(
            "Unexpected end of input while expecting an element at position $start."
        )
    }

    val (token, afterToken) = readToken(s, start)
    val afterTokenWs = skipWhitespace(s, afterToken)

    return if (afterTokenWs < s.length && s[afterTokenWs] == '[') {
        // Container element: recurse into its bracketed contents.
        val (innerIds, afterInner) = parseSequence(s, afterTokenWs + 1)
        val closeIdx = skipWhitespace(s, afterInner)
        if (closeIdx >= s.length || s[closeIdx] != ']') {
            throw MalformedCargoStructureException(
                "Missing closing ']' for container '${token.ifEmpty { "<anonymous>" }}' " +
                        "opened at position $afterTokenWs."
            )
        }
        innerIds to (closeIdx + 1)
    } else {
        // Leaf element: only package ids survive; structural words vanish.
        val ids = if (token.startsWith("PKG-")) listOf(token) else emptyList()
        ids to afterToken
    }
}

/**
 * Recursively reads a maximal run of "token" characters (anything other
 * than '[', ']', ',', or whitespace) starting at index [i], with no use
 * of regular expressions or String.split. Builds the token by prepending
 * one character per stack frame and letting the recursion unwind.
 *
 * @return a pair of (the token text, index just past the token).
 */
private fun readToken(s: String, i: Int): Pair<String, Int> {
    if (i >= s.length || isDelimiter(s[i])) {
        return "" to i
    }
    val (rest, idx) = readToken(s, i + 1)
    return (s[i] + rest) to idx
}

/**
 * Recursively advances [i] past any run of whitespace characters,
 * absorbing arbitrary/irregular padding around tokens and brackets.
 *
 * @return the index of the first non-whitespace character at or after [i]
 *         (or `s.length` if none remain).
 */
private fun skipWhitespace(s: String, i: Int): Int =
    if (i < s.length && s[i].isWhitespace()) skipWhitespace(s, i + 1) else i

/** Characters that terminate a bare token. */
private fun isDelimiter(c: Char): Boolean =
    c == '[' || c == ']' || c == ',' || c.isWhitespace()

/**
 * ----------------------------------------------------------------------
 * Small manual smoke test (safe to remove) demonstrating each edge case
 * described in the requirements.
 * ----------------------------------------------------------------------
 */
fun main() {
    val cases = listOf(
        " Crate [ Box [ PKG-101 ] , PKG-102 ] ",
        "Crate[Box[],PKG-202]",
        "Box[PKG-101,,PKG-102]",
        "Crate[Box[PKG-1],Box[PKG-2,PKG-3]]"
    )
    for (case in cases) {
        println("\"$case\" -> ${parseCargoString(case)}")
    }

    val malformed = listOf(
        "Box[PKG-101",
        "PKG-101]"
    )
    for (case in malformed) {
        try {
            parseCargoString(case)
        } catch (e: MalformedCargoStructureException) {
            println("\"$case\" -> rejected: ${e.message}")
        }
    }
}

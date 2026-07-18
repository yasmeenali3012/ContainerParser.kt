package org.example.containerparser

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
 *     cursor position.
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
 *                 whitespace (captures "Crate"/"Box" and "PKG-101").
 *
 * Only tokens starting with "PKG-" are kept. Container labels like
 * "Crate"/"Box" are structural noise and discarded.
 *
 * ============================================================================
 * JVM CALL STACK WALKTHROUGH - شرح عمل المكدس
 * ============================================================================
 * Every helper function below (`skipWhitespace`, `readToken`,
 * `parseSequence`, `parseElement`) is a plain, non-tail-recursive JVM method.
 * Kotlin does NOT automatically apply tail-call elimination unless a
 * function is marked `tailrec` AND its only recursive call is in tail
 * position with no further work after it. None of these functions qualify
 * for that (they combine the recursive result with local data afterwards,
 * e.g. `s[i] + rest` or `ids + rest`), so each call genuinely pushes a
 * new stack frame onto the JVM thread stack.
 *

 *
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
 *      `MalformedContainerException` is thrown from deep inside the
 *      stack (from `parseElement`). The JVM unwinds every pending frame
 *      above the try/catch (or the top-level caller) automatically — we
 *      never manually pop frames or reach past the end of the string, which
 *      is what avoids a raw `StringIndexOutOfBoundsException`.
 *   4. Because there are no shared mutable structures, each frame's local
 *      `List<String>` is independent; combining results on the way back UP
 *      the stack (`ids + rest`) is the only "merge" operation, so the
 *      function is safe to reason about frame-by-frame.
 * ============================================================================
 */

/**
 * Thrown when the cargo string is structurally invalid — most notably
 * unbalanced brackets (a '[' with no matching ']', a stray ']', or trailing
 * unparsed content after the top-level structure closes).
 */
class MalformedContainerException(message: String) : Exception(message)

/**
 * Public entry point. Parses [input] and returns a flattened, immutable
 * list of every package id (tokens beginning with "PKG-") found anywhere
 * in the nested structure, in left-to-right order.
 *
 * @throws MalformedContainerException if brackets are unbalanced or
 *         unexpected trailing content remains after parsing.
 */
fun parseCargoPackages(input: String): List<String> {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return emptyList()

    val (result, endIndex) = parseSequence(trimmed, 0)
    val trailing = skipWhitespace(trimmed, endIndex)

    if (trailing < trimmed.length) {
        throw MalformedContainerException(
            "Unexpected character '${trimmed[trailing]}' at position $trailing"
        )
    }
    return result
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
        // Base case: ran out of input
        return Pair(emptyList(), j)
    }

    return when (s[j]) {
        ']' -> {
            // Base case: this sequence is empty (e.g. "Box[]")
            // Leave the ']' unconsumed for parseElement to close
            Pair(emptyList(), j)
        }
        ',' -> {
            // Base case turned recursive step: an empty element between
            // commas (or a leading comma). Skip it and keep going.
            parseSequence(s, j + 1)
        }
        else -> {
            // Recursive step: parse one real element, then optionally
            // continue past a comma into the rest of the sequence.
            val (ids, afterElement) = parseElement(s, j)
            val k = skipWhitespace(s, afterElement)

            if (k < s.length && s[k] == ',') {
                val (rest, afterRest) = parseSequence(s, k + 1)
                Pair(ids + rest, afterRest)
            } else {
                Pair(ids, k)
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
 * @throws MalformedContainerException on end-of-input where an
 *         element was expected, or a missing closing ']'.
 */
private fun parseElement(s: String, i: Int): Pair<List<String>, Int> {
    val start = skipWhitespace(s, i)

    if (start >= s.length) {
        throw MalformedContainerException(
            "Unexpected end of input while expecting an element at position $start."
        )
    }

    val (token, afterToken) = readToken(s, start)
    val afterWs = skipWhitespace(s, afterToken)

    return if (afterWs < s.length && s[afterWs] == '[') {
        // Container element: recurse into its bracketed contents.
        val (innerIds, afterInner) = parseSequence(s, afterWs + 1)
        val closeIdx = skipWhitespace(s, afterInner)

        if (closeIdx >= s.length || s[closeIdx] != ']') {
            throw MalformedContainerException(
                "Missing closing ']' for container '$token' opened at position $afterWs."
            )
        }
        // Return the flattened contents (container label is discarded)
        Pair(innerIds, closeIdx + 1)
    } else {
        // Leaf element: only package ids survive; structural words vanish.
        val ids = if (token.startsWith("PKG-")) listOf(token) else emptyList()
        Pair(ids, afterToken)
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
        return Pair("", i)
    }
    val (rest, idx) = readToken(s, i + 1)
    return Pair(s[i] + rest, idx)
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

/**
 * Characters that terminate a bare token:
 * - '[' : start of a container
 * - ']' : end of a container
 * - ',' : separator between elements
 * - whitespace : padding around tokens
 */
private fun isDelimiter(c: Char): Boolean =
    c == '[' || c == ']' || c == ',' || c.isWhitespace()

/**
 * ----------------------------------------------------------------------
 * Small manual smoke test demonstrating each edge case
 * described in the requirements.
 * ----------------------------------------------------------------------
 */
fun main() {
    println("Container Parser - Test Results")
    println("=".repeat(60))

    val testCases = listOf(
        "Crate[Box[PKG-101], PKG-102]" to listOf("PKG-101", "PKG-102"),
        " Crate [ Box [ PKG-101 ] , PKG-102 ] " to listOf("PKG-101", "PKG-102"),
        "Crate[Box[],PKG-202]" to listOf("PKG-202"),
        "Box[PKG-101,,PKG-102]" to listOf("PKG-101", "PKG-102"),
        "Crate[PKG-101, Box[PKG-102, PKG-103]]" to listOf("PKG-101", "PKG-102", "PKG-103"),
        "PKG-001" to listOf("PKG-001"),
        "Crate[]" to emptyList(),
        "" to emptyList()
    )

    var passed = 0
    var failed = 0

    testCases.forEachIndexed { index, (input, expected) ->
        try {
            val result = parseCargoPackages(input)
            val status = if (result == expected) {
                passed++
                "PASS"
            } else {
                failed++
                "FAIL"
            }
            println("${index + 1}. $status")
            println("   Input: '$input'")
            println("   Result: $result")
            println("   Expected: $expected")
        } catch (e: MalformedContainerException) {
            failed++
            println("${index + 1}. FAIL")
            println("   Input: '$input'")
            println("   Error: ${e.message}")
        }
        println()
    }

    println("=".repeat(60))
    println("Exception Cases (Should throw MalformedContainerException):")
    println("=".repeat(60))

    val exceptionTestCases = listOf(
        "Crate[PKG-101",
        "Crate[PKG-101]]",
        "Crate[PKG-101,"
    )

    var exceptionPassed = 0
    var exceptionFailed = 0

    exceptionTestCases.forEachIndexed { index, input ->
        try {
            parseCargoPackages(input)
            exceptionFailed++
            println("${index + 1}. FAIL - Should have thrown exception")
            println("   Input: '$input'")
        } catch (e: MalformedContainerException) {
            exceptionPassed++
            println("${index + 1}. PASS - Correctly threw exception")
            println("   Input: '$input'")
            println("   Exception: ${e.message}")
        }
        println()
    }

    println("=".repeat(60))
    println("Summary:")
    println("   Test Cases: ${passed + failed} total, $passed passed, $failed failed")
    println("   Exception Cases: ${exceptionPassed + exceptionFailed} total, $exceptionPassed passed, $exceptionFailed failed")
    println("=".repeat(60))
}

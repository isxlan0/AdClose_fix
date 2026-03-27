package com.close.hook.ads.rule

import java.util.ArrayDeque

internal class KeywordAutomaton(
    patterns: List<String>
) {

    private data class Node(
        val transitions: MutableMap<Char, Int> = HashMap(),
        var failure: Int = 0,
        val outputs: MutableList<String> = ArrayList()
    )

    private val nodes = mutableListOf(Node())

    init {
        patterns.filter { it.isNotEmpty() }
            .forEach(::insert)
        buildFailures()
    }

    fun findLongestMatch(text: String): String? {
        var state = 0
        var bestMatch: String? = null

        for (char in text) {
            while (state != 0 && nodes[state].transitions[char] == null) {
                state = nodes[state].failure
            }
            state = nodes[state].transitions[char] ?: 0

            nodes[state].outputs.forEach { candidate ->
                if (bestMatch == null || candidate.length > bestMatch!!.length) {
                    bestMatch = candidate
                }
            }
        }

        return bestMatch
    }

    private fun insert(pattern: String) {
        var current = 0
        pattern.forEach { char ->
            val next = nodes[current].transitions[char]
                ?: nodes.size.also {
                    nodes[current].transitions[char] = it
                    nodes.add(Node())
                }
            current = next
        }
        nodes[current].outputs.add(pattern)
    }

    private fun buildFailures() {
        val queue = ArrayDeque<Int>()

        nodes[0].transitions.values.forEach { child ->
            nodes[child].failure = 0
            queue.add(child)
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            nodes[current].transitions.forEach { (char, next) ->
                queue.add(next)

                var failure = nodes[current].failure
                while (failure != 0 && nodes[failure].transitions[char] == null) {
                    failure = nodes[failure].failure
                }

                val fallback = nodes[failure].transitions[char] ?: 0
                nodes[next].failure = fallback
                nodes[next].outputs.addAll(nodes[fallback].outputs)
            }
        }
    }
}

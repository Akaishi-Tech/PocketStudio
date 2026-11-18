package com.hcgstudio.pocketstudio

/** Deliberately handles straight-line Kotlin locals. Ambiguous bindings require a language service. */
internal class KotlinLocalRefactor(private val source: String, private val from: Int, private val to: Int) {
    private data class Token(val word: String, val start: Int, val end: Int, val scope: Int, val string: Boolean)
    private val parents = mutableListOf(-1)
    private val ends = mutableMapOf<Int, Int>()
    private val tokens = lex()
    private val namePattern = Regex("[\\p{L}_][\\p{L}\\p{N}_]*")
    private val keywords = "as break class continue do else false for fun if in interface is null object package return super this throw true try typealias typeof val var when while".split(' ')

    private fun lex(): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        var scope = 0
        while (i < source.length) {
            val start = i
            val c = source[i++]
            if (c.isWhitespace()) continue
            if (source.startsWith("//", start)) { i = source.indexOf('\n', i).let { if (it < 0) source.length else it }; continue }
            if (source.startsWith("/*", start)) {
                var depth = 1
                i++
                while (depth > 0 && i < source.length) when {
                    source.startsWith("/*", i) -> { depth++; i += 2 }
                    source.startsWith("*/", i) -> { depth--; i += 2 }
                    else -> i++
                }
                require(depth == 0) { uiString(R.string.unterminated_block_comment) }
                continue
            }
            var string = false
            when {
                c == '"' || c == '\'' || c == '`' -> {
                    val triple = c == '"' && source.startsWith("\"\"\"", start)
                    val quote = if (triple) "\"\"\"" else c.toString()
                    if (triple) i += 2
                    while (i < source.length && !source.startsWith(quote, i)) {
                        if (!triple && c != '`' && source[i] == '\\') i++
                        i++
                    }
                    require(i < source.length) { uiString(R.string.unterminated_string_or_identifier) }
                    i += quote.length
                    string = true
                }
                Character.isJavaIdentifierPart(c) -> while (i < source.length && Character.isJavaIdentifierPart(source[i])) i++
                c == '}' -> { require(scope != 0) { uiString(R.string.unmatched_braces) }; ends[scope] = start; scope = parents[scope] }
            }
            out += Token(source.substring(start, i), start, i, scope, string)
            if (c == '{') { parents.add(scope); scope = parents.lastIndex }
        }
        require(scope == 0) { uiString(R.string.unmatched_braces) }
        return out
    }

    private fun inScope(inner: Int, outer: Int): Boolean {
        var id = inner
        while (id >= 0) {
            if (id == outer) return true
            id = parents[id]
        }
        return false
    }

    private fun checkName(name: String) {
        require(namePattern.matches(name) && name !in keywords && name != "_") { uiString(R.string.enter_a_valid_kotlin_variable_name) }
        require(tokens.none { it.word == name || it.word == "`" + name + "`" }) { uiString(R.string.this_name_is_already_in_use_in_the) }
    }

    fun run(kind: String, name: String): String {
        if (kind == "variable") {
            checkName(name)
            val expression = source.substring(from, to).trim()
            require(expression.isNotEmpty() && '\n' !in expression) { uiString(R.string.select_a_complete_initializer_or_return_expression_on) }
            val line = source.lastIndexOf('\n', from - 1) + 1
            val end = source.indexOf('\n', to).let { if (it < 0) source.length else it }
            val prefix = source.substring(line, from)
            require(prefix.trim().let { it == "return" || it.matches(Regex("""(?:val|var)\s+[\w]+(?:\s*:\s*[\w.<>?]+)?\s*=""")) }
                    && source.substring(to, end).trim().let { it.isEmpty() || it == ";" }) { uiString(R.string.select_a_complete_local_variable_initializer_or_return) }
            val owner = tokens.firstOrNull { it.start >= line }?.scope ?: 0
            require(owner != 0) { uiString(R.string.extract_the_variable_inside_a_function_body) }
            val indent = prefix.takeWhile { it == ' ' || it == '\t' }
            val newline = if (source.contains("\r\n")) "\r\n" else "\n"
            return source.replaceRange(from, to, name).replaceRange(line, line, indent + "val " + name + " = " + expression + newline)
        }
        val index = tokens.indexOfFirst { from >= it.start && to <= it.end && from < it.end && namePattern.matches(it.word) }
            .let { if (it >= 0) it else tokens.indexOfFirst { token -> from == token.end && namePattern.matches(token.word) } }
        require(index >= 0) { uiString(R.string.place_the_cursor_on_a_local_variable) }
        val nameAtCursor = tokens[index].word
        val declarations = tokens.indices.filter { i ->
            tokens[i].word == nameAtCursor && tokens.getOrNull(i - 1)?.word in setOf("val", "var") &&
            tokens[i].start <= tokens[index].start && inScope(tokens[index].scope, tokens[i].scope)
        }
        require(declarations.size == 1) { uiString(R.string.a_unique_local_val_or_var_declaration_is) }
        val declaration = declarations.single()
        val target = tokens[declaration]
        require(target.scope != 0) { uiString(R.string.select_a_local_declaration_inside_a_function) }
        val opening = tokens.indexOfLast { it.word == "{" && it.start < target.start && it.scope == parents[target.scope] }
        val header = tokens.take(opening).asReversed().takeWhile { it.word !in setOf("{", "}", ";") }
        require(header.any { it.word == "fun" }) { uiString(R.string.select_a_local_variable_declared_directly_in_a) }
        val bound = ends.getValue(target.scope)
        val refs = mutableListOf<Int>()
        for (i in declaration until tokens.size) {
            val t = tokens[i]
            if (t.start >= bound) break
            require(!t.string || (!t.word.contains('$') && t.word != "`" + nameAtCursor + "`")) { uiString(R.string.a_template_or_backticked_reference_makes_the_binding) }
            if (t.word != nameAtCursor || tokens.getOrNull(i - 1)?.word in setOf(".", "@")) continue
            require(t.scope == target.scope && tokens.getOrNull(i + 1)?.word != ":") { uiString(R.string.there_is_a_nested_scope_or_a_parameter) }
            require(i == declaration || tokens.getOrNull(i + 1)?.word != "=") { uiString(R.string.contains_an_assignment_or_named_argument_with_the) }
            refs += i
        }
        require(index in refs) { uiString(R.string.this_reference_points_to_a_different_declaration) }
        if (kind == "rename") {
            checkName(name)
            var result = source
            for (i in refs.reversed()) result = result.replaceRange(tokens[i].start, tokens[i].end, name)
            return result
        }
        require(kind == "inline" && tokens[declaration - 1].word == "val") { uiString(R.string.select_a_local_val_with_a_literal_initializer) }
        val start = tokens[declaration - 1].start
        val eq = tokens.getOrNull(declaration + 1)
        val literal = tokens.getOrNull(declaration + 2)
        require(eq?.word == "=" && literal != null && (literal.word.matches(Regex("""(?:true|false|[0-9]+[Ll]?)""")) ||
                (literal.string && !literal.word.startsWith("`") && '$' !in literal.word))) { uiString(R.string.inlining_requires_a_val_whose_type_is_inferred) }
        val end = source.indexOf('\n', literal.end).let { if (it < 0) source.length else it }
        require(source.substring(literal.end, end).trim().let { it.isEmpty() || it == ";" }) { uiString(R.string.the_initializer_must_be_a_single_literal) }
        var result = source
        for (i in refs.drop(1).asReversed()) result = result.replaceRange(tokens[i].start, tokens[i].end, literal.word)
        return result.removeRange(start, end)
    }
}

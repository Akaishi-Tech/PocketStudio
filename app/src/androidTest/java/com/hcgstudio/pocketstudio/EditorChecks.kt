package com.hcgstudio.pocketstudio

import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Regression checks for editing boundaries and source binding; runs on ART with the shipped parser. */
object EditorChecks {
    private fun agentContextChecks() {
        check(!editorContext(null).getBoolean("active"))
        val node = Project.Node(Uri.EMPTY, "Sample.kt", "src/Sample.kt", false)
        val doc = OpenFile(node, "saved", "one\r\ntwo\r\nthree") {}
        doc.editor.select(8, 5)
        val context = editorContext(doc)
        check(context.getString("path") == node.path && context.getBoolean("dirty"))
        check(context.getJSONObject("cursor").getInt("line") == 2)
        check(context.getJSONObject("cursor").getInt("column") == 1)
        val selection = context.getJSONObject("selection")
        check(selection.getString("text") == "two" && !selection.getBoolean("truncated"))
        check(selection.getJSONObject("start").getInt("column") == 1)
        check(selection.getJSONObject("endExclusive").getInt("column") == 4)
        check(context.getJSONObject("context").getString("text") == doc.editor.text)

        val longLine = "a".repeat(4095) + "😀" + "b".repeat(8192)
        val longDoc = OpenFile(node, longLine, longLine) {}
        longDoc.editor.select(0, longLine.length)
        val bounds = editorContext(longDoc)
        check(!bounds.getBoolean("dirty"))
        check(bounds.getJSONObject("selection").getBoolean("truncated"))
        check(bounds.getJSONObject("selection").getString("text") == "a".repeat(4095))
        check(bounds.getJSONObject("context").getString("text") == "b".repeat(2048))
        longDoc.editor.select(4096 + 2048)
        check(editorContext(longDoc).getJSONObject("context").getString("text").startsWith("😀"))

        val lines = (1..20).joinToString("\n")
        val manyLines = OpenFile(node, lines, lines) {}
        manyLines.editor.go(12, 2)
        val snippet = editorContext(manyLines).getJSONObject("context")
        check(snippet.getJSONObject("start").getInt("line") == 7)
        check(snippet.getString("text") == (7..17).joinToString("\n", postfix = "\n"))
        val empty = editorContext(OpenFile(node, "", "") {})
        check(empty.getBoolean("active") && empty.getInt("lineCount") == 1)
        check(empty.getJSONObject("cursor").getInt("column") == 1)
        check(empty.getJSONObject("selection").getString("text").isEmpty())
    }

    private fun settingsChecks() {
        val tabs = EditorTabStops("\ta\tb\nxy\t\t", 4)
        check(tabs.display == "    a   b\nxy      ")
        for (index in 0..tabs.source.length) {
            check(tabs.transformedToOriginal(tabs.originalToTransformed(index)) == index)
        }
        check(tabs.transformedToOriginal(2) == 0 && tabs.transformedToOriginal(6) == 2)

        val settings = StudioSettings(indentSize = 2)
        val state = StudioEditorState("  fun f() {}\r\n", "Test.kt", 11)
        fun type(ch: String, options: StudioSettings = settings) {
            val next = state.text.replaceRange(state.start, state.end, ch)
            state.type(TextFieldValue(next, TextRange(state.start + ch.length)), options)
        }
        type("\n")
        check(state.text == "  fun f() {\r\n    \r\n  }\r\n" && state.line == 2 && state.column == 5)
        state.undo()
        check(state.text == "  fun f() {}\r\n")
        type("\n", settings.copy(autoIndent = false))
        check(state.text == "  fun f() {\r\n}\r\n")
        state.undo()
        state.select(0, state.text.length)
        type("x")
        type("(")
        check(state.text == "x()" && state.cursor == 2)
        type(")")
        check(state.text == "x()" && state.cursor == 3)
        type("\"", settings.copy(pairQuotes = false))
        check(state.text == "x()\"")
        type("[", settings.copy(pairBrackets = false))
        check(state.text.endsWith("["))
        type("paste{")
        check(state.text.endsWith("paste{"))
        val composing = state.text + "("
        state.type(TextFieldValue(composing, TextRange(composing.length),
            TextRange(composing.length - 1, composing.length)), settings)
        check(state.text == composing)
        val indent = StudioEditorState("a\nb", "Test.kt")
        indent.select(0, 3)
        indent.indent(false, settings)
        check(indent.text == "  a\n  b")
        indent.indent(true, settings)
        check(indent.text == "a\nb")
        indent.indent(false, settings.copy(useTabs = true))
        check(indent.text == "\ta\n\tb")
        val java = JavaRefactor("class X{int x;}\r\n", 0, 0)
        check(java.format(2, false).contains("\r\n  int x;"))
        check(java.format(4, true).contains("\r\n\tint x;"))
    }

    @JvmStatic fun run() {
        agentContextChecks()
        settingsChecks()
        val edits = mutableListOf<String>()
        val state = StudioEditorState("one\r\ntwo\r\nthree", "Sample.kt", onTextChanged = edits::add)
        state.go(2, 2)
        check(state.line == 2 && state.column == 2)
        state.moveLines(false)
        check(state.text == "two\r\none\r\nthree" && state.line == 1 && state.column == 2)
        state.undo()
        check(state.text == "one\r\ntwo\r\nthree" && state.line == 2 && state.column == 2)
        state.redo()
        check(state.text == "two\r\none\r\nthree")
        state.go(3)
        state.deleteLines()
        check(state.text == "two\r\none")
        state.undo()
        state.go(3, 6)
        state.duplicate()
        check(state.text.endsWith("three\r\nthree"))
        state.undo()
        state.go(1)
        state.select(0, 5)
        state.commentLines()
        check(state.text == "// two\r\none\r\nthree")
        check(edits.last() == state.text)
        val search = StudioEditorState("val a = ax + A + a", "S.kt")
        search.find = "a"
        search.wholeWords = true
        search.replacement = "$1"
        check(search.replaceAll() == 3 && search.text == "val $1 = ax + $1 + $1")
        search.undo()
        check(search.match(false) && search.selected == "a" && search.end == search.text.length)
        search.replacement = ""
        search.replaceMatch()
        check(search.text == "val a = ax + A + ")
        check(search.selected == "a")
        val code = "class T { int x; int f(int a) { int x = 3; /* x */ return x + this.x + a; } }"
        val x = code.indexOf("x =")
        val renamed = JavaRefactor(code, x, x).run("rename", "number", "")
        check(renamed.contains("int x;") && renamed.contains("/* x */ return number + this.x + a"))
        check(JavaRefactor(code, x, x).run("inline", "", "").contains("return ((int) 3) + this.x"))
        val method = "class F { private int f(int a, int b) { return a + b; } int g() { return f(2, 4); } }"
        val selected = method.indexOf("a + b")
        val after = JavaRefactor(method, selected, selected + 5).run("method", "sum", "int")
        check(after.contains("return sum(a, b);") && after.contains("private int sum(int a, int b)"))
        val signature = JavaRefactor(method, method.indexOf("f(int"), method.indexOf("f(int")).run("signature", "b,a", "")
        check(signature.contains("f(int b, int a)") && signature.contains("f(4, 2)"))
        val ambiguous = "class X { void f(){ int x=1; Runnable r = new Runnable(){ int x; public void run(){ x++; } }; } }"
        check(runCatching { JavaRefactor(ambiguous, ambiguous.indexOf("x=1"), ambiguous.indexOf("x=1")).run("rename", "n", "") }.isFailure)
        val sideEffect = "class F{private int f(int a,int b){return a+b;} int g(){int i=0;return f(i++,i++);}}"
        check(runCatching { JavaRefactor(sideEffect, sideEffect.indexOf("f(int"), sideEffect.indexOf("f(int")).run("signature", "b,a", "") }.isFailure)
        val kotlin = "fun sum() {\n    val n = 1\n    // n\n    println(\"n\")\n    println(n)\n}"
        val n = kotlin.indexOf("n =")
        val kr = KotlinLocalRefactor(kotlin, n, n).run("rename", "count")
        check(kr.contains("val count = 1") && kr.contains("println(count)") && kr.contains("// n") && kr.contains("println(\"n\")"))
        check(KotlinLocalRefactor(kotlin, n, n).run("inline", "").contains("println(1)"))
    }
}

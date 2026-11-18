package com.hcgstudio.pocketstudio

/** Regression checks for editing boundaries and source binding; runs on ART with the shipped parser. */
object EditorChecks {
    @JvmStatic fun run() {
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

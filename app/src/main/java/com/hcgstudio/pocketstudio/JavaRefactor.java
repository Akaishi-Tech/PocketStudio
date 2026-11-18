package com.hcgstudio.pocketstudio;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;
import java.util.stream.Collectors;

/** Plans source-range edits from a parsed compilation unit. All offsets refer to the original file. */
final class JavaRefactor {
    static final class Patch {
        final int from, to;
        final String replacement;
        Patch(int from, int to, String replacement) {
            this.from = from; this.to = to; this.replacement = replacement;
        }
    }

    private final String source;
    private final CompilationUnit unit;
    private final int[] lines;
    private final ParserConfiguration configuration;
    private final int from, to;
    private final List<Patch> patches = new ArrayList<>();

    JavaRefactor(String source, int from, int to) {
        this.source = source;
        while (from < to && Character.isWhitespace(source.charAt(from))) from++;
        while (from < to && Character.isWhitespace(source.charAt(to - 1))) to--;
        this.from = from; this.to = to;
        configuration = new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE).setTabSize(1);
        var parsed = new JavaParser(configuration).parse(source);
        require(parsed.isSuccessful(), uiString(R.string.fix_java_syntax_errors_in_this_file_first));
        unit = parsed.getResult().get();
        lines = new int[1 + (int) source.chars().filter(c -> c == '\n').count()];
        for (int i = 0, line = 1; i < source.length(); i++) if (source.charAt(i) == '\n') lines[line++] = i + 1;
    }

    static void require(boolean ok, String reason) { if (!ok) throw new IllegalArgumentException(reason); }
    private int offset(Position p) { return lines[p.line - 1] + p.column - 1; }
    private int start(Node n) { return offset(n.getBegin().get()); }
    private int end(Node n) { return 1 + offset(n.getEnd().get()); }
    private String raw(Node n) { return source.substring(start(n), end(n)); }
    private String indent(int at) {
        int first = source.lastIndexOf('\n', at - 1) + 1;
        int i = first;
        while (i < at && (source.charAt(i) == ' ' || source.charAt(i) == '\t')) i++;
        return source.substring(first, i);
    }
    private String eol() { return source.contains("\r\n") ? "\r\n" : "\n"; }
    private void patch(Node n, String value) { patches.add(new Patch(start(n), end(n), value)); }
    private boolean contains(Node n) { return start(n) <= from && to <= end(n); }
    private <T extends Node> T enclosing(Class<T> type, String hint) {
        return unit.findAll(type).stream().filter(this::contains)
            .min(Comparator.comparingInt(n -> end(n) - start(n))).orElseThrow(() -> new IllegalArgumentException(hint));
    }
    private void fresh(String name, Node scope) {
        require(new JavaParser().parseSimpleName(name).isSuccessful()
                && name.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*")
                && !Arrays.asList("var", "yield", "record", "_").contains(name), uiString(R.string.enter_a_valid_java_name));
        require(scope.findAll(SimpleName.class).stream().noneMatch(s -> s.asString().equals(name)), uiString(R.string.that_name_is_already_in_use_in_this));
    }
    private Expression expression() {
        return unit.findAll(Expression.class).stream().filter(n -> start(n) == from && end(n) == to)
            .findFirst().orElseThrow(() -> new IllegalArgumentException(uiString(R.string.select_a_complete_java_expression)));
    }
    private MethodDeclaration method() { return enclosing(MethodDeclaration.class, uiString(R.string.place_the_cursor_inside_a_method)); }

    // Only use local variables declared in a block, and ordinary method parameters. Reject
    // ambiguous scopes (shadowed declarations, anonymous or local classes) instead of guessing.
    private static final class Local {
        final Node decl, scope;
        final SimpleName name;
        final String type;
        Local(Node decl, Node scope, SimpleName name, String type) {
            this.decl = decl; this.scope = scope; this.name = name; this.type = type;
        }
    }
    private Local local(MethodDeclaration method, String name, int at) {
        List<Local> options = new ArrayList<>();
        for (Parameter p : method.findAll(Parameter.class)) {
            if (p.getNameAsString().equals(name)) {
                require(p.getParentNode().orElse(null) == method, uiString(R.string.nested_or_captured_parameters_prevent_identification_of_a));
                options.add(new Local(p, method, p.getName(), p.getType() + (p.isVarArgs() ? "[]" : "")));
            }
        }
        for (VariableDeclarator v : method.findAll(VariableDeclarator.class)) {
            if (!v.getNameAsString().equals(name)) continue;
            Node statement = v.getParentNode().flatMap(Node::getParentNode).orElse(null);
            require(statement instanceof ExpressionStmt && statement.getParentNode().orElse(null) instanceof BlockStmt,
                    uiString(R.string.the_declaration_is_inside_a_loop_header_resource));
            options.add(new Local(v, statement.getParentNode().get(), v.getName(), v.getType().asString()));
        }
        if (options.isEmpty()) return null;
        require(options.size() == 1, uiString(R.string.multiple_scopes_use_the_same_name));
        Local target = options.get(0);
        require(at >= start(target.name) && at <= end(target.scope) && at >= start(target.scope), uiString(R.string.the_name_at_the_cursor_is_outside_the));
        return target;
    }

    private List<NameExpr> usages(Local target) {
        List<NameExpr> result = new ArrayList<>();
        for (NameExpr n : target.scope.findAll(NameExpr.class)) {
            if (!n.getNameAsString().equals(target.name.asString()) || start(n) < end(target.decl)) continue;
            for (Node parent = n; parent != target.scope; parent = parent.getParentNode().get()) {
                require(!(parent instanceof TypeDeclaration) &&
                       !(parent instanceof ObjectCreationExpr && ((ObjectCreationExpr) parent).getAnonymousClassBody().isPresent()),
                       uiString(R.string.a_nested_type_references_a_symbol_with_the));
            }
            result.add(n);
        }
        return result;
    }
    private Local selectedLocal() {
        SimpleName token = enclosing(SimpleName.class, uiString(R.string.place_the_cursor_on_a_local_variable_or));
        MethodDeclaration method = method();
        Local found = local(method, token.asString(), from);
        require(found != null && (token == found.name || usages(found).stream().anyMatch(n -> n.getName() == token)),
                uiString(R.string.select_a_local_variable_or_parameter_inside_a));
        return found;
    }
    private void rename(String name) {
        MethodDeclaration selected = method();
        if (contains(selected.getName())) {
            fresh(name, unit);
            for (MethodCallExpr call : privateCalls(selected)) patch(call.getName(), name);
            patch(selected.getName(), name);
            return;
        }
        Local target = selectedLocal();
        fresh(name, method());
        patch(target.name, name);
        for (NameExpr n : usages(target)) patch(n, name);
    }

    private void inline() {
        Local target = selectedLocal();
        require(target.decl instanceof VariableDeclarator, uiString(R.string.select_a_local_variable));
        VariableDeclarator var = (VariableDeclarator) target.decl;
        Expression init = var.getInitializer().orElseThrow(() -> new IllegalArgumentException(uiString(R.string.the_variable_has_no_initializer)));
        require(init instanceof LiteralExpr && !(init instanceof NullLiteralExpr), uiString(R.string.inlining_requires_a_literal_initializer));
        VariableDeclarationExpr decl = (VariableDeclarationExpr) var.getParentNode().get();
        require(decl.getVariables().size() == 1, uiString(R.string.split_the_multi_variable_declaration_first));
        List<NameExpr> references = usages(target);
        for (NameExpr n : references) {
            Node parent = n.getParentNode().get();
            require(!(parent instanceof AssignExpr && ((AssignExpr) parent).getTarget() == n) &&
                !(parent instanceof UnaryExpr && ((UnaryExpr) parent).getOperator().isPostfix()) &&
                !(parent instanceof UnaryExpr && EnumSet.of(UnaryExpr.Operator.PREFIX_INCREMENT, UnaryExpr.Operator.PREFIX_DECREMENT).contains(((UnaryExpr) parent).getOperator())),
                uiString(R.string.the_variable_is_modified_after_its_declaration));
        }
        String literal = raw(init);
        // Preserve the declared static type across overload resolution and numeric promotion.
        String value = target.type.equals("var") ? literal : "((" + target.type + ") " + literal + ")";
        references.forEach(n -> patch(n, value));
        patch(decl.getParentNode().get(), "");
    }

    private String inferred(Expression exp) {
        if (exp.isStringLiteralExpr() || exp.isTextBlockLiteralExpr()) return "String";
        if (exp.isBooleanLiteralExpr()) return "boolean";
        if (exp.isCharLiteralExpr()) return "char";
        if (exp.isIntegerLiteralExpr()) return "int";
        if (exp.isLongLiteralExpr()) return "long";
        if (exp.isDoubleLiteralExpr()) return raw(exp).matches(".*[fF]$") ? "float" : "double";
        if (exp.isCastExpr()) return exp.asCastExpr().getType().asString();
        if (exp.isEnclosedExpr()) return inferred(exp.asEnclosedExpr().getInner());
        if (exp.isNameExpr()) {
            Local known = local(method(), exp.asNameExpr().getNameAsString(), start(exp));
            if (known != null && !known.type.equals("var")) return known.type;
        }
        if (exp.isBinaryExpr()) {
            BinaryExpr op = exp.asBinaryExpr();
            String left = inferred(op.getLeft()), right = inferred(op.getRight());
            switch (op.getOperator()) {
                case EQUALS: case NOT_EQUALS: case LESS: case LESS_EQUALS: case GREATER: case GREATER_EQUALS:
                case OR: case AND: return "boolean";
                case PLUS: if ("String".equals(left) || "String".equals(right)) return "String"; break;
                case LEFT_SHIFT: case SIGNED_RIGHT_SHIFT: case UNSIGNED_RIGHT_SHIFT:
                    if (left != null) return left.equals("long") ? "long" : "int";
                    return null;
                case BINARY_AND: case BINARY_OR: case XOR: if ("boolean".equals(left) && "boolean".equals(right)) return "boolean"; break;
                default: break;
            }
            List<String> numeric = Arrays.asList("byte", "short", "char", "int", "long", "float", "double");
            if (numeric.contains(left) && numeric.contains(right)) return numeric.get(Math.max(3, Math.max(numeric.indexOf(left), numeric.indexOf(right))));
        }
        return null;
    }

    private String contextualType(Expression exp, MethodDeclaration method) {
        Node parent = exp.getParentNode().get();
        if (parent instanceof ReturnStmt) return method.getType().asString();
        if (parent instanceof VariableDeclarator && ((VariableDeclarator) parent).getInitializer().orElse(null) == exp) {
            String declared = ((VariableDeclarator) parent).getType().asString();
            if (!declared.equals("var")) return declared;
        }
        return null;
    }

    private String type(String requested, Expression exp, MethodDeclaration method, String kind) {
        String inferred = inferred(exp);
        String contextual = contextualType(exp, method);
        String wanted = inferred != null ? inferred : contextual;
        // A nonconstant call/parameter cannot retain Java's implicit constant narrowing.
        if ((kind.equals("method") || kind.equals("parameter")) && ("byte".equals(contextual) || "short".equals(contextual) || "char".equals(contextual)))
            wanted = contextual;
        require(wanted != null, uiString(R.string.unable_to_determine_the_static_type_select_an));
        require(requested.isEmpty() || requested.equals(wanted), uiString(R.string.the_original_type_must_be_preserved_here_format, wanted));
        return wanted;
    }
    private String header(MethodDeclaration m) {
        return indent(start(m)) + "private " + (m.isStatic() ? "static " : "");
    }
    private void insertMember(MethodDeclaration m, String declaration) {
        int at = end(m);
        patches.add(new Patch(at, at, eol() + eol() + declaration));
    }

    private void extract(String kind, String name, String requestedType) {
        Expression exp = expression();
        MethodDeclaration method = method();
        TypeDeclaration<?> owner = method.findAncestor(TypeDeclaration.class).get();
        require(!(owner instanceof AnnotationDeclaration) && !(owner instanceof ClassOrInterfaceDeclaration && ((ClassOrInterfaceDeclaration)owner).isInterface()),
                uiString(R.string.select_an_ordinary_class_method));
        fresh(name, owner);
        require(exp.findAncestor(MethodDeclaration.class).orElse(null) == method
                && exp.findAncestor(TypeDeclaration.class).orElse(null) == owner
                && !exp.findAncestor(LambdaExpr.class).isPresent()
                && exp.findAncestor(ObjectCreationExpr.class).map(o -> !o.getAnonymousClassBody().isPresent()).orElse(true),
                uiString(R.string.the_selection_is_inside_a_nested_type_or));
        String type = type(requestedType, exp, method, kind);
        require(!exp.findAncestor(AnnotationExpr.class).isPresent()
                && exp.findAncestor(SwitchEntry.class).map(entry -> entry.getLabels().stream().noneMatch(label -> start(label) <= from && to <= end(label))).orElse(true),
                uiString(R.string.annotations_and_case_labels_require_constant_expressions));
        if (kind.equals("variable")) {
            Statement statement = exp.findAncestor(Statement.class).get();
            require(statement.getParentNode().orElse(null) instanceof BlockStmt, uiString(R.string.select_an_expression_within_a_standalone_statement_in));
            boolean initializer = exp.getParentNode().orElse(null) instanceof VariableDeclarator &&
                ((VariableDeclarator) exp.getParentNode().get()).getInitializer().orElse(null) == exp &&
                exp.findAncestor(VariableDeclarationExpr.class).map(v -> v.getVariables().size() == 1).orElse(false);
            boolean directReturn = statement instanceof ReturnStmt && ((ReturnStmt)statement).getExpression().orElse(null) == exp;
            require(initializer || directReturn || exp.isLiteralExpr(), uiString(R.string.select_a_complete_initializer_return_value_or_literal));
            int at = start(statement);
            patches.add(new Patch(at, at, "final " + type + " " + name + " = " + raw(exp) + ";" + eol() + indent(at)));
            patch(exp, name);
        } else if (kind.equals("method")) {
            require(exp.findAll(AssignExpr.class).isEmpty() && exp.findAll(UnaryExpr.class).stream().noneMatch(u ->
               u.getOperator().name().contains("INCREMENT") || u.getOperator().name().contains("DECREMENT")), uiString(R.string.the_expression_modifies_a_variable));
            require(exp.findAll(LambdaExpr.class).isEmpty() && exp.findAll(ObjectCreationExpr.class).stream().noneMatch(o -> o.getAnonymousClassBody().isPresent()), uiString(R.string.the_selection_contains_a_nested_scope));
            LinkedHashMap<String, String> arguments = new LinkedHashMap<>();
            for (NameExpr n : exp.findAll(NameExpr.class)) {
                Local captured = local(method, n.getNameAsString(), start(n));
                if (captured != null) {
                    require(!captured.type.equals("var"), uiString(R.string.captured_local_variables_need_explicit_types));
                    arguments.put(n.getNameAsString(), captured.type);
                }
            }
            String params = arguments.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
            String generics = method.getTypeParameters().isEmpty() ? "" : "<" + method.getTypeParameters().stream().map(Object::toString).collect(Collectors.joining(", ")) + "> ";
            String exceptions = method.getThrownExceptions().isEmpty() ? "" : " throws " + method.getThrownExceptions().stream().map(Object::toString).collect(Collectors.joining(", "));
            String pad = indent(start(method));
            insertMember(method, header(method) + generics + type + " " + name + "(" + params + ")" + exceptions + " {" + eol()
                       + pad + "    return " + raw(exp) + ";" + eol() + pad + "}");
            patch(exp, name + "(" + String.join(", ", arguments.keySet()) + ")");
        } else {
            require(exp instanceof LiteralExpr && !exp.isNullLiteralExpr(), uiString(R.string.select_a_literal_with_a_concrete_type_to));
            if (kind.equals("parameter")) {
                List<MethodCallExpr> calls = privateCalls(method);
                int open = methodOpenParen(method);
                patches.add(new Patch(open + 1, open + 1, type + " " + name + (method.getParameters().isEmpty() ? "" : ", ")));
                for (MethodCallExpr call : calls) {
                    int callOpen = openParenAfter(call.getName(), end(call));
                    String argument = type.equals(inferred(exp)) ? raw(exp) : "(" + type + ") (" + raw(exp) + ")";
                    patches.add(new Patch(callOpen + 1, callOpen + 1, argument + (call.getArguments().isEmpty() ? "" : ", ")));
                }
                patch(exp, name);
            } else {
                String fields = kind.equals("constant") ? indent(start(method)) + "private static final " : header(method) + "final ";
                insertMember(method, fields + type + " " + name + " = " + raw(exp) + ";");
                patch(exp, name);
            }
        }
    }
    private int methodOpenParen(MethodDeclaration method) { return openParenAfter(method.getName(), end(method)); }
    private int openParenAfter(SimpleName name, int limit) {
        int pos = end(name);
        while (pos < limit && Character.isWhitespace(source.charAt(pos))) pos++;
        require(pos < limit && source.charAt(pos) == '(', uiString(R.string.unable_to_locate_the_parameter_list_because_it));
        return pos;
    }
    private List<MethodCallExpr> privateCalls(MethodDeclaration method) {
        String name = method.getNameAsString();
        require(method.getAnnotations().isEmpty() && !method.isNative(), uiString(R.string.frameworks_may_invoke_annotated_or_native_methods));
        require(method.isPrivate() && unit.findAll(MethodDeclaration.class).stream().filter(m -> m.getNameAsString().equals(name)).count() == 1,
                uiString(R.string.select_a_non_overloaded_private_method_in_this));
        require(unit.findAll(MethodReferenceExpr.class).stream().noneMatch(r -> r.getIdentifier().equals(name)), uiString(R.string.the_method_has_a_method_reference));
        List<MethodCallExpr> calls = new ArrayList<>();
        TypeDeclaration<?> owner = method.findAncestor(TypeDeclaration.class).get();
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals(name)) continue;
            require(call.findAncestor(TypeDeclaration.class).orElse(null) == owner &&
                    (!call.getScope().isPresent() || call.getScope().get().isThisExpr()) &&
                    call.getArguments().size() == method.getParameters().size(), uiString(R.string.a_call_with_the_same_name_has_an));
            calls.add(call);
        }
        return calls;
    }
    private void changeSignature(String order) {
        MethodDeclaration method = method();
        List<MethodCallExpr> calls = privateCalls(method);
        String[] names = order.trim().split("\\s*,\\s*");
        var params = method.getParameters();
        require(!params.isEmpty() && params.size() == names.length && new HashSet<>(Arrays.asList(names)).size() == names.length &&
            params.stream().noneMatch(Parameter::isVarArgs), uiString(R.string.enter_all_parameter_names_in_the_new_order));
        List<Integer> indices = new ArrayList<>();
        for (String name : names) {
            int index = -1;
            for (int i = 0; i < params.size(); i++) if (params.get(i).getNameAsString().equals(name)) index = i;
            require(index >= 0, uiString(R.string.unknown_parameter_format, name));
            indices.add(index);
        }
        for (int i = 0; i < names.length; i++) patch(params.get(i), raw(params.get(indices.get(i))));
        for (MethodCallExpr call : calls) {
            var args = call.getArguments();
            require(args.stream().allMatch(e -> e.isLiteralExpr() || e.isNameExpr() || e.isThisExpr()), uiString(R.string.the_call_contains_arguments_whose_evaluation_order_cannot));
            for (int i = 0; i < names.length; i++) patch(args.get(i), raw(args.get(indices.get(i))));
        }
    }
    private void privateMember(String action, String name) {
        MethodDeclaration method = method();
        require(contains(method.getName()), uiString(R.string.place_the_cursor_on_a_private_method_declaration));
        List<MethodCallExpr> calls = privateCalls(method);
        if (action.equals("deleteMethod")) {
            require(calls.stream().allMatch(call -> call.findAncestor(MethodDeclaration.class).orElse(null) == method),
                    uiString(R.string.other_methods_still_call_this_method));
            patch(method, "");
        } else if (action.equals("copyMethod")) {
            fresh(name, unit);
            StringBuilder copy = new StringBuilder(raw(method));
            List<SimpleName> names = new ArrayList<>();
            names.add(method.getName());
            for (MethodCallExpr call : calls) {
                if (call.findAncestor(MethodDeclaration.class).orElse(null) == method) names.add(call.getName());
            }
            names.sort(Comparator.comparingInt(this::start).reversed());
            for (SimpleName n : names) copy.replace(start(n) - start(method), end(n) - start(method), name);
            insertMember(method, indent(start(method)) + copy.toString());
        } else {
            require(method.isStatic() && method.getTypeParameters().isEmpty() && method.getAnnotations().isEmpty()
                && method.getType().isPrimitiveType() && method.getParameters().stream().allMatch(p -> p.getType().isPrimitiveType() && !p.isVarArgs()),
                uiString(R.string.select_a_private_static_method_using_primitive_arithmetic));
            require(method.getBody().isPresent() && method.getBody().get().getStatements().size() == 1
                && method.getBody().get().getStatement(0).isReturnStmt(), uiString(R.string.the_method_body_must_be_a_single_return));
            method.getBody().get().getStatement(0).walk(Expression.class, e -> {
                boolean parameter = e.isNameExpr() && method.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(e.asNameExpr().getNameAsString()));
                require(parameter || e.isLiteralExpr() || e.isUnaryExpr() || e.isBinaryExpr() || e.isEnclosedExpr(), uiString(R.string.the_method_body_depends_on_an_external_member));
            });
            ClassOrInterfaceDeclaration current = method.findAncestor(ClassOrInterfaceDeclaration.class).orElseThrow(() -> new IllegalArgumentException(uiString(R.string.only_methods_inside_a_class_are_supported)));
            List<ClassOrInterfaceDeclaration> targets = unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.getNameAsString().equals(name)).collect(Collectors.toList());
            require(targets.size() == 1 && !targets.get(0).isInterface() && targets.get(0) != current, uiString(R.string.enter_a_unique_target_class_name_from_this));
            ClassOrInterfaceDeclaration target = targets.get(0);
            Node outer = current, dest = target;
            while (outer.getParentNode().orElse(null) instanceof TypeDeclaration) outer = outer.getParentNode().get();
            while (dest.getParentNode().orElse(null) instanceof TypeDeclaration) dest = dest.getParentNode().get();
            require(outer == dest && (target.isTopLevelType() || target.isStatic()), uiString(R.string.choose_the_same_top_level_class_or_one));
            String qualifier = target.getFullyQualifiedName().orElseThrow(() -> new IllegalArgumentException(uiString(R.string.unable_to_locate_target_class)));
            patch(method, "");
            int at = end(target) - 1;
            String pad = indent(start(target));
            patches.add(new Patch(at, at, eol() + pad + "    " + raw(method) + eol() + pad));
            for (MethodCallExpr call : calls) {
                int begin = call.getScope().map(this::start).orElse(start(call.getName()));
                patches.add(new Patch(begin, start(call.getName()), qualifier + "."));
            }
        }
    }

    static final class Location {
        final int start, end;
        final String label;
        Location(int start, int end, String label) { this.start = start; this.end = end; this.label = label; }
    }

    private Location location(Node n) {
        int p = start(n), line = n.getBegin().get().line;
        int from = source.lastIndexOf('\n', p - 1) + 1;
        int to = source.indexOf('\n', p);
        if (to < 0) to = source.length();
        return new Location(p, end(n), line + "  " + source.substring(from, Math.min(to, from + 160)).trim());
    }

    List<Location> locations(String action) {
        List<Location> list = new ArrayList<>();
        if (action.equals("structure")) {
            for (TypeDeclaration<?> t : unit.findAll(TypeDeclaration.class)) list.add(location(t.getName()));
            for (CallableDeclaration<?> c : unit.findAll(CallableDeclaration.class)) list.add(location(c.getName()));
            for (FieldDeclaration f : unit.findAll(FieldDeclaration.class)) for (VariableDeclarator v : f.getVariables()) list.add(location(v.getName()));
        } else {
            SimpleName token = enclosing(SimpleName.class, uiString(R.string.select_a_method_name_local_variable_or_parameter));
            Node parent = token.getParentNode().get();
            if (parent instanceof MethodCallExpr || parent instanceof MethodDeclaration) {
                List<MethodDeclaration> method = unit.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getNameAsString().equals(token.asString())).collect(Collectors.toList());
                require(method.size() == 1, uiString(R.string.unable_to_identify_a_unique_method_in_this));
                List<MethodCallExpr> calls = privateCalls(method.get(0));
                if (action.equals("declaration")) list.add(location(method.get(0).getName()));
                else calls.forEach(c -> list.add(location(c.getName())));
            } else {
                Local target = selectedLocal();
                if (action.equals("declaration")) list.add(location(target.name));
                else usages(target).forEach(n -> list.add(location(n.getName())));
            }
        }
        list.sort(Comparator.comparingInt(n -> n.start));
        return list;
    }

    String run(String kind, String name, String type) {
        switch (kind) {
            case "rename": rename(name); break;
            case "inline": inline(); break;
            case "signature": changeSignature(name); break;
            case "copyMethod": case "moveMethod": case "deleteMethod": privateMember(kind, name); break;
            case "imports": {
                List<ImportDeclaration> imports = unit.getImports();
                if (imports.isEmpty()) return source;
                int first = start(imports.get(0)), last = end(imports.get(imports.size() - 1));
                String region = source.substring(first, last);
                require(!region.contains("//") && !region.contains("/*"), uiString(R.string.the_import_section_contains_comments_organize_it_manually));
                String sorted = imports.stream().map(this::raw).distinct().sorted().collect(Collectors.joining(eol()));
                return source.substring(0, first) + sorted + source.substring(last);
            }
            case "format": {
                String result = unit.toString().replace("\r\n", "\n").replace("\n", eol());
                return source.endsWith("\n") ? result : result.replaceFirst("[\\r\\n]+$", "");
            }
            default: extract(kind, name, type);
        }
        StringBuilder edited = new StringBuilder(source);
        patches.sort(Comparator.comparingInt((Patch p) -> p.from).thenComparingInt(p -> p.to).reversed());
        int previous = source.length();
        for (Patch p : patches) {
            require(p.to <= previous, uiString(R.string.edit_ranges_overlap));
            edited.replace(p.from, p.to, p.replacement);
            previous = p.from;
        }
        String result = edited.toString();
        require(new JavaParser(configuration).parse(result).isSuccessful(), uiString(R.string.the_result_contains_invalid_java_syntax));
        return result;
    }
}

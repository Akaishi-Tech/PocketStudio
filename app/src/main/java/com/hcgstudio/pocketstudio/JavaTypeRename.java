package com.hcgstudio.pocketstudio;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.*;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import java.util.*;

/** Binds source types across compilation units; a rename plan never edits the project while resolving. */
final class JavaTypeRename {
    private final MemoryTypeSolver sources = new MemoryTypeSolver();
    private final CombinedTypeSolver types = new CombinedTypeSolver(sources, new ReflectionTypeSolver(false));
    private final Map<String, CompilationUnit> units = new LinkedHashMap<>();
    private final Map<String, String> files;
    private final String oldName, newName, definitionPath;
    final String qualified;

    JavaTypeRename(Map<String, String> files, String definitionPath, String newName) {
        this.files = files;
        this.definitionPath = definitionPath;
        oldName = definitionPath.substring(definitionPath.lastIndexOf('/') + 1, definitionPath.length() - 5);
        this.newName = newName;
        ParserConfiguration config = new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
            .setTabSize(1).setSymbolResolver(new JavaSymbolSolver(types));
        JavaParser parser = new JavaParser(config);
        require(!newName.equals(oldName) && newName.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*")
            && parser.parse("class " + newName + " {}").isSuccessful(), uiString(R.string.enter_a_valid_different_class_name));
        JavaParserFacade facade = JavaParserFacade.get(types);
        Set<String> declared = new HashSet<>();
        for (Map.Entry<String,String> f : files.entrySet()) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException(uiString(R.string.action_canceled));
            ParseResult<CompilationUnit> result = parser.parse(f.getValue());
            require(result.isSuccessful(), uiString(R.string.contains_invalid_java_syntax_format, f.getKey()));
            CompilationUnit unit = result.getResult().get();
            units.put(f.getKey(), unit);
            for (TypeDeclaration<?> t : unit.findAll(TypeDeclaration.class)) {
                if (!t.getFullyQualifiedName().isPresent()) continue;
                String name = t.getFullyQualifiedName().get();
                require(declared.add(name), uiString(R.string.another_source_set_or_module_has_a_type_with_this_name, name));
                sources.addDeclaration(name, facade.getTypeDeclaration(t));
            }
        }
        CompilationUnit current = units.get(definitionPath);
        require(current != null, uiString(R.string.target_source_unavailable));
        TypeDeclaration<?> definition = current.getTypes().stream().filter(t -> oldName.equals(t.getNameAsString())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException(uiString(R.string.a_top_level_java_type_matching_the_file)));
        qualified = definition.getFullyQualifiedName().get();
        String renamed = qualified.substring(0, qualified.length() - oldName.length()) + newName;
        require(!types.tryToSolveType(renamed).isSolved(), uiString(R.string.the_package_already_contains_format, newName));
    }

    private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }

    private boolean refers(Node node, String name) {
        // An explicit import resolves the class even when its dependency JAR isn't on-device.
        CompilationUnit cu = node.findCompilationUnit().get();
        boolean multipleStars = cu.getImports().stream().filter(i -> i.isAsterisk()).count() > 1;
        if (name.equals(oldName) && multipleStars) {
            boolean explicit = cu.getImports().stream().anyMatch(i -> i.getNameAsString().equals(qualified));
            boolean samePackage = cu.getPackageDeclaration().map(p -> p.getNameAsString() + "." + oldName).orElse(oldName).equals(qualified);
            require(explicit || samePackage, uiString(R.string.multiple_wildcard_imports_make_the_binding_for_ambiguous, name));
        }
        var resolution = JavaParserFactory.getContext(node, types).solveType(name);
        if (resolution.isSolved()) {
            var declaration = resolution.getCorrespondingDeclaration();
            return declaration.isReferenceType() && qualified.equals(declaration.asReferenceType().getQualifiedName());
        }
        if (name.equals(oldName) && cu.getImports().stream().anyMatch(i -> !i.isAsterisk() && !i.isStatic()
                      && i.getName().getIdentifier().equals(oldName) && !qualified.equals(i.getNameAsString()))) return false;
        throw new IllegalArgumentException(uiString(R.string.unable_to_resolve_to_a_type, name));
    }

    private boolean isValue(NameExpr expr) {
        try {
            return JavaParserFactory.getContext(expr, types).solveSymbol(expr.getNameAsString()).isSolved();
        } catch (UnsolvedSymbolException e) {
            // A missing supertype can contribute a member with the same name.
            throw new IllegalArgumentException(uiString(R.string.unresolved_variable_or_type, expr), e);
        }
    }

    Map<String,String> changes() {
        Map<String,String> results = new LinkedHashMap<>();
        for (var entry : units.entrySet()) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException(uiString(R.string.action_canceled));
            String path = entry.getKey();
            try {
                String source = files.get(path);
                String after = change(entry.getValue(), source, path.equals(definitionPath));
                if (!after.equals(source)) results.put(path, after);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(path + "：" + (e.getMessage() == null ? uiString(R.string.unable_to_resolve_reference) : e.getMessage()), e);
            }
        }
        return results;
    }

    private String change(CompilationUnit unit, String source, boolean target) {
        // Introducing a simple name could rebind existing unqualified code, even in another file.
        boolean samePackage = unit.getPackageDeclaration().map(p -> p.getNameAsString() + "." + oldName).orElse(oldName).equals(qualified);
        boolean importsPackage = unit.getImports().stream().anyMatch(i -> i.isAsterisk()
                && (i.getNameAsString() + "." + oldName).equals(qualified));
        if (!target && !samePackage && !importsPackage && !source.contains(oldName)) return source;
        Set<Position> positions = new TreeSet<>(Comparator.reverseOrder());
        Set<SimpleName> known = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TypeDeclaration<?> decl : unit.getTypes()) {
            if (!target || !decl.getNameAsString().equals(oldName)) continue;
            positions.add(decl.getName().getBegin().get());
            known.add(decl.getName());
            for (BodyDeclaration<?> member : decl.getMembers()) {
                SimpleName ctor = member.isConstructorDeclaration() ? member.asConstructorDeclaration().getName() :
                    member.isCompactConstructorDeclaration() ? member.asCompactConstructorDeclaration().getName() : null;
                if (ctor != null) { positions.add(ctor.getBegin().get()); known.add(ctor); }
            }
        }
        for (Name n : unit.findAll(Name.class)) {
            if (!n.getIdentifier().equals(oldName)) continue;
            boolean change = false;
            if (n.findAncestor(ImportDeclaration.class).isPresent()) change = n.asString().equals(qualified);
            else if (n.findAncestor(AnnotationExpr.class).isPresent() || n.findAncestor(ThisExpr.class).isPresent() || n.findAncestor(SuperExpr.class).isPresent())
                change = refers(n, n.asString());
            else if (!n.findAncestor(PackageDeclaration.class).isPresent()) throw new IllegalArgumentException(uiString(R.string.unsupported_qualified_name_reference_format, n));
            if (change) positions.add(new Position(n.getEnd().get().line, n.getEnd().get().column - oldName.length() + 1));
        }
        for (SimpleName name : unit.findAll(SimpleName.class)) {
            if (!name.asString().equals(oldName) || known.contains(name)) continue;
            Node parent = name.getParentNode().get();
            boolean change;
            if (parent instanceof ClassOrInterfaceType) {
                change = refers(parent, ((ClassOrInterfaceType) parent).getNameWithScope());
            } else if (parent instanceof NameExpr) {
                NameExpr expression = (NameExpr) parent;
                change = !isValue(expression) && refers(expression, oldName);
            } else if (parent instanceof FieldAccessExpr) {
                FieldAccessExpr field = (FieldAccessExpr) parent;
                if (!field.toString().equals(qualified)) continue;
                Expression first = field.getScope();
                while (first.isFieldAccessExpr()) first = first.asFieldAccessExpr().getScope();
                change = !(first.isNameExpr() && isValue(first.asNameExpr()));
            } else {
                // Local/field/method declarations, method calls, labels and type parameters have
                // distinct binding sites. Unhandled uses with this spelling stop the operation.
                if (parent instanceof TypeDeclaration || parent instanceof CallableDeclaration || parent instanceof VariableDeclarator
                    || parent instanceof Parameter || parent instanceof MethodCallExpr || parent instanceof TypeParameter
                    || parent instanceof EnumConstantDeclaration || parent instanceof AnnotationMemberDeclaration ||
                    parent instanceof com.github.javaparser.ast.stmt.LabeledStmt || parent instanceof com.github.javaparser.ast.stmt.BreakStmt ||
                    parent instanceof com.github.javaparser.ast.stmt.ContinueStmt || parent instanceof CompactConstructorDeclaration) continue;
                throw new IllegalArgumentException(uiString(R.string.unrecognized_reference_format, parent));
            }
            if (change) positions.add(name.getBegin().get());
        }
        if (target || samePackage || importsPackage || !positions.isEmpty()) {
            require(unit.findAll(SimpleName.class).stream().noneMatch(n -> n.asString().equals(newName))
                    && unit.getImports().stream().noneMatch(i -> !i.isAsterisk() && i.getName().getIdentifier().equals(newName)),
                    uiString(R.string.rename_binding_conflict, newName));
        }
        if (positions.isEmpty()) return source;
        int[] starts = new int[1 + (int)source.chars().filter(c -> c == '\n').count()];
        for (int i = 0, line = 1; i < source.length(); i++) if (source.charAt(i) == '\n') starts[line++] = i + 1;
        StringBuilder result = new StringBuilder(source);
        for (Position p : positions) {
            int at = starts[p.line - 1] + p.column - 1;
            require(source.startsWith(oldName, at), uiString(R.string.unable_to_safely_locate_an_escaped_identifier));
            result.replace(at, at + oldName.length(), newName);
        }
        return result.toString();
    }
}

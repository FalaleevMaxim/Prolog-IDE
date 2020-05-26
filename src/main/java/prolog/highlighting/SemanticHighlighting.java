package prolog.highlighting;

import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.fxmisc.richtext.model.StyledDocument;
import ru.prolog.syntaxmodel.TokenType;
import ru.prolog.syntaxmodel.recognizers.Lexer;
import ru.prolog.syntaxmodel.tree.AbstractNode;
import ru.prolog.syntaxmodel.tree.Node;
import ru.prolog.syntaxmodel.tree.Token;
import ru.prolog.syntaxmodel.tree.interfaces.Bracketed;
import ru.prolog.syntaxmodel.tree.interfaces.Named;
import ru.prolog.syntaxmodel.tree.interfaces.Separated;
import ru.prolog.syntaxmodel.tree.misc.NodeError;
import ru.prolog.syntaxmodel.tree.nodes.modules.ProgramNode;
import ru.prolog.syntaxmodel.tree.semantics.SemanticAnalyzer;
import ru.prolog.syntaxmodel.tree.semantics.SemanticInfo;
import ru.prolog.syntaxmodel.tree.semantics.attributes.*;
import ru.prolog.syntaxmodel.tree.semantics.attributes.errors.AbstractSemanticError;
import ru.prolog.syntaxmodel.tree.semantics.attributes.warnings.AbstractSemanticWarning;

import java.util.*;
import java.util.stream.Collectors;

public class SemanticHighlighting implements Highlighter {
    private ProgramNode treeRoot;
    private final Map<Token, String> tokenNodeErrors = new HashMap<>();
    private final Map<Token, Collection<String>> cachedTokenStyles = new HashMap<>();
    private final Set<Token> lastHighlightedTokens = new HashSet<>();
    private SemanticAnalyzer semanticAnalyzer;

    private final CodeArea codeArea;
    private Stage goToWindow;

    public SemanticHighlighting(CodeArea codeArea) {
        this.codeArea = codeArea;
    }

    @Override
    public HighlightingResult computeHighlighting(String text) {
        return new HighlightingResult(0, computeHighlightingFull(text));
    }

    @Override
    public StyleSpans<Collection<String>> computeHighlightingFull(String text) {
        if(goToWindow != null) {
            goToWindow.close();
            goToWindow = null;
        }
        cachedTokenStyles.clear();
        lastHighlightedTokens.clear();
        tokenNodeErrors.clear();
        Lexer lexer = new Lexer(text);
        treeRoot = new ProgramNode(null);
        treeRoot.parse(lexer);
        collectNodeErrors(treeRoot);
        semanticAnalyzer = new SemanticAnalyzer(treeRoot);
        semanticAnalyzer.performSemanticAnalysis();
        return buildStyleSpans(lexer);
    }

    private StyleSpans<Collection<String>> buildStyleSpans(Lexer lexer) {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        lexer.setPointer(null);
        while (!lexer.isEnd()) {
            Token token = lexer.nextToken();
            if (token == null) break;
            List<String> styleClasses = new ArrayList<>();
            if (token.getTokenType() == null) {
                styleClasses.add("unknown");
            } else {
                if (tokenNodeErrors.containsKey(token)) styleClasses.add("nodeError");
                if (token.isPartial()) styleClasses.add("error");
                switch (token.getTokenType()) {
                    case LB:
                    case RB:
                        styleClasses.add("bracket");
                        break;
                    case RSQB:
                    case LSQB:
                    case TAILSEP:
                        styleClasses.add("sbracket");
                        break;
                    case DOT:
                    case COMMA:
                    case SEMICOLON:
                    case IF_SIGN:
                    case IF_KEYWORD:
                    case AND_KEYWORD:
                    case OR_KEYWORD:
                        styleClasses.add("rule_sep");
                        break;
                    case SINGLE_COMMENT:
                    case MULTILINE_COMMENT:
                        styleClasses.add("comment");
                        break;
                    case INTEGER:
                    case REAL:
                        styleClasses.add("number");
                        break;
                    case STRING:
                    case CHAR:
                        styleClasses.add("string");
                        break;
                    case VARIABLE:
                        styleClasses.add("variable");
                        break;
                    case SYMBOL:
                    case CUT_SIGN:
                        styleClasses.add("name");
                        break;
                    case ANONYMOUS:
                        styleClasses.add("anonymous");
                        break;
                    case INCLUDE_KEYWORD:
                    case DOMAINS_KEYWORD:
                    case DATABASE_KEYWORD:
                    case PREDICATES_KEYWORD:
                    case CLAUSES_KEYWORD:
                    case GOAL_KEYWORD:
                        styleClasses.add("header");
                        break;
                    case STAR_MULTIPLY:
                    case PLUS:
                    case MINUS:
                    case DIVIDE:
                    case GREATER:
                    case LESSER:
                    case EQUALS:
                        styleClasses.add("math");
                        break;
                }
                checkSemantic(token, styleClasses);
            }
            spansBuilder.add(styleClasses, token.length());
            cachedTokenStyles.put(token, styleClasses);
        }
        return spansBuilder.create();
    }

    private void checkSemantic(Token token, List<String> styleClasses) {
        for (Node node = token; token.parent() != null; node = node.parent()) {
            if(node == null) break;
            SemanticInfo semanticInfo = node.getSemanticInfo();
            List<AbstractSemanticError> errors = semanticInfo.getErrors();
            List<AbstractSemanticWarning> warnings = semanticInfo.getWarnings();
            if(!errors.isEmpty()) {
                checkNamedAndSetStyle(token, node, styleClasses, "semanticError");
            } else if(!warnings.isEmpty()) {
                checkNamedAndSetStyle(token, node, styleClasses, "warning");
            } else {
                ToUsages usages = semanticInfo.getAttribute(ToUsages.class);
                if(node instanceof Named && ((Named) node).getName() == token && usages!=null && usages.getUsages().isEmpty()) {
                    styleClasses.add("unused");
                }
                if(semanticInfo.getAttribute(SymbolValue.class) != null) {
                    styleClasses.add("string");
                }
            }
        }
    }

    private void checkNamedAndSetStyle(Token token, Node node, List<String> styleClasses, String style) {
        if(node instanceof Named && ((Named) node).getName() != token) {
            return;
        }
        styleClasses.add(style);
    }

    private void collectNodeErrors(AbstractNode node) {
        for (Map.Entry<Node, NodeError> nodeError : node.getErrors().entrySet()) {
            String text = nodeError.getValue().getText();
            Node key = nodeError.getKey();
            if (nodeError.getValue().isAfter()) {
                Token token = key.lastToken();
                addTokenError(token, text);
            } else {
                key.tokens().forEach(token -> addTokenError(token, text));
            }
        }
        node.children().stream()
                .filter(n -> n instanceof AbstractNode)
                .map(n -> (AbstractNode) n)
                .forEach(this::collectNodeErrors);
    }

    private void addTokenError(Token token, String error) {
        if (tokenNodeErrors.containsKey(token)) {
            tokenNodeErrors.put(token, tokenNodeErrors.get(token) + '\n' + error);
        } else {
            tokenNodeErrors.put(token, error);
        }
    }

    @Override
    public String getMessageForPos(int pos) {
        Token token = treeRoot.tokenByRelativePos(pos);
        if (token.getTokenType() == null) return "Unknown character " + token.getText();
        String tokenError = null;
        if (token.getHint() != null) tokenError = token.getHint().errorText;

        String nodeError = null;
        if (tokenNodeErrors.containsKey(token)) nodeError = tokenNodeErrors.get(token);

        String semanticError = null;
        String semanticWarning = null;
        if(token.parent() instanceof Named && ((Named) token.parent()).getName() == token) {
            AbstractNode parent = token.parent();
            semanticError = parent.getSemanticInfo().getErrors().stream().map(AbstractSemanticError::getMessage).collect(Collectors.joining("\n"));
            semanticWarning = parent.getSemanticInfo().getWarnings().stream().map(AbstractSemanticWarning::getMessage).collect(Collectors.joining("\n"));
        }

        StringBuilder error = new StringBuilder();
        if(tokenError != null) error.append(tokenError);
        if(nodeError != null) {
            if(error.length() > 0) error.append('\n');
            error.append(nodeError);
        }
        if(semanticError != null) {
            if(error.length() > 0) error.append('\n');
            error.append(semanticError);
        }
        if(semanticWarning != null) {
            if(error.length() > 0) error.append('\n');
            error.append(semanticWarning);
        }
        String s = error.toString();
        return s.isEmpty() ? null : s;
    }

    @Override
    public List<HighlightingResult> changeStylesOnCursor(int pos) {
        Map<Token, Collection<String>> styles = restoreLast();
        lastHighlightedTokens.clear();
        if (pos < treeRoot.length()) {
            Token token = treeRoot.tokenByRelativePos(pos);
            checkCursorStyleRules(styles, token);
        }
        pos -= 1;
        if (pos > 0 && pos < treeRoot.length()) {
            Token token = treeRoot.tokenByRelativePos(pos);
            checkCursorStyleRules(styles, token);
        }
        if (styles.isEmpty()) return null;

        List<HighlightingResult> results = new ArrayList<>();
        for (Map.Entry<Token, Collection<String>> tokenStyle : styles.entrySet()) {
            Token key = tokenStyle.getKey();
            StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
            spansBuilder.add(tokenStyle.getValue(), key.length());
            results.add(new HighlightingResult(key.startPos(), spansBuilder.create()));
        }
        return results;
    }

    @Override
    public ContextMenu getContextMenu(int pos) {
        List<MenuItem> menuItems = new ArrayList<>();
        Token token = treeRoot.tokenByRelativePos(pos);
        if (pos < treeRoot.length()) {
            checkMenuItems(menuItems, token);
        }
        pos -= 1;
        if (pos > 0 && pos < treeRoot.length()) {
            Token token1 = treeRoot.tokenByRelativePos(pos);
            if(token1 != token) {
                checkMenuItems(menuItems, token1);
            }
        }
        if(menuItems.isEmpty()) return null;
        ContextMenu contextMenu = new ContextMenu();
        for (MenuItem menuItem : menuItems) {
            contextMenu.getItems().add(menuItem);
        }
        return contextMenu;
    }

    @Override
    public void close() {
        if(goToWindow != null) {
            goToWindow.close();
            goToWindow = null;
        }
    }

    private void checkMenuItems(List<MenuItem> menuItems, Token token) {
        NameOf nameAttr = token.getSemanticInfo().getAttribute(NameOf.class);
        if(nameAttr == null) return;
        Node namedNode = nameAttr.getNamedNode();
        ToDeclaration toDeclaration = namedNode.getSemanticInfo().getAttribute(ToDeclaration.class);
        if(toDeclaration != null) {
            Node declaration = toDeclaration.getDeclaration();
            MenuItem goToDeclaration = new MenuItem("Go to declaration");
            goToDeclaration.setOnAction(event -> {
                codeArea.moveTo(declaration.startPos());
                codeArea.requestFollowCaret();
            });
            menuItems.add(goToDeclaration);
        }
        ToImplementations toImplementations = namedNode.getSemanticInfo().getAttribute(ToImplementations.class);
        if(toImplementations!=null) {
            Set<Node> implementations = toImplementations.getImplementations();
            if(!implementations.isEmpty()) {
                addFindAction(menuItems, implementations,
                        "Go to implementations of " + token.getText(),
                        "Implementations of " + token.getText());
            }
        }

        ToUsages toUsages = namedNode.getSemanticInfo().getAttribute(ToUsages.class);
        if(toUsages != null) {
            Set<Node> usages = toUsages.getUsages();
            if(!usages.isEmpty()) {
                addFindAction(menuItems, usages,
                        "Go to usages of " + token.getText(),
                        "Usages of " + token.getText());
            }
        }
    }

    /**
     * Добавляет в контекстное меню действие по переходу к узлам.
     *
     * @param menuItems Список действий контекстного меню.
     * @param foundNodes Найденные узлы, к которым должно вести действие
     * @param menuText Название действия в контекстном меню
     * @param windowTitle Заголовок окна, в котором отобразятся результаты поиска, если их несколько
     */
    private void addFindAction(List<MenuItem> menuItems, Set<Node> foundNodes, String menuText, String windowTitle) {
        MenuItem menuItem = new MenuItem(menuText);
        if (foundNodes.size() == 1) {
            Node singleResult = foundNodes.iterator().next();
            menuItem.setOnAction(event -> {
                codeArea.moveTo(singleResult.startPos());
                codeArea.requestFollowCaret();
            });
        } else {
            menuItem.setOnAction(event -> showFindResultsWindow(foundNodes, windowTitle));
        }
        menuItems.add(menuItem);
    }

    private void showFindResultsWindow(Collection<Node> results, String title) {
        Map<Token, Collection<String>> restoreStyles = restoreLast();
        for (Map.Entry<Token, Collection<String>> style : restoreStyles.entrySet()) {
            Token token = style.getKey();
            codeArea.setStyleSpans(token.startPos(), StyleSpans.singleton(style.getValue(), token.length()) );
        }

        if(goToWindow != null) {
            goToWindow.close();
        }
        goToWindow = new Stage();
        goToWindow.setTitle(title);
        goToWindow.initModality(Modality.NONE);

        VBox layout = new VBox();
        ScrollPane scrollPane = new ScrollPane(layout);
        scrollPane.setFitToWidth(true);
        Scene scene = new Scene(scrollPane, 300, 200);
        goToWindow.setScene(scene);

        results = results.stream().sorted(Comparator.comparingInt(Node::startPos)).collect(Collectors.toList());
        for (Node result : results) {
            int lineNumber = result.line();
            StyledDocument<Collection<String>, String, Collection<String>> styledLine = codeArea.subDocument(lineNumber);
            CodeArea line = new CodeArea();
            line.setEditable(false);
            VBox.setVgrow(line, Priority.NEVER);
            line.setParagraphGraphicFactory(n->new Label(lineNumber + ":"));
            line.setMaxHeight(30);
            line.getStylesheets().addAll(codeArea.getStylesheets());
            line.append(styledLine);
            layout.getChildren().add(line);

            line.setOnMouseClicked(event -> {
                int startPos = result.startPos();
                codeArea.selectRange(startPos, startPos + result.firstToken().length());
                codeArea.requestFollowCaret();
            });
        }

        goToWindow.show();
    }

    private void checkCursorStyleRules(Map<Token, Collection<String>> styles, Token token) {
        checkBrackets(styles, token);
        checkSeparators(styles, token);
        checkUsages(styles, token);
        checkVariable(styles, token);
    }

    private void checkVariable(Map<Token, Collection<String>> styles, Token token) {
        if(token.getTokenType() != TokenType.VARIABLE) return;
        ToVariablesHolder toVariablesHolder = token.getSemanticInfo().getAttribute(ToVariablesHolder.class);
        if(toVariablesHolder == null) return;
        VariablesHolder variablesHolder = toVariablesHolder.getVariablesHolder();
        String name = token.getText();
        for (Token variable : variablesHolder.byName(name)) {
            styles.put(variable, Arrays.asList("onCursor", "usage"));
            lastHighlightedTokens.add(variable);
        }
        if(variablesHolder instanceof StatementSetVariablesHolder) {
            RuleLeftVariablesHolder ruleLeftVariablesHolder = ((StatementSetVariablesHolder) variablesHolder).getRuleLeftVariablesHolder();
            for (Token variable : ruleLeftVariablesHolder.byName(name)) {
                styles.put(variable, Arrays.asList("onCursor", "usage"));
                lastHighlightedTokens.add(variable);
            }
        } else if(variablesHolder instanceof RuleLeftVariablesHolder) {
            for (StatementSetVariablesHolder statementSetVariablesHolder : ((RuleLeftVariablesHolder) variablesHolder).getStatementSetVariablesHolders()) {
                for (Token variable : statementSetVariablesHolder.byName(name)) {
                    styles.put(variable, Arrays.asList("onCursor", "usage"));
                    lastHighlightedTokens.add(variable);
                }
            }
        }
    }

    private void checkBrackets(Map<Token, Collection<String>> styles, Token token) {
        AbstractNode parent = token.parent();
        if (parent instanceof Bracketed) {
            Bracketed bracketed = (Bracketed) parent;
            Token otherBracket = null;
            if (bracketed.getLb() == token) {
                otherBracket = bracketed.getRb();
            } else if (bracketed.getRb() == token) {
                otherBracket = bracketed.getLb();
            }
            if (otherBracket != null) {
                styles.put(otherBracket, Collections.singleton("onCursor"));
                styles.put(token, Collections.singleton("onCursor"));
                lastHighlightedTokens.add(otherBracket);
                lastHighlightedTokens.add(token);
            }
        }
    }

    private void checkSeparators(Map<Token, Collection<String>> styles, Token token) {
        AbstractNode parent = token.parent();
        if (parent instanceof Separated) {
            Separated separated = (Separated) parent;
            List<Token> separators = separated.getSeparators();
            if (separators.contains(token)) {
                for (Token separator : separators) {
                    styles.put(separator, Collections.singleton("onCursor"));
                    lastHighlightedTokens.add(separator);
                }
            }
        }
    }

    private void checkUsages(Map<Token, Collection<String>> styles, Token token) {
        NameOf nameAttr = token.getSemanticInfo().getAttribute(NameOf.class);
        if(nameAttr == null) return;
        Node namedNode = nameAttr.getNamedNode();
        Node declaration = null;
        ToDeclaration toDeclaration = namedNode.getSemanticInfo().getAttribute(ToDeclaration.class);
        ToUsages toUsages;
        ToImplementations toImplementations;
        if(toDeclaration != null) {
            declaration = toDeclaration.getDeclaration();
        } else {
            if(namedNode.getSemanticInfo().getAttribute(ToUsages.class) != null) {
                declaration = namedNode;
            }
        }
        if(declaration == null) return;
        toUsages = declaration.getSemanticInfo().getAttribute(ToUsages.class);
        toImplementations = declaration.getSemanticInfo().getAttribute(ToImplementations.class);
        addUsageToken(styles, declaration);
        if(toUsages != null) {
            for (Node usage : toUsages.getUsages()) {
                addUsageToken(styles, usage);
            }
        }
        if(toImplementations != null) {
            for (Node implementation : toImplementations.getImplementations()) {
                addUsageToken(styles, implementation);
            }
        }
    }

    private void addUsageToken(Map<Token, Collection<String>> styles, Node usage) {
        Token usageToken;
        if(usage instanceof Token) usageToken = (Token) usage;
        else if(usage instanceof Named) usageToken = ((Named) usage).getName();
        else return;
        styles.put(usageToken, Arrays.asList("onCursor", "usage"));
        lastHighlightedTokens.add(usageToken);
    }

    private Map<Token, Collection<String>> restoreLast() {
        Map<Token, Collection<String>> styles = new HashMap<>();
        for (Token token : lastHighlightedTokens) {
            styles.put(token, cachedTokenStyles.get(token));
        }
        return styles;
    }
}

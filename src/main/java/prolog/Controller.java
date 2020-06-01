package prolog;

import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.geometry.Point2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.event.MouseOverTextEvent;
import org.fxmisc.wellbehaved.event.EventPattern;
import org.fxmisc.wellbehaved.event.InputMap;
import org.fxmisc.wellbehaved.event.Nodes;
import org.reactfx.Subscription;
import prolog.devices.ErrorsOutputDevice;
import prolog.devices.ProgramInputDevice;
import prolog.devices.ProgramOutputDevice;
import prolog.highlighting.*;
import ru.prolog.compiler.CompileException;
import ru.prolog.compiler.PrologCompiler;
import ru.prolog.etc.exceptions.model.ModelStateException;
import ru.prolog.model.program.Program;
import ru.prolog.runtime.context.program.BaseProgramContextDecorator;
import ru.prolog.runtime.context.program.ProgramContext;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

@SuppressWarnings("StatementWithEmptyBody")
public class Controller implements Initializable {
    public static final String POPUP_STYLE = "-fx-background-color: grey;" +
            "-fx-text-fill: darkred;" +
            "-fx-padding: 5;";
    public ProgramInputDevice programInput;
    public ProgramOutputDevice programOutput;
    public CodeArea codeArea;
    public Button stopBtn;
    public Button debugBtn;
    public Button runBtn;
    public VBox root;
    public ErrorsOutputDevice errorsOutput;
    public Label caretPos;
    public MenuItem runMenuItem;
    public MenuItem debugMenuItem;
    public MenuItem stopMenuItem;
    public ToggleGroup highlightingToggleGroup;
    public RadioMenuItem noHighlightingMenuItem;
    public RadioMenuItem lexerHighlightingMenuItem;
    public RadioMenuItem parserHighlightingMenuItem;
    public RadioMenuItem semanticHighlightingMenuItem;
    public TextField stackSizeTF;
    public TextField highlightingDelayTF;

    private File file;
    private boolean fileSaved = true;
    private ProgramContext programContext;
    private Thread programThread;
    private ThreadGroup programThreadGroup;
    private volatile boolean running = false;
    private Service<Boolean> programRunService;
    private Highlighter highlighter = new LexerHighlighting();
    private Subscription updateHighlightSubscription;
    private volatile boolean textChanged;
    private Stage searchWindow;

    public File getFile() {
        if (!fileSaved) saveFile();
        if (!fileSaved) return null;
        if (file == null) requestFileName();
        if (file == null) return null;
        return file;
    }

    public void setFile(File file) {
        this.file = file;
        try {
            byte[] encoded = Files.readAllBytes(file.toPath());
            codeArea.replaceText(new String(encoded, StandardCharsets.UTF_8));
            fileSaved = true;
        } catch (IOException e) {
            alertReadError(e);
        }
    }

    public boolean isFileSaved() {
        return fileSaved;
    }

    public void setFileSaved(boolean fileSaved) {
        if (this.fileSaved == fileSaved) return;
        this.fileSaved = fileSaved;
        Stage window = (Stage) root.getScene().getWindow();
        if (fileSaved) {
            String title = window.getTitle();
            if (title.charAt(title.length() - 1) == '*') {
                window.setTitle(title.substring(0, title.length() - 1));
            }
        } else {
            window.setTitle(window.getTitle() + '*');
        }
    }

    public void onRunKeyPressed(MouseEvent mouseEvent) {
        run();
    }

    public void onDebugKeyPressed(MouseEvent mouseEvent) {
        debug();
    }

    private void run() {
        run(null);
    }

    private void run(String debugFile) {
        if (running) return;
        File f = getFile();
        if (f == null) return;
        PrologCompiler compiler = new PrologCompiler(f.getAbsolutePath(), debugFile);
        Program program;
        errorsOutput.clear();
        programOutput.clear();

        errorsOutput.println("Start compiling...");
        try {
            program = compiler.compileProgram();
        } catch (IOException e) {
            alertReadError(e);
            return;
        }

        if (!compiler.getExceptions().isEmpty()) {
            for (CompileException e : compiler.getExceptions()) {
                errorsOutput.println(e.toString());
            }
            for (CompileException e : compiler.getExceptions()) {
                if (e.getInterval() == null) continue;
                codeArea.selectRange(e.getInterval().getStart(), e.getInterval().getEnd() + 1);
                break;
            }
            return;
        }

        errorsOutput.println("Compile finished. Validating model...");
        Collection<ModelStateException> exceptions = program.exceptions();
        if (!exceptions.isEmpty()) {
            for (ModelStateException e : exceptions) {
                errorsOutput.println(e.toString());
            }
            for (ModelStateException e : exceptions) {
                if (e.getInterval() == null) continue;
                codeArea.selectRange(e.getInterval().getStart(), e.getInterval().getEnd() + 1);
                break;
            }
            return;
        }

        errorsOutput.println("Validating complete. Prepare for launch...");
        program.managers().getProgramManager().addOption(ctx -> new BaseProgramContextDecorator(ctx) {
            @Override
            public boolean execute() {
                boolean r = ctx.execute();
                programStopped();
                return r;
            }
        });
        program.managers().getProgramManager().addOption(ctx -> {
            //ctx.getOutputDevices().removeAll();
            ctx.getOutputDevices().add(programOutput);
            //ctx.getErrorListeners().removeAll();
            ctx.getErrorListeners().add(errorsOutput);
            ctx.setInputDevice(programInput);
            return ctx;
        });

        errorsOutput.println("Creating thread...");
        programRunning();
        programContext = ((Program) program.fix()).createContext();
        programRunService = new Service<Boolean>() {
            @Override
            protected Task<Boolean> createTask() {
                return new Task<Boolean>() {
                    @Override
                    protected Boolean call() {

                        return programContext.execute();
                    }
                };
            }
        };

        programRunService.setOnRunning(event -> errorsOutput.println("Program running!"));
        programRunService.setOnFailed(event -> {
            Throwable exception = event.getSource().getException();
            if (exception instanceof StackOverflowError) {
                errorsOutput.println("Stack overflow error!");
            } else {
                errorsOutput.runtimeException(new RuntimeException("Error in program thread", exception));
            }
            programStopped();
        });
        programRunService.setOnSucceeded(event -> {
            errorsOutput.println("Program finished with result: " + event.getSource().getValue());
            programStopped();
        });
        programRunService.setOnCancelled(event -> {
            errorsOutput.println("Program terminated.");
            programStopped();
        });
        programRunService.start();
    }

    private void debug() {
        if (running) return;
        FileChooser chooser = new FileChooser();
        chooser.setInitialDirectory(Paths.get("").toAbsolutePath().toFile());
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("All files", "*.*"),
                new FileChooser.ExtensionFilter("Text files", "*.txt"));
        File debug = chooser.showSaveDialog(root.getScene().getWindow());
        if (debug == null) return;
        run(debug.getAbsolutePath());
    }

    public void onStopKeyPressed(MouseEvent mouseEvent) {
        stop();
    }

    private void stop() {
        errorsOutput.println("Terminating program...");
        programRunService.cancel();
    }

    public boolean saveFile() {
        if (file == null) requestFileName();
        if (file == null) return false;
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            String code = codeArea.getText();
            pw.write(code);
            setFileSaved(true);
        } catch (FileNotFoundException e) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setHeaderText("Error writing file");
            alert.getDialogPane().setExpandableContent(new ScrollPane(new TextArea(e.toString())));
            alert.showAndWait();
            return false;
        }
        return true;
    }

    private void programRunning() {
        running = true;
        runBtn.setDisable(true);
        debugBtn.setDisable(true);
        stopBtn.setDisable(false);
        runMenuItem.setDisable(true);
        debugMenuItem.setDisable(true);
        stopMenuItem.setDisable(false);
    }

    private void programStopped() {
        programContext = null;
        runBtn.setDisable(false);
        debugBtn.setDisable(false);
        stopBtn.setDisable(true);
        runMenuItem.setDisable(false);
        debugMenuItem.setDisable(false);
        stopMenuItem.setDisable(true);
        errorsOutput.println("Program finished. ");
        running = false;
    }

    private boolean requestFileName() {
        return requestFileName(true);
    }

    private boolean requestFileName(boolean save) {
        FileChooser chooser = new FileChooser();
        chooser.setInitialDirectory(Paths.get("").toAbsolutePath().toFile());
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("All files", "*.*"),
                new FileChooser.ExtensionFilter("Prolog code files", "*.pro"),
                new FileChooser.ExtensionFilter("Text files", "*.txt"));
        File f = save ?
                chooser.showSaveDialog(root.getScene().getWindow()) :
                chooser.showOpenDialog(root.getScene().getWindow());
        if (f != null) {
            if (!f.exists()) {
                try {
                    f.createNewFile();
                } catch (IOException e) {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setHeaderText("Error creating file");
                    alert.getDialogPane().setExpandableContent(new ScrollPane(new TextArea(e.toString())));
                    alert.showAndWait();
                    return false;
                }
            }
            file = f;
            ((Stage) root.getScene().getWindow()).setTitle(f.getName());
            return true;
        }
        return false;
    }

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        codeArea.textProperty().addListener((observableValue, s, s2) -> setFileSaved(false));
        codeArea.textProperty().addListener((observableValue, s, s2) -> textChanged = true);
        codeArea.textProperty().addListener((observableValue, s, s2) -> updateCaretPos(codeArea.getCaretPosition()));
        codeArea.caretPositionProperty().addListener((observable, oldValue, newValue) -> updateCaretPos(newValue));
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        final Pattern whiteSpace = Pattern.compile("^\\s+");
/*        codeArea.addEventHandler(KeyEvent.KEY_PRESSED, KE ->
        {
            if (KE.getCode() == KeyCode.ENTER) {
                int caretPosition = codeArea.getCaretPosition();
                int currentParagraph = codeArea.getCurrentParagraph();
                Matcher m0 = whiteSpace.matcher(codeArea.getParagraph(currentParagraph - 1).getSegments().get(0));
                if (m0.find()) Platform.runLater(() -> codeArea.insertText(caretPosition, m0.group()));
            }
        });*/

        subscribeHighlighter(500);
        codeArea.getStylesheets().add(getClass().getResource("/editor.css").toExternalForm());

        Popup popup = new Popup();
        Label popupMsg = new Label();
        popupMsg.setStyle(
                "-fx-background-color: grey;" +
                        "-fx-text-fill: darkred;" +
                        "-fx-padding: 5;");
        popup.getContent().add(popupMsg);

        codeArea.setMouseOverTextDelay(Duration.ofMillis(250));
        codeArea.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_BEGIN, e -> {
            if (textChanged) return;
            int chIdx = e.getCharacterIndex();
            String message = highlighter.getMessageForPos(chIdx);
            if (message != null) {
                Point2D pos = e.getScreenPosition();
                popupMsg.setText(message);
                popup.show(codeArea, pos.getX(), pos.getY() + 10);
            }
        });
        codeArea.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_END, e -> {
            popup.hide();
        });

        codeArea.caretPositionProperty().addListener((observable, oldValue, newValue) -> {
            if (textChanged) return;
            List<Highlighter.HighlightingResult> results = highlighter.changeStylesOnCursor(newValue);
            if (results != null) {
                for (Highlighter.HighlightingResult result : results) {
                    codeArea.setStyleSpans(result.start, result.styleSpans);
                }
            }
        });
        codeArea.setOnContextMenuRequested(event -> {
            codeArea.hideContextMenu();
            OptionalInt characterIndex = codeArea.hit(event.getX(), event.getY()).getCharacterIndex();
            if(!characterIndex.isPresent()) return;
            ContextMenu contextMenu = highlighter.getContextMenu(characterIndex.getAsInt());
            if(contextMenu == null) return;
            contextMenu.setAutoHide(true);
            codeArea.setContextMenu(contextMenu);

            contextMenu.show(codeArea, event.getScreenX(), event.getScreenY());
        });
        Nodes.addInputMap(codeArea, InputMap.sequence(
                InputMap.consume(
                        EventPattern.keyPressed(KeyCode.D, KeyCombination.CONTROL_DOWN),
                        e -> duplicateLine())));
        Nodes.addInputMap(codeArea, InputMap.sequence(
                InputMap.consume(
                        EventPattern.keyPressed(KeyCode.F, KeyCombination.CONTROL_DOWN),
                        e -> {
                            try {
                                if(searchWindow == null) {
                                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/SearchDialog.fxml"));
                                    Parent root = loader.load();
                                    searchWindow = new Stage();
                                    searchWindow.setTitle("Search");
                                    searchWindow.setScene(new Scene(root));
                                    SearchDialogController controller = loader.getController();
                                    controller.setCodeArea(codeArea);
                                } else {
                                    searchWindow.show();
                                }
                                searchWindow.show();
                            } catch (Exception ignored) {

                            }
                        })));
        Nodes.addInputMap(codeArea, InputMap.sequence(
                InputMap.consume(
                        EventPattern.keyPressed(KeyCode.DELETE, KeyCombination.SHIFT_DOWN),
                        e -> deleteLine())));
        Nodes.addInputMap(codeArea, InputMap.sequence(
                InputMap.consume(
                        EventPattern.keyPressed(new KeyCodeCombination(KeyCode.TAB,
                                KeyCombination.ModifierValue.UP,
                                KeyCombination.ModifierValue.ANY,
                                KeyCombination.ModifierValue.ANY,
                                KeyCombination.ModifierValue.ANY,
                                KeyCombination.ModifierValue.ANY)),
                        e -> codeArea.replaceSelection("    "))));
        Nodes.addInputMap(codeArea, InputMap.sequence(
                InputMap.consume(
                        EventPattern.keyPressed(KeyCode.TAB, KeyCombination.SHIFT_DOWN),
                        e -> untab())));

        highlightingToggleGroup.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
            highlighter.close();
            if (newValue == noHighlightingMenuItem) {
                highlighter = new NoHighlighting();
            } else if (newValue == lexerHighlightingMenuItem) {
                highlighter = new LexerHighlighting();
            } else if (newValue == parserHighlightingMenuItem) {
                highlighter = new ParserHighlighting();
            } else if (newValue == semanticHighlightingMenuItem) {
                highlighter = new SemanticHighlighting(codeArea);
            }
            updateHighlighting();
        });

        programInput.setListener(new ProgramInputDevice.InputListener() {
            @Override
            public void onReadChar(char c) {
                if (c == 27) return;
                if (programContext != null) programContext.getOutputDevices().println(String.valueOf(c));
            }

            @Override
            public void onReadString(String s) {
                if (s == null) return;
                if (programContext != null) programContext.getOutputDevices().println(s);
            }
        });
        stackSizeTF.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.matches("\\d*") || Integer.parseInt(newValue) < 100) {
                stackSizeTF.setText(oldValue);
            }
        });

        highlightingDelayTF.textProperty().addListener((observable, oldValue, newValue) -> {
            int newValueInt = Integer.parseInt(newValue);
            if (!newValue.matches("\\d*") || newValueInt < 1) {
                highlightingDelayTF.setText(oldValue);
            }
            subscribeHighlighter(newValueInt);
        });
    }

    private void subscribeHighlighter(int millis) {
        if (updateHighlightSubscription != null) {
            updateHighlightSubscription.unsubscribe();
        }
        updateHighlightSubscription = codeArea
                // plain changes = ignore style changes that are emitted when syntax highlighting is reapplied
                // multi plain changes = save computation by not rerunning the code multiple times
                //   when making multiple changes (e.g. renaming a method at multiple parts in file)
                .multiPlainChanges()

                // do not emit an event until specified delay have passed since the last emission of previous stream
                .successionEnds(Duration.ofMillis(millis))

                // run the following code block when previous stream emits an event
                .subscribe(ignore -> {
                    Highlighter.HighlightingResult highlightingResult = highlighter.computeHighlighting(codeArea.getText());
                    codeArea.setStyleSpans(highlightingResult.start, highlightingResult.styleSpans);
                    textChanged = false;
                });
    }

    private void duplicateLine() {
        int caretPosition = codeArea.getCaretPosition();
        String text = codeArea.getText();
        final int lineStart = text.substring(0, caretPosition).lastIndexOf('\n') + 1;
        int i;
        for (i = lineStart; i < text.length() && text.charAt(i) != '\r' && text.charAt(i) != '\n'; i++) ;
        if (i < text.length() && text.charAt(i) == '\r') i++;
        if (i < text.length() && text.charAt(i) == '\n') i++;
        String line = text.substring(lineStart, i);
        int start = i;
        codeArea.insertText(start, line);
        codeArea.moveTo(caretPosition + line.length());
    }

    private void deleteLine() {
        int caretPosition = codeArea.getCaretPosition();
        String text = codeArea.getText();
        final int lineStart = text.substring(0, caretPosition).lastIndexOf('\n') + 1;
        int i;
        for (i = lineStart; i < text.length() && text.charAt(i) != '\r' && text.charAt(i) != '\n'; i++) ;
        if (i < text.length() && text.charAt(i) == '\r') i++;
        if (i < text.length() && text.charAt(i) == '\n') i++;
        codeArea.selectRange(lineStart, i);
        codeArea.cut();
        codeArea.moveTo(caretPosition);
    }

    private void untab() {
        int caretPosition = codeArea.getCaretPosition();
        String text = codeArea.getText();
        final int lineStart = text.substring(0, caretPosition).lastIndexOf('\n') + 1;
        if (text.charAt(lineStart) == '\t') {
            codeArea.deleteText(lineStart, lineStart + 1);
            codeArea.moveTo(caretPosition - 1);
            return;
        }
        int i;
        for (i = 0; i < 4; i++) {
            if (text.charAt(lineStart + i) != ' ') break;
        }
        codeArea.deleteText(lineStart, lineStart + i);
        codeArea.moveTo(caretPosition - i);
    }

    public void updateHighlighting() {
        codeArea.setStyleSpans(0, highlighter.computeHighlightingFull(codeArea.getText()));
        textChanged = false;
    }

    private void updateCaretPos(int pos) {
        String text = codeArea.getText();
        text = text.substring(0, Math.min(pos, text.length()));
        String[] lines = text.split("\n");
        int line = lines.length;
        int inLine = lines.length == 0 ? 0 : lines[lines.length - 1].length();
        caretPos.setText(line + ":" + inLine);
    }

    public void onSaveAction(ActionEvent actionEvent) {
        saveFile();
    }

    public void onSaveAsAction(ActionEvent actionEvent) {
        if (!requestFileName()) return;
        saveFile();
    }

    public void newFile(ActionEvent actionEvent) {
        if (!fileSaved && !codeArea.getText().equals("")) {
            if (!saveFile()) return;
        }
        codeArea.clear();
        fileSaved = true;
        file = null;
        ((Stage) root.getScene().getWindow()).setTitle(":new file:");
    }

    public void loadFile(ActionEvent actionEvent) {
        if (!fileSaved && !codeArea.getText().equals("")) {
            if (!saveFile()) return;
        }
        if (!requestFileName(false)) return;
        try {
            byte[] encoded = Files.readAllBytes(file.toPath());
            codeArea.replaceText(new String(encoded, StandardCharsets.UTF_8));
            setFileSaved(true);
        } catch (IOException e) {
            alertReadError(e);
        }
    }

    public void runMenuAction(ActionEvent actionEvent) {
        run();
    }

    public void debugMenuAction(ActionEvent actionEvent) {
        debug();
    }

    private void alertReadError(IOException e) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setHeaderText("Error reading file");
        alert.getDialogPane().setExpandableContent(new ScrollPane(new TextArea(e.toString())));
        alert.showAndWait();
    }

    public void stopMenuAction(ActionEvent actionEvent) {
        stop();
    }

    public void close() {
        highlighter.close();
        if(searchWindow != null)  searchWindow.close();
    }
}
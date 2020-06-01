package prolog;

import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import org.fxmisc.richtext.CodeArea;

public class SearchDialogController {
    public TextArea textInput;
    public CheckBox matchCaseCB;
    private CodeArea codeArea;

    public void setCodeArea(CodeArea codeArea) {
        this.codeArea = codeArea;
    }

    public void searchDown() {
        String text = textInput.getText();
        String code = codeArea.getText();

        if(text.isEmpty()) return;

        if(!matchCaseCB.isSelected()) {
            text = text.toLowerCase();
            code = code.toLowerCase();
        }
        int i = code.indexOf(text, codeArea.getCaretPosition());
        if (i > 0) {
            codeArea.selectRange(i, i + text.length());
            codeArea.requestFollowCaret();
        }
    }
}

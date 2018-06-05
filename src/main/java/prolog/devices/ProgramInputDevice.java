package prolog.devices;

import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import ru.prolog.util.io.InputDevice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ProgramInputDevice extends TextField implements InputDevice {
    private enum State{
        DISABLED,
        WAIT_LINE,
        WAIT_CHAR
    }

    private volatile State state = State.DISABLED;
    private volatile char charPressed;
    private volatile String lineEntered;
    private final Object waitLock = new Object();
    private InputListener listener;
    private List<String> inputs = new ArrayList<>();
    private int currInput = -1;

    public void setListener(InputListener listener) {
        this.listener = listener;
    }

    {
        setOnKeyPressed(event -> {
            if(KeyCode.UP.equals(event.getCode())){
                if(currInput<inputs.size()-1){
                    currInput++;
                    setText(inputs.get(currInput));
                }
            }
            if(KeyCode.DOWN.equals(event.getCode())){
                if(currInput>=0 && inputs.size()>0){
                    currInput--;
                    if(currInput==-1) setText("");
                    else setText(inputs.get(currInput));
                }
            }
        });
        setOnKeyTyped(event -> {
            char c = event.getCharacter().charAt(0);
            if(c ==27){//escape
                lineEntered = null;
                charPressed = c;
                synchronized (waitLock) {
                    waitLock.notifyAll();
                }
                return;
            }
            if(state==State.WAIT_CHAR){
                charPressed = c;
                synchronized (waitLock) {
                    waitLock.notifyAll();
                }
            }
            if(state==State.WAIT_LINE && (c =='\r' || c=='\n')){//enter
                lineEntered = getText();
                if(inputs.isEmpty() || !inputs.get(0).equals(lineEntered))
                    inputs.add(0, lineEntered);
                currInput=-1;
                synchronized (waitLock) {
                    waitLock.notifyAll();
                }
            }
        });
    }

    @Override
    public String readLine() throws IOException {
        state = State.WAIT_LINE;
        setDisable(false);
        String style = getStyle();
        setStyle("-fx-background-color: lawngreen");
        try {
            synchronized (waitLock) {
                waitLock.wait();//Wait for input. Key listener notifies on input.
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            setDisable(true);
            setStyle(style);
            state = State.DISABLED;
            clear();
        }
        String ret = lineEntered;
        lineEntered = null;
        if(listener!=null) listener.onReadString(ret);
        return ret;
    }

    @Override
    public char readChar() throws IOException {
        state = State.WAIT_CHAR;
        setDisable(false);
        try {
            synchronized (waitLock) {
                waitLock.wait();//Wait for input. Key listener notifies on input.
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 27;//escape
        } finally {
            setDisable(true);
            state = State.DISABLED;
            clear();
        }
        if(listener!=null) listener.onReadChar(charPressed);
        return charPressed;
    }

    public interface InputListener{
        void onReadChar(char c);
        void onReadString(String s);
    }
}

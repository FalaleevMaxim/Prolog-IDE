package prolog.devices;

import javafx.scene.control.TextField;
import ru.prolog.util.io.InputDevice;

import java.io.IOException;

public class ProgramInputDevice extends TextField implements InputDevice {
    private enum State{
        DISABLED,
        WAIT_LINE,
        WAIT_CHAR
    }

    private volatile State state = State.DISABLED;
    private volatile char charPressed;
    private volatile String lineEntered;
    private volatile boolean escPressed = false;
    private final Object waitLock = new Object();


    {
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
        return charPressed;
    }
}

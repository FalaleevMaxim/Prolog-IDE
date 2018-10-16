package prolog.devices;

import javafx.application.Platform;
import ru.prolog.util.io.OutputDevice;

import javafx.scene.control.TextArea;

public class ProgramOutputDevice extends TextArea implements OutputDevice {
    public ProgramOutputDevice() {
    }

    public ProgramOutputDevice(String text) {
        super(text);
    }

    @Override
    public void print(String s) {
        Platform.runLater(() -> appendText(s));

    }

    @Override
    public void println(String s) {
        Platform.runLater(() -> appendText(s + "\n"));
    }
}
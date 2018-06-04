package prolog;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception{
        FXMLLoader loader=new FXMLLoader(getClass().getResource("sample.fxml"));
        Parent root = loader.load();
        primaryStage.setScene(new Scene(root, 800, 600));
        Controller c = loader.getController();

        primaryStage.setOnCloseRequest(event -> {
            if(c.isFileSaved()) return;
            Alert alert = new Alert(Alert.AlertType.WARNING, "", ButtonType.YES, ButtonType.NO, ButtonType.CANCEL);
            alert.setHeaderText("Save file?");
            alert.setTitle("File not saved!");
            ButtonType result = alert.showAndWait().orElse(ButtonType.CANCEL);
            //If cancel, do not close window
            if(result==ButtonType.CANCEL) event.consume();
                //If Yes, but save failed, do not close window
            else if(result==ButtonType.YES && !c.saveFile()) event.consume();
            //Answer NO or file successfully saved, close window
        });

        List<String> args = getParameters().getRaw();
        if(args.size()>0){
            File file = new File(args.get(0));
            c.setFile(file);
            primaryStage.setTitle(file.getPath());
        }else {
            primaryStage.setTitle(":new file:");
        }

        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

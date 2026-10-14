module dev.apitestingplatform.ui {
    requires transitive javafx.controls;
    requires dev.apitestingplatform.application;
    requires com.fasterxml.jackson.core;
    requires org.fxmisc.richtext;
    requires org.fxmisc.flowless;
    requires org.fxmisc.undo;
    exports dev.apitestingplatform.ui;
    exports dev.apitestingplatform.ui.controller;
    exports dev.apitestingplatform.ui.components;
    exports dev.apitestingplatform.ui.response;
    exports dev.apitestingplatform.ui.viewmodel;
}


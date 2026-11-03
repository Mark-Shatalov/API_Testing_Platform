module dev.apitestingplatform.ui {
    requires transitive javafx.controls;
    requires dev.apitestingplatform.application;
    requires dev.apitestingplatform.importexport;
    requires com.fasterxml.jackson.core;
    requires org.fxmisc.richtext;
    requires org.fxmisc.flowless;
    requires org.fxmisc.undo;
    requires reactfx;
    exports dev.apitestingplatform.ui;
    exports dev.apitestingplatform.ui.controller;
    exports dev.apitestingplatform.ui.components;
    exports dev.apitestingplatform.ui.response;
    exports dev.apitestingplatform.ui.viewmodel;
    exports dev.apitestingplatform.ui.theme;
}


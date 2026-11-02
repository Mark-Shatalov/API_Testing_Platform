module dev.apitestingplatform.app {
    requires javafx.controls;
    requires dev.apitestingplatform.ui;
    // Response debugging tests inspect CodeArea directly; declare this for IDE compilers too.
    requires static org.fxmisc.richtext;
    requires dev.apitestingplatform.httpclient;
    requires dev.apitestingplatform.persistence.sqlite;
    requires dev.apitestingplatform.security.windows;
    requires dev.apitestingplatform.importexport;
    exports dev.apitestingplatform.app;
}


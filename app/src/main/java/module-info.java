module dev.apitestingplatform.app {
    requires javafx.controls;
    requires java.desktop;
    requires java.management;
    requires dev.apitestingplatform.ui;
    // Response debugging tests inspect CodeArea directly; declare this for IDE compilers too.
    requires static org.fxmisc.richtext;
    // Integration tests inject temporary SQLite write failures.
    requires static java.sql;
    requires dev.apitestingplatform.httpclient;
    requires dev.apitestingplatform.persistence.sqlite;
    requires dev.apitestingplatform.security.windows;
    requires dev.apitestingplatform.importexport;
    exports dev.apitestingplatform.app;
}


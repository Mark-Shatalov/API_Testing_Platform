module dev.apitestingplatform.persistence.sqlite {
    requires java.sql;
    requires transitive dev.apitestingplatform.application;
    requires org.xerial.sqlitejdbc;
    requires com.fasterxml.jackson.databind;
    requires org.slf4j;
    exports dev.apitestingplatform.persistence.sqlite;
}

